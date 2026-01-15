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

  test("FilesService.resource creates configured directories") {
    val baseTemp = Files.createTempDirectory("files-service-test")
    val uploadsPath = baseTemp.resolve("uploads").resolve("nested")

    assert(!Files.exists(uploadsPath))

    mkResources(uploadsPath).use { case (_, _) =>
      IO {
        assert(Files.exists(uploadsPath))
        assert(Files.isDirectory(uploadsPath))
        assert(Files.exists(uploadsPath.resolve("processed")))
        assert(Files.isDirectory(uploadsPath.resolve("processed")))
      }
    }
  }

  test("save and retrieve file") {
    val tempDir = Files.createTempDirectory("files-service-test")
    val resources = mkResources(tempDir)

    resources.use { case (_, service) =>
      val filename = "test.png"
      val mimeType = "image/png"
      val bytes = pngBytes(10, 10)
      val altText = Some("Test file")

      for {
        metadata <- service.saveFile(filename, bytes, altText)
        retrieved <- service.getFile(metadata.uuid)
      } yield {
        assert(retrieved.isDefined)
        val file = retrieved.get
        assertEquals(file.originalName, filename)
        assertEquals(file.mimeType, mimeType)
        assert(file.bytes.length > 0)
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
      val filename = "disk-test.png"
      val bytes = pngBytes(10, 10)

      for {
        metadata <- service.saveFile(filename, bytes, None)
        filePath = tempDir.resolve("processed").resolve(metadata.hash.get)
      } yield {
        assert(Files.exists(filePath))
        val content = Files.readAllBytes(filePath)
        assert(content.length > 0)
      }
    }
  }

  test("getFile resizes oversized images") {
    val tempDir = Files.createTempDirectory("files-service-test")
    val resources = mkResources(tempDir)

    resources.use { case (_, service) =>
      val bytes = pngBytes(3000, 2000)

      for {
        metadata <- service.saveFile("large.png", bytes, None)
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
        service.saveFile("bad.gif", bytes, None)
      }
    }
  }

  test("saveFile preserves alt text per upload even with same file hash") {
    val tempDir = Files.createTempDirectory("files-service-test")
    val resources = mkResources(tempDir)

    resources.use { case (_, service) =>
      val filename = "test.png"
      val bytes = pngBytes(10, 10)

      for {
        // First upload with alt text "First"
        metadata1 <- service.saveFile(filename, bytes, Some("First"))
        // Second upload of same file with different alt text "Second"
        metadata2 <- service.saveFile(filename, bytes, Some("Second"))
        // Third upload with no alt text
        metadata3 <- service.saveFile(filename, bytes, None)

        // Retrieve all three
        file1 <- service.getFile(metadata1.uuid)
        file2 <- service.getFile(metadata2.uuid)
        file3 <- service.getFile(metadata3.uuid)
      } yield {
        // UUIDs should be different (different alt text = different UUID)
        assert(metadata1.uuid != metadata2.uuid)
        assert(metadata1.uuid != metadata3.uuid)
        assert(metadata2.uuid != metadata3.uuid)

        // But all should have the same hash
        assertEquals(metadata1.hash, metadata2.hash)
        assertEquals(metadata1.hash, metadata3.hash)

        // Alt text should be preserved per upload
        assertEquals(file1.flatMap(_.altText), Some("First"))
        assertEquals(file2.flatMap(_.altText), Some("Second"))
        assertEquals(file3.flatMap(_.altText), None)
      }
    }
  }

}
