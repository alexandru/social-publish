package socialpublish.http

import cats.effect.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtCirce
import pdi.jwt.JwtClaim
import socialpublish.integrations.twitter.TwitterApi
import socialpublish.models.ApiError
import sttp.tapir.Schema

case class UserPayload(username: String) derives Codec.AsObject

object UserPayload {
  given Schema[UserPayload] =
    Schema.derived
}

case class LoginRequest(username: String, password: String) derives Codec.AsObject

object LoginRequest {
  given Schema[LoginRequest] =
    Schema.derived
}

case class LoginResponse(token: String, hasAuth: AuthStatus) derives Codec.AsObject

object LoginResponse {
  given Schema[LoginResponse] =
    Schema.derived
}

case class AuthStatus(twitter: Boolean) derives Codec.AsObject

object AuthStatus {
  given Schema[AuthStatus] =
    Schema.derived
}

case class ProtectedResponse(username: String) derives Codec.AsObject

object ProtectedResponse {
  given Schema[ProtectedResponse] =
    Schema.derived
}

case class AuthInputs(
  authHeader: Option[String],
  accessTokenQuery: Option[String],
  accessTokenCookie: Option[String]
)

case class AuthContext(user: UserPayload, token: String)

object AuthContext {
  given Schema[AuthContext] =
    Schema.derived
}

class AuthMiddleware(
  server: ServerConfig,
  twitter: TwitterApi,
  logger: Logger[IO]
) {

  def login(credentials: LoginRequest)(using Raise[IO, ApiError]): IO[LoginResponse] =
    if credentials.username == server.authUser && credentials.password == server.authPass then {
      for {
        hasTwitter <- twitter.hasTwitterAuth
        token <- generateToken(credentials.username)
        response = LoginResponse(token, AuthStatus(hasTwitter))
      } yield response
    } else {
      logger.warn("Invalid login credentials") *>
        ApiError.unauthorized("Invalid credentials").raise[IO, LoginResponse]
    }

  def authenticate(inputs: AuthInputs)(using Raise[IO, ApiError]): IO[AuthContext] =
    tokenFromInputs(inputs) match {
      case Some(token) =>
        validateToken(token).flatMap {
          case Some(user) => IO.pure(AuthContext(user, token))
          case None => ApiError.unauthorized("Unauthorized").raise[IO, AuthContext]
        }
      case None =>
        ApiError.unauthorized("Unauthorized").raise[IO, AuthContext]
    }

  def protectedResponse(user: UserPayload): ProtectedResponse =
    ProtectedResponse(user.username)

  private def tokenFromInputs(inputs: AuthInputs): Option[String] =
    List(inputs.authHeader, inputs.accessTokenQuery, inputs.accessTokenCookie)
      .collectFirst { case Some(token) => normalizeToken(token) }

  private def validateToken(token: String): IO[Option[UserPayload]] =
    IO {
      JwtCirce.decode(
        token,
        server.jwtSecret,
        Seq(JwtAlgorithm.HS256)
      ).toOption.flatMap { claim =>
        io.circe.parser.decode[UserPayload](claim.content).toOption
      }
    }

  private def normalizeToken(token: String): String =
    if token.startsWith("Bearer ") then {
      token.substring(7)
    } else {
      token
    }

  private def generateToken(username: String): IO[String] =
    for {
      now <- Clock[IO].realTimeInstant
      claim = JwtClaim(
        content = UserPayload(username).asJson.noSpaces,
        expiration = Some(now.plusSeconds(168 * 3600).getEpochSecond),
        issuedAt = Some(now.getEpochSecond)
      )
    } yield JwtCirce.encode(claim, server.jwtSecret, JwtAlgorithm.HS256)

}
