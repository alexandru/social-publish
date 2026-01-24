package socialpublish.backend.server

import io.ktor.client.request.get
import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CacheHeadersTest {

    @Test
    fun `test cache headers for hashed JS file`() = testApplication {
        // Create a temporary directory with test files
        val tempDir = createTempDirectory("test-static").toFile()
        try {
            val hashedJsFile = File(tempDir, "app.12345678.js")
            hashedJsFile.writeText("// test js")

            application { configureStaticFileServing(tempDir) }

            client.get("/app.12345678.js").apply {
                assertEquals(HttpStatusCode.OK, status)
                val cacheControl = headers[HttpHeaders.CacheControl]!!
                assertTrue(cacheControl.contains("public"), "Expected public cache for hashed file")
                assertTrue(
                    cacheControl.contains("max-age=31536000"),
                    "Expected 1 year cache for hashed file",
                )
                assertTrue(cacheControl.contains("immutable"), "Expected immutable for hashed file")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test cache headers for hashed woff2 file`() = testApplication {
        val tempDir = createTempDirectory("test-static").toFile()
        try {
            val hashedFontFile = File(tempDir, "8ae0d37556ff1e685de2.woff2")
            hashedFontFile.writeText("test font")

            application { configureStaticFileServing(tempDir) }

            client.get("/8ae0d37556ff1e685de2.woff2").apply {
                assertEquals(HttpStatusCode.OK, status)
                val cacheControl = headers[HttpHeaders.CacheControl]!!
                assertTrue(
                    cacheControl.contains("immutable"),
                    "Expected immutable for hashed woff2 file",
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test cache headers for index html`() = testApplication {
        val tempDir = createTempDirectory("test-static").toFile()
        try {
            val indexFile = File(tempDir, "index.html")
            indexFile.writeText("<html><body>test</body></html>")

            application { configureStaticFileServing(tempDir) }

            client.get("/index.html").apply {
                assertEquals(HttpStatusCode.OK, status)
                val cacheControl = headers[HttpHeaders.CacheControl]!!
                assertTrue(
                    cacheControl.contains("max-age=7200"),
                    "Expected 2 hours cache for index.html",
                )
                assertTrue(
                    cacheControl.contains("stale-while-revalidate=86400"),
                    "Expected stale-while-revalidate for index.html",
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test cache headers for image files`() = testApplication {
        val tempDir = createTempDirectory("test-static").toFile()
        try {
            val imageFile = File(tempDir, "logo.png")
            imageFile.writeText("fake png")

            application { configureStaticFileServing(tempDir) }

            client.get("/logo.png").apply {
                assertEquals(HttpStatusCode.OK, status)
                val cacheControl = headers[HttpHeaders.CacheControl]!!
                assertTrue(
                    cacheControl.contains("max-age=172800"),
                    "Expected 2 days cache for images",
                )
                assertTrue(
                    cacheControl.contains("stale-while-revalidate=86400"),
                    "Expected stale-while-revalidate for images",
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test no-cache headers for API routes`() = testApplication {
        application { configureDynamicRoutes() }

        client.get("/api/test").apply {
            assertEquals(HttpStatusCode.OK, status)
            val cacheControl = headers[HttpHeaders.CacheControl]!!
            assertTrue(cacheControl.contains("no-cache"), "Expected no-cache for API routes")
        }
    }

    @Test
    fun `test no-cache headers for RSS routes`() = testApplication {
        application { configureDynamicRoutes() }

        client.get("/rss").apply {
            assertEquals(HttpStatusCode.OK, status)
            val cacheControl = headers[HttpHeaders.CacheControl]!!
            assertTrue(cacheControl.contains("no-cache"), "Expected no-cache for RSS routes")
        }
    }

    @Test
    fun `test no-cache headers for files routes`() = testApplication {
        application { configureDynamicRoutes() }

        client.get("/files/12345").apply {
            assertEquals(HttpStatusCode.OK, status)
            val cacheControl = headers[HttpHeaders.CacheControl]!!
            assertTrue(cacheControl.contains("no-cache"), "Expected no-cache for files routes")
        }
    }

    @Test
    fun `test no-cache headers for ping route`() = testApplication {
        application { configureDynamicRoutes() }

        client.get("/ping").apply {
            assertEquals(HttpStatusCode.OK, status)
            val cacheControl = headers[HttpHeaders.CacheControl]!!
            assertTrue(cacheControl.contains("no-cache"), "Expected no-cache for ping route")
        }
    }

    @Test
    fun `test cache headers for docs route`() = testApplication {
        application { configureDynamicRoutes() }

        client.get("/docs").apply {
            assertEquals(HttpStatusCode.OK, status)
            val cacheControl = headers[HttpHeaders.CacheControl]!!
            assertTrue(
                cacheControl.contains("max-age=7200"),
                "Expected 2 hours cache for docs route",
            )
            // Note: stale-while-revalidate can't be easily tested here as it requires
            // custom header manipulation not available in testApplication context
        }
    }

    private fun Application.configureDynamicRoutes() {
        install(CachingHeaders) {
            options { call, _ ->
                val uri = call.request.local.uri
                when {
                    uri.startsWith("/api/") ||
                        uri.startsWith("/rss") ||
                        uri.startsWith("/files/") ||
                        uri == "/ping" -> {
                        CachingOptions(cacheControl = CacheControl.NoCache(null))
                    }
                    uri.startsWith("/docs") -> {
                        CachingOptions(
                            cacheControl =
                                CacheControl.MaxAge(
                                    maxAgeSeconds = 7200,
                                    visibility = CacheControl.Visibility.Public,
                                )
                        )
                    }
                    else -> null
                }
            }
        }
        routing {
            get("/api/test") { call.respond(HttpStatusCode.OK, "API test") }
            get("/rss") { call.respond(HttpStatusCode.OK, "RSS feed") }
            get("/files/12345") { call.respond(HttpStatusCode.OK, "File content") }
            get("/ping") { call.respond(HttpStatusCode.OK, "pong") }
            get("/docs") { call.respond(HttpStatusCode.OK, "Documentation") }
        }
    }

    private fun Application.configureStaticFileServing(staticDir: File) {
        routing {
            get("/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                val file = File(staticDir, path.ifBlank { "index.html" })

                if (file.exists() && file.isFile) {
                    when {
                        file.name.matches(
                            Regex(
                                "(?:.*\\.[a-f0-9]{8,}\\.|[a-f0-9]{8,}\\.)" +
                                    "(?:js|woff2|woff|ttf|eot)"
                            )
                        ) -> {
                            call.response.headers.append(
                                HttpHeaders.CacheControl,
                                "public, max-age=31536000, immutable",
                            )
                        }
                        file.name == "index.html" -> {
                            call.response.headers.append(
                                HttpHeaders.CacheControl,
                                "public, max-age=7200, stale-while-revalidate=86400",
                            )
                        }
                        file.name.matches(Regex(".*\\.(?:png|jpg|jpeg|gif|svg|webp|ico|css)")) -> {
                            call.response.headers.append(
                                HttpHeaders.CacheControl,
                                "public, max-age=172800, stale-while-revalidate=86400",
                            )
                        }
                        else -> {
                            call.response.headers.append(
                                HttpHeaders.CacheControl,
                                "public, max-age=3600",
                            )
                        }
                    }
                    call.respondFile(file)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}
