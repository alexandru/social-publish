package socialpublish.integrations.imagemagick

import cats.effect.IO
import org.apache.tika.Tika
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import java.io.File

enum ImageMagickVersion {
  case V6 // Uses separate 'convert' and 'identify' commands
  case V7 // Uses unified 'magick' command
}

enum MimeType {
  case JPEG
  case PNG
  case IMAGE_UNKNOWN
  case OTHER
}

case class MagickImageSize(width: Int, height: Int)

case class MagickOptimizeOptions(
  jpegQuality: Int = 95,
  maxWidth: Int = 1600,
  maxHeight: Int = 1600,
  maxSizeBytes: Long = 1_000_000L // 1 MB
)

class MagickException(message: String, cause: Throwable = null)
    extends Exception(message, cause)

class ImageMagick private (
  magickPath: File,
  version: ImageMagickVersion,
  identifyPath: Option[File], // Only used for V6
  options: MagickOptimizeOptions,
  logger: Logger[IO]
) {

  /** Returns the dimensions of an image file, using ImageMagick's `identify` command.
    */
  def identifyImageSize(source: File): IO[Either[MagickException, MagickImageSize]] = {
    if !source.exists() || !source.canRead() then {
      IO.pure(Left(
        new MagickException(
          s"Source file does not exist or is not readable: ${source.getAbsolutePath}"
        )
      ))
    } else {
      val (command, params) = version match {
        case ImageMagickVersion.V7 =>
          (
            magickPath.getAbsolutePath,
            Array("identify", "-format", "%w %h", source.getAbsolutePath)
          )
        case ImageMagickVersion.V6 =>
          (identifyPath.get.getAbsolutePath, Array("-format", "%w %h", source.getAbsolutePath))
      }

      Cli.executeShellCommand(command, params*).map { result =>
        Cli.orError(result) match {
          case Right(output) =>
            // Parse output into (width, height)
            val parts = output.trim.split("\\s+").flatMap(_.toIntOption)
            if parts.length != 2 then {
              Left(new MagickException(
                s"Failed to parse image size from identify output: '$output' for file: ${source.getAbsolutePath}"
              ))
            } else {
              Right(MagickImageSize(width = parts(0), height = parts(1)))
            }
          case Left(err) =>
            Left(new MagickException(
              "ImageMagick-powered identification of image size failed",
              err
            ))
        }
      }
    }
  }

  /** Optimizes an image file, writing the optimized result to the destination file.
    *
    * The optimization process may involve resizing, recompressing, and converting between formats
    * (PNG to JPEG) to meet the specified size constraints. If the optimized image still exceeds the
    * maximum size, the process will iteratively adjust parameters (e.g., reducing JPEG quality)
    * until the size requirement is met or quality limits are reached.
    */
  def optimizeImage(source: File, dest: File): IO[Either[MagickException, Unit]] = {
    def optimizeLoop(mimeType: MimeType, quality: Int): IO[Either[MagickException, Unit]] = {
      val optimizeResult = mimeType match {
        case MimeType.PNG => optimizePng(source, dest)
        case MimeType.JPEG | MimeType.IMAGE_UNKNOWN => optimizeJpeg(source, dest, quality)
        case MimeType.OTHER =>
          IO.pure(Left(
            new MagickException(s"File is not a supported image type: ${source.getAbsolutePath}")
          ))
      }

      optimizeResult.flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right(_) =>
          IO.blocking {
            dest.length()
          }.flatMap { fileLength =>
            val withinLimit = fileLength <= options.maxSizeBytes
            if withinLimit then {
              IO.pure(Right(()))
            } else {
              logger.warn(
                s"Optimized image still too large ($fileLength bytes), re-optimizing: ${source.getAbsolutePath}"
              ) *>
                IO.blocking(dest.delete()) *> {
                  // Adjust parameters for next iteration
                  if mimeType == MimeType.PNG then {
                    // Convert PNG to JPEG
                    optimizeLoop(MimeType.JPEG, quality)
                  } else {
                    // Reduce JPEG quality
                    val newQuality = quality - 10
                    if newQuality < 40 then {
                      IO.pure(Left(new MagickException(
                        s"Cannot optimize image below size limit without excessive quality loss: ${source.getAbsolutePath}"
                      )))
                    } else {
                      optimizeLoop(MimeType.JPEG, newQuality)
                    }
                  }
                }
            }
          }
      }
    }

    for {
      mimeType <- detectMimeType(source)
      result <- optimizeLoop(mimeType, options.jpegQuality)
    } yield result
  }

  private def detectMimeType(file: File): IO[MimeType] = {
    IO.blocking {
      try {
        val tika = new Tika()
        val mimeTypeStr = tika.detect(file).toLowerCase
        mimeTypeStr match {
          case "image/jpeg" | "image/jpg" => MimeType.JPEG
          case "image/png" => MimeType.PNG
          case s if s.startsWith("image/") => MimeType.IMAGE_UNKNOWN
          case _ => MimeType.OTHER
        }
      } catch {
        case _: Exception =>
          MimeType.OTHER
      }
    }.flatTap {
      case MimeType.OTHER =>
        logger.warn(s"Failed to detect MIME type for file: ${file.getAbsolutePath}")
      case _ => IO.unit
    }
  }

  private def validateFiles(source: File, dest: File): Either[MagickException, Unit] = {
    if !source.exists() then {
      Left(new MagickException(s"Source file does not exist: ${source.getAbsolutePath}"))
    } else if !source.canRead() then {
      Left(new MagickException(s"Source file is not readable: ${source.getAbsolutePath}"))
    } else if dest.exists() then {
      Left(new MagickException(s"Destination file already exists: ${dest.getAbsolutePath}"))
    } else {
      Right(())
    }
  }

  private def optimizeJpeg(
    source: File,
    dest: File,
    quality: Int
  ): IO[Either[MagickException, Unit]] = {
    validateFiles(source, dest) match {
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        // Ensure parent directory exists
        IO.blocking {
          val parentDir = dest.getParentFile
          if parentDir != null && !parentDir.exists() then {
            parentDir.mkdirs()
            ()
          }
        } *> {
          val command = magickPath.getAbsolutePath
          val params = Array(
            source.getAbsolutePath,
            "-auto-orient",
            "-resize",
            s"${options.maxWidth}x${options.maxHeight}>",
            "-strip",
            "-quality",
            quality.toString,
            "-sampling-factor",
            "4:2:2",
            "-interlace",
            "JPEG",
            s"jpeg:${dest.getAbsolutePath}"
          )

          Cli.executeShellCommand(command, params*).map { result =>
            Cli.orError(result) match {
              case Right(_) =>
                if !dest.exists() then {
                  Left(new MagickException(
                    s"Optimization failed, destination file not created: ${dest.getAbsolutePath}"
                  ))
                } else {
                  Right(())
                }
              case Left(err) =>
                Left(new MagickException(
                  s"ImageMagick JPEG optimization command failed.\nCommand: ${err.command}\nExit code: ${err.exitCode}\nStdout: ${err.stdout}\nStderr: ${err.stderr}",
                  err
                ))
            }
          }
        }
    }
  }

  private def optimizePng(source: File, dest: File): IO[Either[MagickException, Unit]] = {
    validateFiles(source, dest) match {
      case Left(err) => IO.pure(Left(err))
      case Right(_) =>
        // Ensure parent directory exists
        IO.blocking {
          val parentDir = dest.getParentFile
          if parentDir != null && !parentDir.exists() then {
            parentDir.mkdirs()
            ()
          }
        } *> {
          val command = magickPath.getAbsolutePath
          val params = Array(
            source.getAbsolutePath,
            "-auto-orient",
            "-resize",
            s"${options.maxWidth}x${options.maxHeight}>",
            "-strip",
            "-define",
            "png:compression-level=9",
            "-define",
            "png:compression-strategy=1",
            s"png:${dest.getAbsolutePath}"
          )

          Cli.executeShellCommand(command, params*).map { result =>
            Cli.orError(result) match {
              case Right(_) =>
                if !dest.exists() then {
                  Left(new MagickException(
                    s"Optimization failed, destination file not created: ${dest.getAbsolutePath}"
                  ))
                } else {
                  Right(())
                }
              case Left(err) =>
                Left(new MagickException("ImageMagick PNG optimization command failed", err))
            }
          }
        }
    }
  }

}

