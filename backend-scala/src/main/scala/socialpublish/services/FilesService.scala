package socialpublish.services

import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import socialpublish.db.FilesDatabase
import socialpublish.models.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, Path}
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
      _ <- IO.blocking {
        val uploadPath = config.uploadedFilesPath
        if !Files.exists(uploadPath) then {
          val _ = Files.createDirectories(uploadPath)
        }
      }
    } yield new FilesServiceImpl(config, db, logger)

  def resource(cfg: FilesConfig, db: FilesDatabase): Resource[IO, FilesService] =
    Resource.eval {
      for {
        logger <- Slf4jLogger.create[IO]
        _ <- IO.blocking {
          val uploadPath = cfg.uploadedFilesPath
          if !Files.exists(uploadPath) then {
            val _ = Files.createDirectories(uploadPath)
          }
        }
      } yield new FilesServiceImpl(
        cfg,
        db,
        logger
      )
    }

}

private class FilesServiceImpl(
  config: FilesConfig,
  db: FilesDatabase,
  logger: Logger[IO]
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
      uuid <- IO.delay(UUID.randomUUID())
      dimensionsOpt <- extractImageDimensions(bytes, mimeType)
      (widthOpt, heightOpt) = dimensionsOpt match {
        case Some((width, height)) => (Some(width), Some(height))
        case None => (None, None)
      }
      _ <- writeFileToDisk(uuid, bytes)
      metadata = FileMetadata(
        uuid = uuid,
        originalName = filename,
        mimeType = mimeType,
        size = bytes.length.toLong,
        altText = altText,
        width = widthOpt,
        height = heightOpt,
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
          readFileFromDisk(uuid).flatMap { bytes =>
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
                uuid = uuid,
                originalName = metadata.originalName,
                mimeType = metadata.mimeType,
                bytes = processedBytes,
                altText = metadata.altText,
                width = width,
                height = height
              ))
            }
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
