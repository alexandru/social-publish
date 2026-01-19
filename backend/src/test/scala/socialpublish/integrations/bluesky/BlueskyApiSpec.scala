package socialpublish.integrations.bluesky

import cats.effect.*
import cats.mtl.Handle
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import socialpublish.integrations.bluesky.BlueskyModels.*
import socialpublish.models.{ApiError, Content, NewPostRequest}
import socialpublish.testutils.{Http4sTestServer, ServiceFixtures}
import sttp.client4.httpclient.cats.HttpClientCatsBackend

class BlueskyApiSpec extends CatsEffectSuite {

  test("createPost sends record to Bluesky") {
    for {
      loginRef <- Ref.of[IO, Option[LoginRequest]](None)
      recordRef <- Ref.of[IO, Option[CreatePostRequest]](None)
      routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
        case req @ POST -> Root / "xrpc" / "com.atproto.server.createSession" =>
          req.as[LoginRequest].flatMap { request =>
            loginRef.set(Some(request)) *>
              Ok(LoginResponse("access", "refresh", "handle", "did:example"))
          }
        case req @ POST -> Root / "xrpc" / "com.atproto.repo.createRecord" =>
          req.as[CreatePostRequest].flatMap { request =>
            recordRef.set(Some(request)) *>
              Ok(CreatePostResponse("at://post/1", "cid-1"))
          }
      }
      response <- Http4sTestServer.resource(routes).use { server =>
        ServiceFixtures.filesServiceResource.use { filesService =>
          HttpClientCatsBackend.resource[IO]().use { backend =>
            val config = BlueskyConfig.Enabled(server.baseUri.toString(), "user", "pass")
            BlueskyApi.resource(config, backend, filesService).use { api =>
              val request = NewPostRequest(
                content = Content.unsafe("Hello Bluesky"),
                targets = None,
                link = Some("https://example.com"),
                language = Some("en"),
                cleanupHtml = None,
                images = None
              )
              Handle[IO, ApiError].attempt(api.createPost(request))
            }
          }
        }
      }
      login <- loginRef.get
      record <- recordRef.get
    } yield {
      assertEquals(login.map(_.identifier), Some("user"))
      assertEquals(record.map(_.record.text), Some("Hello Bluesky\n\nhttps://example.com"))
      assertEquals(response.map(_.module), Right("bluesky"))
    }
  }

}
