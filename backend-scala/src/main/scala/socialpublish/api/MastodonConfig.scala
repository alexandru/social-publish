package socialpublish.api

import com.monovore.decline.Opts
import cats.syntax.all.*

case class MastodonConfig(host: String, accessToken: String)

object MastodonConfig {
  private val mastodonHostOpt: Opts[String] =
    Opts.option[String](
      "mastodon-host",
      help = "Host of the Mastodon service"
    ).orElse(Opts(sys.env.getOrElse("MASTODON_HOST", "")))
  private val mastodonAccessTokenOpt: Opts[String] =
    Opts.option[String](
      "mastodon-access-token",
      help = "Access token for the Mastodon service"
    ).orElse(Opts(sys.env.getOrElse("MASTODON_ACCESS_TOKEN", "")))

  val opts: Opts[MastodonConfig] =
    (mastodonHostOpt, mastodonAccessTokenOpt).mapN(MastodonConfig.apply)
}
