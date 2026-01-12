package socialpublish.integrations.twitter

import cats.effect.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import socialpublish.models.DocumentTag
import socialpublish.http.ServerConfig
import socialpublish.models.NewPostRequest
import socialpublish.testutils.{DatabaseFixtures, NettyTestServer, ServiceFixtures}
import sttp.client4.httpclient.cats.HttpClientCatsBackend

class TwitterApiSpec extends CatsEffectSuite {

  test("getAuthorizationUrl uses request token endpoint") {
    val endpoints = List(
      TwitterEndpoints.requestToken.serverLogicSuccess { case (_, callback, accessType) =>
        if callback.contains("token-123") && accessType == "write" then {
          IO.pure("oauth_token=request-token")
        } else {
          IO.pure("oauth_token=invalid")
        }
      }
    )

    NettyTestServer.resource(endpoints).use { server =>
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

            api.getAuthorizationUrl("token-123").value.map { response =>
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
      tweetRef <- Ref.of[IO, Option[TwitterEndpoints.CreateTweetRequest]](None)
      endpoints = List(
        TwitterEndpoints.createTweet.serverLogicSuccess { case (_, request) =>
          tweetRef.set(Some(request)) *>
            IO.pure(TwitterEndpoints.TweetResponse(TwitterEndpoints.TweetData("tweet-1")))
        }
      )
      response <- NettyTestServer.resource(endpoints).use { server =>
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
                response <- api.createPost(request).value
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
