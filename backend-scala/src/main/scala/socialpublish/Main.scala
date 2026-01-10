package socialpublish

import cats.effect.*
import com.comcast.ip4s.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.server.middleware.Logger as ServerLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import socialpublish.api.BlueskyApi
import socialpublish.api.MastodonApi
import socialpublish.api.TwitterApi
import socialpublish.config.AppConfig
import socialpublish.db.{DocumentsDatabase, FilesDatabase, DatabaseConfig, PostsDatabaseImpl}
import socialpublish.http.{AuthMiddleware, Routes}
import socialpublish.services.FilesService

object Main extends CommandIOApp(
      name = "social-publish",
      header = "Social publishing backend server",
      version = "1.0.0"
    ) {

  override def main: Opts[IO[ExitCode]] =
    AppConfig.opts.map { config =>
      val programResource: Resource[IO, Unit] = for {
        logger <- Resource.eval(Slf4jLogger.create[IO])
        _ <- Resource.eval(logger.info("Starting social-publish backend..."))
        _ <- Resource.eval(logger.info(s"Database path: ${config.database.path}"))
        _ <- Resource.eval(logger.info(s"HTTP port: ${config.server.port}"))
        _ <- Resource.eval(logger.info(s"Base URL: ${config.server.baseUrl}"))

        // Transactor resource
        xa <- DatabaseConfig.transactorResource(config.database)

        // Databases (migrations run inside apply)
        docsDb <- Resource.eval(DocumentsDatabase(xa))
        filesDb <- Resource.eval(FilesDatabase(xa))
        _ <- Resource.eval(IO.delay(new PostsDatabaseImpl(docsDb)))

        // Services
        filesService <- FilesService.resource(config.files, filesDb)

        // HTTP client
        httpClient <- EmberClientBuilder.default[IO].build

        // APIs
        blueskyApi <- BlueskyApi.resource(config.bluesky, httpClient, filesService, logger)
        mastodonApi <-
          Resource.eval(IO.pure(MastodonApi(config.mastodon, httpClient, filesService, logger)))
        twitterApi <- Resource.eval(IO.pure(TwitterApi(
          config.server,
          config.twitter,
          httpClient,
          filesService,
          docsDb,
          logger
        )))

        // Auth middleware and routes
        authMiddleware <-
          Resource.eval(IO.delay(new AuthMiddleware(config.server, twitterApi, logger)))
        routes <- Resource.eval(IO.delay(new Routes(
          config.server,
          authMiddleware,
          blueskyApi,
          mastodonApi,
          twitterApi,
          filesService,
          new PostsDatabaseImpl(docsDb),
          logger
        )))

        // Server
        _ <- EmberServerBuilder
          .default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(Port.fromInt(config.server.port).getOrElse(port"3000"))
          .withHttpApp(ServerLogger.httpApp(
            logHeaders = true,
            logBody = false
          )(CORS.policy.withAllowOriginAll(routes.routes).orNotFound))
          .build
      } yield ()

      programResource.use(_ => IO.never).as(ExitCode.Success)
    }
}
