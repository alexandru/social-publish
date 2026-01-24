package socialpublish.backend.modules

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
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
                // The CachingHeaders plugin handles standard cache directives
                // Here we only add custom directives it doesn't support
                when {
                    // immutable for hashed files
                    file.name.matches(
                        Regex("(?:.*\\.[a-f0-9]{8,}\\.|[a-f0-9]{8,}\\.)(?:js|woff2|woff|ttf|eot)")
                    ) -> {
                        call.response.headers.append(
                            HttpHeaders.CacheControl,
                            "public, max-age=31536000, immutable",
                        )
                    }
                    // stale-while-revalidate for index.html
                    file.name == "index.html" -> {
                        call.response.headers.append(
                            HttpHeaders.CacheControl,
                            "public, max-age=7200, stale-while-revalidate=86400",
                        )
                    }
                    // stale-while-revalidate for images and other assets
                    file.name.matches(Regex(".*\\.(?:png|jpg|jpeg|gif|svg|webp|ico|css)")) -> {
                        call.response.headers.append(
                            HttpHeaders.CacheControl,
                            "public, max-age=172800, stale-while-revalidate=86400",
                        )
                    }
                }

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
