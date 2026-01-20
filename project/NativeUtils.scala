import cats.syntax.all.*
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import java.io.File
import org.eclipse.jgit.api.Git
import org.apache.ivy.plugins.repository.Resource
import sbt.std.TaskStreams
import sbt.util.Logger

final class NativeImageGenerateMetadataFiles(
  targetDirectory: File
)(implicit logger: Logger) {
  import NativeImageGenerateMetadataFiles.*

  lazy val localRepoMetadata: File = {
    val remoteUrl = "https://github.com/oracle/graalvm-reachability-metadata.git"
    val localPath = new File(targetDirectory, "graalvm-reachability-metadata")
    if (!localPath.exists()) {
      logger.info(s"[native-image-utils] Cloning to $localPath")
      Git.cloneRepository()
        .setURI(remoteUrl)
        .setDirectory(localPath)
        .call()
    } else {
      logger.info(s"[native-image-utils] Pulling $localPath")
      Git.open(localPath).pull().call()
    }
    new File(localPath, "metadata")
  }

  lazy val localRepoMetadataIndex = {
    val path = new File(localRepoMetadata, "index.json")
    val jsonTxt = scala.io.Source.fromFile(path).mkString
    decode[List[ModuleIndexEntry]](jsonTxt).valueOr(throw _)
      .map(v => (v.key, v))
      .foldLeft(Map.empty[Module, ModuleIndexEntry]) {
        case (acc, (module, entry)) =>
          if (acc.contains(module))
            throw new IllegalArgumentException(s"Duplicate module: $module")
          acc.updated(module, entry)
      }
  }

  def readModuleVersions(module: Module): Option[ModuleVersions] = {
    for {
      metaEntry <- localRepoMetadataIndex.get(module)
      directory <- metaEntry.directory
      directoryPath = new File(localRepoMetadata, directory)
      indexJsonPath = new File(directoryPath, "index.json")
    } yield {
      val jsonTxt = scala.io.Source.fromFile(indexJsonPath).mkString
      val list = decode[List[ModuleVersionsIndexEntry]](jsonTxt).valueOr(throw _)
      ModuleVersions(metaEntry, directoryPath, list)
    }
  }

  def findArtefactVersion(module: Module, version: Option[String]): Option[ArtefactMeta] = {
    val ret = readModuleVersions(module).flatMap { versions =>
      versions.entries.find { entry =>
        version match {
          case None =>
            entry.latest.getOrElse(false)
          case Some(info) =>
            entry.defaultFor.exists(regex => info.matches(regex))
        }
      }.map(ArtefactMeta(versions, _))
    }
    if (ret.isEmpty && version.isDefined)
      findArtefactVersion(module, None)
    else
      ret
  }

  def findArtefact(
    module: Module,
    version: Option[String]
  ): Option[(ArtefactMeta, ArtefactFiles)] = {
    findArtefactVersion(module, version).map { meta =>
      val dirPath = new File(meta.allVersions.directory, meta.version.metadataVersion)
      val indexFile = new File(dirPath, "index.json")
      if (indexFile.exists()) {
        val index = readAndDecodeFile[List[String]](indexFile)

        val reflectConfig = index
          .find(_.endsWith("reflect-config.json"))
          .map { name =>
            val path = new File(dirPath, name)
            readAndDecodeFile[List[JsonObject]](path)
          }
        val resourcesConfig = index
          .find(_.endsWith("resource-config.json"))
          .map { name =>
            val path = new File(dirPath, name)
            readAndDecodeFile[ResourcesJson](path)
          }

        meta -> ArtefactFiles(resourcesConfig, reflectConfig)
      } else {
        val reflectConfig = Option(new File(dirPath, "reflect-config.json"))
          .filter(_.exists())
          .map { file =>
            readAndDecodeFile[List[JsonObject]](file)
          }
        val resourcesConfig = Option(new File(dirPath, "resource-config.json"))
          .filter(_.exists())
          .map { file =>
            readAndDecodeFile[ResourcesJson](file)
          }
        meta -> ArtefactFiles(resourcesConfig, reflectConfig)
      }
    }
  }

  def findAndBuildFiles(artefacts: List[Artefact]): ArtefactFiles =
    artefacts
      .flatMap { artefact =>
        findArtefact(artefact.module, artefact.version).map(_._2)
      }.foldLeft(ArtefactFiles(None, None)) {
        (acc, res) => acc ++ res
      }

  def buildResourcesOfArtefactsIds(artefacts: List[String]): ArtefactFiles =
    findAndBuildFiles(artefacts.map(Artefact.apply))

  def buildResources(items: List[ResourceType]): ArtefactFiles =
    items.foldLeft(ArtefactFiles(None, None)) {
      case (acc, artefact: Artefact) =>
        acc ++ findAndBuildFiles(List(artefact))
      case (acc, ProjectResourceConfigFile(name)) =>
        acc ++ ArtefactFiles(Some(readAndDecodeResource[ResourcesJson](name)), None)
      case (acc, ProjectReflectConfigFile(name)) =>
        acc ++ ArtefactFiles(None, Some(readAndDecodeResource[List[JsonObject]](name)))
    }

  def generateResourceFiles(root: File, items: List[ResourceType]): List[File] =
    buildResources(items.toList).writeFilesContent(root)

  private def readAndDecodeFile[T: Decoder](file: File): T = {
    val jsonTxt = scala.io.Source.fromFile(file).mkString
    decode[T](jsonTxt).valueOr(throw _)
  }

  private def readAndDecodeResource[T: Decoder](name: String): T = {
    val res = getClass().getClassLoader().getResourceAsStream(name)
    val jsonTxt = scala.io.Source.fromInputStream(res).mkString
    decode[T](jsonTxt).valueOr(throw _)
  }
}

