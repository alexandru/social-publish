package socialpublish.backend.modules

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import java.io.File
import socialpublish.backend.server.ServerConfig
import socialpublish.backend.utils.isPathWithinBase

private val logger = KotlinLogging.logger {}

class StaticFilesModule(private val serverConfig: ServerConfig) {
    suspend fun serveStaticFile(call: ApplicationCall) {
        val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
        val triedPaths = mutableListOf<String>()
        for (baseDir in serverConfig.staticContentPaths) {
            val canonicalBaseDir = baseDir.canonicalFile

            val file =
                if (path.isBlank() || path.matches(Regex("^(login|form|account).*"))) {
                    File(canonicalBaseDir, "index.html")
                } else {
                    File(canonicalBaseDir, path)
                }

            // Security: Check that the resolved file is within the allowed directory
            if (file.exists() && file.isFile && isPathWithinBase(file, canonicalBaseDir)) {
                call.respondFile(file)
                return
            }
            triedPaths.add(file.canonicalPath)
        }

        logger.warn {
            "Static file not found. Tried paths:\n${
                triedPaths.joinToString(
                    ",\n"
                )
            }"
        }
        call.respond(HttpStatusCode.NotFound)
    }
}
