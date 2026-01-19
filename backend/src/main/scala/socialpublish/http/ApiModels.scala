package socialpublish.http

import io.circe.Codec

import socialpublish.models.NewPostResponse

case class ErrorResponse(error: String) derives Codec.AsObject

case class MultiPostResponse(results: Map[String, NewPostResponse])
    derives Codec.AsObject

case class TwitterAuthStatusResponse(
  hasAuthorization: Boolean,
  createdAt: Long
) derives Codec.AsObject
