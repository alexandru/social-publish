package socialpublish

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import doobie.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.server.Server
import org.http4s.server.middleware.{Logger => ServerLogger, CORS}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import socialpublish.api.{BlueskyApi, MastodonApi, TwitterApi}
import socialpublish.config.AppConfig
import socialpublish.db.{DocumentsDatabase, FilesDatabase, PostsDatabaseImpl}
import socialpublish.http.Routes
import socialpublish.services.FilesService

object Main extends CommandIOApp(
  name = "social-publish",
  header = "Social publishing backend server",
  version = "1.0.0"
):
  
  override def main: Opts[IO[ExitCode]] =
    AppConfig.opts.map { config =>
      program(config).as(ExitCode.Success)
    }
  
  private def program(config: AppConfig): IO[Unit] =
    for
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info("Starting social-publish backend...")
      _ <- logger.info(s"Database path: ${config.dbPath}")
      _ <- logger.info(s"HTTP port: ${config.httpPort}")
      _ <- logger.info(s"Base URL: ${config.baseUrl}")
      
      // Create transactor for database
      xa <- createTransactor(config)
      
      // Initialize databases
      docsDb <- DocumentsDatabase(xa)
      filesDb <- FilesDatabase(xa)
      postsDb = new PostsDatabaseImpl(docsDb)
      
      // Initialize services
      filesService <- FilesService(config, filesDb)
      
      // Create HTTP client and API clients
      _ <- EmberClientBuilder.default[IO].build.use { httpClient =>
        for
          blueskyApi <- BlueskyApi(config, httpClient, filesService, logger)
          mastodonApi = MastodonApi(config, httpClient, filesService, logger)
          twitterApi = TwitterApi(config, httpClient, filesService, docsDb, logger)
          
          // Create HTTP routes
          routes = new Routes(config, blueskyApi, mastodonApi, twitterApi, filesService, postsDb, logger)
          
          // Add middleware
          httpApp = ServerLogger.httpApp(logHeaders = true, logBody = false)(
            CORS.policy.withAllowOriginAll(routes.routes).orNotFound
          )
          
          // Start server
          _ <- logger.info(s"Starting HTTP server on port ${config.httpPort}...")
          server <- EmberServerBuilder
            .default[IO]
            .withHost(ipv4"0.0.0.0")
            .withPort(Port.fromInt(config.httpPort).getOrElse(port"3000"))
            .withHttpApp(httpApp)
            .build
            .use { server =>
              logger.info(s"Server started at ${server.address}") *>
              logger.info("Press Ctrl+C to stop...") *>
              IO.never
            }
        yield server
      }
    yield ()
  
  private def createTransactor(config: AppConfig): IO[Transactor[IO]] =
    IO.delay {
      Transactor.fromDriverManager[IO](
        driver = "org.sqlite.JDBC",
        url = s"jdbc:sqlite:${config.dbPath}",
        user = "",
        password = "",
        logHandler = None
      )
    }
