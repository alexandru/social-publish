package socialpublish.backend.modules

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.apache.tika.Tika
import socialpublish.backend.clients.imagemagick.ImageMagick
import socialpublish.backend.common.*
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.UploadPayload

private val logger = KotlinLogging.logger {}

@Serializable data class FileUploadResponse(val uuid: String, val url: String)

data class UploadedFile(
    val fileName: String,
    val source: UploadSource,
    val altText: String?
)

data class ProcessedUpload(
    val originalname: String,
    val mimetype: String,
    val altText: String?,
    val width: Int,
    val height: Int,
    val size: Long,
    val source: UploadSource,
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
    suspend fun uploadFile(upload: UploadedFile): ApiResult<FileUploadResponse> = resourceScope {
        try {
            val altText = upload.altText
            val fileName = upload.fileName
            val originalFileTmp = upload.source.asFileResource().bind()
            val hash = originalFileTmp.calculateHash()
            val formatName = detectImageFormat(originalFileTmp)

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

            val processedFilePath = File(processedPath, hash)
            val processed =
                processedFilePath.deleteWithBackup {
                    processImage(
                        // WARNING: This param is fine, only because `processImage`
                        // doesn't need to keep the source after:
                        UploadSource.FromFile(originalFileTmp),
                        originalName = fileName,
                        mimeType = mimeType,
                        altText = altText,
                        saveToFile = processedFilePath
                    ).bind()
                }

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
                // copy from temporary file to permanent location
                originalFileTmp.copyTo(originalFilePath, overwrite = true)
            }

            logger.info { "File uploaded: ${upload.uuid} (${upload.originalname})" }
            FileUploadResponse(uuid = upload.uuid, url = "${config.baseUrl}/files/${upload.uuid}").right()
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

        val (source, size) =
            runInterruptible(Dispatchers.LoomIO) {
                if (!filePath.exists()) {
                    null
                } else {
                    val source = UploadSource.FromFile(filePath)
                    val size = filePath.length()
                    Pair(source, size)
                }
            } ?: return null

        return ProcessedUpload(
            originalname = upload.originalname,
            mimetype = upload.mimetype,
            altText = upload.altText,
            width = upload.imageWidth ?: 0,
            height = upload.imageHeight ?: 0,
            size = size,
            source = source,
        )
    }

    private suspend fun detectImageFormat(file: File): String? {
        return runInterruptible(Dispatchers.LoomIO) {
            try {
                val mimeType = Tika().detect(file).lowercase()
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

    /**
     * Process and optimize image upload.
     *
     * @param uploadSource The source of the uploaded image — this can be destroyed
     *        after processing.
     * @param originalName The original file name of the uploaded image.
     * @param mimeType The MIME type of the uploaded image.
     * @param altText Optional alt text for the image.
     *
     * @return A [Resource] containing the [ProcessedUpload] information.
     */
    private fun processImage(
        uploadSource: UploadSource,
        originalName: String,
        mimeType: String,
        altText: String?,
        saveToFile: File? = null,
    ): Resource<ProcessedUpload> = resource {
        val sourceFile =
            uploadSource.asFileResource().bind()
        val optimizedFile =
            saveToFile ?: createTempFileName("upload-optimized-", ".tmp")
        // Go, go, go
        imageMagick.optimizeImage(sourceFile, optimizedFile).getOrElse { throw it }

        val fileSize =
            withContext(Dispatchers.LoomIO) { optimizedFile.length() }
        val imageSize =
            imageMagick.identifyImageSize(optimizedFile).getOrElse {
                logger.warn(it) { "Failed to identify optimized image size" }
                null
            }
        ProcessedUpload(
            originalname = originalName,
            mimetype = mimeType,
            altText = altText,
            width = imageSize?.width ?: 0,
            height = imageSize?.height ?: 0,
            size = fileSize,
            source = UploadSource.FromFile(optimizedFile)
        )
    }
}
