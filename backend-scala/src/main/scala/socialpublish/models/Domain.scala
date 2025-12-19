package socialpublish.models

import java.time.Instant
import java.util.UUID

// Target social networks
enum Target derives io.circe.Codec.AsObject:
  case Mastodon, Bluesky, Twitter, LinkedIn

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
) derives io.circe.Codec.AsObject

// Response from creating a post
enum NewPostResponse derives io.circe.Codec.AsObject:
  case Bluesky(uri: String, cid: Option[String])
  case Mastodon(uri: String)
  case Twitter(id: String)
  case Rss(uri: String)
  
  def module: String = this match
    case Bluesky(_, _) => "bluesky"
    case Mastodon(_) => "mastodon"
    case Twitter(_) => "twitter"
    case Rss(_) => "rss"

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
