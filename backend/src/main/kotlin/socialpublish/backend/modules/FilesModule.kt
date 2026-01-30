package socialpublish.backend.modules

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import org.apache.tika.Tika
import socialpublish.backend.clients.imagemagick.ImageMagick
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.CaughtException
import socialpublish.backend.common.LoomIO
import socialpublish.backend.common.ValidationError
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.UploadPayload

private val logger = KotlinLogging.logger {}

@Serializable data class FileUploadResponse(val uuid: String, val url: String)

data class UploadedFile(val fileName: String, val fileBytes: ByteArray, val altText: String?)

data class ProcessedUpload(
    val originalname: String,
    val mimetype: String,
    val altText: String?,
    val width: Int,
    val height: Int,
    val size: Long,
    val bytes: ByteArray,
)

data class StoredFile(val file: File, val mimeType: String, val originalName: String)

data class FilesConfig(val uploadedFilesPath: File, val baseUrl: String)

class FilesModule
private constructor(
    private val config: FilesConfig,
    private val db: FilesDatabase,
    private val uploadedFilesPath: File,
    private val imageMagick: ImageMagick,
) {
    private val originalPath = File(uploadedFilesPath, "original")
    private val processedPath = File(uploadedFilesPath, "processed")

    companion object {
        suspend fun create(config: FilesConfig, db: FilesDatabase): FilesModule {
            val imageMagick =
                ImageMagick().getOrElse { error("Failed to initialize ImageMagick: ${it.message}") }
            val ref = FilesModule(config, db, config.uploadedFilesPath, imageMagick)
            runInterruptible(Dispatchers.LoomIO) {
                ref.uploadedFilesPath.mkdirs()
                ref.originalPath.mkdirs()
                ref.processedPath.mkdirs()
            }
            logger.info { "Files module initialized at ${config.uploadedFilesPath}" }
            return ref
        }
    }

    /** Upload and process file */
    suspend fun uploadFile(upload: UploadedFile): ApiResult<FileUploadResponse> {
        return try {
            val altText = upload.altText
            val fileBytes = upload.fileBytes
            val fileName = upload.fileName

            // Calculate hash
            val hash = calculateHash(fileBytes)

            val formatName = detectImageFormat(fileBytes)
            val mimeType = formatName?.let { toSupportedMimeType(it) }
            if (mimeType == null) {
                return ValidationError(
                        status = 400,
                        errorMessage =
                            "Only PNG and JPEG images are supported, got: ${formatName ?: "unknown"}",
                        module = "files",
                    )
                    .left()
            }

            val processed = processImage(fileBytes, fileName, mimeType, altText)

            // Save to database
            val upload =
                db.createFile(
                        UploadPayload(
                            hash = hash,
                            originalname = processed.originalname,
                            mimetype = processed.mimetype,
                            size = processed.size,
                            altText = processed.altText,
                            imageWidth = if (processed.width > 0) processed.width else null,
                            imageHeight = if (processed.height > 0) processed.height else null,
                        )
                    )
                    .getOrElse { throw it }

            // Save both original and processed files to disk
            runInterruptible(Dispatchers.LoomIO) {
                // Save original unprocessed file
                val originalFilePath = File(originalPath, upload.hash)
                originalFilePath.writeBytes(fileBytes)

                // Save processed/optimized file
                val processedFilePath = File(processedPath, upload.hash)
                processedFilePath.writeBytes(processed.bytes)
            }

            logger.info { "File uploaded: ${upload.uuid} (${upload.originalname})" }

            FileUploadResponse(uuid = upload.uuid, url = "${config.baseUrl}/files/${upload.uuid}")
                .right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload file" }
            CaughtException(
                    status = 500,
                    module = "files",
                    errorMessage = "Failed to upload file: ${e.message}",
                )
                .left()
        }
    }

    /** Retrieve uploaded file */
    suspend fun getFile(uuid: String): ApiResult<StoredFile> {
        return try {
            val upload = db.getFileByUuid(uuid).getOrElse { throw it }
            if (upload == null) {
                return ValidationError(
                        status = 404,
                        errorMessage = "File not found",
                        module = "files",
                    )
                    .left()
            }

            val filePath = File(processedPath, upload.hash)
            if (!filePath.exists()) {
                return ValidationError(
                        status = 404,
                        errorMessage = "File content not found",
                        module = "files",
                    )
                    .left()
            }

            StoredFile(
                    file = filePath,
                    mimeType = upload.mimetype,
                    originalName = upload.originalname,
                )
                .right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get file" }
            CaughtException(
                    status = 500,
                    module = "files",
                    errorMessage = "Failed to get file: ${e.message}",
                )
                .left()
        }
    }

    /** Read image file for API posting */
    suspend fun readImageFile(uuid: String): ProcessedUpload? {
        val upload = db.getFileByUuid(uuid).getOrElse { throw it } ?: return null
        val filePath = File(processedPath, upload.hash)

        val bytes =
            runInterruptible(Dispatchers.LoomIO) {
                if (!filePath.exists()) {
                    null
                } else {
                    filePath.readBytes()
                }
            } ?: return null

        return ProcessedUpload(
            originalname = upload.originalname,
            mimetype = upload.mimetype,
            altText = upload.altText,
            width = upload.imageWidth ?: 0,
            height = upload.imageHeight ?: 0,
            size = bytes.size.toLong(),
            bytes = bytes,
        )
    }

    /** Get the public URL for a file */
    fun getFileUrl(uuid: String): String {
        return "${config.baseUrl}/files/$uuid"
    }

    private suspend fun detectImageFormat(bytes: ByteArray): String? {
        return runInterruptible(Dispatchers.LoomIO) {
            try {
                val mimeType = Tika().detect(bytes).lowercase()
                when {
                    mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpeg"
                    mimeType.contains("png") -> "png"
                    else -> null
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to detect image format" }
                null
            }
        }
    }

    private fun toSupportedMimeType(format: String): String? {
        return when (format.lowercase()) {
            "png" -> "image/png"
            "jpg",
            "jpeg" -> "image/jpeg"
            else -> null
        }
    }

    private suspend fun processImage(
        bytes: ByteArray,
        originalName: String,
        mimeType: String,
        altText: String?,
    ): ProcessedUpload {
        return try {
            // Write bytes to temp file for ImageMagick processing
            val sourceFile =
                runInterruptible(Dispatchers.LoomIO) {
                    File.createTempFile("upload-source-", ".tmp").apply { writeBytes(bytes) }
                }
            try {
                // Optimize image using ImageMagick (resizes to max 1600x1600, compresses)
                val optimizedFile =
                    runInterruptible(Dispatchers.LoomIO) {
                        File.createTempFile("upload-optimized-", ".tmp").apply {
                            delete() // Delete the file, we just want the path
                        }
                    }
                try {
                    imageMagick.optimizeImage(sourceFile, optimizedFile).getOrElse { throw it }

                    val optimizedBytes =
                        runInterruptible(Dispatchers.LoomIO) { optimizedFile.readBytes() }

                    val size =
                        imageMagick.identifyImageSize(optimizedFile).getOrElse {
                            logger.warn(it) { "Failed to identify optimized image size" }
                            null
                        }

                    ProcessedUpload(
                        originalname = originalName,
                        mimetype = mimeType,
                        altText = altText,
                        width = size?.width ?: 0,
                        height = size?.height ?: 0,
                        size = optimizedBytes.size.toLong(),
                        bytes = optimizedBytes,
                    )
                } finally {
                    runInterruptible(Dispatchers.LoomIO) { optimizedFile.delete() }
                }
            } finally {
                runInterruptible(Dispatchers.LoomIO) { sourceFile.delete() }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to optimize image, using original" }
            // Fallback to using original if optimization fails
            val tempFile =
                runInterruptible(Dispatchers.LoomIO) {
                    File.createTempFile("upload-fallback-", ".tmp").apply { writeBytes(bytes) }
                }
            try {
                val size =
                    imageMagick.identifyImageSize(tempFile).getOrElse {
                        logger.warn(it) { "Failed to identify image size" }
                        null
                    }
                ProcessedUpload(
                    originalname = originalName,
                    mimetype = mimeType,
                    altText = altText,
                    width = size?.width ?: 0,
                    height = size?.height ?: 0,
                    size = bytes.size.toLong(),
                    bytes = bytes,
                )
            } finally {
                runInterruptible(Dispatchers.LoomIO) { tempFile.delete() }
            }
        }
    }

    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