object NativeImageGenerateMetadataFiles {
  def generateResourceFiles(
    targetDirectory: File,
    generatedFilesDirectory: File,
    items: List[ResourceType]
  )(implicit logger: Logger): List[File] =
    new NativeImageGenerateMetadataFiles(targetDirectory)
      .generateResourceFiles(generatedFilesDirectory, items)

  case class Module(
    groupId: String,
    artifactId: String
  ) {
    override def toString =
      s"$groupId:$artifactId"
  }

  object Module {
    def apply(raw: String): Module =
      raw.split(":").toList match {
        case g :: a :: rest if rest.length <= 1 => Module(g, a)
        case _ => throw new IllegalArgumentException(s"Invalid package: $raw")
      }
  }

  sealed trait ResourceType

  case class Artefact(
    module: Module,
    version: Option[String]
  ) extends ResourceType {
    override def toString =
      s"$module:$version"
  }

  object Artefact {
    def apply(raw: String): Artefact =
      raw.split(":").toList match {
        case g :: a :: v :: Nil => Artefact(Module(g, a), Some(v))
        case g :: a :: Nil => Artefact(Module(g, a), None)
        case _ => throw new IllegalArgumentException(s"Invalid package: $raw")
      }
  }

  case class ProjectResourceConfigFile(
    resourceName: String
  ) extends ResourceType {
    override def toString =
      s"resource:$resourceName"
  }

  case class ProjectReflectConfigFile(
    resourceName: String
  ) extends ResourceType {
    override def toString =
      s"resource:$resourceName"
  }

  final case class ModuleIndexEntry(
    allowedPackages: List[String],
    directory: Option[String],
    module: String,
    requires: Option[List[String]]
  ) {
    val key: Module = Module(module)
  }

  object ModuleIndexEntry {
    implicit val json: Codec[ModuleIndexEntry] =
      Codec.forProduct4(
        "allowed-packages",
        "directory",
        "module",
        "requires"
      )(ModuleIndexEntry.apply)(m =>
        (m.allowedPackages, m.directory, m.module, m.requires)
      )
  }

