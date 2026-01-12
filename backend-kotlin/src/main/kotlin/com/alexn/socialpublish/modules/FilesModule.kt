package com.alexn.socialpublish.modules

import arrow.core.left
import arrow.core.right
import com.alexn.socialpublish.config.AppConfig
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.db.UploadPayload
import com.alexn.socialpublish.models.ApiResult
import com.alexn.socialpublish.models.CaughtException
import com.alexn.socialpublish.models.ValidationError
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.nio.PngWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

@Serializable
data class FileUploadResponse(
    val uuid: String,
    val url: String,
)

data class ProcessedUpload(
    val originalname: String,
    val mimetype: String,
    val altText: String?,
    val width: Int,
    val height: Int,
    val size: Long,
    val bytes: ByteArray,
)

class FilesModule(
    private val config: AppConfig,
    private val db: FilesDatabase,
) {
    private val uploadedFilesPath = File(config.uploadedFilesPath)
    private val processedPath = File(uploadedFilesPath, "processed")

    init {
        // Create directories
        uploadedFilesPath.mkdirs()
        processedPath.mkdirs()
        logger.info { "Files module initialized at ${config.uploadedFilesPath}" }
    }

    /**
     * Upload and process file
     */
    suspend fun uploadFile(call: ApplicationCall): ApiResult<FileUploadResponse> {
        return try {
            val multipart = call.receiveMultipart()
            var altText: String? = null
            var fileBytes: ByteArray? = null
            var fileName: String? = null
            var contentType: String? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == "altText") {
                            altText = part.value
                        }
                    }
                    is PartData.FileItem -> {
                        fileName = part.originalFileName ?: "unknown"
                        contentType = part.contentType?.toString() ?: "application/octet-stream"
                        fileBytes =
                            withContext(Dispatchers.IO) {
                                val channel = part.provider()
                                val buffer = mutableListOf<Byte>()
                                val tempArray = ByteArray(8192)
                                var read: Int
                                while (channel.readAvailable(tempArray).also { read = it } > 0) {
                                    buffer.addAll(tempArray.take(read))
                                }
                                buffer.toByteArray()
                            }
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (fileBytes == null || fileName == null) {
                return ValidationError(
                    status = 400,
                    errorMessage = "Missing file in upload",
                    module = "files",
                ).left()
            }

            // Calculate hash
            val hash = calculateHash(fileBytes)

            // Process image if it's an image type
            val processed =
                if (contentType?.startsWith("image/") == true) {
                    processImage(fileBytes, fileName, contentType, altText)
                } else {
                    ProcessedUpload(
                        originalname = fileName,
                        mimetype = contentType!!,
                        altText = altText,
                        width = 0,
                        height = 0,
                        size = fileBytes.size.toLong(),
                        bytes = fileBytes,
                    )
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
                    ),
                )

            // Save file to disk
            val filePath = File(processedPath, upload.hash)
            filePath.writeBytes(processed.bytes)

            logger.info { "File uploaded: ${upload.uuid} (${upload.originalname})" }

            FileUploadResponse(
                uuid = upload.uuid,
                url = "${config.baseUrl}/files/${upload.uuid}",
            ).right()
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload file" }
            CaughtException(
                status = 500,
                module = "files",
                errorMessage = "Failed to upload file: ${e.message}",
            ).left()
        }
    }

    /**
     * Retrieve uploaded file
     */
    suspend fun getFile(call: ApplicationCall) {
        val uuid =
            call.parameters["uuid"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing UUID"))
                return
            }

        val upload = db.getFileByUuid(uuid)
        if (upload == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
            return
        }

        val filePath = File(processedPath, upload.hash)
        if (!filePath.exists()) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "File content not found"))
            return
        }

        call.respondFile(filePath)
    }

    /**
     * Read image file for API posting
     */
    fun readImageFile(uuid: String): ProcessedUpload? {
        val upload = db.getFileByUuid(uuid) ?: return null
        val filePath = File(processedPath, upload.hash)

        if (!filePath.exists()) return null

        return ProcessedUpload(
            originalname = upload.originalname,
            mimetype = upload.mimetype,
            altText = upload.altText,
            width = upload.imageWidth ?: 0,
            height = upload.imageHeight ?: 0,
            size = upload.size,
            bytes = filePath.readBytes(),
        )
    }

    private fun processImage(
        bytes: ByteArray,
        originalName: String,
        mimeType: String,
        altText: String?,
    ): ProcessedUpload {
        return try {
            val image = ImmutableImage.loader().fromBytes(bytes)
            val width = image.width
            val height = image.height

            // Resize if too large
            val maxWidth = 1920
            val maxHeight = 1080
            val resized =
                if (width > maxWidth || height > maxHeight) {
                    val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
                    val newWidth = (width * scale).toInt()
                    val newHeight = (height * scale).toInt()
                    image.scaleTo(newWidth, newHeight)
                } else {
                    image
                }

            // Convert to bytes
            val outputBytes =
                when {
                    mimeType.contains("jpeg") || mimeType.contains("jpg") -> {
                        resized.bytes(JpegWriter.Default.withCompression(80))
                    }
                    mimeType.contains("png") -> {
                        resized.bytes(PngWriter.MaxCompression)
                    }
                    else -> {
                        resized.bytes(JpegWriter.Default.withCompression(80))
                    }
                }

            ProcessedUpload(
                originalname = originalName,
                mimetype = mimeType,
                altText = altText,
                width = resized.width,
                height = resized.height,
                size = outputBytes.size.toLong(),
                bytes = outputBytes,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to process image, using original" }
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
}
