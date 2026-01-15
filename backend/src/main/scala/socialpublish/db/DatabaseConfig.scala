package socialpublish.db

import cats.effect.{IO, Resource}
import com.monovore.decline.Opts
import doobie.hikari.HikariTransactor
import doobie.util.transactor.Transactor
import java.nio.file.{Files, Path}
import scala.concurrent.ExecutionContext

case class DatabaseConfig(path: Path)

object DatabaseConfig {

  private def envOrDefault(envName: String, default: => String): String =
    sys.env.getOrElse(envName, default)

  private val dbPathOpt: Opts[Path] =
    Opts.option[Path](
      "db-path",
      help = "Path to the SQLite database file",
      metavar = "path"
    ).orElse(Opts(Path.of(envOrDefault("DB_PATH", "/var/lib/social-publish/sqlite3.db"))))

  val opts: Opts[DatabaseConfig] = dbPathOpt.map(DatabaseConfig.apply)

  private def ensureParentDirectoryExists(cfg: DatabaseConfig): IO[Unit] =
    IO.blocking {
      val absPath = cfg.path.toAbsolutePath
      val parent = absPath.getParent
      if parent != null then {
        val _ = Files.createDirectories(parent)
      }
    }

  def transactorResource(cfg: DatabaseConfig): Resource[IO, Transactor[IO]] =
    for {
      _ <- Resource.eval(ensureParentDirectoryExists(cfg))
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.sqlite.JDBC",
        s"jdbc:sqlite:${cfg.path.toAbsolutePath.toString}",
        "",
        "",
        ExecutionContext.global
      )
    } yield xa

}
