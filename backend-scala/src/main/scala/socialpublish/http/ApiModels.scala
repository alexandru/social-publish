package socialpublish.http

import io.circe.Codec
import sttp.model.Part
import sttp.tapir.Schema

case class ErrorResponse(error: String) derives Codec.AsObject

object ErrorResponse {
  given Schema[ErrorResponse] =
    Schema.derived
}

case class MultiPostResponse(results: Map[String, socialpublish.models.NewPostResponse])
    derives Codec.AsObject

object MultiPostResponse {
  given Schema[MultiPostResponse] =
    Schema.derived
}

case class TwitterAuthStatusResponse(
  hasAuthorization: Boolean,
  createdAt: Long
) derives Codec.AsObject

object TwitterAuthStatusResponse {
  given Schema[TwitterAuthStatusResponse] =
    Schema.derived
}

case class FileUploadForm(
  file: Part[Array[Byte]],
  altText: Option[String]
)

object FileUploadForm {
  given Schema[FileUploadForm] =
    Schema.derived
}
