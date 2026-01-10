package socialpublish.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.circe.*
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.Logger
import socialpublish.models.*
import socialpublish.services.FilesService
import socialpublish.utils.TextUtils

import java.util.UUID

// Bluesky API implementation
// Based on AT Protocol: https://docs.bsky.app/docs/api/
trait BlueskyApi {
  def createPost(request: NewPostRequest): Result[NewPostResponse]
}

object BlueskyApi {

  case class LoginResponse(accessJwt: String, refreshJwt: String, handle: String, did: String)
      derives Codec.AsObject

  def resource(
    cfg: BlueskyConfig,
    client: Client[IO],
    files: FilesService,
    logger: Logger[IO]
  ): Resource[IO, BlueskyApi] =
    cfg match {
      case BlueskyConfig.Enabled(service, username, password) =>
        Resource.eval(loginInternal(service, username, password, client)).map(session =>
          new BlueskyApiImpl(
            BlueskyConfig.Enabled(service, username, password),
            client,
            files,
            logger,
            session
          )
        )
      case BlueskyConfig.Disabled =>
        Resource.eval(IO.pure(new DisabledBlueskyApi()))
    }

  private case class LoginRequest(identifier: String, password: String) derives Codec.AsObject

  private def loginInternal(
    service: String,
    username: String,
    password: String,
    client: Client[IO]
  ): IO[LoginResponse] = {
    val uri = Uri.unsafeFromString(s"$service/xrpc/com.atproto.server.createSession")
    val request = Request[IO](Method.POST, uri).withEntity(LoginRequest(username, password).asJson)
    client.expect[Json](request).flatMap { json => IO.fromEither(json.as[LoginResponse]) }
  }

}

private class BlueskyApiImpl(
  config: BlueskyConfig.Enabled,
  client: Client[IO],
  files: FilesService,
  logger: Logger[IO],
  session: BlueskyApi.LoginResponse
) extends BlueskyApi {

  import BlueskyApi.*

  override def createPost(request: NewPostRequest): Result[NewPostResponse] =
    for {
      // Upload images if any
      images <- request.images.getOrElse(Nil).traverse(uuid => uploadImage(uuid))

      // Prepare text
      text = prepareText(request)

      // Detect facets (links, mentions, etc.)
      facets <- detectFacets(text)

      // Create post record
      record = createPostRecord(text, request.language, images, facets)

      // Post to Bluesky
      response <- postToBluesky(record)
    } yield NewPostResponse.Bluesky(response.uri, Some(response.cid))

  private def prepareText(request: NewPostRequest): String = {
    val content =
      if request.cleanupHtml.getOrElse(false) then TextUtils.convertHtml(request.content)
      else
        request.content.trim()

    request.link match {
      case Some(link) => s"$content\n\n$link"
      case None => content
    }
  }

  private case class BlobRef(
    `$type`: String,
    ref: Json,
    mimeType: String
  ) derives Codec.AsObject

  private case class ImageEmbed(
    alt: String,
    image: BlobRef,
    aspectRatio: Option[AspectRatio]
  ) derives Codec.AsObject

  private case class AspectRatio(
    width: Int,
    height: Int
  ) derives Codec.AsObject

  private def uploadImage(uuid: UUID): Result[ImageEmbed] =
    for {
      fileOpt <- Result.liftIO(files.getFile(uuid))
      file <- Result.fromOption(fileOpt, ApiError.notFound(s"File not found: $uuid"))
      blobRef <- uploadBlob(file.bytes, file.mimeType)
    } yield ImageEmbed(
      alt = file.altText.getOrElse(""),
      image = blobRef,
      aspectRatio = Some(AspectRatio(file.width, file.height))
    )

  private def uploadBlob(bytes: Array[Byte], mimeType: String): Result[BlobRef] = {
    val uri = Uri.unsafeFromString(s"${config.service}/xrpc/com.atproto.repo.uploadBlob")
    val request = Request[IO](Method.POST, uri)
      .withHeaders(
        Header.Raw(ci"Authorization", s"Bearer ${session.accessJwt}"),
        Header.Raw(ci"Content-Type", mimeType)
      )
      .withEntity(bytes)

    Result.liftIO {
      client.expect[Json](request).flatMap { json =>
        IO.fromEither(
          for {
            blob <- json.hcursor.downField("blob").as[Json]
            ref <- blob.hcursor.downField("ref").as[Json]
            mime <- blob.hcursor.downField("mimeType").as[String]
          } yield BlobRef("blob", ref, mime)
        )
      }
    }
  }

  private case class Facet(index: ByteSlice, features: List[Feature]) derives Codec.AsObject
  private case class ByteSlice(byteStart: Int, byteEnd: Int) derives Codec.AsObject
  private case class Feature(`$type`: String, uri: String) derives Codec.AsObject

  private def detectFacets(text: String): Result[List[Facet]] =
    // Simplified facet detection - just finds URLs
    Result.success {
      val urlPattern = """https?://[^\s]+""".r
      urlPattern.findAllMatchIn(text).toList.map { m =>
        Facet(
          ByteSlice(m.start, m.end),
          List(Feature("app.bsky.richtext.facet#link", m.matched))
        )
      }
    }

  private case class PostRecord(
    `$type`: String,
    text: String,
    langs: Option[List[String]],
    facets: Option[List[Facet]],
    embed: Option[Json],
    createdAt: String
  ) derives Codec.AsObject

  private def createPostRecord(
    text: String,
    language: Option[String],
    images: List[ImageEmbed],
    facets: List[Facet]
  ): PostRecord = {
    val embed = if images.nonEmpty then Some(Json.obj(
      "$type" -> Json.fromString("app.bsky.embed.images"),
      "images" -> images.asJson
    ))
    else None

    PostRecord(
      `$type` = "app.bsky.feed.post",
      text = text,
      langs = language.map(List(_)),
      facets = if facets.nonEmpty then Some(facets) else None,
      embed = embed,
      createdAt = java.time.Instant.now().toString
    )
  }

  private case class CreatePostRequest(
    repo: String,
    collection: String,
    record: PostRecord
  ) derives Codec.AsObject

  private case class CreatePostResponse(uri: String, cid: String) derives Codec.AsObject

  private def postToBluesky(record: PostRecord): Result[CreatePostResponse] = {
    val uri = Uri.unsafeFromString(s"${config.service}/xrpc/com.atproto.repo.createRecord")
    val postRequest = CreatePostRequest(
      repo = session.did,
      collection = "app.bsky.feed.post",
      record = record
    )

    val request = Request[IO](Method.POST, uri)
      .withHeaders(Header.Raw(ci"Authorization", s"Bearer ${session.accessJwt}"))
      .withEntity(postRequest.asJson)

    Result.liftIO {
      client.expect[Json](request).flatMap { json =>
        IO.fromEither(json.as[CreatePostResponse])
      }.handleErrorWith { err =>
        logger.error(err)("Failed to post to Bluesky") *>
          IO.raiseError(err)
      }
    }
  }

}

private class DisabledBlueskyApi() extends BlueskyApi {
  override def createPost(request: NewPostRequest): Result[NewPostResponse] =
    Result.error(ApiError.validationError("Bluesky integration is disabled", "bluesky"))
}
