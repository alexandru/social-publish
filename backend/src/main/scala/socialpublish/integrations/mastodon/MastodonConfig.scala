package socialpublish.integrations.mastodon

import com.monovore.decline.*
import cats.syntax.all.*

enum MastodonConfig {
  case Enabled(host: String, accessToken: String)
  case Disabled
}

// case class MastodonConfig(host: String, accessToken: String)

object MastodonConfig {

  private val mastodonEnabledOpt: Opts[Boolean] = {
    def isEnabled(x: String): Boolean =
      x.toLowerCase == "true" || x == "1"

    Opts.option[String](
      "mastodon-enabled",
      help = "Enable Mastodon integration"
    ).map(isEnabled).orElse(
      Opts.env[String]("MASTODON_ENABLED", help = "Enable Mastodon integration")
        .map(isEnabled)
    )
  }

  private val mastodonHostOpt: Opts[Option[String]] =
    Opts.option[String](
      "mastodon-host",
      help = "Host of the Mastodon service"
    ).orElse(Opts.env[String]("MASTODON_HOST", help = "Host of the Mastodon service")).orNone

  private val mastodonAccessTokenOpt: Opts[Option[String]] =
    Opts.option[String](
      "mastodon-access-token",
      help = "Access token for the Mastodon service"
    ).orElse(Opts.env[String](
      "MASTODON_ACCESS_TOKEN",
      help = "Access token for the Mastodon service"
    )).orNone

  val opts: Opts[MastodonConfig] =
    (
      mastodonEnabledOpt,
      mastodonHostOpt,
      mastodonAccessTokenOpt
    ).mapN { (enabled, hostOpt, tokenOpt) =>
      (enabled, hostOpt, tokenOpt)
    }.mapValidated {
      case (false, _, _) =>
        MastodonConfig.Disabled.valid
      case (true, Some(host), Some(token)) =>
        MastodonConfig.Enabled(host, token).valid
      case (true, None, _) =>
        "Mastodon is enabled but mastodon-host is not provided".invalidNel
      case (true, _, None) =>
        "Mastodon is enabled but mastodon-access-token is not provided".invalidNel
    }

}
