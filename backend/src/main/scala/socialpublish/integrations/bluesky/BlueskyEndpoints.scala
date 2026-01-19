package socialpublish.integrations.bluesky

import io.circe.Codec

/** Bluesky API data models for AT Protocol.
  *
  * These are used for making direct HTTP requests to the Bluesky API.
  */
object BlueskyModels {

  final case class LoginRequest(identifier: String, password: String) derives Codec.AsObject

  final case class LoginResponse(accessJwt: String, refreshJwt: String, handle: String, did: String)
      derives Codec.AsObject

  final case class BlobRefLink(`$link`: String) derives Codec.AsObject

  final case class BlobRef(`$type`: String, ref: BlobRefLink, mimeType: String)
      derives Codec.AsObject

  final case class UploadBlobResponse(blob: BlobRef) derives Codec.AsObject

  final case class ImageEmbed(
    alt: String,
    image: BlobRef,
    aspectRatio: Option[AspectRatio]
  ) derives Codec.AsObject

  final case class AspectRatio(width: Int, height: Int) derives Codec.AsObject

  final case class Facet(index: ByteSlice, features: List[Feature]) derives Codec.AsObject

  final case class ByteSlice(byteStart: Int, byteEnd: Int) derives Codec.AsObject

  final case class Feature(`$type`: String, uri: String) derives Codec.AsObject

  final case class PostRecord(
    `$type`: String,
    text: String,
    langs: Option[List[String]],
    facets: Option[List[Facet]],
    embed: Option[ImageEmbedPayload],
    createdAt: String
  ) derives Codec.AsObject

  final case class ImageEmbedPayload(`$type`: String, images: List[ImageEmbed])
      derives Codec.AsObject

  final case class CreatePostRequest(repo: String, collection: String, record: PostRecord)
      derives Codec.AsObject

  final case class CreatePostResponse(uri: String, cid: String) derives Codec.AsObject

}
