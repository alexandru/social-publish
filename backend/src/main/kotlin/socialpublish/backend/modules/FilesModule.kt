package socialpublish.backend.modules

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.utils.io.readRemaining
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.apache.tika.Tika
import socialpublish.backend.clients.imagemagick.ImageMagick
import socialpublish.backend.clients.imagemagick.MagickOptimizeOptions
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.UploadPayload
import socialpublish.backend.models.ApiResult
import socialpublish.backend.models.CaughtException
import socialpublish.backend.models.ErrorResponse
import socialpublish.backend.models.ValidationError
import socialpublish.backend.utils.LOOM
import socialpublish.backend.utils.sanitizeFilename

private val logger = KotlinLogging.logger {}

@Serializable data class FileUploadResponse(val uuid: String, val url: String)

@Serializable data class UpdateAltTextRequest(val altText: String)

@Serializable data class UpdateAltTextResponse(val success: Boolean)

data class ProcessedUpload(
    val originalname: String,
    val mimetype: String,
    val altText: String?,
    val width: Int,
    val height: Int,
    val size: Long,
    val bytes: ByteArray,
)

data class FilesConfig(val uploadedFilesPath: File, val baseUrl: String)

class FilesModule
private constructor(
    private val config: FilesConfig,
    private val db: FilesDatabase,
    private val uploadedFilesPath: File,
    private val imageMagick: ImageMagick,
) {
    private val processedPath = File(uploadedFilesPath, "processed")
    private val resizingPath = File(uploadedFilesPath, "resizing")

    companion object {
        suspend fun create(config: FilesConfig, db: FilesDatabase): FilesModule {
            val imageMagick =
                ImageMagick().getOrElse { error("Failed to initialize ImageMagick: ${it.message}") }
            val ref = FilesModule(config, db, config.uploadedFilesPath, imageMagick)
            runInterruptible(Dispatchers.LOOM) {
                ref.uploadedFilesPath.mkdirs()
                ref.processedPath.mkdirs()
                ref.resizingPath.mkdirs()
            }
            logger.info { "Files module initialized at ${config.uploadedFilesPath}" }
            return ref
        }
    }

    /** Upload and process file */
    suspend fun uploadFile(call: ApplicationCall): ApiResult<FileUploadResponse> {
        return try {
            val multipart = call.receiveMultipart()
            var altText: String? = null
            var fileBytes: ByteArray? = null
            var fileName: String? = null

            multipart.forEachPart { part ->
                try {
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name == "altText") {
                                altText = part.value
                            }
                        }
                        is PartData.FileItem -> {
                            if (part.name == "file") {
                                fileName = part.originalFileName ?: "unknown"
                                fileBytes =
                                    withContext(Dispatchers.LOOM) {
                                        part.provider().readRemaining().readByteArray()
                                    }
                            }
                        }
                        else -> {}
                    }
                } finally {
                    part.dispose()
                }
            }

            if (fileBytes == null || fileName == null) {
                return ValidationError(
                        status = 400,
                        errorMessage = "Missing file in upload",
                        module = "files",
                    )
                    .left()
            }

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

            // Save file to disk
            val filePath = File(processedPath, upload.hash)
            runInterruptible(Dispatchers.LOOM) { filePath.writeBytes(processed.bytes) }

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
    suspend fun getFile(call: ApplicationCall) {
        val uuid =
            call.parameters["uuid"]
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error = "Missing UUID"))
                    return
                }

        val upload = db.getFileByUuid(uuid).getOrElse { throw it }
        if (upload == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "File not found"))
            return
        }

        val filePath = File(processedPath, upload.hash)
        if (!filePath.exists()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(error = "File content not found"))
            return
        }

        call.response.header(HttpHeaders.ContentType, upload.mimetype)
        call.response.header(
            HttpHeaders.ContentDisposition,
            "inline; filename=\"${sanitizeFilename(upload.originalname)}\"",
        )
        call.respondFile(filePath)
    }

    /** Read image file for API posting */
    suspend fun readImageFile(
        uuid: String,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
    ): ProcessedUpload? {
        val upload = db.getFileByUuid(uuid).getOrElse { throw it } ?: return null
        val filePath = File(processedPath, upload.hash)

        val bytes =
            runInterruptible(Dispatchers.LOOM) {
                if (!filePath.exists()) {
                    null
                } else {
                    filePath.readBytes()
                }
            } ?: return null

        val storedWidth = upload.imageWidth ?: 0
        val storedHeight = upload.imageHeight ?: 0

        if (maxWidth != null && maxHeight != null) {
            val storedWithinBounds =
                storedWidth > 0 &&
                    storedHeight > 0 &&
                    storedWidth <= maxWidth &&
                    storedHeight <= maxHeight

            if (!storedWithinBounds) {
                val cachedFile = File(resizingPath, upload.hash)
                val cachedBytes =
                    runInterruptible(Dispatchers.LOOM) {
                        if (cachedFile.exists()) cachedFile.readBytes() else null
                    }

                if (cachedBytes != null) {
                    // Verify cached file dimensions
                    val tempFile =
                        runInterruptible(Dispatchers.LOOM) {
                            File.createTempFile("cached-", ".tmp").apply { writeBytes(cachedBytes) }
                        }
                    try {
                        val cachedSize = imageMagick.identifyImageSize(tempFile).getOrNull()
                        if (cachedSize != null) {
                            return ProcessedUpload(
                                originalname = upload.originalname,
                                mimetype = upload.mimetype,
                                altText = upload.altText,
                                width = cachedSize.width,
                                height = cachedSize.height,
                                size = cachedBytes.size.toLong(),
                                bytes = cachedBytes,
                            )
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to verify cached resized image" }
                    } finally {
                        runInterruptible(Dispatchers.LOOM) { tempFile.delete() }
                    }
                }

                // Need to resize
                val resized =
                    try {
                        val sourceFile =
                            runInterruptible(Dispatchers.LOOM) {
                                File.createTempFile("source-", ".tmp").apply { writeBytes(bytes) }
                            }
                        try {
                            val size =
                                imageMagick.identifyImageSize(sourceFile).getOrElse { throw it }
                            val width = size.width
                            val height = size.height

                            if (width > maxWidth || height > maxHeight) {
                                // Create resized image using ImageMagick
                                val resizedFile =
                                    runInterruptible(Dispatchers.LOOM) {
                                        File.createTempFile("resized-", ".tmp").apply {
                                            delete() // Delete the file, we just want the path
                                        }
                                    }
                                try {
                                    val resizeMagick =
                                        ImageMagick(
                                                options =
                                                    MagickOptimizeOptions(
                                                        maxWidth = maxWidth,
                                                        maxHeight = maxHeight,
                                                        maxSizeBytes = Long.MAX_VALUE,
                                                        jpegQuality = 90,
                                                    )
                                            )
                                            .getOrElse { throw it }
                                    resizeMagick.optimizeImage(sourceFile, resizedFile).getOrElse {
                                        throw it
                                    }
                                    val resizedBytes =
                                        runInterruptible(Dispatchers.LOOM) {
                                            resizedFile.readBytes()
                                        }
                                    val resizedSize =
                                        imageMagick.identifyImageSize(resizedFile).getOrElse {
                                            throw it
                                        }

                                    // Cache the resized image
                                    runInterruptible(Dispatchers.LOOM) {
                                        cachedFile.writeBytes(resizedBytes)
                                    }

                                    return ProcessedUpload(
                                        originalname = upload.originalname,
                                        mimetype = upload.mimetype,
                                        altText = upload.altText,
                                        width = resizedSize.width,
                                        height = resizedSize.height,
                                        size = resizedBytes.size.toLong(),
                                        bytes = resizedBytes,
                                    )
                                } finally {
                                    runInterruptible(Dispatchers.LOOM) { resizedFile.delete() }
                                }
                            }

                            ProcessedUpload(
                                originalname = upload.originalname,
                                mimetype = upload.mimetype,
                                altText = upload.altText,
                                width = if (storedWidth > 0) storedWidth else width,
                                height = if (storedHeight > 0) storedHeight else height,
                                size = bytes.size.toLong(),
                                bytes = bytes,
                            )
                        } finally {
                            runInterruptible(Dispatchers.LOOM) { sourceFile.delete() }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to resize image, using original" }
                        null
                    }

                if (resized != null) {
                    return resized
                }
            }
        }

        return ProcessedUpload(
            originalname = upload.originalname,
            mimetype = upload.mimetype,
            altText = upload.altText,
            width = storedWidth,
            height = storedHeight,
            size = bytes.size.toLong(),
            bytes = bytes,
        )
    }

    private suspend fun detectImageFormat(bytes: ByteArray): String? {
        return runInterruptible(Dispatchers.LOOM) {
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
            val tempFile =
                runInterruptible(Dispatchers.LOOM) {
                    File.createTempFile("upload-", ".tmp").apply { writeBytes(bytes) }
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
                runInterruptible(Dispatchers.LOOM) { tempFile.delete() }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read image metadata, using original" }
            ProcessedUpload(
                originalname = originalName,
                mimetype = mimeType,
                altText = altText,
                width = 0,
                height = 0,
                size = bytes.size.toLong(),
                bytes = bytes,
            )
        }
    }

    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /** Update alt-text for an uploaded file */
    suspend fun updateAltText(call: ApplicationCall): ApiResult<UpdateAltTextResponse> {
        return try {
            val uuid =
                call.parameters["uuid"]
                    ?: return ValidationError(
                            status = 400,
                            errorMessage = "Missing UUID parameter",
                            module = "files",
                        )
                        .left()

            val request =
                runCatching { call.receive<UpdateAltTextRequest>() }.getOrNull()
                    ?: return ValidationError(
                            status = 400,
                            errorMessage = "Invalid request body",
                            module = "files",
                        )
                        .left()

            val updated =
                db.updateAltText(uuid, request.altText).getOrElse {
                    return CaughtException(
                            status = 500,
                            errorMessage = "Failed to update alt-text: ${it.message}",
                            module = "files",
                        )
                        .left()
                }

            if (updated) {
                logger.info { "Updated alt-text for file: $uuid" }
                UpdateAltTextResponse(success = true).right()
            } else {
                ValidationError(status = 404, errorMessage = "File not found", module = "files")
                    .left()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update alt-text" }
            CaughtException(
                    status = 500,
                    module = "files",
                    errorMessage = "Failed to update alt-text: ${e.message}",
                )
                .left()
        }
    }
}
