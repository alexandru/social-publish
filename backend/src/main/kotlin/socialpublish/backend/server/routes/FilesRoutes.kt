package socialpublish.backend.server.routes

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.TextContent
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.ErrorResponse
import socialpublish.backend.common.LoomIO
import socialpublish.backend.common.ValidationError
import socialpublish.backend.common.sanitizeFilename
import socialpublish.backend.modules.FilesModule
import socialpublish.backend.modules.StoredFile
import socialpublish.backend.modules.UploadedFile
import socialpublish.backend.server.serverJson

class FilesRoutes(private val filesModule: FilesModule) {
    suspend fun uploadFileRoute(call: ApplicationCall) {
        val result =
            when (val upload = receiveUpload(call)) {
                is Either.Right -> filesModule.uploadFile(upload.value)
                is Either.Left -> upload
            }

        when (result) {
            is Either.Right -> respondJson(call, result.value)
            is Either.Left -> {
                val error = result.value
                respondJson(
                    call,
                    ErrorResponse(error = error.errorMessage),
                    HttpStatusCode.fromValue(error.status),
                )
            }
        }
    }

    suspend fun getFileRoute(call: ApplicationCall) {
        val uuid =
            call.parameters["uuid"]
                ?: run {
                    respondJson(
                        call,
                        ErrorResponse(error = "Missing UUID"),
                        HttpStatusCode.BadRequest,
                    )
                    return
                }

        when (val result = filesModule.getFile(uuid)) {
            is Either.Right -> respondFile(call, result.value)
            is Either.Left -> {
                val error = result.value
                respondJson(
                    call,
                    ErrorResponse(error = error.errorMessage),
                    HttpStatusCode.fromValue(error.status),
                )
            }
        }
    }

    private suspend fun receiveUpload(call: ApplicationCall): ApiResult<UploadedFile> {
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
                                withContext(Dispatchers.LoomIO) {
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

        return UploadedFile(fileName = fileName, fileBytes = fileBytes, altText = altText).right()
    }

    private suspend fun respondFile(call: ApplicationCall, storedFile: StoredFile) {
        call.response.header(HttpHeaders.ContentType, storedFile.mimeType)
        call.response.header(
            HttpHeaders.ContentDisposition,
            "inline; filename=\"${sanitizeFilename(storedFile.originalName)}\"",
        )
        call.respondFile(storedFile.file)
    }

    private suspend inline fun <reified T> respondJson(
        call: ApplicationCall,
        payload: T,
        status: HttpStatusCode? = null,
    ) {
        val body = serverJson().encodeToString(payload)
        call.respond(TextContent(body, ContentType.Application.Json, status))
    }
}
