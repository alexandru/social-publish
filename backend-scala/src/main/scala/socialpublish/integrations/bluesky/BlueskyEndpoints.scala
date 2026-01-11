package socialpublish.integrations.bluesky

import io.circe.Codec
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.Schema

object BlueskyEndpoints {

  /** Create a Bluesky session.
    *
    * Docs: https://docs.bsky.app/docs/api/com-atproto-server-create-session
    */
  val createSession: PublicEndpoint[LoginRequest, String, LoginResponse, Any] =
    endpoint.post
      .in("xrpc" / "com.atproto.server.createSession")
      .in(jsonBody[LoginRequest])
      .errorOut(stringBody)
      .out(jsonBody[LoginResponse])
      .name("blueskyCreateSession")

  /** Upload a blob for later reference in a post.
    *
    * Docs: https://docs.bsky.app/docs/api/com-atproto-repo-upload-blob
    */
  val uploadBlob: PublicEndpoint[(String, String, Array[Byte]), String, UploadBlobResponse, Any] =
    endpoint.post
      .in("xrpc" / "com.atproto.repo.uploadBlob")
      .in(header[String]("Authorization"))
      .in(header[String]("Content-Type"))
      .in(byteArrayBody)
      .errorOut(stringBody)
      .out(jsonBody[UploadBlobResponse])
      .name("blueskyUploadBlob")

  /** Create a record (post) in the user's repository.
    *
    * Docs: https://docs.bsky.app/docs/api/com-atproto-repo-create-record
    */
  val createRecord: PublicEndpoint[(String, CreatePostRequest), String, CreatePostResponse, Any] =
    endpoint.post
      .in("xrpc" / "com.atproto.repo.createRecord")
      .in(header[String]("Authorization"))
      .in(jsonBody[CreatePostRequest])
      .errorOut(stringBody)
      .out(jsonBody[CreatePostResponse])
      .name("blueskyCreateRecord")

  final case class LoginRequest(identifier: String, password: String) derives Codec.AsObject

  object LoginRequest {
    given Schema[LoginRequest] =
      Schema.derived
  }

  final case class LoginResponse(accessJwt: String, refreshJwt: String, handle: String, did: String)
      derives Codec.AsObject

  object LoginResponse {
    given Schema[LoginResponse] =
      Schema.derived
  }

  final case class BlobRefLink(`$link`: String) derives Codec.AsObject

  object BlobRefLink {
    given Schema[BlobRefLink] =
      Schema.derived
  }

  final case class BlobRef(`$type`: String, ref: BlobRefLink, mimeType: String)
      derives Codec.AsObject

  object BlobRef {
    given Schema[BlobRef] =
      Schema.derived
  }

  final case class UploadBlobResponse(blob: BlobRef) derives Codec.AsObject

  object UploadBlobResponse {
    given Schema[UploadBlobResponse] =
      Schema.derived
  }

  final case class ImageEmbed(
    alt: String,
    image: BlobRef,
    aspectRatio: Option[AspectRatio]
  ) derives Codec.AsObject

  object ImageEmbed {
    given Schema[ImageEmbed] =
      Schema.derived
  }

  final case class AspectRatio(width: Int, height: Int) derives Codec.AsObject

  object AspectRatio {
    given Schema[AspectRatio] =
      Schema.derived
  }

  final case class Facet(index: ByteSlice, features: List[Feature]) derives Codec.AsObject

  object Facet {
    given Schema[Facet] =
      Schema.derived
  }

  final case class ByteSlice(byteStart: Int, byteEnd: Int) derives Codec.AsObject

  object ByteSlice {
    given Schema[ByteSlice] =
      Schema.derived
  }

  final case class Feature(`$type`: String, uri: String) derives Codec.AsObject

  object Feature {
    given Schema[Feature] =
      Schema.derived
  }

  final case class PostRecord(
    `$type`: String,
    text: String,
    langs: Option[List[String]],
    facets: Option[List[Facet]],
    embed: Option[ImageEmbedPayload],
    createdAt: String
  ) derives Codec.AsObject

  object PostRecord {
    given Schema[PostRecord] =
      Schema.derived
  }

  final case class ImageEmbedPayload(`$type`: String, images: List[ImageEmbed])
      derives Codec.AsObject

  object ImageEmbedPayload {
    given Schema[ImageEmbedPayload] =
      Schema.derived
  }

  final case class CreatePostRequest(repo: String, collection: String, record: PostRecord)
      derives Codec.AsObject

  object CreatePostRequest {
    given Schema[CreatePostRequest] =
      Schema.derived
  }

  final case class CreatePostResponse(uri: String, cid: String) derives Codec.AsObject

  object CreatePostResponse {
    given Schema[CreatePostResponse] =
      Schema.derived
  }

}
