package socialpublish.services

import cats.effect.*
import munit.CatsEffectSuite
import socialpublish.db.FilesDatabase
import java.nio.file.{Files, Path}
import java.util.UUID

class FilesServiceSpec extends CatsEffectSuite {

  private def mkResources(tempDir: Path)
      : Resource[IO, (doobie.util.transactor.Transactor[IO], FilesService)] = {
    {
      val dbCfg = socialpublish.db.DatabaseConfig(tempDir.resolve("test.db"))
      for {
        transactor <- socialpublish.db.DatabaseConfig.transactorResource(dbCfg)
        filesDb <- Resource.eval(FilesDatabase(transactor))
        filesService <- FilesService.resource(socialpublish.services.FilesConfig(tempDir), filesDb)
      } yield (transactor, filesService)
    }
  }

  test("save and retrieve file") {
    val tempDir = Files.createTempDirectory("files-service-test")
    val resources = mkResources(tempDir)

    resources.use { case (_, service) =>
      val filename = "test.txt"
      val mimeType = "text/plain"
      val bytes = "test content".getBytes
      val altText = Some("Test file")

      for {
        metadata <- service.saveFile(filename, mimeType, bytes, altText)
        retrieved <- service.getFile(metadata.uuid)
      } yield {
        assert(retrieved.isDefined)
        val file = retrieved.get
        assertEquals(file.originalName, filename)
        assertEquals(file.mimeType, mimeType)
        assertEquals(new String(file.bytes), "test content")
        assertEquals(file.altText, altText)
      }
    }
  }

  test("getFile returns None for non-existent UUID") {
    val tempDir = Files.createTempDirectory("files-service-test")
    val resources = mkResources(tempDir)

    resources.use { case (_, service) =>
      for {
        result <- service.getFile(UUID.randomUUID())
      } yield assertEquals(result, None)
    }
  }

  test("saveFile creates file on disk") {
    val tempDir = Files.createTempDirectory("files-service-test")
    val resources = mkResources(tempDir)

    resources.use { case (_, service) =>
      val filename = "disk-test.txt"
      val bytes = "disk content".getBytes

      for {
        metadata <- service.saveFile(filename, "text/plain", bytes, None)
        filePath = service.getFilePath(metadata.uuid)
      } yield {
        assert(Files.exists(filePath))
        val content = Files.readAllBytes(filePath)
        assertEquals(new String(content), "disk content")
      }
    }
  }
}
