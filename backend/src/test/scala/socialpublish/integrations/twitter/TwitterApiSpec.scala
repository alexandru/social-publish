package socialpublish.integrations.twitter

import cats.effect.*
import cats.mtl.Handle
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import socialpublish.integrations.twitter.TwitterModels.*
import socialpublish.models.{ApiError, Content, DocumentTag, NewPostRequest}
import socialpublish.http.ServerConfig
import socialpublish.testutils.{DatabaseFixtures, Http4sTestServer, ServiceFixtures}
import sttp.client4.httpclient.cats.HttpClientCatsBackend

class TwitterApiSpec extends CatsEffectSuite {

  test("getAuthorizationUrl uses request token endpoint") {
    val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
      case POST -> Root / "oauth" / "request_token" =>
        // Return a plain text oauth_token response without using implicit JSON encoders
        val body = "oauth_token=request-token"
        IO.pure(
          Response[IO](Status.Ok)
            .withEntity(body)(using org.http4s.EntityEncoder.stringEncoder)
            .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))
        )
    }

    Http4sTestServer.resource(routes).use { server =>
      ServiceFixtures.filesServiceResource.use { filesService =>
        DatabaseFixtures.tempDocumentsDbResource.use { docsDb =>
          HttpClientCatsBackend.resource[IO]().use { backend =>
            val config = TwitterConfig.Enabled(
              "key",
              "secret",
              server.baseUri.toString(),
              server.baseUri.toString(),
              server.baseUri.toString()
            )
            val api = TwitterApi(
              ServerConfig(3000, "http://localhost:3000", "user", "pass", "secret"),
              config,
              backend,
              filesService,
              docsDb.db
            )

            Handle[IO, ApiError].attempt(api.getAuthorizationUrl("token-123")).map { response =>
              assertEquals(
                response,
                Right(s"${server.baseUri}/oauth/authorize?oauth_token=request-token")
              )
            }
          }
        }
      }
    }
  }

  test("createPost sends tweet payload") {
    for {
      tweetRef <- Ref.of[IO, Option[CreateTweetRequest]](None)
      routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
        case req @ POST -> Root / "2" / "tweets" =>
          req.as[CreateTweetRequest].flatMap { request =>
            tweetRef.set(Some(request)) *>
              Ok(TweetResponse(TweetData("tweet-1")))
          }
      }
      response <- Http4sTestServer.resource(routes).use { server =>
        ServiceFixtures.filesServiceResource.use { filesService =>
          DatabaseFixtures.tempDocumentsDbResource.use { docsDb =>
            HttpClientCatsBackend.resource[IO]().use { backend =>
              val config = TwitterConfig.Enabled(
                "key",
                "secret",
                server.baseUri.toString(),
                server.baseUri.toString(),
                server.baseUri.toString()
              )

              val api = TwitterApi(
                ServerConfig(3000, "http://localhost:3000", "user", "pass", "secret"),
                config,
                backend,
                filesService,
                docsDb.db
              )

              val oauthPayload =
                Map("key" -> "access-key", "secret" -> "access-secret").asJson.noSpaces
              val tags = List(DocumentTag("twitter-oauth-token", "key"))

              val request = NewPostRequest(
                content = Content.unsafe("Hello Twitter"),
                targets = None,
                link = Some("https://example.com"),
                language = None,
                cleanupHtml = None,
                images = None
              )

              for {
                _ <- docsDb.db.createOrUpdate("twitter-oauth-token", oauthPayload, tags)
                response <- Handle[IO, ApiError].attempt(api.createPost(request))
              } yield response
            }
          }
        }
      }
      tweet <- tweetRef.get
    } yield {
      assertEquals(tweet.map(_.text), Some("Hello Twitter\n\nhttps://example.com"))
      assertEquals(response.map(_.module), Right("twitter"))
    }
  }

}
