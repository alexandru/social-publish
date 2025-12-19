package socialpublish.models

import cats.data.EitherT
import cats.effect.IO

// Custom error types using ADTs
enum ApiError:
  case ValidationError(status: Int, message: String, module: String)
  case RequestError(status: Int, message: String, module: String, body: String)
  case CaughtException(message: String, module: String, cause: Throwable)
  case NotFound(message: String)
  case Unauthorized(message: String)
  
  def status: Int = this match
    case ValidationError(s, _, _) => s
    case RequestError(s, _, _, _) => s
    case CaughtException(_, _, _) => 500
    case NotFound(_) => 404
    case Unauthorized(_) => 401
  
  def message: String = this match
    case ValidationError(_, m, _) => m
    case RequestError(_, m, _, _) => m
    case CaughtException(m, _, _) => m
    case NotFound(m) => m
    case Unauthorized(m) => m
  
  def module: String = this match
    case ValidationError(_, _, mod) => mod
    case RequestError(_, _, mod, _) => mod
    case CaughtException(_, mod, _) => mod
    case NotFound(_) => "server"
    case Unauthorized(_) => "auth"

object ApiError:
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

// Type alias for IO-based Either
type Result[A] = EitherT[IO, ApiError, A]

object Result:
  def success[A](value: A): Result[A] = EitherT.rightT(value)
  def error[A](error: ApiError): Result[A] = EitherT.leftT(error)
  def liftIO[A](io: IO[A]): Result[A] = EitherT.liftF(io)
  def fromEither[A](either: Either[ApiError, A]): Result[A] = EitherT.fromEither(either)
  def fromOption[A](option: Option[A], ifNone: => ApiError): Result[A] = EitherT.fromOption(option, ifNone)
