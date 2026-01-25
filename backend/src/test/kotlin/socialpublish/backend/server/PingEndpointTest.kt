package socialpublish.backend.server

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PingEndpointTest {

    @Test
    fun `GET ping should return pong`() = testApplication {
        application { configurePingEndpoint() }

        client.get("/ping").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("pong", bodyAsText())
        }
    }

    @Test
    fun `HEAD ping should return 200 OK without body`() = testApplication {
        application { configurePingEndpoint() }

        client.head("/ping").apply {
            assertEquals(HttpStatusCode.OK, status)
            // HEAD responses should not have a body
            val body = bodyAsText()
            assertTrue(body.isEmpty(), "HEAD response should have empty body, but got: '$body'")
        }
    }

    private fun Application.configurePingEndpoint() {
        routing {
            get("/ping") { call.respondText("pong", status = HttpStatusCode.OK) }
            head("/ping") { call.respondText("", status = HttpStatusCode.OK) }
        }
    }
}
