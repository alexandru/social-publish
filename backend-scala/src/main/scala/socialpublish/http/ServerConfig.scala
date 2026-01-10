package socialpublish.http

import com.monovore.decline.Opts
import cats.syntax.all.*

case class ServerConfig(
  port: Int,
  baseUrl: String,
  authUser: String,
  authPass: String,
  jwtSecret: String
)

object ServerConfig {

  private val httpPortOpt =
    Opts.option[Int](
      "http-port",
      help = "Port to listen on",
      metavar = "port"
    ).orElse(Opts(sys.env.getOrElse("HTTP_PORT", "3000").toInt))

  private val baseUrlOpt =
    Opts.option[String](
      "base-url",
      help = "Public URL of this server",
      metavar = "url"
    ).orElse(Opts(sys.env.getOrElse("BASE_URL", "http://localhost:3000")))

  private val serverAuthUsernameOpt =
    Opts.option[String](
      "server-auth-username",
      help = "Your username for this server",
      metavar = "username"
    ).orElse(Opts(sys.env.getOrElse("SERVER_AUTH_USERNAME", "admin")))

  private val serverAuthPasswordOpt =
    Opts.option[String](
      "server-auth-password",
      help = "Your password for this server",
      metavar = "password"
    ).orElse(Opts(sys.env.getOrElse("SERVER_AUTH_PASSWORD", "admin")))

  private val serverAuthJwtSecretOpt =
    Opts.option[String](
      "server-auth-jwt-secret",
      help = "JWT secret for this server's authentication",
      metavar = "secret"
    ).orElse(Opts(sys.env.getOrElse("JWT_SECRET", "changeme")))

  val opts: Opts[ServerConfig] =
    (
      httpPortOpt,
      baseUrlOpt,
      serverAuthUsernameOpt,
      serverAuthPasswordOpt,
      serverAuthJwtSecretOpt
    ).mapN(ServerConfig.apply)

}
