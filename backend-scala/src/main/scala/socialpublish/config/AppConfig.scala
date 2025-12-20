package socialpublish.config

import cats.syntax.all.*
import com.monovore.decline.*
import java.nio.file.Path

case class AppConfig(
    dbPath: Path,
    httpPort: Int,
    baseUrl: String,
    serverAuthUsername: String,
    serverAuthPassword: String,
    serverAuthJwtSecret: String,
    blueskyService: String,
    blueskyUsername: String,
    blueskyPassword: String,
    mastodonHost: String,
    mastodonAccessToken: String,
    twitterOauth1ConsumerKey: String,
    twitterOauth1ConsumerSecret: String,
    uploadedFilesPath: Path
)

object AppConfig {

  private def envOrDefault(envName: String, default: => String): String =
    sys.env.getOrElse(envName, default)

  private val dbPathOpt = Opts.option[Path](
    "db-path",
    help = "Path to the SQLite database file",
    metavar = "path"
  ).orElse(
    Opts(Path.of(envOrDefault("DB_PATH", "/var/lib/social-publish/sqlite3.db")))
  )

  private val httpPortOpt = Opts.option[Int](
    "http-port",
    help = "Port to listen on",
    metavar = "port"
  ).orElse(
    Opts(envOrDefault("HTTP_PORT", "3000").toInt)
  )

  private val baseUrlOpt = Opts.option[String](
    "base-url",
    help = "Public URL of this server",
    metavar = "url"
  ).orElse(
    Opts(envOrDefault("BASE_URL", "http://localhost:3000"))
  )

  private val serverAuthUsernameOpt = Opts.option[String](
    "server-auth-username",
    help = "Your username for this server",
    metavar = "username"
  ).orElse(
    Opts(sys.env.getOrElse("SERVER_AUTH_USERNAME", "admin"))
  )

  private val serverAuthPasswordOpt = Opts.option[String](
    "server-auth-password",
    help = "Your password for this server",
    metavar = "password"
  ).orElse(
    Opts(sys.env.getOrElse("SERVER_AUTH_PASSWORD", "admin"))
  )

  private val serverAuthJwtSecretOpt = Opts.option[String](
    "server-auth-jwt-secret",
    help = "JWT secret for this server's authentication",
    metavar = "secret"
  ).orElse(
    Opts(sys.env.getOrElse("JWT_SECRET", "changeme"))
  )

  private val blueskyServiceOpt = Opts.option[String](
    "bluesky-service",
    help = "URL of the BlueSky service",
    metavar = "url"
  ).orElse(
    Opts(envOrDefault("BSKY_SERVICE", "https://bsky.social"))
  )

  private val blueskyUsernameOpt = Opts.option[String](
    "bluesky-username",
    help = "Username for the Bluesky authentication",
    metavar = "username"
  ).orElse(
    Opts(sys.env.getOrElse("BSKY_USERNAME", ""))
  )

  private val blueskyPasswordOpt = Opts.option[String](
    "bluesky-password",
    help = "Password for the Bluesky authentication",
    metavar = "password"
  ).orElse(
    Opts(sys.env.getOrElse("BSKY_PASSWORD", ""))
  )

  private val mastodonHostOpt = Opts.option[String](
    "mastodon-host",
    help = "Host of the Mastodon service",
    metavar = "host"
  ).orElse(
    Opts(sys.env.getOrElse("MASTODON_HOST", ""))
  )

  private val mastodonAccessTokenOpt = Opts.option[String](
    "mastodon-access-token",
    help = "Access token for the Mastodon service",
    metavar = "token"
  ).orElse(
    Opts(sys.env.getOrElse("MASTODON_ACCESS_TOKEN", ""))
  )

  private val twitterOauth1ConsumerKeyOpt = Opts.option[String](
    "twitter-oauth1-consumer-key",
    help = "Twitter OAuth1 consumer key",
    metavar = "key"
  ).orElse(
    Opts(sys.env.getOrElse("TWITTER_OAUTH1_CONSUMER_KEY", ""))
  )

  private val twitterOauth1ConsumerSecretOpt = Opts.option[String](
    "twitter-oauth1-consumer-secret",
    help = "Twitter OAuth1 consumer secret",
    metavar = "secret"
  ).orElse(
    Opts(sys.env.getOrElse("TWITTER_OAUTH1_CONSUMER_SECRET", ""))
  )

  private val uploadedFilesPathOpt = Opts.option[Path](
    "uploaded-files-path",
    help = "Directory where uploaded files are stored and processed",
    metavar = "path"
  ).orElse(
    Opts(Path.of(envOrDefault("UPLOADED_FILES_PATH", "/var/lib/social-publish/uploads")))
  )

  val opts: Opts[AppConfig] = (
    dbPathOpt,
    httpPortOpt,
    baseUrlOpt,
    serverAuthUsernameOpt,
    serverAuthPasswordOpt,
    serverAuthJwtSecretOpt,
    blueskyServiceOpt,
    blueskyUsernameOpt,
    blueskyPasswordOpt,
    mastodonHostOpt,
    mastodonAccessTokenOpt,
    twitterOauth1ConsumerKeyOpt,
    twitterOauth1ConsumerSecretOpt,
    uploadedFilesPathOpt
  ).mapN(AppConfig.apply)

  val command: Command[AppConfig] = Command(
    name = "social-publish",
    header = "Social publishing backend server"
  )(opts)
}
