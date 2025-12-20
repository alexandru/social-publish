package socialpublish.services

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import socialpublish.config.AppConfig
import socialpublish.db.FilesDatabase
import socialpublish.models.*
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream

case class ProcessedFile(
    uuid: UUID,
    originalName: String,
    mimeType: String,
    bytes: Array[Byte],
    altText: Option[String],
    width: Int,
    height: Int
)

trait FilesService {
  def saveFile(
      filename: String,
      mimeType: String,
      bytes: Array[Byte],
      altText: Option[String]
  ): IO[FileMetadata]

  def getFile(uuid: UUID): IO[Option[ProcessedFile]]
  def getFileMetadata(uuid: UUID): IO[Option[FileMetadata]]
  def getFilePath(uuid: UUID): Path
}

object FilesService {
  def apply(config: AppConfig, db: FilesDatabase): IO[FilesService] =
    for {
      logger <- Slf4jLogger.create[IO]
      _ <- IO.blocking {
        val uploadPath = config.uploadedFilesPath
        if !Files.exists(uploadPath) then {
          val _ = Files.createDirectories(uploadPath)
        }
      }
    } yield new FilesServiceImpl(config, db, logger)
}

private class FilesServiceImpl(
    config: AppConfig,
    db: FilesDatabase,
    logger: Logger[IO]
) extends FilesService {

  override def saveFile(
      filename: String,
      mimeType: String,
      bytes: Array[Byte],
      altText: Option[String]
  ): IO[FileMetadata] =
    for {
      uuid <- IO.delay(UUID.randomUUID())
      dimensions <- extractImageDimensions(bytes, mimeType)
      (width, height) = dimensions
      _ <- writeFileToDisk(uuid, bytes)
      metadata = FileMetadata(
        uuid = uuid,
        originalName = filename,
        mimeType = mimeType,
        size = bytes.length.toLong,
        altText = altText,
        width = Some(width),
        height = Some(height),
        createdAt = Instant.now()
      )
      _ <- db.save(metadata)
      _ <- logger.info(s"Saved file $uuid ($filename)")
    } yield metadata

  override def getFile(uuid: UUID): IO[Option[ProcessedFile]] =
    for {
      metadataOpt <- db.getByUUID(uuid)
      result <- metadataOpt match {
        case None => IO.pure(None)
        case Some(metadata) =>
          readFileFromDisk(uuid).map { bytes =>
            Some(ProcessedFile(
              uuid = uuid,
              originalName = metadata.originalName,
              mimeType = metadata.mimeType,
              bytes = bytes,
              altText = metadata.altText,
              width = metadata.width.getOrElse(0),
              height = metadata.height.getOrElse(0)
            ))
          }.handleErrorWith { err =>
            logger
              .error(err)(s"Failed to read file $uuid from disk")
              .as(None)
          }
      }
    } yield result

  override def getFileMetadata(uuid: UUID): IO[Option[FileMetadata]] =
    db.getByUUID(uuid)

  override def getFilePath(uuid: UUID): Path =
    config.uploadedFilesPath.resolve(uuid.toString)

  private def writeFileToDisk(uuid: UUID, bytes: Array[Byte]): IO[Unit] =
    IO.blocking {
      val path = getFilePath(uuid)
      val _ = Files.write(path, bytes)
    }

  private def readFileFromDisk(uuid: UUID): IO[Array[Byte]] =
    IO.blocking {
      val path = getFilePath(uuid)
      Files.readAllBytes(path)
    }

  private def extractImageDimensions(bytes: Array[Byte], mimeType: String): IO[(Int, Int)] =
    IO.blocking {
      if mimeType.startsWith("image/") then try {
        val image = ImageIO.read(new ByteArrayInputStream(bytes))
        if image != null then (image.getWidth, image.getHeight)
        else
          (0, 0)
      } catch {
        case _: Exception => (0, 0)
      }
      else
        (0, 0)
    }
}
