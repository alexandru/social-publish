package socialpublish.api

import com.monovore.decline.Opts
import cats.syntax.all.*

case class TwitterConfig(oauth1ConsumerKey: String, oauth1ConsumerSecret: String)

object TwitterConfig {
  private val twitterOauth1ConsumerKeyOpt: Opts[String] =
    Opts.option[String](
      "twitter-oauth1-consumer-key",
      help = "Twitter OAuth1 consumer key"
    ).orElse(Opts(sys.env.getOrElse("TWITTER_OAUTH1_CONSUMER_KEY", "")))
  private val twitterOauth1ConsumerSecretOpt: Opts[String] =
    Opts.option[String](
      "twitter-oauth1-consumer-secret",
      help = "Twitter OAuth1 consumer secret"
    ).orElse(Opts(sys.env.getOrElse("TWITTER_OAUTH1_CONSUMER_SECRET", "")))

  val opts: Opts[TwitterConfig] =
    (twitterOauth1ConsumerKeyOpt, twitterOauth1ConsumerSecretOpt).mapN(TwitterConfig.apply)
}