  final case class ModuleVersions(
    metadata: ModuleIndexEntry,
    directory: File,
    entries: List[ModuleVersionsIndexEntry]
  )

  final case class ModuleVersionsIndexEntry(
    latest: Option[Boolean],
    metadataVersion: String,
    module: String,
    defaultFor: Option[String],
    testedVersions: Option[List[String]]
  )

  object ModuleVersionsIndexEntry {
    implicit val json: Codec[ModuleVersionsIndexEntry] =
      Codec.forProduct5(
        "latest",
        "metadata-version",
        "module",
        "default-for",
        "tested-versions"
      )(ModuleVersionsIndexEntry.apply)(m =>
        (m.latest, m.metadataVersion, m.module, m.defaultFor, m.testedVersions)
      )
  }

  final case class ArtefactMeta(
    allVersions: ModuleVersions,
    version: ModuleVersionsIndexEntry
  )

  final case class ResourcesJsonPatterns(
    includes: Option[List[JsonObject]],
    excludes: Option[List[JsonObject]]
  ) {
    def nonEmpty =
      includes.nonEmpty || excludes.nonEmpty
  }

  object ResourcesJsonPatterns {
    implicit val json: Codec[ResourcesJsonPatterns] =
      Codec.forProduct2(
        "includes",
        "excludes"
      )(ResourcesJsonPatterns.apply)(m =>
        (m.includes, m.excludes)
      )
  }

  final case class ResourcesJson(
    resources: Option[ResourcesJsonPatterns],
    bundles: Option[List[JsonObject]]
  )

  object ResourcesJson {
    implicit val json: Codec[ResourcesJson] =
      Codec.forProduct2(
        "resources",
        "bundles"
      )(ResourcesJson.apply)(m =>
        (m.resources, m.bundles)
      )
  }

  case class ArtefactFiles(
    resourcesConfig: Option[ResourcesJson],
    reflectConfig: Option[List[JsonObject]]
  ) {
    def toFilesContent: Map[String, String] =
      Map(
        "reflect-config.json" -> reflectConfig.fold("")(_.asJson.spaces2),
        "resource-config.json" -> resourcesConfig.fold("")(_.asJson.spaces2)
      ).filter(_._2.nonEmpty)

    def writeFilesContent(root: File)(implicit logger: Logger): List[File] =
      toFilesContent.map {
        case (name, content) =>
          val file = new File(root, name)
          sbt.IO.write(file, content)
          logger.info(s"[native-image-utils] Generated: $file")
          file
      }.toList

    def ++(other: ArtefactFiles): ArtefactFiles = {
      val newRes = (resourcesConfig, other.resourcesConfig) match {
        case (Some(acc), Some(res)) =>
          val newInc = Some(
            acc.resources.flatMap(_.includes).getOrElse(Nil) ++
              res.resources.flatMap(_.includes).getOrElse(Nil)
          ).filter(_.nonEmpty)
          val newExc = Some(
            acc.resources.flatMap(_.excludes).getOrElse(Nil) ++
              res.resources.flatMap(_.excludes).getOrElse(Nil)
          ).filter(_.nonEmpty)
          val newBundles = Some(
            acc.bundles.getOrElse(Nil) ++ res.bundles.getOrElse(Nil)
          ).filter(_.nonEmpty)
          Some(ResourcesJson(
            Some(ResourcesJsonPatterns(newInc, newExc)).filter(_.nonEmpty),
            newBundles
          ))
        case _ =>
          resourcesConfig.orElse(other.resourcesConfig)
      }
      val newReflect = (reflectConfig, other.reflectConfig) match {
        case (Some(acc), Some(res)) => Some(acc ++ res)
        case _ => reflectConfig.orElse(other.reflectConfig)
      }
      ArtefactFiles(newRes, newReflect)
    }
  }
}
