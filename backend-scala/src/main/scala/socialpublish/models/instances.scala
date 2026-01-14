package socialpublish.models

import java.time.Instant
import java.util.UUID
import scala.util.Try
import io.circe.{Codec, Decoder, Encoder}
import sttp.tapir.{Schema, Validator}
import cats.syntax.all.*

object Instances {

  given Codec[UUID] =
    Codec.from(
      Decoder.decodeString.emap { value =>
        Try(UUID.fromString(value)).toEither.leftMap(_.getMessage)
      },
      Encoder.encodeString.contramap(_.toString)
    )

  // Explicit regex for canonical UUID format (8-4-4-4-12 hex digits)
  // Matches exactly what UUID.fromString accepts for canonical strings, no more, no less
  private val uuidRegex =
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

  given Schema[UUID] =
    Schema.string
      .format("uuid")
      .validate(Validator.pattern(uuidRegex))
      .map(str => Try(UUID.fromString(str)).toOption)(_.toString)

  given Codec[Instant] =
    Codec.from(
      Decoder.decodeString.emap { value =>
        Try(Instant.parse(value)).toEither.leftMap(_.getMessage)
      },
      Encoder.encodeString.contramap(_.toString)
    )

  given Schema[Instant] =
    Schema.string.format("date-time")

}
