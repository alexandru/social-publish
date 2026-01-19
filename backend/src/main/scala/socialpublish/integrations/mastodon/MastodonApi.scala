package socialpublish.integrations.mastodon

import cats.effect.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import io.circe.syntax.*
import socialpublish.integrations.mastodon.MastodonModels.*
import socialpublish.models.*
import socialpublish.services.{FilesService, ProcessedFile}
import socialpublish.utils.TextUtils
import sttp.client4.*
import sttp.client4.circe.*
import sttp.model.{MediaType, Uri}

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

      filePart = multipart("file", file.bytes)
        .fileName(file.originalName)
        .contentType(MediaType.unsafeParse(file.mimeType))

      parts = file.altText match {
        case Some(altText) => Seq(filePart, multipart("description", altText))
        case None => Seq(filePart)
      }

      request = basicRequest
        .post(baseUri.addPath("api", "v2", "media"))
        .header("Authorization", bearerToken(config.accessToken))
        .multipartBody(parts)
        .response(asJson[MediaUploadResponse])

      response <- backend.send(request).flatMap { response =>
        response.code.code match {
          case 200 =>
            // Immediate success
            response.body match {
              case Right(value) => IO.pure(value.id)
              case Left(error) =>
                ApiError.requestError(
                  response.code.code,
                  s"Mastodon upload failed: ${error.getMessage}",
                  "mastodon"
                )
                  .raise[IO, String]
            }
          case 202 =>
            // Async processing - need to poll
            response.body match {
              case Right(initial) => pollMediaUntilReady(initial.id)
              case Left(error) =>
                ApiError.requestError(
                  response.code.code,
                  s"Mastodon upload failed: ${error.getMessage}",
                  "mastodon"
                )
                  .raise[IO, String]
            }
          case _ =>
            val errorMsg = response.body.left.map(_.getMessage).merge.toString
            ApiError.requestError(
              response.code.code,
              s"Mastodon upload failed: $errorMsg",
              "mastodon"
            )
              .raise[IO, String]
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
        val request = basicRequest
          .get(baseUri.addPath("api", "v1", "media", mediaId))
          .header("Authorization", bearerToken(config.accessToken))
          .response(asJson[MediaUploadResponse])

        backend.send(request).flatMap { response =>
          response.code.code match {
            case 200 =>
              response.body match {
                case Right(value) => IO.pure(value.id)
                case Left(error) =>
                  ApiError.requestError(
                    response.code.code,
                    s"Mastodon media check failed: ${error.getMessage}",
                    "mastodon"
                  )
                    .raise[IO, String]
              }
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

    val request = basicRequest
      .post(baseUri.addPath("api", "v1", "statuses"))
      .header("Authorization", bearerToken(config.accessToken))
      .body(payload.asJson.noSpaces)
      .contentType("application/json")
      .response(asJson[StatusResponse])

    backend.send(request).flatMap { response =>
      response.body match {
        case Right(value) => IO.pure(value)
        case Left(error) =>
          ApiError.requestError(response.code.code, s"Mastodon status failed: $error", "mastodon")
            .raise[IO, StatusResponse]
      }
    }
  }

  private def bearerToken(token: String): String =
    s"Bearer $token"

}

private class DisabledMastodonApi() extends MastodonApi {
  override def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse] =
    ApiError.validationError("Mastodon integration is disabled", "mastodon")
      .raise[IO, NewPostResponse]
}
