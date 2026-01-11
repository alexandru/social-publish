package socialpublish.integrations

import cats.effect.{IO, Resource}
import socialpublish.integrations.bluesky.{BlueskyApi, BlueskyConfig}
import socialpublish.integrations.mastodon.{MastodonApi, MastodonConfig}
import socialpublish.integrations.twitter.{TwitterApi, TwitterConfig}
import socialpublish.db.DocumentsDatabase
import socialpublish.http.ServerConfig
import socialpublish.services.FilesService
import sttp.client4.httpclient.cats.HttpClientCatsBackend

final case class Integrations(
  bluesky: BlueskyApi,
  mastodon: MastodonApi,
  twitter: TwitterApi
)

object Integrations {

  def resource(
    server: ServerConfig,
    blueskyConfig: BlueskyConfig,
    mastodonConfig: MastodonConfig,
    twitterConfig: TwitterConfig,
    files: FilesService,
    docsDb: DocumentsDatabase
  ): Resource[IO, Integrations] =
    HttpClientCatsBackend.resource[IO]().flatMap { backend =>
      for {
        bluesky <- BlueskyApi.resource(blueskyConfig, backend, files)
        mastodon <- Resource.eval(IO.pure(MastodonApi(mastodonConfig, backend, files)))
        twitter <- Resource.eval(IO.pure(TwitterApi(
          server,
          twitterConfig,
          backend,
          files,
          docsDb
        )))
      } yield Integrations(bluesky, mastodon, twitter)
    }

}
