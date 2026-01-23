package socialpublish.backend.modules

import arrow.core.left
import arrow.core.right
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.nio.PngWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.utils.io.readRemaining
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.db.UploadPayload
import socialpublish.backend.models.ApiResult
import socialpublish.backend.models.CaughtException
import socialpublish.backend.models.ErrorResponse
import socialpublish.backend.models.ValidationError

private val logger = KotlinLogging.logger {}

@Serializable data class FileUploadResponse(val uuid: String, val url: String)

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
) {
    private val processedPath = File(uploadedFilesPath, "processed")
    private val resizingPath = File(uploadedFilesPath, "resizing")

    companion object {
        suspend fun create(config: FilesConfig, db: FilesDatabase): FilesModule {
            val ref = FilesModule(config, db, config.uploadedFilesPath)
            runInterruptible(Dispatchers.IO) {
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
                                    withContext(Dispatchers.IO) {
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
                            "Only PNG, JPEG, and WebP images are supported, got: ${formatName ?: "unknown"}",
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

            // Save file to disk
            val filePath = File(processedPath, upload.hash)
            runInterruptible(Dispatchers.IO) { filePath.writeBytes(processed.bytes) }

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

        val upload = db.getFileByUuid(uuid)
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
            "inline; filename=\"${upload.originalname}\"",
        )
        call.respondFile(filePath)
    }

    /** Read image file for API posting */
    suspend fun readImageFile(
        uuid: String,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
    ): ProcessedUpload? {
        val upload = db.getFileByUuid(uuid) ?: return null
        val filePath = File(processedPath, upload.hash)

        val bytes =
            runInterruptible(Dispatchers.IO) {
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
                val cachedBytes =
                    runInterruptible(Dispatchers.IO) {
                        val cachedFile = File(resizingPath, upload.hash)
                        if (cachedFile.exists()) cachedFile.readBytes() else null
                    }

                if (cachedBytes != null) {
                    val cachedImage =
                        try {
                            ImmutableImage.loader().fromBytes(cachedBytes)
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to read cached resized image" }
                            null
                        }

                    if (cachedImage != null) {
                        return ProcessedUpload(
                            originalname = upload.originalname,
                            mimetype = getOutputMimeType(upload.mimetype),
                            altText = upload.altText,
                            width = cachedImage.width,
                            height = cachedImage.height,
                            size = cachedBytes.size.toLong(),
                            bytes = cachedBytes,
                        )
                    }
                }

                val resized =
                    try {
                        val image = ImmutableImage.loader().fromBytes(bytes)
                        val width = image.width
                        val height = image.height

                        if (width > maxWidth || height > maxHeight) {
                            val scale =
                                minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
                            val newWidth = (width * scale).toInt()
                            val newHeight = (height * scale).toInt()
                            val scaled = image.scaleTo(newWidth, newHeight)
                            val resizedBytes = encodeImage(scaled, upload.mimetype)
                            runInterruptible(Dispatchers.IO) {
                                val cacheFile = File(resizingPath, upload.hash)
                                cacheFile.writeBytes(resizedBytes)
                            }
                            return ProcessedUpload(
                                originalname = upload.originalname,
                                mimetype = getOutputMimeType(upload.mimetype),
                                altText = upload.altText,
                                width = scaled.width,
                                height = scaled.height,
                                size = resizedBytes.size.toLong(),
                                bytes = resizedBytes,
                            )
                        }

                        // Image is within bounds, return original without resizing
                        ProcessedUpload(
                            originalname = upload.originalname,
                            mimetype = upload.mimetype, // Original MIME type for original bytes
                            altText = upload.altText,
                            width = if (storedWidth > 0) storedWidth else width,
                            height = if (storedHeight > 0) storedHeight else height,
                            size = bytes.size.toLong(),
                            bytes = bytes,
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to resize image, using original" }
                        null
                    }

                if (resized != null) {
                    return resized
                }
            }
        }

        // No resizing requested or image within stored bounds, return original
        return ProcessedUpload(
            originalname = upload.originalname,
            mimetype = upload.mimetype, // Original MIME type for original bytes
            altText = upload.altText,
            width = storedWidth,
            height = storedHeight,
            size = bytes.size.toLong(),
            bytes = bytes,
        )
    }

    private fun detectImageFormat(bytes: ByteArray): String? {
        val inputStream = ByteArrayInputStream(bytes)
        val imageStream = ImageIO.createImageInputStream(inputStream) ?: return null
        return imageStream.use {
            val readers = ImageIO.getImageReaders(it)
            if (!readers.hasNext()) {
                return@use null
            }
            val reader = readers.next()
            try {
                reader.formatName.lowercase()
            } finally {
                reader.dispose()
            }
        }
    }

    private fun toSupportedMimeType(format: String): String? {
        return when (format.lowercase()) {
            "png" -> "image/png"
            "jpg",
            "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> null
        }
    }

    /** Get the output MIME type after encoding. WebP is converted to JPEG. */
    private fun getOutputMimeType(inputMimeType: String): String {
        return if (inputMimeType == "image/webp") "image/jpeg" else inputMimeType
    }

    private fun processImage(
        bytes: ByteArray,
        originalName: String,
        mimeType: String,
        altText: String?,
    ): ProcessedUpload {
        return try {
            val image = ImmutableImage.loader().fromBytes(bytes)
            ProcessedUpload(
                originalname = originalName,
                mimetype = mimeType,
                altText = altText,
                width = image.width,
                height = image.height,
                size = bytes.size.toLong(),
                bytes = bytes,
            )
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

    private fun encodeImage(image: ImmutableImage, mimeType: String): ByteArray {
        return when (mimeType) {
            "image/jpeg" -> {
                image.bytes(JpegWriter.Default.withCompression(80))
            }
            "image/png" -> {
                image.bytes(PngWriter.MaxCompression)
            }
            "image/webp" -> {
                // Convert WebP to JPEG to avoid encoding issues and reduce file size
                image.bytes(JpegWriter.Default.withCompression(80))
            }
            else -> {
                // Default to JPEG for unknown types
                image.bytes(JpegWriter.Default.withCompression(80))
            }
        }
    }

    private fun calculateHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
