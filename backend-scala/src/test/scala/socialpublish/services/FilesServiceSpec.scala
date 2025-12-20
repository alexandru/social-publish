package socialpublish.services

import cats.effect.*
import munit.CatsEffectSuite
import doobie.*
import socialpublish.config.AppConfig
import socialpublish.db.FilesDatabase
import java.nio.file.{Files, Path}
import java.util.UUID

class FilesServiceSpec extends CatsEffectSuite {

  val fixture: FunFixture[(Path, FilesService)] = FunFixture[(Path, FilesService)](
    setup = { _ =>
      val tempDir = Files.createTempDirectory("files-service-test")
      val config = AppConfig(
        dbPath = tempDir.resolve("test.db"),
        httpPort = 3000,
        baseUrl = "http://localhost:3000",
        serverAuthUsername = "test",
        serverAuthPassword = "test",
        serverAuthJwtSecret = "secret",
        blueskyService = "https://bsky.social",
        blueskyUsername = "",
        blueskyPassword = "",
        mastodonHost = "",
        mastodonAccessToken = "",
        twitterOauth1ConsumerKey = "",
        twitterOauth1ConsumerSecret = "",
        uploadedFilesPath = tempDir
      )

      val xa = Transactor.fromDriverManager[IO](
        driver = "org.sqlite.JDBC",
        url = s"jdbc:sqlite:${tempDir.resolve("test.db")}",
        user = "",
        password = "",
        logHandler = None
      )

      val service = (for {
        filesDb <- FilesDatabase(xa)
        filesService <- FilesService(config, filesDb)
      } yield filesService).unsafeRunSync()

      (tempDir, service)
    },
    teardown = { case (tempDir, _) =>
      // Clean up temp directory
      def deleteRecursively(path: Path): Unit = {
        if Files.isDirectory(path) then Files.list(path).forEach(deleteRecursively)
        val _ = Files.deleteIfExists(path)
      }
      deleteRecursively(tempDir)
    }
  )

  fixture.test("save and retrieve file") { case (_, service) =>
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

  fixture.test("getFile returns None for non-existent UUID") { case (_, service) =>
    for {
      result <- service.getFile(UUID.randomUUID())
    } yield assertEquals(result, None)
  }

  fixture.test("saveFile creates file on disk") { case (tempDir, service) =>
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
