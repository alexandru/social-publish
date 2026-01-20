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
    ).orElse(
      Opts.env[Int]("HTTP_PORT", help = "Port to listen on")
    ).withDefault(3000)

  private val baseUrlOpt =
    Opts.option[String](
      "base-url",
      help = "Public URL of this server",
      metavar = "url"
    ).orElse(Opts.env[String]("BASE_URL", help = "Public URL of this server"))

  private val serverAuthUsernameOpt =
    Opts.option[String](
      "server-auth-username",
      help = "Your username for this server",
      metavar = "username"
    ).orElse(Opts.env[String]("SERVER_AUTH_USERNAME", help = "Your username for this server"))

  private val serverAuthPasswordOpt =
    Opts.option[String](
      "server-auth-password",
      help = "Your password for this server (bcrypt hash) - the CLI/env must contain a bcrypt hash",
      metavar = "password"
    ).orElse(Opts.env[String](
      "SERVER_AUTH_PASSWORD",
      help = "Your password for this server (bcrypt hash) - the CLI/env must contain a bcrypt hash"
    ))

  private val serverAuthJwtSecretOpt =
    Opts.option[String](
      "server-auth-jwt-secret",
      help = "JWT secret for this server's authentication",
      metavar = "secret"
    ).orElse(Opts.env[String]("JWT_SECRET", help = "JWT secret for this server's authentication"))

  val opts: Opts[ServerConfig] =
    (
      httpPortOpt,
      baseUrlOpt,
      serverAuthUsernameOpt,
      serverAuthPasswordOpt,
      serverAuthJwtSecretOpt
    ).mapN(ServerConfig.apply)

}
