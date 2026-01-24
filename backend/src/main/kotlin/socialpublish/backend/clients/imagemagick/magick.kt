package socialpublish.backend.clients.imagemagick

import arrow.core.Either
import arrow.core.raise.either
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.apache.tika.Tika

private val logger = KotlinLogging.logger {}

enum class ImageMagickVersion {
    V6, // Uses separate 'convert' and 'identify' commands
    V7, // Uses unified 'magick' command
}

class ImageMagick
private constructor(
    private val magickPath: File,
    private val version: ImageMagickVersion,
    private val identifyPath: File? = null, // Only used for V6
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
        val (command, params) =
            when (version) {
                ImageMagickVersion.V7 ->
                    Pair(
                        magickPath.absolutePath,
                        arrayOf("identify", "-format", "%w %h", source.absolutePath),
                    )
                ImageMagickVersion.V6 ->
                    Pair(
                        identifyPath!!.absolutePath,
                        arrayOf("-format", "%w %h", source.absolutePath),
                    )
            }
        val output =
            executeShellCommand(command, *params)
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
        // Both ImageMagick 6 and 7 use the same parameters for JPEG optimization
        val command = magickPath.absolutePath
        val params =
            arrayOf(
                source.absolutePath,
                "-auto-orient",
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
            executeShellCommand(command, *params)
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
            // Both ImageMagick 6 and 7 use the same parameters for PNG optimization
            val command = magickPath.absolutePath
            val params =
                arrayOf(
                    source.absolutePath,
                    "-auto-orient",
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
                executeShellCommand(command, *params)
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
            // Try to find ImageMagick 7 (unified magick command) first
            val magickV7Result = executeShellCommand("which", "magick")
            val magickV7 = magickV7Result.orError().getOrNull()?.trim()

            if (magickV7 != null) {
                val path = File(magickV7)
                if (path.exists() && path.canExecute()) {
                    logger.info { "Found ImageMagick 7 at: ${path.absolutePath}" }
                    return@either ImageMagick(path, ImageMagickVersion.V7, null, options)
                }
            }

            // Fall back to ImageMagick 6 (separate convert and identify commands)
            logger.info { "ImageMagick 7 'magick' command not found, trying ImageMagick 6..." }

            val convertResult = executeShellCommand("which", "convert")
            val convertPath = convertResult.orError().getOrNull()?.trim()

            val identifyResult = executeShellCommand("which", "identify")
            val identifyPath = identifyResult.orError().getOrNull()?.trim()

            if (convertPath != null && identifyPath != null) {
                val convert = File(convertPath)
                val identify = File(identifyPath)

                if (
                    convert.exists() &&
                        convert.canExecute() &&
                        identify.exists() &&
                        identify.canExecute()
                ) {
                    logger.info {
                        "Found ImageMagick 6 - convert: ${convert.absolutePath}, identify: ${identify.absolutePath}"
                    }
                    return@either ImageMagick(convert, ImageMagickVersion.V6, identify, options)
                }
            }

            // Neither version found
            raise(
                MagickException(
                    "ImageMagick not found. Please install ImageMagick:\n" +
                        "  Ubuntu/Debian: sudo apt-get install imagemagick\n" +
                        "  macOS: brew install imagemagick\n" +
                        "  Or visit: https://imagemagick.org/script/download.php"
                )
            )
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
