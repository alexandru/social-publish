package socialpublish.http

import cats.effect.*
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtCirce
import socialpublish.db.PostsDatabaseImpl
import socialpublish.integrations.Integrations
import socialpublish.integrations.bluesky.{BlueskyConfig, BlueskyEndpoints}
import socialpublish.integrations.mastodon.{MastodonConfig, MastodonEndpoints}
import socialpublish.integrations.twitter.TwitterConfig
import socialpublish.models.{NewPostRequest, NewPostResponse}
import socialpublish.testutils.{DatabaseFixtures, NettyTestServer, ServiceFixtures}
import sttp.client4.*
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.model.StatusCode

class ServerIntegrationSpec extends CatsEffectSuite {

  test("main server posts to mocked integrations") {
    val blueskyEndpoints: List[sttp.tapir.server.ServerEndpoint[Any, IO]] = List(
      BlueskyEndpoints.createSession.serverLogicSuccess { _ =>
        IO.pure(BlueskyEndpoints.LoginResponse("access", "refresh", "handle", "did:example"))
      },
      BlueskyEndpoints.createRecord.serverLogicSuccess { case (_, request) =>
        IO.pure(BlueskyEndpoints.CreatePostResponse("at://example/post", "cid-123"))
      }
    )

    val mastodonEndpoints: List[sttp.tapir.server.ServerEndpoint[Any, IO]] = List(
      MastodonEndpoints.createStatus.serverLogicSuccess { case (_, request) =>
        IO.pure(MastodonEndpoints.StatusResponse(
          "id-1",
          "https://mastodon/post/1",
          "https://mastodon/post/1"
        ))
      }
    )

    (
      NettyTestServer.resource(blueskyEndpoints),
      NettyTestServer.resource(mastodonEndpoints)
    ).tupled.use {
      case (blueskyServer, mastodonServer) =>
        ServiceFixtures.filesServiceResource.use { filesService =>
          DatabaseFixtures.tempDocumentsDbResource.use { docsDb =>
            HttpClientCatsBackend.resource[IO]().use { clientBackend =>
              for {
                port <- freePort
                serverConfig = ServerConfig(
                  port = port,
                  baseUrl = s"http://127.0.0.1:$port",
                  authUser = "admin",
                  authPass = "secret",
                  jwtSecret = "jwt-secret"
                )
                response <- Integrations.resource(
                  serverConfig,
                  BlueskyConfig.Enabled(blueskyServer.baseUri.toString(), "user", "pass"),
                  MastodonConfig.Enabled(mastodonServer.baseUri.toString(), "token"),
                  TwitterConfig.Disabled,
                  filesService,
                  docsDb.db
                ).use { integrations =>
                  val authMiddleware =
                    new AuthMiddleware(serverConfig, integrations.twitter, logger)
                  val routes = new Routes(
                    serverConfig,
                    authMiddleware,
                    integrations.bluesky,
                    integrations.mastodon,
                    integrations.twitter,
                    filesService,
                    new PostsDatabaseImpl(docsDb.db),
                    logger
                  )
                  HttpServer.resource(serverConfig, routes).use { _ =>
                    val loginRequest = LoginRequest("admin", "secret")

                    val loginResponse = basicRequest
                      .post(uri"http://127.0.0.1:$port/api/login")
                      .contentType("application/json")
                      .body(loginRequest.asJson.noSpaces)
                      .response(asStringAlways)
                      .send(clientBackend)
                      .flatMap(resp => IO.fromEither(decode[LoginResponse](resp.body)))

                    loginResponse.flatMap { login =>
                      val tokenValid = JwtCirce.decode(
                        login.token,
                        serverConfig.jwtSecret,
                        Seq(JwtAlgorithm.HS256)
                      ).toOption.isDefined

                      IO.raiseWhen(!tokenValid) {
                        new RuntimeException("Login token could not be decoded")
                      } *> authMiddleware.authenticate(AuthInputs(login.token)).flatMap {
                        case Left(error) =>
                          IO.raiseError(
                            new RuntimeException(s"Auth check failed: ${error.message}")
                          )
                        case Right(_) =>
                          IO.unit
                      } *> {
                        val postRequest = NewPostRequest(
                          content = "Hello world",
                          targets = None,
                          link = None,
                          language = None,
                          cleanupHtml = None,
                          images = None
                        )

                        val blueskyRequest = basicRequest
                          .post(uri"http://127.0.0.1:$port/api/bluesky/post")
                          .contentType("application/json")
                          .header("Authorization", s"Bearer ${login.token}")
                          .body(postRequest.asJson.noSpaces)
                          .response(asStringAlways)

                        val mastodonRequest = basicRequest
                          .post(uri"http://127.0.0.1:$port/api/mastodon/post")
                          .contentType("application/json")
                          .header("Authorization", s"Bearer ${login.token}")
                          .body(postRequest.asJson.noSpaces)
                          .response(asStringAlways)

                        for {
                          blueskyResponse <- blueskyRequest.send(clientBackend)
                          _ <- IO.raiseWhen(blueskyResponse.code != StatusCode.Ok) {
                            new RuntimeException(s"Bluesky response: ${blueskyResponse.body}")
                          }
                          bluesky <- IO.fromEither(decode[NewPostResponse](blueskyResponse.body))
                          mastodonResponse <- mastodonRequest.send(clientBackend)
                          _ <- IO.raiseWhen(mastodonResponse.code != StatusCode.Ok) {
                            new RuntimeException(s"Mastodon response: ${mastodonResponse.body}")
                          }
                          mastodon <- IO.fromEither(decode[NewPostResponse](mastodonResponse.body))
                        } yield (bluesky, mastodon)
                      }
                    }
                  }
                }
              } yield response
            }
          }
        }
    }.map { case (bluesky, mastodon) =>
      assertEquals(bluesky.module, "bluesky")
      assertEquals(mastodon.module, "mastodon")
    }
  }

  private def freePort: IO[Int] =
    IO.blocking {
      val socket = new java.net.ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }

  private val logger = org.typelevel.log4cats.noop.NoOpLogger[IO]

}
