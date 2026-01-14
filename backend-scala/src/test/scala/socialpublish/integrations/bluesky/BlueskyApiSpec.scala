package socialpublish.integrations.bluesky

import cats.effect.*
import munit.CatsEffectSuite
import socialpublish.models.{Content, NewPostRequest}
import socialpublish.testutils.{Http4sTestServer, ServiceFixtures}

import sttp.client4.httpclient.cats.HttpClientCatsBackend

class BlueskyApiSpec extends CatsEffectSuite {

  test("createPost sends record to Bluesky") {
    for {
      loginRef <- Ref.of[IO, Option[BlueskyEndpoints.LoginRequest]](None)
      recordRef <- Ref.of[IO, Option[BlueskyEndpoints.CreatePostRequest]](None)
      endpoints: List[sttp.tapir.server.ServerEndpoint[Any, IO]] = List(
        BlueskyEndpoints.createSession.serverLogicSuccess { request =>
          loginRef.set(Some(request)) *>
            IO.pure(BlueskyEndpoints.LoginResponse("access", "refresh", "handle", "did:example"))
        },
        BlueskyEndpoints.createRecord.serverLogicSuccess { case (_, request) =>
          recordRef.set(Some(request)) *>
            IO.pure(BlueskyEndpoints.CreatePostResponse("at://post/1", "cid-1"))
        }
      )
      response <- Http4sTestServer.resource(endpoints).use { server =>
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
              api.createPost(request).value
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
