package socialpublish.testutils

import cats.effect.{IO, Resource}
import socialpublish.db.{DatabaseConfig, FilesDatabase}
import socialpublish.services.{FilesConfig, FilesService}

import java.nio.file.{Files, Path}

object ServiceFixtures {

  def filesServiceResource: Resource[IO, FilesService] =
    tempDirectoryResource("files-service-test").flatMap { tempDir =>
      for {
        transactor <- DatabaseConfig.transactorResource(DatabaseConfig(tempDir.resolve("files.db")))
        filesDb <- Resource.eval(FilesDatabase(transactor))
        filesService <- FilesService.resource(FilesConfig(tempDir), filesDb)
      } yield filesService
    }

  private def tempDirectoryResource(prefix: String): Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory(prefix)))(deleteDirectory)

  private def deleteDirectory(path: Path): IO[Unit] =
    IO.blocking {
      val stream = Files.walk(path)
      try {
        val iterator = stream.sorted(java.util.Comparator.reverseOrder()).iterator()
        iterator.forEachRemaining { path =>
          Files.deleteIfExists(path)
          ()
        }
      } finally {
        stream.close()
      }
    }.void

}
