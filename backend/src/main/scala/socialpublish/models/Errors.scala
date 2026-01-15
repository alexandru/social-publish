package socialpublish.models

import cats.Applicative
import cats.effect.IO
import cats.mtl.Handle

// Custom error types using ADTs
enum ApiError(val status: Int, val message: String, val module: String) {
  case ValidationError(s: Int, m: String, mod: String) extends ApiError(s, m, mod)
  case RequestError(s: Int, m: String, mod: String, body: String) extends ApiError(s, m, mod)
  case CaughtException(m: String, mod: String, cause: Throwable) extends ApiError(500, m, mod)
  case NotFound(m: String) extends ApiError(404, m, "server")
  case Unauthorized(m: String) extends ApiError(401, m, "auth")
}

final case class ApiErrorException(error: ApiError) extends RuntimeException(error.message)

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

  given Handle[IO, ApiError] with {
    override def applicative: Applicative[IO] =
      Applicative[IO]

    override def raise[E2 <: ApiError, A](error: E2): IO[A] =
      IO.raiseError(ApiErrorException(error))

    override def handleWith[A](fa: IO[A])(f: ApiError => IO[A]): IO[A] =
      fa.handleErrorWith {
        case ApiErrorException(error) => f(error)
        case other => IO.raiseError(other)
      }
  }

}
