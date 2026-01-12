package socialpublish.integrations.mastodon

import cats.effect.*
import cats.syntax.all.*
import socialpublish.integrations.mastodon.MastodonEndpoints.*
import socialpublish.models.*
import socialpublish.services.FilesService
import socialpublish.utils.TextUtils
import sttp.client4.Backend
import sttp.model.{MediaType, Part, Uri}
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp4.SttpClientInterpreter

import java.util.UUID
import scala.concurrent.duration.*

// Mastodon API implementation
trait MastodonApi {
  def createPost(request: NewPostRequest): Result[NewPostResponse]
}

object MastodonApi {

  def apply(
    config: MastodonConfig,
    backend: Backend[IO],
    files: FilesService
  ): MastodonApi =
    config match {
      case enabled: MastodonConfig.Enabled =>
        new MastodonApiImpl(enabled, backend, files)
      case MastodonConfig.Disabled =>
        new DisabledMastodonApi()
    }

}

private class MastodonApiImpl(
  config: MastodonConfig.Enabled,
  backend: Backend[IO],
  files: FilesService
) extends MastodonApi {

  private val interpreter = SttpClientInterpreter()
  private val baseUri = Uri.unsafeParse(config.host)

  override def createPost(request: NewPostRequest): Result[NewPostResponse] =
    for {
      mediaIds <- request.images.getOrElse(Nil).traverse(uuid => uploadMedia(uuid))
      text = prepareText(request)
      response <- createStatus(text, mediaIds, request.language)
    } yield NewPostResponse.Mastodon(response.uri)

  private def prepareText(request: NewPostRequest): String = {
    val content =
      if request.cleanupHtml.getOrElse(false) then {
        TextUtils.convertHtml(request.content)
      } else {
        request.content.trim()
      }

    request.link match {
      case Some(link) => s"$content\n\n$link"
      case None => content
    }
  }

  private def uploadMedia(uuid: UUID): Result[String] =
    for {
      fileOpt <- Result.liftIO(files.getFile(uuid))
      file <- Result.fromOption(fileOpt, ApiError.notFound(s"File not found: $uuid"))
      filePart =
        Part("file", file.bytes)
          .fileName(file.originalName)
          .contentType(MediaType.unsafeParse(file.mimeType))

      descriptionPart =
        file.altText.map(altText => Part("description", altText))

      request = interpreter.toRequest(MastodonEndpoints.uploadMedia, Some(baseUri))(
        bearerToken(config.accessToken) -> MediaUploadForm(filePart, descriptionPart)
      )

      response <- Result.liftIO(backend.send(request)).flatMap { response =>
        response.code.code match {
          case 200 =>
            // Immediate success
            Result.fromEither(decodeApiResponse(response.body, response.code.code))
              .map(_.id)
          case 202 =>
            // Async processing - need to poll
            Result.fromEither(decodeApiResponse(response.body, response.code.code))
              .flatMap(initial => pollMediaUntilReady(initial.id))
          case _ =>
            Result.fromEither(decodeApiResponse(response.body, response.code.code))
              .map(_.id)
        }
      }
    } yield response

  private def pollMediaUntilReady(mediaId: String): Result[String] = {
    def poll(attempt: Int = 0): IO[Either[ApiError, String]] =
      if attempt > 30 then {
        // Max 30 attempts (6 seconds)
        IO.pure(Left(ApiError.requestError(
          408,
          s"Mastodon media upload timeout for $mediaId",
          "mastodon"
        )))
      } else {
        val request = interpreter.toRequest(MastodonEndpoints.getMedia, Some(baseUri))(
          bearerToken(config.accessToken) -> mediaId
        )

        backend.send(request).flatMap { response =>
          response.code.code match {
            case 200 =>
              decodeApiResponse(response.body, response.code.code) match {
                case Right(media) => IO.pure(Right(media.id))
                case Left(error) => IO.pure(Left(error))
              }
            case 202 =>
              // Still processing, wait and retry
              IO.sleep(200.millis) *> poll(attempt + 1)
            case _ =>
              IO.pure(Left(ApiError.requestError(
                response.code.code,
                s"Mastodon media check failed for $mediaId",
                "mastodon"
              )))
          }
        }
      }

    Result(poll())
  }

  private def createStatus(
    text: String,
    mediaIds: List[String],
    language: Option[String]
  ): Result[StatusResponse] = {
    val payload = StatusCreateRequest(
      status = text,
      media_ids = mediaIds,
      language = language
    )

    val request = interpreter.toRequest(MastodonEndpoints.createStatus, Some(baseUri))(
      bearerToken(config.accessToken) -> payload
    )

    Result.liftIO(backend.send(request)).flatMap { response =>
      Result.fromEither(decodeApiResponse(response.body, response.code.code))
    }
  }

  private def decodeApiResponse[A](
    decoded: DecodeResult[Either[String, A]],
    status: Int
  ): Either[ApiError, A] =
    decoded match {
      case DecodeResult.Value(Right(value)) =>
        Right(value)
      case DecodeResult.Value(Left(errorBody)) =>
        Left(ApiError.requestError(status, "Mastodon request failed", "mastodon", errorBody))
      case DecodeResult.Error(original, error) =>
        Left(ApiError.caughtException(s"Mastodon decode failure: $original", "mastodon", error))
      case DecodeResult.Missing =>
        Left(ApiError.requestError(status, "Mastodon response missing body", "mastodon"))
      case DecodeResult.Mismatch(_, _) =>
        Left(ApiError.requestError(status, "Mastodon response mismatch", "mastodon"))
      case DecodeResult.InvalidValue(errors) =>
        Left(ApiError.requestError(
          status,
          s"Mastodon invalid response: ${errors.toList.mkString(", ")}",
          "mastodon"
        ))
      case DecodeResult.Multiple(errors) =>
        Left(ApiError.requestError(
          status,
          s"Mastodon multiple responses: ${errors.toList.mkString(", ")}",
          "mastodon"
        ))
    }

  private def bearerToken(token: String): String =
    s"Bearer $token"

}

private class DisabledMastodonApi() extends MastodonApi {
  override def createPost(request: NewPostRequest): Result[NewPostResponse] =
    Result.error(ApiError.validationError("Mastodon integration is disabled", "mastodon"))
}
