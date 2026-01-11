package socialpublish.integrations.mastodon

import cats.effect.*
import munit.CatsEffectSuite
import socialpublish.models.NewPostRequest
import socialpublish.testutils.{NettyTestServer, ServiceFixtures}
import sttp.client4.httpclient.cats.HttpClientCatsBackend

class MastodonApiSpec extends CatsEffectSuite {

  test("createPost sends status to Mastodon") {
    for {
      statusRef <- Ref.of[IO, Option[MastodonEndpoints.StatusCreateRequest]](None)
      endpoints: List[sttp.tapir.server.ServerEndpoint[Any, IO]] = List(
        MastodonEndpoints.createStatus.serverLogicSuccess { case (_, request) =>
          statusRef.set(Some(request)) *>
            IO.pure(MastodonEndpoints.StatusResponse(
              "id-1",
              "https://mastodon/post/1",
              "https://mastodon/post/1"
            ))
        }
      )
      response <- NettyTestServer.resource(endpoints).use { server =>
        ServiceFixtures.filesServiceResource.use { filesService =>
          HttpClientCatsBackend.resource[IO]().use { backend =>
            val config = MastodonConfig.Enabled(server.baseUri.toString(), "token")
            val api = MastodonApi(config, backend, filesService)
            val request = NewPostRequest(
              content = "Hello Mastodon",
              targets = None,
              link = Some("https://example.com"),
              language = Some("pt"),
              cleanupHtml = None,
              images = None
            )
            api.createPost(request).value
          }
        }
      }
      status <- statusRef.get
    } yield {
      assertEquals(status.map(_.status), Some("Hello Mastodon\n\nhttps://example.com"))
      assertEquals(status.map(_.language), Some(Some("pt")))
      assertEquals(response.map(_.module), Right("mastodon"))
    }
  }

}
