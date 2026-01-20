package socialpublish

import cats.effect.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import org.typelevel.log4cats.slf4j.Slf4jLogger
import socialpublish.integrations.Integrations
import socialpublish.config.AppConfig
import socialpublish.db.{DocumentsDatabase, FilesDatabase, DatabaseConfig, PostsDatabaseImpl}
import socialpublish.http.{AuthMiddleware, HttpServer, Routes}
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

        integrations <- Integrations.resource(
          config.server,
          config.bluesky,
          config.mastodon,
          config.twitter,
          filesService,
          docsDb
        )

        // Auth middleware and routes
        authMiddleware <-
          Resource.eval(IO.delay(new AuthMiddleware(config.server, integrations.twitter, logger)))
        routes <- Resource.eval(IO.delay(new Routes(
          authMiddleware,
          integrations.bluesky,
          integrations.mastodon,
          integrations.twitter,
          integrations.rss,
          filesService,
          logger
        )))

        // Server
        _ <- HttpServer.resource(config.server, routes).map(_ => ())
      } yield ()

      programResource.use(_ => IO.never).as(ExitCode.Success)
    }

}
