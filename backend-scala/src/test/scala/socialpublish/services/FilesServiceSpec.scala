package socialpublish.services

import cats.effect.*
import munit.CatsEffectSuite
import socialpublish.db.FilesDatabase
import java.nio.file.{Files, Path}
import java.util.UUID

class FilesServiceSpec extends CatsEffectSuite {

  private def pngBytes(width: Int, height: Int): Array[Byte] = {
    val image =
      new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
      graphics.setColor(java.awt.Color.WHITE)
      graphics.fillRect(0, 0, width, height)
    } finally {
      graphics.dispose()
    }

    val output = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(image, "png", output)
    output.toByteArray
  }

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

  test("getFile resizes oversized images") {
    val tempDir = Files.createTempDirectory("files-service-test")
    val resources = mkResources(tempDir)

    resources.use { case (_, service) =>
      val bytes = pngBytes(3000, 2000)

      for {
        metadata <- service.saveFile("large.png", "image/png", bytes, None)
        fetched <- service.getFile(metadata.uuid)
      } yield {
        assert(fetched.isDefined)
        val file = fetched.get
        assertEquals(file.width, 1920)
        assertEquals(file.height, 1280)
      }
    }
  }

  test("saveFile rejects unsupported image types") {
    val tempDir = Files.createTempDirectory("files-service-test")
    val resources = mkResources(tempDir)

    resources.use { case (_, service) =>
      val bytes = "gif89".getBytes
      interceptIO[IllegalArgumentException] {
        service.saveFile("bad.gif", "image/gif", bytes, None)
      }
    }
  }

}
