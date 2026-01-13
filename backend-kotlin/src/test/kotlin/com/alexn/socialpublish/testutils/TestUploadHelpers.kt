package com.alexn.socialpublish.testutils

import com.alexn.socialpublish.FilesConfig
import com.alexn.socialpublish.db.Database
import com.alexn.socialpublish.db.FilesDatabase
import com.alexn.socialpublish.modules.FileUploadResponse
import com.alexn.socialpublish.modules.FilesModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.readAvailable
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO

private const val UPLOAD_ENDPOINT = "/api/files/upload"

internal data class ImageDimensions(val width: Int, val height: Int)

internal data class MultipartFile(
    val name: String?,
    val filename: String?,
    val contentType: ContentType?,
    val bytes: ByteArray,
)

internal data class MultipartRequest(
    val files: List<MultipartFile>,
    val fields: Map<String, List<String>>,
)

internal fun createTestDatabase(tempDir: Path): Jdbi {
    val dbPath = tempDir.resolve("test.db").toString()
    val jdbi = Jdbi.create("jdbc:sqlite:$dbPath").installPlugin(KotlinPlugin())
    Database.migrate(jdbi)
    return jdbi
}

internal fun createFilesModule(
    tempDir: Path,
    jdbi: Jdbi,
): FilesModule {
    val uploadsDir = tempDir.resolve("uploads")
    val filesConfig = FilesConfig(uploadedFilesPath = uploadsDir.toString(), baseUrl = "http://localhost")
    return FilesModule(filesConfig, FilesDatabase(jdbi))
}

internal fun loadTestResourceBytes(resourceName: String): ByteArray {
    val stream =
        Thread.currentThread().contextClassLoader.getResourceAsStream(resourceName)
            ?: error("Missing test resource: $resourceName")
    return stream.use { it.readBytes() }
}

internal fun imageDimensions(bytes: ByteArray): ImageDimensions {
    val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("Unable to decode image")
    return ImageDimensions(width = image.width, height = image.height)
}

internal suspend fun uploadTestImage(
    client: HttpClient,
    resourceName: String,
    altText: String,
    uploadEndpoint: String = UPLOAD_ENDPOINT,
): FileUploadResponse {
    val bytes = loadTestResourceBytes(resourceName)
    return client
        .submitFormWithBinaryData(
            url = uploadEndpoint,
            formData =
                io.ktor.client.request.forms.formData {
                    append("altText", altText)
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType("image", "jpeg").toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"$resourceName\"")
                        },
                    )
                },
        ).body()
}

internal suspend fun receiveMultipart(call: ApplicationCall): MultipartRequest {
    val files = mutableListOf<MultipartFile>()
    val fields = mutableMapOf<String, MutableList<String>>()

    call.receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FormItem -> {
                val name = part.name ?: "unknown"
                fields.getOrPut(name) { mutableListOf() }.add(part.value)
            }
            is PartData.FileItem -> {
                val channel = part.provider()
                val buffer = mutableListOf<Byte>()
                val tempArray = ByteArray(8192)
                var read: Int
                while (channel.readAvailable(tempArray).also { read = it } > 0) {
                    buffer.addAll(tempArray.take(read))
                }
                files.add(
                    MultipartFile(
                        name = part.name,
                        filename = part.originalFileName,
                        contentType = part.contentType,
                        bytes = buffer.toByteArray(),
                    ),
                )
            }
            else -> {}
        }
        part.dispose()
    }

    return MultipartRequest(files = files, fields = fields)
}
