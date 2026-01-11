package socialpublish.models

import java.time.Instant
import java.util.UUID
import io.circe.{Codec, Decoder, DecodingFailure, Encoder}
import io.circe.generic.semiauto.*
import sttp.tapir.{Schema, Validator}

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

  given Schema[Target] =
    Schema.string.validate(
      Validator.enumeration(Target.values.toList, target => Some(target.toString.toLowerCase))
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

  given Schema[NewPostRequest] =
    Schema.derived

}

// Response from creating a post
sealed trait NewPostResponse {
  def module: String
}

object NewPostResponse {

  final case class Bluesky(
    uri: String,
    cid: Option[String],
    module: String = "bluesky"
  ) extends NewPostResponse

  final case class Mastodon(
    uri: String,
    module: String = "mastodon"
  ) extends NewPostResponse

  final case class Twitter(
    id: String,
    module: String = "twitter"
  ) extends NewPostResponse

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

  given Schema[NewPostResponse] =
    Schema.derived

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
