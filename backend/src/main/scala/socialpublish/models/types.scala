package socialpublish.models

import java.time.Instant
import java.util.UUID
import io.circe.{Codec, Decoder, DecodingFailure, Encoder}
import io.circe.generic.semiauto.*
import sttp.tapir.{Schema, Validator}

// Target social networks
/** Supported social network targets for publishing. Used in NewPostRequest to specify destination
  * platforms.
  */
enum Target {
  case Mastodon, Bluesky, Twitter, LinkedIn
}

object Target {

  given Codec[Target] =
    Codec.from(
      Decoder.decodeString.emap {
        case "mastodon" | "Mastodon" => Right(Target.Mastodon)
        case "bluesky" | "Bluesky" => Right(Target.Bluesky)
        case "twitter" | "Twitter" => Right(Target.Twitter)
        case "linkedin" | "LinkedIn" => Right(Target.LinkedIn)
        case other => Left(s"Unknown target: $other")
      },
      Encoder.encodeString.contramap(_.toString.toLowerCase)
    )

  given Schema[Target] =
    Schema.string.validate(
      Validator.enumeration(Target.values.toList, target => Some(target.toString.toLowerCase))
    )

}

// Content with length validation (1-1000 characters like TypeScript version)
/** Post content with enforced length validation (1-1000 characters). Serves as the primary text
  * body for posts across all platforms.
  */
opaque type Content = String

object Content {
  def apply(value: String): Either[String, Content] =
    if value.isEmpty then Left("Content cannot be empty")
    else if value.length > 1000 then Left("Content cannot exceed 1000 characters")
    else Right(value)

  def unsafe(value: String): Content =
    value

  extension (content: Content) {
    def value: String =
      content
  }

  given Decoder[Content] =
    Decoder.decodeString.emap { str =>
      Content.apply(str)
    }

  given Encoder[Content] =
    Encoder.encodeString.contramap(_.value)

  given Schema[Content] =
    Schema.string.validate(
      Validator.minLength(1).and(Validator.maxLength(1000))
    )
}

// Post domain model
/** Represents a published or scheduled post within the system. Contains normalized data that may be
  * distributed to multiple targets.
  *
  * @param uuid
  *   Unique identifier for this post
  * @param content
  *   The text content of the post
  * @param link
  *   Optional external link to attach
  * @param tags
  *   List of hashtags (without # prefix)
  * @param language
  *   Optional language code (e.g., "en", "es")
  * @param images
  *   List of image UUIDs attached to this post
  * @param targets
  *   List of platforms where this post was published/targeted
  * @param createdAt
  *   Creation timestamp
  */
case class Post(
  uuid: UUID,
  content: String,
  link: Option[String],
  tags: List[String],
  language: Option[String],
  images: List[UUID],
  targets: List[Target],
  createdAt: Instant
)

object Post {
  import Instances.given

  given Codec[Post] =
    deriveCodec

  given Schema[Post] =
    Schema.derived

}

// Request to create a new post
/** Payload for creating a new post.
  *
  * @param content
  *   The text content (must be non-empty, max 1000 chars)
  * @param targets
  *   Optional list of specific targets. If omitted, defaults may apply based on user settings.
  * @param link
  *   Optional URL to attach to the post
  * @param language
  *   Optional language hint
  * @param cleanupHtml
  *   If true, attempts to strip HTML tags from content before publishing
  * @param images
  *   Optional list of uploaded image UUIDs to attach
  */
case class NewPostRequest(
  content: Content,
  targets: Option[List[Target]],
  link: Option[String],
  language: Option[String],
  cleanupHtml: Option[Boolean],
  images: Option[List[UUID]]
)

object NewPostRequest {
  import Instances.given

  given Codec[NewPostRequest] =
    deriveCodec

  given Schema[NewPostRequest] =
    Schema.derived

}

// Response from creating a post
/** Response returned after a successful publication. Polymorphic structure distinguished by the
  * `module` field.
  */
sealed trait NewPostResponse {
  def module: String
}

object NewPostResponse {

