package socialpublish.api

import com.monovore.decline.Opts
import cats.syntax.all.*

case class BlueskyConfig(service: String, username: String, password: String)

object BlueskyConfig {

  private val blueskyServiceOpt: Opts[String] =
    Opts.option[String](
      "bluesky-service",
      help = "URL of the BlueSky service"
    ).orElse(Opts(sys.env.getOrElse("BSKY_SERVICE", "https://bsky.social")))

  private val blueskyUsernameOpt: Opts[String] =
    Opts.option[String](
      "bluesky-username",
      help = "Username for the Bluesky authentication"
    ).orElse(Opts(sys.env.getOrElse("BSKY_USERNAME", "")))

  private val blueskyPasswordOpt: Opts[String] =
    Opts.option[String](
      "bluesky-password",
      help = "Password for the Bluesky authentication"
    ).orElse(Opts(sys.env.getOrElse("BSKY_PASSWORD", "")))

  val opts: Opts[BlueskyConfig] =
    (blueskyServiceOpt, blueskyUsernameOpt, blueskyPasswordOpt).mapN(BlueskyConfig.apply)

}
