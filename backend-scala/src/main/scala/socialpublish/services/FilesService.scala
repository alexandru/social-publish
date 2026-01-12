package socialpublish.services

import cats.effect.*
import cats.effect.std.Semaphore
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import socialpublish.db.FilesDatabase
import socialpublish.models.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

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

  def apply(config: FilesConfig, db: FilesDatabase): IO[FilesService] =
    for {
      logger <- Slf4jLogger.create[IO]
      locks <- Ref.of[IO, Map[String, Semaphore[IO]]](Map.empty)
      _ <- IO.blocking {
        val uploadPath = config.uploadedFilesPath
        if !Files.exists(uploadPath) then {
          val _ = Files.createDirectories(uploadPath)
        }
      }
    } yield new FilesServiceImpl(config, db, logger, locks)

  def resource(cfg: FilesConfig, db: FilesDatabase): Resource[IO, FilesService] =
    Resource.eval {
      for {
        logger <- Slf4jLogger.create[IO]
        locks <- Ref.of[IO, Map[String, Semaphore[IO]]](Map.empty)
        _ <- IO.blocking {
          val uploadPath = cfg.uploadedFilesPath
          if !Files.exists(uploadPath) then {
            val _ = Files.createDirectories(uploadPath)
          }
        }
      } yield new FilesServiceImpl(
        cfg,
        db,
        logger,
        locks
      )
    }

}