  /** Response for a post published to Bluesky */
  final case class Bluesky(
    uri: String,
    cid: Option[String],
    module: String = "bluesky"
  ) extends NewPostResponse

  /** Response for a post published to Mastodon */
  final case class Mastodon(
    uri: String,
    module: String = "mastodon"
  ) extends NewPostResponse

  /** Response for a post published to Twitter */
  final case class Twitter(
    id: String,
    module: String = "twitter"
  ) extends NewPostResponse

  /** Response for a post published via RSS feed */
  final case class Rss(
    uri: String,
    module: String = "rss"
  ) extends NewPostResponse

  given Encoder[NewPostResponse] =
    Encoder.instance {
      case response @ Bluesky(uri, cid, _) =>
        io.circe.Json.obj(
          "module" -> io.circe.Json.fromString(response.module),
          "uri" -> io.circe.Json.fromString(uri),
          "cid" -> cid.fold(io.circe.Json.Null)(io.circe.Json.fromString)
        )
      case response @ Mastodon(uri, _) =>
        io.circe.Json.obj(
          "module" -> io.circe.Json.fromString(response.module),
          "uri" -> io.circe.Json.fromString(uri)
        )
      case response @ Twitter(id, _) =>
        io.circe.Json.obj(
          "module" -> io.circe.Json.fromString(response.module),
          "id" -> io.circe.Json.fromString(id)
        )
      case response @ Rss(uri, _) =>
        io.circe.Json.obj(
          "module" -> io.circe.Json.fromString(response.module),
          "uri" -> io.circe.Json.fromString(uri)
        )
    }

  given Decoder[NewPostResponse] =
    Decoder.instance { cursor =>
      cursor.get[String]("module").flatMap {
        case "bluesky" =>
          for {
            uri <- cursor.get[String]("uri")
            cid <- cursor.get[Option[String]]("cid")
          } yield Bluesky(uri, cid)
        case "mastodon" =>
          cursor.get[String]("uri").map(uri => Mastodon(uri))
        case "twitter" =>
          cursor.get[String]("id").map(id => Twitter(id))
        case "rss" =>
          cursor.get[String]("uri").map(uri => Rss(uri))
        case other =>
          Left(DecodingFailure(s"Unknown module: $other", cursor.history))
      }
    }

  // Define schemas for concrete types so they can be referenced by the discriminator
  given Schema[Bluesky] =
    Schema.derived
  given Schema[Mastodon] =
    Schema.derived
  given Schema[Twitter] =
    Schema.derived
  given Schema[Rss] =
    Schema.derived

  given Schema[NewPostResponse] =
    Schema.oneOfUsingField[NewPostResponse, String](
      _.module,
      _.toString
    )(
      "bluesky" -> Schema.derived[Bluesky],
      "mastodon" -> Schema.derived[Mastodon],
      "twitter" -> Schema.derived[Twitter],
      "rss" -> Schema.derived[Rss]
    )

}

// File/Image metadata
/** Metadata for uploaded files/images.
  *
  * @param uuid
  *   Unique ID of the file
  * @param originalName
  *   Original filename uploaded
  * @param mimeType
  *   MIME type (e.g., image/jpeg)
  * @param size
  *   File size in bytes
  * @param altText
  *   Optional accessibility text
  * @param width
  *   Image width in pixels (if image)
  * @param height
  *   Image height in pixels (if image)
  * @param hash
  *   SHA-256 hash of file content
  * @param createdAt
  *   Upload timestamp
  */
case class FileMetadata(
  uuid: UUID,
  originalName: String,
  mimeType: String,
  size: Long,
  altText: Option[String],
  width: Option[Int],
  height: Option[Int],
  hash: Option[String],
  createdAt: Instant
)

object FileMetadata {
  import Instances.given

  given Codec[FileMetadata] =
    deriveCodec

  given Schema[FileMetadata] =
    Schema.derived

}

// Document stored in database
/** Generic document storage structure.
  */
case class Document(
  uuid: UUID,
  kind: String,
  payload: String,
  tags: List[DocumentTag],
  createdAt: Instant
)

/** Tag associated with a document.
  */
case class DocumentTag(
  name: String,
  kind: String
)
