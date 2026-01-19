package socialpublish.models

import java.time.Instant
import java.util.UUID
import scala.util.Try
import io.circe.{Codec, Decoder, Encoder}
import cats.syntax.all.*

object Instances {

  given Codec[UUID] =
    Codec.from(
      Decoder.decodeString.emap { value =>
        Try(UUID.fromString(value)).toEither.leftMap(_.getMessage)
      },
      Encoder.encodeString.contramap(_.toString)
    )

  given Codec[Instant] =
    Codec.from(
      Decoder.decodeString.emap { value =>
        Try(Instant.parse(value)).toEither.leftMap(_.getMessage)
      },
      Encoder.encodeString.contramap(_.toString)
    )

}
