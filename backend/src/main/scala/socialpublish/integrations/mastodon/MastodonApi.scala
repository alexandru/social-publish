package socialpublish.integrations.mastodon

import cats.effect.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import socialpublish.integrations.mastodon.MastodonEndpoints.*
import socialpublish.models.*
import socialpublish.services.{FilesService, ProcessedFile}
import socialpublish.utils.TextUtils
import sttp.client4.Backend
import sttp.model.{MediaType, Part, Uri}
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp4.SttpClientInterpreter

import java.util.UUID
import scala.concurrent.duration.*

// Mastodon API implementation
trait MastodonApi {
  def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse]
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

  override def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse] =
    for {
      mediaIds <- request.images.getOrElse(Nil).traverse(uuid => uploadMedia(uuid))
      text = prepareText(request)
      response <- createStatus(text, mediaIds, request.language)
    } yield NewPostResponse.Mastodon(response.uri)

  private def prepareText(request: NewPostRequest): String = {
    val contentStr =
      if request.cleanupHtml.getOrElse(false) then {
        TextUtils.convertHtml(request.content.value)
      } else {
        request.content.value.trim()
      }

    request.link match {
      case Some(link) => s"$contentStr\n\n$link"
      case None => contentStr
    }
  }

  private def uploadMedia(uuid: UUID)(using Raise[IO, ApiError]): IO[String] =
    for {
      fileOpt <- files.getFile(uuid)
      file <- fileOpt.fold(
        ApiError.notFound(s"File not found: $uuid").raise[IO, ProcessedFile]
      )(IO.pure)
      filePart =
        Part("file", file.bytes)
          .fileName(file.originalName)
          .contentType(MediaType.unsafeParse(file.mimeType))

      descriptionPart =
        file.altText.map(altText => Part("description", altText))

      request = interpreter.toRequest(MastodonEndpoints.uploadMedia, Some(baseUri))(
        bearerToken(config.accessToken) -> MediaUploadForm(filePart, descriptionPart)
      )

      response <- backend.send(request).flatMap { response =>
        response.code.code match {
          case 200 =>
            // Immediate success
            Raise[IO, ApiError]
              .fromEither(decodeApiResponse(response.body, response.code.code))
              .map(_.id)
          case 202 =>
            // Async processing - need to poll
            Raise[IO, ApiError]
              .fromEither(decodeApiResponse(response.body, response.code.code))
              .flatMap(initial => pollMediaUntilReady(initial.id))
          case _ =>
            Raise[IO, ApiError]
              .fromEither(decodeApiResponse(response.body, response.code.code))
              .map(_.id)
        }
      }
    } yield response

  private def pollMediaUntilReady(mediaId: String)(using Raise[IO, ApiError]): IO[String] = {
    def poll(attempt: Int = 0): IO[String] =
      if attempt > 30 then {
        // Max 30 attempts (6 seconds)
        ApiError.requestError(
          408,
          s"Mastodon media upload timeout for $mediaId",
          "mastodon"
        ).raise[IO, String]
      } else {
        val request = interpreter.toRequest(MastodonEndpoints.getMedia, Some(baseUri))(
          bearerToken(config.accessToken) -> mediaId
        )

        backend.send(request).flatMap { response =>
          response.code.code match {
            case 200 =>
              Raise[IO, ApiError].fromEither(decodeApiResponse(response.body, response.code.code))
                .map(_.id)
            case 202 =>
              // Still processing, wait and retry
              IO.sleep(200.millis) *> poll(attempt + 1)
            case _ =>
              ApiError.requestError(
                response.code.code,
                s"Mastodon media check failed for $mediaId",
                "mastodon"
              ).raise[IO, String]
          }
        }
      }

    poll()
  }

  private def createStatus(
    text: String,
    mediaIds: List[String],
    language: Option[String]
  )(using Raise[IO, ApiError]): IO[StatusResponse] = {
    val payload = StatusCreateRequest(
      status = text,
      media_ids = mediaIds,
      language = language
    )

    val request = interpreter.toRequest(MastodonEndpoints.createStatus, Some(baseUri))(
      bearerToken(config.accessToken) -> payload
    )

    backend.send(request).flatMap { response =>
      Raise[IO, ApiError].fromEither(decodeApiResponse(response.body, response.code.code))
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
  override def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse] =
    ApiError.validationError("Mastodon integration is disabled", "mastodon")
      .raise[IO, NewPostResponse]
}
