package socialpublish.http

import cats.data.Kleisli
import cats.effect.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtCirce
import pdi.jwt.JwtClaim
import socialpublish.api.TwitterApi

import java.time.Instant

case class UserPayload(username: String, iat: Long, exp: Long) derives Codec.AsObject
case class LoginRequest(username: String, password: String) derives Codec.AsObject
case class LoginResponse(token: String, hasAuth: AuthStatus) derives Codec.AsObject
case class AuthStatus(twitter: Boolean) derives Codec.AsObject

class AuthMiddleware(
  server: ServerConfig,
  twitter: TwitterApi,
  logger: Logger[IO]
) {

  // Middleware that validates JWT tokens - simplified version that just validates
  def middleware: Kleisli[IO, Request[IO], Option[Request[IO]]] =
    Kleisli { req =>
      validateToken(req).map {
        case Some(_) => Some(req)
        case None => None
      }
    }

  def login(req: Request[IO]): IO[Response[IO]] =
    req.as[LoginRequest].flatMap { credentials =>
      if credentials.username == server.authUser &&
      credentials.password == server.authPass then for {
        hasTwitter <- twitter.hasTwitterAuth
        token = generateToken(credentials.username)
        response = LoginResponse(token, AuthStatus(hasTwitter))
      } yield Response[IO](Status.Ok).withEntity(response.asJson)
      else
        IO.pure(Response[IO](
          Status.Unauthorized
        ).withEntity(Json.obj("error" -> Json.fromString("Invalid credentials"))))
    }.handleErrorWith { err =>
      logger.error(err)("Login failed") *>
        IO.pure(Response[IO](
          Status.BadRequest
        ).withEntity(Json.obj("error" -> Json.fromString(err.getMessage))))
    }

  def protectedRoute(req: Request[IO]): IO[Response[IO]] =
    validateToken(req).flatMap {
      case Some(user) =>
        IO.pure(
          Response[IO](Status.Ok).withEntity(Json.obj("username" -> Json.fromString(user.username)))
        )
      case None =>
        IO.pure(Response[IO](
          Status.Unauthorized
        ).withEntity(Json.obj("error" -> Json.fromString("Unauthorized"))))
    }

  private def validateToken(req: Request[IO]): IO[Option[UserPayload]] =
    IO {
      extractToken(req).flatMap { token =>
        JwtCirce.decode(
          token,
          server.jwtSecret,
          Seq(JwtAlgorithm.HS256)
        ).toOption.flatMap { claim =>
          io.circe.parser.decode[UserPayload](claim.content).toOption
        }
      }
    }

  private def extractToken(req: Request[IO]): Option[String] =
    // Check Authorization header
    req.headers.get(ci"Authorization").flatMap { header =>
      val value = header.head.value
      if value.startsWith("Bearer ") then Some(value.substring(7))
      else
        None
    }.orElse {
      // Check query parameter
      req.uri.query.params.get("access_token")
    }.orElse {
      // Check cookie
      req.cookies.find(_.name == "access_token").map(_.content)
    }

  private def generateToken(username: String): String = {
    val now = Instant.now()
    val claim = JwtClaim(
      content = UserPayload(
        username,
        now.getEpochSecond,
        now.plusSeconds(168 * 3600).getEpochSecond
      ).asJson.noSpaces,
      expiration = Some(now.plusSeconds(168 * 3600).getEpochSecond),
      issuedAt = Some(now.getEpochSecond)
    )
    JwtCirce.encode(claim, server.jwtSecret, JwtAlgorithm.HS256)
  }
}
