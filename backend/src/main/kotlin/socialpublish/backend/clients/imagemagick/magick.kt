package socialpublish.backend.clients.imagemagick

import arrow.core.Either
import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.apache.tika.Tika

private val logger = KotlinLogging.logger {}

class ImageMagick
private constructor(
    private val magickPath: File,
    private val options: MagickOptimizeOptions = MagickOptimizeOptions(),
) {
    /** Returns the dimensions of an image file, using ImageMagick's `identify` command. */
    suspend fun identifyImageSize(source: File): Either<MagickException, MagickImageSize> = either {
        if (!source.exists() || !source.canRead()) {
            raise(
                MagickException(
                    "Source file does not exist or is not readable: ${source.absolutePath}"
                )
            )
        }
        val params = arrayOf("identify", "-format", "%w %h", source.absolutePath)
        val output =
            executeShellCommand(magickPath.absolutePath, *params)
                .orError()
                .mapLeft {
                    MagickException("ImageMagick-powered identification of image size failed", it)
                }
                .bind()
        // Parse output into Pair<Int, Int>
        val parts = output.trim().split("\\s+".toRegex()).mapNotNull { it.toIntOrNull() }
        if (parts.size != 2)
            raise(
                MagickException(
                    "Failed to parse image size from identify output: '$output' for file: ${source.absolutePath}"
                )
            )
        MagickImageSize(width = parts[0], height = parts[1])
    }

    /**
     * Optimizes an image file, writing the optimized result to the destination file.
     *
     * The optimization process may involve resizing, recompressing, and converting between formats
     * (PNG to JPEG) to meet the specified size constraints. If the optimized image still exceeds
     * the maximum size, the process will iteratively adjust parameters (e.g., reducing JPEG
     * quality) until the size requirement is met or quality limits are reached.
     */
    suspend fun optimizeImage(source: File, dest: File): Either<MagickException, Unit> = either {
        var mimeType = detectMimeType(source)
        var hasDestination = false
        var quality = options.jpegQuality

        while (!hasDestination) {
            when (mimeType) {
                MimeType.PNG -> optimizePng(source, dest).bind()
                MimeType.JPEG,
                MimeType.IMAGE_UNKNOWN -> optimizeJpeg(source, dest, quality).bind()
                MimeType.OTHER ->
                    raise(
                        MagickException(
                            "File is not a supported image type: ${source.absolutePath}"
                        )
                    )
            }
            runInterruptible(Dispatchers.IO) {
                // Check file size
                val fileLength = dest.length()
                hasDestination = fileLength <= options.maxSizeBytes
                if (!hasDestination) {
                    logger.warn {
                        "Optimized image still too large (${fileLength} bytes), re-optimizing: ${source.absolutePath}"
                    }
                    // Aiming for a smaller size, adjusting parameters
                    // First, if PNG, convert to JPEG
                    if (mimeType == MimeType.PNG) {
                        mimeType = MimeType.JPEG
                    } else {
                        // Reduce quality for JPEGs
                        quality -= 10
                        if (quality < 40) {
                            raise(
                                MagickException(
                                    "Cannot optimize image below size limit without excessive quality loss: ${source.absolutePath}"
                                )
                            )
                        }
                    }
                    // Delete previous dest file before re-optimizing
                    dest.delete()
                }
            }
        }
    }

    private suspend fun detectMimeType(file: File): MimeType =
        runInterruptible(Dispatchers.IO) {
            try {
                when (val mimeType = Tika().detect(file).lowercase()) {
                    "image/jpeg",
                    "image/jpg" -> MimeType.JPEG
                    "image/png" -> MimeType.PNG
                    else ->
                        if (mimeType.startsWith("image/")) MimeType.IMAGE_UNKNOWN
                        else MimeType.OTHER
                }
            } catch (_: Exception) {
                logger.warn { "Failed to detect MIME type for file: ${file.absolutePath}" }
                MimeType.OTHER
            }
        }

    private fun validateFiles(source: File, dest: File): Either<MagickException, Unit> = either {
        if (!source.exists()) {
            raise(MagickException("Source file does not exist: ${source.absolutePath}"))
        } else if (!source.canRead()) {
            raise(MagickException("Source file is not readable: ${source.absolutePath}"))
        } else if (dest.exists()) {
            raise(MagickException("Destination file already exists: ${dest.absolutePath}"))
        }
    }

    private suspend fun optimizeJpeg(
        source: File,
        dest: File,
        quality: Int = options.jpegQuality,
    ): Either<MagickException, Unit> = either {
        validateFiles(source, dest).bind()
        val params =
            arrayOf(
                source.absolutePath,
                "-resize",
                "${options.maxWidth}x${options.maxHeight}>",
                "-strip",
                "-quality",
                quality.toString(),
                "-sampling-factor",
                "4:2:2",
                "-interlace",
                "JPEG",
                "jpeg:${dest.absolutePath}",
            )
        val _ =
            executeShellCommand(magickPath.absolutePath, *params)
                .orError()
                .mapLeft { MagickException("ImageMagick JPEG optimization command failed", it) }
                .bind()
        if (!dest.exists()) {
            raise(
                MagickException(
                    "Optimization failed, destination file not created: ${dest.absolutePath}"
                )
            )
        }
    }

    private suspend fun optimizePng(source: File, dest: File): Either<MagickException, Unit> =
        either {
            validateFiles(source, dest).bind()
            val params =
                arrayOf(
                    source.absolutePath,
                    "-resize",
                    "${options.maxWidth}x${options.maxHeight}>",
                    "-strip",
                    "-define",
                    "png:compression-level=9",
                    "-define",
                    "png:compression-strategy=1",
                    "png:${dest.absolutePath}",
                )
            val _ =
                executeShellCommand(magickPath.absolutePath, *params)
                    .orError()
                    .mapLeft { MagickException("ImageMagick PNG optimization command failed", it) }
                    .bind()
            if (!dest.exists()) {
                raise(
                    MagickException(
                        "Optimization failed, destination file not created: ${dest.absolutePath}"
                    )
                )
            }
        }

    companion object {
        suspend operator fun invoke(
            options: MagickOptimizeOptions = MagickOptimizeOptions()
        ): Either<MagickException, ImageMagick> = either {
            // Find path
            val which =
                executeShellCommand("which", "magick")
                    .orError()
                    .mapLeft {
                        MagickException("Failed to locate ImageMagick 'magick' command", it)
                    }
                    .bind()
            val path = File(which.trim())
            if (!path.exists() || !path.canExecute()) {
                raise(
                    MagickException(
                        "ImageMagick 'magick' command not found or not executable at path: ${path.absolutePath}"
                    )
                )
            }
            ImageMagick(path, options)
        }
    }
}

data class MagickOptimizeOptions(
    val jpegQuality: Int = 95,
    val maxWidth: Int = 1600,
    val maxHeight: Int = 1600,
    val maxSizeBytes: Long = 1_000_000L, // 1 MB
)

class MagickException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class MimeType {
    JPEG,
    PNG,
    IMAGE_UNKNOWN,
    OTHER,
}

data class MagickImageSize(val width: Int, val height: Int)
