package socialpublish.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.multipart.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import socialpublish.config.AppConfig
import socialpublish.models.*
import socialpublish.services.FilesService
import socialpublish.utils.TextUtils
import java.util.UUID

// Mastodon API implementation
trait MastodonApi:
  def createPost(request: NewPostRequest): Result[NewPostResponse]

object MastodonApi:
  def apply(config: AppConfig, client: Client[IO], files: FilesService, logger: Logger[IO]): MastodonApi =
    new MastodonApiImpl(config, client, files, logger)

private class MastodonApiImpl(
  config: AppConfig,
  client: Client[IO],
  files: FilesService,
  logger: Logger[IO]
) extends MastodonApi:
  
  private case class MediaUploadResponse(id: String, url: String) derives Codec.AsObject
  private case class StatusResponse(id: String, uri: String, url: String) derives Codec.AsObject
  
  override def createPost(request: NewPostRequest): Result[NewPostResponse] =
    for
      // Upload media files
      mediaIds <- request.images.getOrElse(Nil).traverse(uuid => uploadMedia(uuid))
      
      // Prepare status text
      text = prepareText(request)
      
      // Create status
      response <- createStatus(text, mediaIds, request.language)
    yield NewPostResponse.Mastodon(response.uri)
  
  private def prepareText(request: NewPostRequest): String =
    val content = if request.cleanupHtml.getOrElse(false) then
      TextUtils.convertHtml(request.content)
    else
      request.content.trim()
    
    request.link match
      case Some(link) => s"$content\n\n$link"
      case None => content
  
  private def uploadMedia(uuid: UUID): Result[String] =
    for
      fileOpt <- Result.liftIO(files.getFile(uuid))
      file <- Result.fromOption(fileOpt, ApiError.notFound(s"File not found: $uuid"))
      response <- Result.liftIO {
        val uri = Uri.unsafeFromString(s"${config.mastodonHost}/api/v2/media")
        
        val filePart = Part.fileData(
          "file",
          file.originalName,
          EntityBody.fromArray[IO](file.bytes),
          `Content-Type`(MediaType.unsafeParse(file.mimeType))
        )
        
        val parts = file.altText match
          case Some(alt) => Vector(filePart, Part.formData("description", alt))
          case None => Vector(filePart)
        
        val multipart = Multipart[IO](parts)
        val request = Request[IO](Method.POST, uri)
          .withHeaders(Header.Raw(ci"Authorization", s"Bearer ${config.mastodonAccessToken}"))
          .withEntity(multipart)
          .withHeaders(multipart.headers)
        
        client.expect[Json](request).flatMap { json =>
          IO.fromEither(json.hcursor.get[String]("id"))
        }
      }
    yield response
  
  private def createStatus(text: String, mediaIds: List[String], language: Option[String]): Result[StatusResponse] =
    val uri = Uri.unsafeFromString(s"${config.mastodonHost}/api/v1/statuses")
    
    val payload = Json.obj(
      "status" -> Json.fromString(text),
      "media_ids" -> mediaIds.asJson,
      "language" -> language.asJson
    )
    
    val request = Request[IO](Method.POST, uri)
      .withHeaders(Header.Raw(ci"Authorization", s"Bearer ${config.mastodonAccessToken}"))
      .withEntity(payload)
    
    Result.liftIO {
      client.expect[Json](request).flatMap { json =>
        IO.fromEither(json.as[StatusResponse])
      }.handleErrorWith { err =>
        logger.error(err)("Failed to post to Mastodon") *>
        IO.raiseError(err)
      }
    }
