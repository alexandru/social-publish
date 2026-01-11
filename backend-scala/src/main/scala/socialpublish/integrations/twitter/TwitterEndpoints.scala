package socialpublish.integrations.twitter

import io.circe.Codec
import sttp.model.Part
import sttp.tapir.*
import sttp.tapir.Schema
import sttp.tapir.json.circe.*

object TwitterEndpoints {

  /** Obtain a request token to start the OAuth 1.0a flow.
    *
    * Docs: https://developer.twitter.com/en/docs/authentication/api-reference/request_token
    */
  val requestToken: PublicEndpoint[(String, String, String), String, String, Any] =
    endpoint.post
      .in("oauth" / "request_token")
      .in(header[String]("Authorization"))
      .in(query[String]("oauth_callback"))
      .in(query[String]("x_auth_access_type"))
      .errorOut(stringBody)
      .out(stringBody)
      .name("twitterRequestToken")

  /** Exchange the request token for an access token.
    *
    * Docs: https://developer.twitter.com/en/docs/authentication/api-reference/access_token
    */
  val accessToken: PublicEndpoint[(String, String, String), String, String, Any] =
    endpoint.get
      .in("oauth" / "access_token")
      .in(header[String]("Authorization"))
      .in(query[String]("oauth_token"))
      .in(query[String]("oauth_verifier"))
      .errorOut(stringBody)
      .out(stringBody)
      .name("twitterAccessToken")

  /** Create a Tweet.
    *
    * Docs:
    * https://developer.twitter.com/en/docs/twitter-api/tweets/manage-tweets/api-reference/post-tweets
    */
  val createTweet: PublicEndpoint[(String, CreateTweetRequest), String, TweetResponse, Any] =
    endpoint.post
      .in("2" / "tweets")
      .in(header[String]("Authorization"))
      .in(jsonBody[CreateTweetRequest])
      .errorOut(stringBody)
      .out(jsonBody[TweetResponse])
      .name("twitterCreateTweet")

  /** Upload media using the Twitter v1.1 media endpoint.
    *
    * Docs:
    * https://developer.twitter.com/en/docs/twitter-api/v1/media/upload-media/api-reference/post-media-upload
    */
  val uploadMedia
    : PublicEndpoint[(String, TwitterMediaUploadForm), String, TwitterMediaUploadResponse, Any] =
    endpoint.post
      .in("1.1" / "media" / "upload.json")
      .in(header[String]("Authorization"))
      .in(multipartBody[TwitterMediaUploadForm])
      .errorOut(stringBody)
      .out(jsonBody[TwitterMediaUploadResponse])
      .name("twitterUploadMedia")

  /** Attach alt text metadata to an uploaded media item.
    *
    * Docs:
    * https://developer.twitter.com/en/docs/twitter-api/v1/media/upload-media/api-reference/post-media-metadata-create
    */
  val createAltText: PublicEndpoint[(String, AltTextRequest), String, String, Any] =
    endpoint.post
      .in("1.1" / "media" / "metadata" / "create.json")
      .in(header[String]("Authorization"))
      .in(jsonBody[AltTextRequest])
      .errorOut(stringBody)
      .out(stringBody)
      .name("twitterCreateAltText")

  final case class TwitterMediaUploadForm(media: Part[Array[Byte]])

  object TwitterMediaUploadForm {
    given Schema[TwitterMediaUploadForm] =
      Schema.derived
  }

  final case class TwitterMediaUploadResponse(media_id_string: String) derives Codec.AsObject

  object TwitterMediaUploadResponse {
    given Schema[TwitterMediaUploadResponse] =
      Schema.derived
  }

  final case class CreateTweetRequest(text: String, media: Option[MediaIds]) derives Codec.AsObject

  object CreateTweetRequest {
    given Schema[CreateTweetRequest] =
      Schema.derived
  }

  final case class MediaIds(media_ids: List[String]) derives Codec.AsObject

  object MediaIds {
    given Schema[MediaIds] =
      Schema.derived
  }

  final case class TweetData(id: String) derives Codec.AsObject

  object TweetData {
    given Schema[TweetData] =
      Schema.derived
  }

  final case class TweetResponse(data: TweetData) derives Codec.AsObject

  object TweetResponse {
    given Schema[TweetResponse] =
      Schema.derived
  }

  final case class AltTextRequest(media_id: String, alt_text: AltTextPayload) derives Codec.AsObject

  object AltTextRequest {
    given Schema[AltTextRequest] =
      Schema.derived
  }

  final case class AltTextPayload(text: String) derives Codec.AsObject

  object AltTextPayload {
    given Schema[AltTextPayload] =
      Schema.derived
  }

}
