package socialpublish.integrations.mastodon

import cats.effect.*
import cats.mtl.Handle
import munit.CatsEffectSuite
import socialpublish.models.{ApiError, Content, NewPostRequest}
import socialpublish.testutils.{Http4sTestServer, ServiceFixtures}

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
      response <- Http4sTestServer.resource(endpoints).use { server =>
        ServiceFixtures.filesServiceResource.use { filesService =>
          HttpClientCatsBackend.resource[IO]().use { backend =>
            val config = MastodonConfig.Enabled(server.baseUri.toString(), "token")
            val api = MastodonApi(config, backend, filesService)
            val request = NewPostRequest(
              content = Content.unsafe("Hello Mastodon"),
              targets = None,
              link = Some("https://example.com"),
              language = Some("pt"),
              cleanupHtml = None,
              images = None
            )
            Handle[IO, ApiError].attempt(api.createPost(request))
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
