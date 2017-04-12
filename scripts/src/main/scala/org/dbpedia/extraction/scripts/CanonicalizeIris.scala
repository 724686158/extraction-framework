package org.dbpedia.extraction.scripts

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import org.dbpedia.extraction.config.provenance.{DBpediaDatasets, Dataset}
import org.dbpedia.extraction.destinations.DestinationUtils
import org.dbpedia.extraction.destinations.formatters.{Formatter, TerseFormatter}
import org.dbpedia.extraction.util.RichFile.wrapFile
import org.dbpedia.extraction.util._

import scala.collection.concurrent
import scala.collection.convert.decorateAsScala._

/**
  * Created by Chile on 11/8/2016.
  */
object CanonicalizeIris {

  private val mappings = new ConcurrentHashMap[Language, concurrent.Map[String, String]]().asScala

  private val finders = new ConcurrentHashMap[Language, DateFinder[File]]().asScala

  //TODO make this configurable
  private val inputSuffix = ".tql.bz2"

  private var baseDir: File = null

  private var genericLanguage: Language = Language.English
  private val dbpPrefix = "http://dbpedia.org"
  private var newLanguage: Language = null
  private var newPrefix: String = null
  private var newResource: String = null

  case class WorkParameters(language: Language, source: FileLike[_], destination: Dataset)

  private var frmts: Map[String, Formatter] = null

  /**
    * produces a copy of the Formatter map (necessary to guarantee concurrent processing)
 *
    * @return
    */
  def formats = frmts.map(x => x._1 -> (x._2 match {
    case formatter: TerseFormatter => new QuadMapperFormatter(formatter)
    case _ => x._2.getClass.newInstance()
  }))

  def uriPrefix(language: Language): String = {
    val zw = if (language == genericLanguage)
      dbpPrefix
    else
      "http://" + language.dbpediaDomain
    zw+"/"
  }

  /**
    * get finder specific to a language directory
 *
    * @param lang
    * @return
    */
  def finder(lang: Language) = finders.get(lang) match{
    case Some(f) => f
    case None => {
      val f = new DateFinder(baseDir, lang)
      finders.put(lang, f)
      f.byName("download-complete", auto = true) //get date workaround
      f
    }
  }

  /**
    * this worker reads the mapping file into a concurrent HashMap
    */
  val mappingsReader = { langFile: WorkParameters =>
    val map = mappings.get(langFile.language) match {
      case Some(m) => m
      case None => {
        val zw = new ConcurrentHashMap[String, String]().asScala
        mappings.put(langFile.language, zw)
        zw
      }
    }
    val oldReource = uriPrefix(langFile.language)+"resource/"
    val qReader = new QuadReader()
    qReader.readQuads(langFile.language, langFile.source) { quad =>
      if (quad.datatype != null)
        qReader.addQuadRecord(quad, langFile.language, null, new IllegalArgumentException("expected object uri, found object literal: " + quad))
      if (quad.value.startsWith(newResource)) {
        map(new String(quad.subject.replace(oldReource, ""))) = new String(quad.value.replace(newResource, ""))
      }
    }
  }

  /**
    * this worker does the actual mapping of URIs
    */
  val mappingExecutor = { langSourceDest: WorkParameters =>
    val oldPrefix = uriPrefix(langSourceDest.language)
    val oldResource = oldPrefix+"resource/"

    val destination = DestinationUtils.createDestination(finder(langSourceDest.language), Seq(langSourceDest.destination), formats)
    val destName = langSourceDest.destination.encoded
    val map = mappings.get(langSourceDest.language).get //if this fails something is really wrong

    def newUri(oldUri: String): String = {
      //let our properties pass :)
      //TODO maybe include a switch for this behavior?
      if(oldUri.startsWith(dbpPrefix + "ontology") || oldUri.startsWith(dbpPrefix + "property"))
        return oldUri
      if (oldUri.startsWith(oldPrefix))
        newPrefix + oldUri.substring(oldPrefix.length)
      else oldUri // not a DBpedia URI, copy it unchanged
    }

    def mapUri(oldUri: String): String = {
      if (oldUri.startsWith(oldResource))
        map.get(oldUri.replace(oldResource, "")) match{
          case Some(r) => newResource+r
          case None => null
        }
      else newUri(oldUri)
    }

    new QuadMapper().mapQuads(langSourceDest.language, langSourceDest.source, destination, required = false) { quad =>
      val pred = newUri(quad.predicate)
      val subj = mapUri(quad.subject)
      if (subj == null) {
        // no mapping for this subject URI - discard the quad. TODO: make this configurable
        List()
      }
      else if (quad.datatype == null && quad.language == null) {
        // URI value - change subject and object URIs, copy everything else
        val obj = mapUri(quad.value)
        if (obj == null) {
          // no mapping for this object URI - discard the quad. TODO: make this configurable
          List()
        } else {
          // map subject, predicate and object URI, copy everything else
          List(quad.copy(subject = subj, predicate = pred, value = obj, dataset = destName))
        }
      } else {
        // literal value - change subject and predicate URI, copy everything else
        List(quad.copy(subject = subj, predicate = pred, dataset = destName))
      }
    }
  }

  def main(args: Array[String]): Unit = {

    require(args != null && args.length == 1, "One arguments required, extraction config file")

    val config = new Config(args.head)

    baseDir = config.dumpDir

    val mappings = ConfigUtils.getValues(config.properties, "mapping-files",",", required=true)(x => x)
    require(mappings.nonEmpty, "no mapping datasets")

    // Suffix of mapping files, for example ".nt", ".ttl.gz", ".nt.bz2" and so on.
    // This script works with .nt, .ttl, .nq or .tql files, using IRIs or URIs.
    val mappingSuffix = ConfigUtils.getString(config.properties, "mapping-suffix",required=true)
    require(mappingSuffix.nonEmpty, "no mapping file suffix")

    val inputs = ConfigUtils.getValues(config.properties, "input-files",",", required=true)(x => x)
    require(inputs.nonEmpty, "no input datasets")

    val extension = ConfigUtils.getString(config.properties, "name-extension",required=true)
    require(extension.nonEmpty, "no result name extension")

    val threads = config.parallelProcesses

    // Language using generic domain (usually en)
    genericLanguage = ConfigUtils.getValue(config.properties, "generic-language",required=false)(x => Language(x))

    newLanguage = ConfigUtils.getValue(config.properties, "mapping-language",required=true)(x => Language(x))
    newPrefix = uriPrefix(newLanguage)
    newResource = newPrefix+"resource/"

    val languages = config.languages
    require(languages.nonEmpty, "no languages")

    frmts = config.formats.toMap

    // load all mappings
    val loadParameters = for (lang <- languages; mapping <- mappings)
      yield
        new WorkParameters(language = lang, source = new RichFile(finder(lang).byName(mapping+mappingSuffix, auto = true).get), null)

    Workers.work[WorkParameters](SimpleWorkers(threads, threads)(mappingsReader), loadParameters.toList, "language mappings loading")

    //execute all file mappings
    val parameters = for (lang <- languages; input <- inputs)
      yield
        new WorkParameters(language = lang, source = new RichFile(finder(lang).byName(input+inputSuffix, auto = true).get), DBpediaDatasets.getDataset(input+ (if(extension != null) extension else "")))

    Workers.work[WorkParameters](SimpleWorkers(threads, threads)(mappingExecutor), parameters.toList, "executing language mappings")
  }
}