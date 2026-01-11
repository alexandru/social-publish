package socialpublish.integrations.mastodon

import io.circe.Codec
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.Schema
import sttp.model.Part

object MastodonEndpoints {

  /** Upload a media attachment.
    *
    * Docs: https://docs.joinmastodon.org/methods/media/
    */
  val uploadMedia
    : PublicEndpoint[(String, MediaUploadForm), String, MediaUploadResponse, Any] =
    endpoint.post
      .in("api" / "v2" / "media")
      .in(header[String]("Authorization"))
      .in(multipartBody[MediaUploadForm])
      .errorOut(stringBody)
      .out(jsonBody[MediaUploadResponse])
      .name("mastodonUploadMedia")

  /** Create a status (post).
    *
    * Docs: https://docs.joinmastodon.org/methods/statuses/
    */
  val createStatus
    : PublicEndpoint[(String, StatusCreateRequest), String, StatusResponse, Any] =
    endpoint.post
      .in("api" / "v1" / "statuses")
      .in(header[String]("Authorization"))
      .in(jsonBody[StatusCreateRequest])
      .errorOut(stringBody)
      .out(jsonBody[StatusResponse])
      .name("mastodonCreateStatus")

  final case class MediaUploadForm(
    file: Part[Array[Byte]],
    description: Option[Part[String]]
  )

  object MediaUploadForm {
    given Schema[MediaUploadForm] =
      Schema.derived
  }

  final case class MediaUploadResponse(id: String) derives Codec.AsObject

  object MediaUploadResponse {
    given Schema[MediaUploadResponse] =
      Schema.derived
  }

  final case class StatusCreateRequest(
    status: String,
    media_ids: List[String],
    language: Option[String]
  ) derives Codec.AsObject

  object StatusCreateRequest {
    given Schema[StatusCreateRequest] =
      Schema.derived
  }

  final case class StatusResponse(id: String, uri: String, url: String) derives Codec.AsObject

  object StatusResponse {
    given Schema[StatusResponse] =
      Schema.derived
  }

}
