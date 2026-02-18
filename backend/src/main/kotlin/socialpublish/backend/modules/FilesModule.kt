package socialpublish.backend.modules

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.apache.tika.Tika
import socialpublish.backend.clients.imagemagick.ImageMagick
import socialpublish.backend.common.*
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.UploadPayload

private val logger = KotlinLogging.logger {}

@Serializable data class FileUploadResponse(val uuid: String, val url: String, val mimeType: String)

data class UploadedFile(val fileName: String, val source: UploadSource, val altText: String?)

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
    companion object {
        private val optimizationStripes = Array(64) { Mutex() }

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

    private val originalPath = File(uploadedFilesPath, "original")
    private val processedPath = File(uploadedFilesPath, "processed")

    /** Upload and process file */
    suspend fun uploadFile(upload: UploadedFile, userUuid: UUID): ApiResult<FileUploadResponse> =
        resourceScope {
            try {
                val originalFileTmp = upload.source.asFileResource().bind()
                val hash = originalFileTmp.calculateHash()
                val lock =
                    optimizationStripes[
                        (hash.hashCode() and Int.MAX_VALUE) % optimizationStripes.size]

                lock.withLock {
                    val processedFilePath = File(processedPath, hash)
                    val processed =
                        processFile(
                                upload.copy(source = UploadSource.FromFile(originalFileTmp)),
                                saveToFile = processedFilePath,
                            )
                            .bind()
                            .getOrElse {
                                return@resourceScope it.left()
                            }

                    // Save to database
                    val storedUpload =
                        db.createFile(
                                UploadPayload(
                                    hash = hash,
                                    originalname = processed.originalname,
                                    mimetype = processed.mimetype,
                                    size = processed.size,
                                    userUuid = userUuid,
                                    altText = processed.altText,
                                    imageWidth = if (processed.width > 0) processed.width else null,
                                    imageHeight =
                                        if (processed.height > 0) processed.height else null,
                                )
                            )
                            .getOrElse { throw it }

                    // Save both original and processed files to disk
                    runInterruptible(Dispatchers.LoomIO) {
                        // Save original unprocessed file
                        val originalFilePath = File(originalPath, storedUpload.hash)
                        // copy from temporary file to permanent location
                        originalFileTmp.copyTo(originalFilePath, overwrite = true)
                    }

                    logger.info {
                        "File uploaded: ${storedUpload.uuid} (${storedUpload.originalname})"
                    }
                    FileUploadResponse(
                            uuid = storedUpload.uuid,
                            url = "${config.baseUrl}/files/${storedUpload.uuid}",
                            mimeType = storedUpload.mimetype,
                        )
                        .right()
                }
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

    /** Process an uploaded file without saving it. */
    fun processFile(
        upload: UploadedFile,
        saveToFile: File? = null,
    ): Resource<Either<ApiError, ProcessedUpload>> = resource {
        try {
            val altText = upload.altText
            val fileName = upload.fileName
            val originalFileTmp = upload.source.asFileResource().bind()
            val formatName = detectImageFormat(originalFileTmp)

            val mimeType = formatName?.let { toSupportedMimeType(it) }
            if (mimeType == null) {
                return@resource ValidationError(
                        status = 400,
                        errorMessage =
                            "Only PNG and JPEG images are supported, got: ${formatName ?: "unknown"}",
                        module = "files",
                    )
                    .left()
            }

            val safeSuffix = sanitizeFilename(fileName)
            val processedFilePath = saveToFile ?: createTempFileResource("tmp-", safeSuffix).bind()
            processedFilePath.deleteWithBackup {
                processImage(
                        // WARNING: This param is fine, only because `processImage`
                        // doesn't need to keep the source after:
                        UploadSource.FromFile(originalFileTmp),
                        originalName = fileName,
                        mimeType = mimeType,
                        altText = altText,
                        saveToFile = processedFilePath,
                    )
                    .bind()
                    .right()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to process uploaded file" }
            CaughtException(
                    status = 500,
                    module = "files",
                    errorMessage = "Failed to process uploaded file",
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
    suspend fun readImageFile(uuid: String, userUuid: UUID): ProcessedUpload? {
        val upload = db.getFileByUuidForUser(uuid, userUuid).getOrElse { throw it } ?: return null
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
     * @param uploadSource The source of the uploaded image — this can be destroyed after
     *   processing.
     * @param originalName The original file name of the uploaded image.
     * @param mimeType The MIME type of the uploaded image.
     * @param altText Optional alt text for the image.
     * @return A [Resource] containing the [ProcessedUpload] information.
     */
    private fun processImage(
        uploadSource: UploadSource,
        originalName: String,
        mimeType: String,
        altText: String?,
        saveToFile: File? = null,
    ): Resource<ProcessedUpload> = resource {
        val sourceFile = uploadSource.asFileResource().bind()
        val optimizedFile = saveToFile ?: createTempFileName("upload-optimized-", ".tmp")
        // Go, go, go
        imageMagick.optimizeImage(sourceFile, optimizedFile).getOrElse { throw it }

        val fileSize = withContext(Dispatchers.LoomIO) { optimizedFile.length() }
        val imageSize =
            imageMagick.identifyImageSize(optimizedFile).getOrElse {
                logger.warn(it) { "Failed to identify optimized image size" }
                null
            }
        val newMimeType =
            detectImageFormat(optimizedFile)?.let { toSupportedMimeType(it) } ?: mimeType
        ProcessedUpload(
            originalname = originalName,
            mimetype = newMimeType,
            altText = altText,
            width = imageSize?.width ?: 0,
            height = imageSize?.height ?: 0,
            size = fileSize,
            source = UploadSource.FromFile(optimizedFile),
        )
    }
}
