package socialpublish.models

import java.time.Instant
import java.util.UUID
import io.circe.{Encoder, Decoder, Codec}
import io.circe.generic.semiauto.*

// Target social networks
enum Target {
  case Mastodon, Bluesky, Twitter
}

object Target {

  given Codec[Target] =
    Codec.from(
      Decoder.decodeString.emap {
        case "mastodon" | "Mastodon" => Right(Target.Mastodon)
        case "bluesky" | "Bluesky" => Right(Target.Bluesky)
        case "twitter" | "Twitter" => Right(Target.Twitter)
        // case "linkedin" | "LinkedIn" => Right(Target.LinkedIn)
        case other => Left(s"Unknown target: $other")
      },
      Encoder.encodeString.contramap(_.toString.toLowerCase)
    )

}

// Post domain model
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

// Request to create a new post
case class NewPostRequest(
  content: String,
  targets: Option[List[Target]],
  link: Option[String],
  language: Option[String],
  cleanupHtml: Option[Boolean],
  images: Option[List[UUID]]
)

object NewPostRequest {

  given Codec[NewPostRequest] =
    deriveCodec

}

// Response from creating a post
enum NewPostResponse {
  case Bluesky(uri: String, cid: Option[String])
  case Mastodon(uri: String)
  case Twitter(id: String)
  case Rss(uri: String)

  def module: String =
    this match {
      case Bluesky(_, _) => "bluesky"
      case Mastodon(_) => "mastodon"
      case Twitter(_) => "twitter"
      case Rss(_) => "rss"
    }

}

object NewPostResponse {

  given Encoder[NewPostResponse] =
    Encoder.instance {
      case Bluesky(uri, cid) =>
        io.circe.Json.obj(
          "module" -> io.circe.Json.fromString("bluesky"),
          "uri" -> io.circe.Json.fromString(uri),
          "cid" -> cid.fold(io.circe.Json.Null)(io.circe.Json.fromString)
        )
      case Mastodon(uri) =>
        io.circe.Json.obj(
          "module" -> io.circe.Json.fromString("mastodon"),
          "uri" -> io.circe.Json.fromString(uri)
        )
      case Twitter(id) =>
        io.circe.Json.obj(
          "module" -> io.circe.Json.fromString("twitter"),
          "id" -> io.circe.Json.fromString(id)
        )
      case Rss(uri) =>
        io.circe.Json.obj(
          "module" -> io.circe.Json.fromString("rss"),
          "uri" -> io.circe.Json.fromString(uri)
        )
    }

}

// File/Image metadata
case class FileMetadata(
  uuid: UUID,
  originalName: String,
  mimeType: String,
  size: Long,
  altText: Option[String],
  width: Option[Int],
  height: Option[Int],
  createdAt: Instant
)

// Document stored in database
case class Document(
  uuid: UUID,
  kind: String,
  payload: String,
  tags: List[DocumentTag],
  createdAt: Instant
)

case class DocumentTag(
  name: String,
  kind: String
)
