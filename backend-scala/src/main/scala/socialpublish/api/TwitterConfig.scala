package socialpublish.api

import com.monovore.decline.Opts
import cats.syntax.all.*

enum TwitterConfig {
  case Enabled(oauth1ConsumerKey: String, oauth1ConsumerSecret: String)
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

  val opts: Opts[TwitterConfig] =
    (twitterEnabledOpt, twitterOauth1ConsumerKeyOpt, twitterOauth1ConsumerSecretOpt).mapN(
      (enabled, keyOpt, secretOpt) => (enabled, keyOpt, secretOpt)
    )
      .mapValidated {
        case (false, _, _) => TwitterConfig.Disabled.valid
        case (true, Some(key), Some(secret)) => TwitterConfig.Enabled(key, secret).valid
        case (true, None, _) =>
          "Twitter is enabled but twitter-oauth1-consumer-key is not provided".invalidNel
        case (true, _, None) =>
          "Twitter is enabled but twitter-oauth1-consumer-secret is not provided".invalidNel
      }

}
