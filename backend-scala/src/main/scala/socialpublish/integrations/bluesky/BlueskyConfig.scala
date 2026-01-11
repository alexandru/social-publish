package socialpublish.integrations.bluesky

import com.monovore.decline.*
import cats.syntax.all.*

enum BlueskyConfig {
  case Enabled(service: String, username: String, password: String)
  case Disabled
}

object BlueskyConfig {

  private val blueskyEnabledOpt: Opts[Boolean] = {
    def isEnabled(x: String) =
      x.toLowerCase == "true" || x == "1"

    Opts.option[String]("bluesky-enabled", help = "Enable Bluesky integration").map(isEnabled)
      .orElse(Opts.env[String]("BSKY_ENABLED", help = "Enable Bluesky integration").map(isEnabled))
  }

  private val blueskyServiceOpt: Opts[Option[String]] =
    Opts.option[String]("bluesky-service", help = "URL of the BlueSky service")
      .orElse(Opts.env[String]("BSKY_SERVICE", help = "URL of the BlueSky service")).orNone

  private val blueskyUsernameOpt: Opts[Option[String]] =
    Opts.option[String]("bluesky-username", help = "Username for the Bluesky authentication")
      .orElse(Opts.env[String](
        "BSKY_USERNAME",
        help = "Username for the Bluesky authentication"
      )).orNone

  private val blueskyPasswordOpt: Opts[Option[String]] =
    Opts.option[String]("bluesky-password", help = "Password for the Bluesky authentication")
      .orElse(Opts.env[String](
        "BSKY_PASSWORD",
        help = "Password for the Bluesky authentication"
      )).orNone

  val opts: Opts[BlueskyConfig] =
    (blueskyEnabledOpt, blueskyServiceOpt, blueskyUsernameOpt, blueskyPasswordOpt).mapN {
      (enabled, svc, user, pass) =>
        (enabled, svc, user, pass)
    }.mapValidated {
      case (false, _, _, _) => BlueskyConfig.Disabled.valid
      case (true, Some(s), Some(u), Some(p)) => BlueskyConfig.Enabled(s, u, p).valid
      case (true, None, _, _) => "Bluesky is enabled but bluesky-service is not provided".invalidNel
      case (true, _, None, _) =>
        "Bluesky is enabled but bluesky-username is not provided".invalidNel
      case (true, _, _, None) =>
        "Bluesky is enabled but bluesky-password is not provided".invalidNel
    }

}
