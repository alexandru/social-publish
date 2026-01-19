package socialpublish.integrations.mastodon

import io.circe.Codec

/** Mastodon API data models.
  *
  * These are used for making direct HTTP requests to the Mastodon API.
  */
object MastodonModels {

  final case class MediaUploadResponse(
    id: String,
    url: Option[String] = None,
    preview_url: Option[String] = None,
    description: Option[String] = None
  ) derives Codec.AsObject

  final case class StatusCreateRequest(
    status: String,
    media_ids: List[String],
    language: Option[String]
  ) derives Codec.AsObject

  final case class StatusResponse(id: String, uri: String, url: String) derives Codec.AsObject

}
