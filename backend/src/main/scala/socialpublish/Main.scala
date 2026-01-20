package socialpublish

import cats.effect.*
import com.monovore.decline.*
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

  private def runServer(config: AppConfig): IO[ExitCode] = {
    val programResource: Resource[IO, Unit] = for {
      logger <- Resource.eval(Slf4jLogger.create[IO])
      _ <- Resource.eval(logger.info("Starting social-publish backend..."))
      _ <- Resource.eval(logger.info(s"Database path: ${config.database.path}"))
      _ <- Resource.eval(logger.info(s"HTTP port: ${config.server.port}"))
      _ <- Resource.eval(logger.info(s"Base URL: ${config.server.baseUrl}"))

      xa <- DatabaseConfig.transactorResource(config.database)

      docsDb <- Resource.eval(DocumentsDatabase(xa))
      filesDb <- Resource.eval(FilesDatabase(xa))
      _ <- Resource.eval(IO.delay(new PostsDatabaseImpl(docsDb)))

      filesService <- FilesService.resource(config.files, filesDb)

      integrations <- Integrations.resource(
        config.server,
        config.bluesky,
        config.mastodon,
        config.twitter,
        filesService,
        docsDb
      )

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

      _ <- HttpServer.resource(config.server, routes).map(_ => ())
    } yield ()

    programResource.use(_ => IO.never).as(ExitCode.Success)
  }

  private val startServerCommand: Command[IO[ExitCode]] =
    Command("start-server", "Start the HTTP server")(
      AppConfig.opts.map(runServer)
    )

  private val genBcryptCommand: Command[IO[ExitCode]] =
    Command("gen-bcrypt-hash", "Read a password from STDIN and print a bcrypt hash") {
      val quiet = Opts.flag("quiet", "Quiet mode, no extra output", short = "q").orFalse
      quiet.map { isQuiet =>
        IO.blocking {
          if !isQuiet then print("Enter password to hash: ")
          val pw = scala.io.StdIn.readLine()
          val hash = org.mindrot.jbcrypt.BCrypt.hashpw(pw, org.mindrot.jbcrypt.BCrypt.gensalt())
          if !isQuiet then println("Generated bcrypt hash:\n")
          println(hash)
          if !isQuiet then println()
          ExitCode.Success
        }
      }
    }

  override def main: Opts[IO[ExitCode]] =
    List(
      startServerCommand,
      genBcryptCommand
    ).map(
      Opts.subcommand[IO[ExitCode]]
    ).reduce(_ orElse _).map(_.as(ExitCode.Success))

}
