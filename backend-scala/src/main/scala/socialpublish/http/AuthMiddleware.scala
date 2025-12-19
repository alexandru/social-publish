package socialpublish.http

import cats.effect.*
import cats.data.{Kleisli, OptionT}
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import io.circe.generic.auto.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.circe.CirceEntityDecoder.*
import org.typelevel.ci.CIStringSyntax
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtCirce}
import socialpublish.config.AppConfig
import socialpublish.api.TwitterApi
import org.typelevel.log4cats.Logger
import java.time.Instant

case class UserPayload(username: String, iat: Long, exp: Long) derives Codec.AsObject
case class LoginRequest(username: String, password: String) derives Codec.AsObject
case class LoginResponse(token: String, hasAuth: AuthStatus) derives Codec.AsObject
case class AuthStatus(twitter: Boolean) derives Codec.AsObject

class AuthMiddleware(
  config: AppConfig,
  twitter: TwitterApi,
  logger: Logger[IO]
):
  
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
      if credentials.username == config.serverAuthUsername && 
         credentials.password == config.serverAuthPassword then
        for
          hasTwitter <- twitter.hasTwitterAuth
          token = generateToken(credentials.username)
          response = LoginResponse(token, AuthStatus(hasTwitter))
        yield Response[IO](Status.Ok).withEntity(response.asJson)
      else
        IO.pure(Response[IO](Status.Unauthorized).withEntity(Json.obj("error" -> Json.fromString("Invalid credentials"))))
    }.handleErrorWith { err =>
      logger.error(err)("Login failed") *>
      IO.pure(Response[IO](Status.BadRequest).withEntity(Json.obj("error" -> Json.fromString(err.getMessage))))
    }
  
  def protectedRoute(req: Request[IO]): IO[Response[IO]] =
    validateToken(req).flatMap {
      case Some(user) =>
        IO.pure(Response[IO](Status.Ok).withEntity(Json.obj("username" -> Json.fromString(user.username))))
      case None =>
        IO.pure(Response[IO](Status.Unauthorized).withEntity(Json.obj("error" -> Json.fromString("Unauthorized"))))
    }
  
  private def validateToken(req: Request[IO]): IO[Option[UserPayload]] =
    IO {
      extractToken(req).flatMap { token =>
        JwtCirce.decode(token, config.serverAuthJwtSecret, Seq(JwtAlgorithm.HS256)).toOption.flatMap { claim =>
          io.circe.parser.decode[UserPayload](claim.content).toOption
        }
      }
    }
  
  private def extractToken(req: Request[IO]): Option[String] =
    // Check Authorization header
    req.headers.get(ci"Authorization").flatMap { header =>
      val value = header.head.value
      if value.startsWith("Bearer ") then
        Some(value.substring(7))
      else
        None
    }.orElse {
      // Check query parameter
      req.uri.query.params.get("access_token")
    }.orElse {
      // Check cookie
      req.cookies.find(_.name == "access_token").map(_.content)
    }
  
  private def generateToken(username: String): String =
    val now = Instant.now()
    val claim = JwtClaim(
      content = UserPayload(username, now.getEpochSecond, now.plusSeconds(168 * 3600).getEpochSecond).asJson.noSpaces,
      expiration = Some(now.plusSeconds(168 * 3600).getEpochSecond),
      issuedAt = Some(now.getEpochSecond)
    )
    JwtCirce.encode(claim, config.serverAuthJwtSecret, JwtAlgorithm.HS256)
