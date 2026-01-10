package socialpublish.testutils

import cats.effect.*
import doobie.*
import java.nio.file.{Files, Path}
import socialpublish.db.*

case class TestDocumentsDatabase(db: DocumentsDatabase, path: Path)
case class TestPostsDatabase(db: PostsDatabase, path: Path)

object DatabaseFixtures {

  def tempDocumentsDbResource: Resource[IO, TestDocumentsDatabase] =
    Resource.make(IO.blocking(Files.createTempFile(
      "testdb-",
      "-" + java.util.UUID.randomUUID().toString
    ))) { path =>
      IO.blocking(Files.deleteIfExists(path)).attempt.void
    }.flatMap { path =>
      val dbPath = path.toAbsolutePath.toString
      val xa = Transactor.fromDriverManager[IO](
        driver = "org.sqlite.JDBC",
        url = s"jdbc:sqlite:$dbPath",
        user = "",
        password = "",
        logHandler = None
      )
      Resource.eval(DocumentsDatabase(xa).map(db => TestDocumentsDatabase(db, path)))
    }

  def tempPostsDbResource: Resource[IO, TestPostsDatabase] =
    tempDocumentsDbResource.flatMap { tdocs =>
      Resource.eval(IO.pure(TestPostsDatabase(new PostsDatabaseImpl(tdocs.db), tdocs.path)))
    }

}