private class FilesServiceImpl(
  config: FilesConfig,
  db: FilesDatabase,
  logger: Logger[IO],
  locks: Ref[IO, Map[String, Semaphore[IO]]]
) extends FilesService {

  private val supportedImageTypes = Set("image/png", "image/jpeg")
  private val maxImageWidth = 1920
  private val maxImageHeight = 1080

  override def saveFile(
    filename: String,
    mimeType: String,
    bytes: Array[Byte],
    altText: Option[String]
  ): IO[FileMetadata] =
    for {
      hash <- calculateHash(bytes)
      existingFile <- db.getByHash(hash)
      metadata <- existingFile match {
        case Some(existing) =>
          logger.info(s"File already exists with hash $hash, reusing ${existing.uuid}") *>
            IO.pure(existing)
        case None =>
          for {
            uuid <- IO.delay(UUID.randomUUID())
            dimensionsOpt <- extractImageDimensions(bytes, mimeType)
            (widthOpt, heightOpt) = dimensionsOpt match {
              case Some((width, height)) => (Some(width), Some(height))
              case None => (None, None)
            }
            _ <- writeFileToDisk(hash, bytes)
            metadata = FileMetadata(
              uuid = uuid,
              originalName = filename,
              mimeType = mimeType,
              size = bytes.length.toLong,
              altText = altText,
              width = widthOpt,
              height = heightOpt,
              hash = Some(hash),
              createdAt = Instant.now()
            )
            _ <- db.save(metadata)
            _ <- logger.info(s"Saved new file $uuid ($filename) with hash $hash")
          } yield metadata
      }
    } yield metadata

  override def getFile(uuid: UUID): IO[Option[ProcessedFile]] =
    for {
      metadataOpt <- db.getByUUID(uuid)
      result <- metadataOpt match {
        case None => IO.pure(None)
        case Some(metadata) =>
          metadata.hash match {
            case Some(hash) =>
              withLock(hash) {
                readFileFromDiskByHash(hash).flatMap { bytes =>
                  processFile(metadata, bytes)
                }
              }.handleErrorWith { err =>
                logger
                  .error(err)(s"Failed to read file $uuid from disk")
                  .as(None)
              }
            case None =>
              logger.warn(s"File $uuid has no hash, cannot read") *> IO.pure(None)
          }
      }
    } yield result

  override def getFileMetadata(uuid: UUID): IO[Option[FileMetadata]] =
    db.getByUUID(uuid)

  override def getFilePath(uuid: UUID): Path =
    config.uploadedFilesPath.resolve(uuid.toString)

  private def getFilePathByHash(hash: String): Path =
    config.uploadedFilesPath.resolve(hash)

  private def calculateHash(bytes: Array[Byte]): IO[String] =
    IO.blocking {
      val digest = MessageDigest.getInstance("SHA-256")
      val hashBytes = digest.digest(bytes)
      hashBytes.map("%02x".format(_)).mkString
    }

  private def withLock[A](key: String)(f: IO[A]): IO[A] =
    for {
      sem <- locks.modify { current =>
        current.get(key) match {
          case Some(existing) => (current, existing.pure[IO])
          case None => 
            val newSem = Semaphore[IO](1)
            (current + (key -> newSem.unsafeRunSync()(cats.effect.unsafe.IORuntime.global)), newSem)
        }
      }.flatten
      result <- sem.permit.use(_ => f)
    } yield result

  private def writeFileToDisk(hash: String, bytes: Array[Byte]): IO[Unit] =
    IO.blocking {
      val path = getFilePathByHash(hash)
      val _ = Files.write(path, bytes)
    }

  private def readFileFromDiskByHash(hash: String): IO[Array[Byte]] =
    IO.blocking {
      val path = getFilePathByHash(hash)
      Files.readAllBytes(path)
    }

  private def processFile(metadata: FileMetadata, bytes: Array[Byte]): IO[Option[ProcessedFile]] = {
    val processed =
      if metadata.mimeType.startsWith("image/") then {
        for {
          dimensionsOpt <- extractImageDimensions(bytes, metadata.mimeType)
          (width, height) = dimensionsOpt.getOrElse((0, 0))
          resized <- resizeImage(bytes, metadata.mimeType, width, height)
        } yield resized
      } else {
        IO.pure((bytes, metadata.width.getOrElse(0), metadata.height.getOrElse(0)))
      }

    processed.map { case (processedBytes, width, height) =>
      Some(ProcessedFile(
        uuid = metadata.uuid,
        originalName = metadata.originalName,
        mimeType = metadata.mimeType,
        bytes = processedBytes,
        altText = metadata.altText,
        width = width,
        height = height
      ))
    }
  }

  private def extractImageDimensions(
    bytes: Array[Byte],
    mimeType: String
  ): IO[Option[(Int, Int)]] =
    if mimeType.startsWith("image/") then {
      IO.blocking {
        val normalized = mimeType.toLowerCase
        if !supportedImageTypes.contains(normalized) then {
          throw new IllegalArgumentException(s"Unsupported image type: $mimeType")
        }
        val image = ImageIO.read(new ByteArrayInputStream(bytes))
        if image == null then {
          throw new IllegalArgumentException("Invalid image data")
        }
        Some((image.getWidth, image.getHeight))
      }
    } else {
      IO.pure(None)
    }

  private def resizeImage(
    bytes: Array[Byte],
    mimeType: String,
    width: Int,
    height: Int
  ): IO[(Array[Byte], Int, Int)] =
    IO.blocking {
      val normalized = mimeType.toLowerCase
      if !supportedImageTypes.contains(normalized) || width == 0 || height == 0 then {
        (bytes, width, height)
      } else {
        val (newWidth, newHeight) =
          if width >= height && width > maxImageWidth then {
            val scaledHeight = Math.round(height.toDouble / width * maxImageWidth).toInt
            (maxImageWidth, scaledHeight)
          } else if height > maxImageHeight then {
            val scaledWidth = Math.round(width.toDouble / height * maxImageHeight).toInt
            (scaledWidth, maxImageHeight)
          } else {
            (width, height)
          }

        if newWidth == width && newHeight == height then {
          (bytes, width, height)
        } else {
          val image = ImageIO.read(new ByteArrayInputStream(bytes))
          if image == null then {
            throw new IllegalArgumentException("Invalid image data")
          }
          val imageType =
            if normalized == "image/png" then BufferedImage.TYPE_INT_ARGB
            else BufferedImage.TYPE_INT_RGB
          val resized = new BufferedImage(newWidth, newHeight, imageType)
          val graphics = resized.createGraphics()
          try {
            graphics.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION,
              RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            graphics.drawImage(image, 0, 0, newWidth, newHeight, null)
          } finally {
            graphics.dispose()
          }
          val output = new ByteArrayOutputStream()
          val format = if normalized == "image/png" then "png" else "jpg"
          val _ = ImageIO.write(resized, format, output)
          (output.toByteArray, newWidth, newHeight)
        }
      }
    }

}
