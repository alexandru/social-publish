package socialpublish.backend.testutils

import arrow.core.getOrElse
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
import io.ktor.utils.io.readRemaining
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.io.readByteArray
import socialpublish.backend.clients.imagemagick.ImageMagick
import socialpublish.backend.db.Database
import socialpublish.backend.db.FilesDatabase
import socialpublish.backend.modules.FileUploadResponse
import socialpublish.backend.modules.FilesConfig
import socialpublish.backend.modules.FilesModule
import socialpublish.backend.utils.LoomIO

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

internal suspend fun createTestDatabase(tempDir: Path): Database {
    val dbPath = tempDir.resolve("test.db").toString()
    // Use unmanaged connection for tests since the database needs to outlive
    // the test setup scope and remain open for the duration of the test
    return Database.connectUnmanaged(dbPath)
}

internal suspend fun createFilesModule(tempDir: Path, db: Database): FilesModule {
    val uploadsDir = tempDir.resolve("uploads").toFile()
    val filesConfig = FilesConfig(uploadedFilesPath = uploadsDir, baseUrl = "http://localhost")
    return FilesModule.create(filesConfig, FilesDatabase(db))
}

internal fun loadTestResourceBytes(resourceName: String): ByteArray {
    val stream =
        Thread.currentThread().contextClassLoader.getResourceAsStream(resourceName)
            ?: error("Missing test resource: $resourceName")
    return stream.use { it.readBytes() }
}

internal suspend fun imageDimensions(bytes: ByteArray): ImageDimensions {
    val tempFile =
        runInterruptible(Dispatchers.LoomIO) {
            File.createTempFile("test-", ".tmp").apply { writeBytes(bytes) }
        }
    try {
        val imageMagick =
            ImageMagick().getOrElse { error("ImageMagick not available: ${it.message}") }
        val size =
            imageMagick.identifyImageSize(tempFile).getOrElse {
                error("Unable to identify image: ${it.message}")
            }
        return ImageDimensions(width = size.width, height = size.height)
    } finally {
        runInterruptible(Dispatchers.LoomIO) { tempFile.delete() }
    }
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
        )
        .body()
}

internal suspend fun receiveMultipart(call: ApplicationCall): MultipartRequest {
    val files = mutableListOf<MultipartFile>()
    val fields = mutableMapOf<String, MutableList<String>>()

    call.receiveMultipart().forEachPart { part ->
        try {
            when (part) {
                is PartData.FormItem -> {
                    val name = part.name ?: "unknown"
                    fields.getOrPut(name) { mutableListOf() }.add(part.value)
                }
                is PartData.FileItem -> {
                    val bytes = part.provider().readRemaining().readByteArray()
                    files.add(
                        MultipartFile(
                            name = part.name,
                            filename = part.originalFileName,
                            contentType = part.contentType,
                            bytes = bytes,
                        )
                    )
                }
                else -> {}
            }
        } finally {
            part.dispose()
        }
    }

    return MultipartRequest(files = files, fields = fields)
}
