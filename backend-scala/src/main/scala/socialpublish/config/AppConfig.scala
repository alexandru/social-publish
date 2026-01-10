package socialpublish.config

import com.monovore.decline.Opts
import cats.syntax.all.*
import socialpublish.api.{BlueskyConfig, MastodonConfig, TwitterConfig}
import socialpublish.db.DatabaseConfig
import socialpublish.http.ServerConfig
import socialpublish.services.FilesConfig

case class AppConfig(
  database: DatabaseConfig,
  server: ServerConfig,
  bluesky: BlueskyConfig,
  mastodon: MastodonConfig,
  twitter: TwitterConfig,
  files: FilesConfig
)

object AppConfig {

  val opts: Opts[AppConfig] = (
    DatabaseConfig.opts,
    ServerConfig.opts,
    BlueskyConfig.opts,
    MastodonConfig.opts,
    TwitterConfig.opts,
    FilesConfig.opts
  ).mapN(AppConfig.apply)

}
