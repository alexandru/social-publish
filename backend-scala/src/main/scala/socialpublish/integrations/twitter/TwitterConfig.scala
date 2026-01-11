package socialpublish.integrations.twitter

import com.monovore.decline.Opts
import cats.syntax.all.*

enum TwitterConfig {
  case Enabled(
    oauth1ConsumerKey: String,
    oauth1ConsumerSecret: String,
    apiBaseUrl: String,
    uploadBaseUrl: String,
    authBaseUrl: String
  )
  case Disabled
}

object TwitterConfig {

  private def isEnabled(s: String): Boolean =
    s.toLowerCase == "true" || s == "1"

  private val twitterEnabledOpt: Opts[Boolean] =
    Opts.option[String](
      "twitter-enabled",
      help = "Enable Twitter integration"
    ).map(isEnabled)
      .orElse(Opts.env[String](
        "TWITTER_ENABLED",
        help = "Enable Twitter integration"
      ).map(isEnabled))

  private val twitterOauth1ConsumerKeyOpt: Opts[Option[String]] =
    Opts.option[String](
      "twitter-oauth1-consumer-key",
      help = "Twitter OAuth1 consumer key"
    ).orElse(Opts.env[String](
      "TWITTER_OAUTH1_CONSUMER_KEY",
      help = "Twitter OAuth1 consumer key"
    )).orNone

  private val twitterOauth1ConsumerSecretOpt: Opts[Option[String]] =
    Opts.option[String](
      "twitter-oauth1-consumer-secret",
      help = "Twitter OAuth1 consumer secret"
    ).orElse(Opts.env[String](
      "TWITTER_OAUTH1_CONSUMER_SECRET",
      help = "Twitter OAuth1 consumer secret"
    )).orNone

  private val twitterApiBaseUrlOpt: Opts[String] =
    Opts.option[String](
      "twitter-api-base-url",
      help = "Base URL for Twitter API v2/v1.1 endpoints"
    ).orElse(Opts.env[String](
      "TWITTER_API_BASE_URL",
      help = "Base URL for Twitter API v2/v1.1 endpoints"
    )).withDefault("https://api.twitter.com")

  private val twitterUploadBaseUrlOpt: Opts[String] =
    Opts.option[String](
      "twitter-upload-base-url",
      help = "Base URL for Twitter upload endpoints"
    ).orElse(Opts.env[String](
      "TWITTER_UPLOAD_BASE_URL",
      help = "Base URL for Twitter upload endpoints"
    )).withDefault("https://upload.twitter.com")

  private val twitterAuthBaseUrlOpt: Opts[String] =
    Opts.option[String](
      "twitter-auth-base-url",
      help = "Base URL for Twitter OAuth endpoints"
    ).orElse(Opts.env[String](
      "TWITTER_AUTH_BASE_URL",
      help = "Base URL for Twitter OAuth endpoints"
    )).withDefault("https://api.twitter.com")

  val opts: Opts[TwitterConfig] =
    (
      twitterEnabledOpt,
      twitterOauth1ConsumerKeyOpt,
      twitterOauth1ConsumerSecretOpt,
      twitterApiBaseUrlOpt,
      twitterUploadBaseUrlOpt,
      twitterAuthBaseUrlOpt
    ).mapN((enabled, keyOpt, secretOpt, apiUrl, uploadUrl, authUrl) =>
      (enabled, keyOpt, secretOpt, apiUrl, uploadUrl, authUrl)
    )
      .mapValidated {
        case (false, _, _, _, _, _) => TwitterConfig.Disabled.valid
        case (true, Some(key), Some(secret), apiUrl, uploadUrl, authUrl) =>
          TwitterConfig.Enabled(key, secret, apiUrl, uploadUrl, authUrl).valid
        case (true, None, _, _, _, _) =>
          "Twitter is enabled but twitter-oauth1-consumer-key is not provided".invalidNel
        case (true, _, None, _, _, _) =>
          "Twitter is enabled but twitter-oauth1-consumer-secret is not provided".invalidNel
      }

}
