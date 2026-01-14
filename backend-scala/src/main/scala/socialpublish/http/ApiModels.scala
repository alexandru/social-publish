package socialpublish.http

import io.circe.Codec
import sttp.model.Part
import sttp.tapir.Schema

case class ErrorResponse(error: String) derives Codec.AsObject

object ErrorResponse {
  given Schema[ErrorResponse] =
    Schema.derived
}

import socialpublish.models.NewPostResponse

case class MultiPostResponse(results: Map[String, NewPostResponse])
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

  // Define the multipart body input here to avoid codec conflicts in Routes.scala
  // This ensures Array[Byte] is treated as binary (default Tapir behavior) rather than JSON (Circe behavior)
  val body: sttp.tapir.EndpointInput[FileUploadForm] = 
    sttp.tapir.multipartBody[FileUploadForm]
}
