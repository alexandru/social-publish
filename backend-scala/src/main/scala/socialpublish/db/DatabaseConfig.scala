package socialpublish.db

import com.monovore.decline.Opts
import java.nio.file.Path
import cats.syntax.all.*

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

  // Resource helper for creating a Transactor when needed. Use HikariCP for pooling.
  import cats.effect.{Resource, IO}
  import doobie.util.transactor.Transactor
  import doobie.hikari.HikariTransactor
  import scala.concurrent.ExecutionContext

  def transactorResource(cfg: DatabaseConfig): Resource[IO, Transactor[IO]] =
    HikariTransactor.newHikariTransactor[IO](
      "org.sqlite.JDBC",
      s"jdbc:sqlite:${cfg.path.toAbsolutePath.toString}",
      "",
      "",
      ExecutionContext.global
    )
}
