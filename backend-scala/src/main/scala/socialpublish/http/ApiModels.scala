package socialpublish.http

import io.circe.Codec
import java.util.UUID
import sttp.model.Part
import sttp.tapir.Schema

case class ErrorResponse(error: String) derives Codec.AsObject

object ErrorResponse {
  given Schema[ErrorResponse] =
    Schema.derived
}

case class FileUploadItem(uuid: UUID, filename: String) derives Codec.AsObject

object FileUploadItem {
  given Schema[FileUploadItem] =
    Schema.derived
}

case class FileUploadResponse(uploads: List[FileUploadItem]) derives Codec.AsObject

object FileUploadResponse {
  given Schema[FileUploadResponse] =
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

case class FileUploadForm(files: List[Part[Array[Byte]]])

object FileUploadForm {
  given Schema[FileUploadForm] =
    Schema.derived
}