object ImageMagick {

  /** Creates an ImageMagick instance by detecting the installed version. Tries ImageMagick 7
    * (unified 'magick' command) first, then falls back to ImageMagick 6 (separate 'convert' and
    * 'identify' commands).
    */
  def apply(options: MagickOptimizeOptions =
    MagickOptimizeOptions()): IO[Either[MagickException, ImageMagick]] = {
    for {
      logger <- Slf4jLogger.create[IO]
      // Try to find ImageMagick 7 (unified magick command) first
      magickV7Result <- Cli.executeShellCommand("which", "magick")
      magickV7 = Cli.orError(magickV7Result).toOption.map(_.trim).filter(_.nonEmpty)

      result <- magickV7 match {
        case Some(path) =>
          val file = new File(path)
          if file.exists() && file.canExecute() then {
            logger.info(s"Found ImageMagick 7 at: ${file.getAbsolutePath}") *>
              IO.pure(Right(new ImageMagick(file, ImageMagickVersion.V7, None, options, logger)))
          } else {
            findImageMagick6(options, logger)
          }
        case None =>
          logger.info("ImageMagick 7 'magick' command not found, trying ImageMagick 6...") *>
            findImageMagick6(options, logger)
      }
    } yield result
  }

  private def findImageMagick6(
    options: MagickOptimizeOptions,
    logger: Logger[IO]
  ): IO[Either[MagickException, ImageMagick]] = {
    for {
      convertResult <- Cli.executeShellCommand("which", "convert")
      convertPath = Cli.orError(convertResult).toOption.map(_.trim).filter(_.nonEmpty)

      identifyResult <- Cli.executeShellCommand("which", "identify")
      identifyPath = Cli.orError(identifyResult).toOption.map(_.trim).filter(_.nonEmpty)

      result <- (convertPath, identifyPath) match {
        case (Some(cPath), Some(iPath)) =>
          val convert = new File(cPath)
          val identify = new File(iPath)

          if convert.exists() && convert.canExecute() && identify.exists() && identify.canExecute()
          then {
            logger.info(
              s"Found ImageMagick 6 - convert: ${convert.getAbsolutePath}, identify: ${identify.getAbsolutePath}"
            ) *>
              IO.pure(Right(new ImageMagick(
                convert,
                ImageMagickVersion.V6,
                Some(identify),
                options,
                logger
              )))
          } else {
            notFoundError()
          }
        case _ =>
          notFoundError()
      }
    } yield result
  }

  private def notFoundError(): IO[Either[MagickException, ImageMagick]] = {
    IO.pure(Left(new MagickException(
      "ImageMagick not found. Please install ImageMagick:\n" +
        "  Ubuntu/Debian: sudo apt-get install imagemagick\n" +
        "  macOS: brew install imagemagick\n" +
        "  Or visit: https://imagemagick.org/script/download.php"
    )))
  }

}
