package socialpublish.models

import cats.data.EitherT
import cats.effect.IO

// Custom error types using ADTs
enum ApiError(val status: Int, val message: String, val module: String) {
  case ValidationError(s: Int, m: String, mod: String) extends ApiError(s, m, mod)
  case RequestError(s: Int, m: String, mod: String, body: String) extends ApiError(s, m, mod)
  case CaughtException(m: String, mod: String, cause: Throwable) extends ApiError(500, m, mod)
  case NotFound(m: String) extends ApiError(404, m, "server")
  case Unauthorized(m: String) extends ApiError(401, m, "auth")
}

object ApiError {
  def validationError(message: String, module: String = "server"): ApiError =
    ValidationError(400, message, module)

  def requestError(status: Int, message: String, module: String, body: String = ""): ApiError =
    RequestError(status, message, module, body)

  def caughtException(message: String, module: String, cause: Throwable): ApiError =
    CaughtException(message, module, cause)

  def notFound(message: String): ApiError =
    NotFound(message)

  def unauthorized(message: String): ApiError =
    Unauthorized(message)
}

// Type alias for IO-based Either
type Result[A] = EitherT[IO, ApiError, A]

object Result {
  def success[A](value: A): Result[A] = EitherT.rightT(value)
  def error[A](error: ApiError): Result[A] = EitherT.leftT(error)
  def liftIO[A](io: IO[A]): Result[A] = EitherT.liftF(io)
  def fromEither[A](either: Either[ApiError, A]): Result[A] = EitherT.fromEither(either)
  def fromOption[A](option: Option[A], ifNone: => ApiError): Result[A] =
    EitherT.fromOption(option, ifNone)
}
