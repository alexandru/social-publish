package socialpublish.integrations.bluesky

import cats.effect.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import io.circe.syntax.*
import socialpublish.integrations.bluesky.BlueskyModels.*
import socialpublish.models.*
import socialpublish.services.{FilesService, ProcessedFile}
import socialpublish.utils.TextUtils
import sttp.client4.*
import sttp.client4.circe.*
import sttp.model.Uri

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Bluesky API implementation
  *
  * Based on AT Protocol: <https://docs.bsky.app/docs/api/>
  */
trait BlueskyApi {
  def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse]
}

object BlueskyApi {

  def resource(
    cfg: BlueskyConfig,
    backend: Backend[IO],
    files: FilesService
  ): Resource[IO, BlueskyApi] =
    cfg match {
      case BlueskyConfig.Enabled(service, username, password) =>
        Resource.eval(loginInternal(service, username, password, backend)).map(session =>
          new BlueskyApiImpl(
            BlueskyConfig.Enabled(service, username, password),
            backend,
            files,
            session
          )
        )
      case BlueskyConfig.Disabled =>
        Resource.eval(IO.pure(new DisabledBlueskyApi()))
    }

  private def loginInternal(
    service: String,
    username: String,
    password: String,
    backend: Backend[IO]
  ): IO[LoginResponse] = {
    val baseUri = Uri.unsafeParse(service)
    val request = basicRequest
      .post(baseUri.addPath("xrpc", "com.atproto.server.createSession"))
      .body(LoginRequest(username, password).asJson.noSpaces)
      .contentType("application/json")
      .response(asJson[LoginResponse])

    backend.send(request).flatMap { response =>
      response.body match {
        case Right(value) => IO.pure(value)
        case Left(error) =>
          IO.raiseError(new RuntimeException(s"Bluesky login failed: $error"))
      }
    }
  }

}

private class BlueskyApiImpl(
  config: BlueskyConfig.Enabled,
  backend: Backend[IO],
  files: FilesService,
  session: LoginResponse
) extends BlueskyApi {

  private val baseUri = Uri.unsafeParse(config.service)

  override def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse] =
    for {
      images <- request.images.getOrElse(Nil).traverse(uuid => uploadImage(uuid))
      text = prepareText(request)
      facets <- detectFacets(text)
      record = createPostRecord(text, request.language, images, facets)
      response <- postToBluesky(record)
    } yield NewPostResponse.Bluesky(response.uri, Some(response.cid))

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

  private def uploadImage(uuid: UUID)(using Raise[IO, ApiError]): IO[ImageEmbed] =
    for {
      fileOpt <- files.getFile(uuid)
      file <- fileOpt.fold(
        ApiError.notFound(s"File not found: $uuid").raise[IO, ProcessedFile]
      )(IO.pure)
      blobRef <- uploadBlobRef(file.bytes, file.mimeType)
    } yield ImageEmbed(
      alt = file.altText.getOrElse(""),
      image = blobRef,
      aspectRatio = Some(AspectRatio(file.width, file.height))
    )

  private def uploadBlobRef(bytes: Array[Byte], mimeType: String)(using
    Raise[IO, ApiError]
  ): IO[BlobRef] = {
    val request = basicRequest
      .post(baseUri.addPath("xrpc", "com.atproto.repo.uploadBlob"))
      .header("Authorization", bearerToken(session.accessJwt))
      .contentType(mimeType)
      .body(bytes)
      .response(asJson[UploadBlobResponse])

    backend.send(request).flatMap { response =>
      response.body match {
        case Right(value) => IO.pure(value.blob)
        case Left(error) =>
          ApiError
            .requestError(
              response.code.code,
              s"Bluesky upload failed: ${error.getMessage}",
              "bluesky"
            )
            .raise[IO, BlobRef]
      }
    }
  }

  private def detectFacets(text: String): IO[List[Facet]] =
    IO.pure {
      val urlPattern = """https?://[^\s]+""".r
      urlPattern.findAllMatchIn(text).toList.map { m =>
        val start = utf8ByteOffset(text, m.start)
        val end = utf8ByteOffset(text, m.end)
        Facet(
          ByteSlice(start, end),
          List(Feature("app.bsky.richtext.facet#link", m.matched))
        )
      }
    }

  private def utf8ByteOffset(text: String, charIndex: Int): Int =
    text.substring(0, charIndex).getBytes(StandardCharsets.UTF_8).length

  private def createPostRecord(
    text: String,
    language: Option[String],
    images: List[ImageEmbed],
    facets: List[Facet]
  ): PostRecord = {
    val embed =
      if images.nonEmpty then {
        Some(ImageEmbedPayload("app.bsky.embed.images", images))
      } else {
        None
      }

    PostRecord(
      `$type` = "app.bsky.feed.post",
      text = text,
      langs = language.map(List(_)),
      facets = if facets.nonEmpty then Some(facets) else None,
      embed = embed,
      createdAt = java.time.Instant.now().toString
    )
  }

  private def postToBluesky(record: PostRecord)(using
    Raise[IO, ApiError]
  ): IO[CreatePostResponse] = {
    val payload = CreatePostRequest(
      repo = session.did,
      collection = "app.bsky.feed.post",
      record = record
    )

    val request = basicRequest
      .post(baseUri.addPath("xrpc", "com.atproto.repo.createRecord"))
      .header("Authorization", bearerToken(session.accessJwt))
      .body(payload.asJson.noSpaces)
      .contentType("application/json")
      .response(asJson[CreatePostResponse])

    backend.send(request).flatMap { response =>
      response.body match {
        case Right(value) => IO.pure(value)
        case Left(error) =>
          ApiError
            .requestError(response.code.code, s"Bluesky post failed: $error", "bluesky")
            .raise[IO, CreatePostResponse]
      }
    }
  }

  private def bearerToken(token: String): String =
    s"Bearer $token"

}

private class DisabledBlueskyApi() extends BlueskyApi {
  override def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse] =
    ApiError.validationError("Bluesky integration is disabled", "bluesky")
      .raise[IO, NewPostResponse]
}
