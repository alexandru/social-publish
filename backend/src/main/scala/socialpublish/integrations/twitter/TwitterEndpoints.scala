package socialpublish.integrations.twitter

import io.circe.Codec

/** Twitter API data models.
  *
  * These are used for making direct HTTP requests to the Twitter API.
  */
object TwitterModels {

  final case class TwitterMediaUploadResponse(media_id_string: String) derives Codec.AsObject

  final case class CreateTweetRequest(text: String, media: Option[MediaIds]) derives Codec.AsObject

  final case class MediaIds(media_ids: List[String]) derives Codec.AsObject

  final case class TweetData(id: String) derives Codec.AsObject

  final case class TweetResponse(data: TweetData) derives Codec.AsObject

  final case class AltTextRequest(media_id: String, alt_text: AltTextPayload) derives Codec.AsObject

  final case class AltTextPayload(text: String) derives Codec.AsObject

}
