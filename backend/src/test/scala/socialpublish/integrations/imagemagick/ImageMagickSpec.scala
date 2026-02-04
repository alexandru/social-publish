package socialpublish.integrations.imagemagick

import cats.effect.IO
import munit.CatsEffectSuite
import java.io.File
import java.nio.file.Files

class ImageMagickSpec extends CatsEffectSuite {

  private def getTestResource(name: String): File = {
    val url = getClass.getClassLoader.getResource(name)
    if url == null then {
      fail(s"Test resource not found: $name")
    }
    new File(url.toURI)
  }

  private def deleteRecursively(file: File): Unit = {
    if file.isDirectory then {
      file.listFiles().foreach(deleteRecursively)
    }
    file.delete()
    ()
  }

  private val testFlower1 = getTestResource("flower1.jpeg")
  private val testFlower2 = getTestResource("flower2.jpeg")

  test("ImageMagick.apply should find magick executable") {
    ImageMagick().map {
      case Right(magick) => assertNotEquals(magick, null)
      case Left(err) =>
        fail(s"ImageMagick not available: ${err.getMessage}. Is ImageMagick installed?")
    }
  }

  test("identifyImageSize should return correct dimensions for flower1.jpeg") {
    ImageMagick().flatMap {
      case Right(magick) =>
        magick.identifyImageSize(testFlower1).map {
          case Right(size) =>
            assertEquals(size.width, 4966, "Width should match expected value")
            assertEquals(size.height, 3313, "Height should match expected value")
          case Left(err) =>
            fail(s"identifyImageSize failed: ${err.getMessage}")
        }
      case Left(err) =>
        IO(fail(s"ImageMagick not available: ${err.getMessage}"))
    }
  }

  test("identifyImageSize should return correct dimensions for flower2.jpeg") {
    ImageMagick().flatMap {
      case Right(magick) =>
        magick.identifyImageSize(testFlower2).map {
          case Right(size) =>
            assertEquals(size.width, 1600, "Width should match expected value")
            assertEquals(size.height, 1200, "Height should match expected value")
          case Left(err) =>
            fail(s"identifyImageSize failed: ${err.getMessage}")
        }
      case Left(err) =>
        IO(fail(s"ImageMagick not available: ${err.getMessage}"))
    }
  }

  test("identifyImageSize should fail for non-existent file") {
    val tempDir = Files.createTempDirectory("imagemagick-test")
    try {
      ImageMagick().flatMap {
        case Right(magick) =>
          val nonExistent = tempDir.resolve("does-not-exist.jpg").toFile
          magick.identifyImageSize(nonExistent).map {
            case Left(err) =>
              assert(
                err.getMessage.contains("does not exist"),
                "Error should mention file does not exist"
              )
            case Right(_) =>
              fail("identifyImageSize should fail for non-existent file")
          }
        case Left(err) =>
          IO(fail(s"ImageMagick not available: ${err.getMessage}"))
      }
    } finally {
      deleteRecursively(tempDir.toFile)
    }
  }

  test("optimizeImage should create optimized JPEG file") {
    val tempDir = Files.createTempDirectory("imagemagick-test")
    try {
      ImageMagick().flatMap {
        case Right(magick) =>
          val dest = tempDir.resolve("optimized.jpg").toFile
          magick.optimizeImage(testFlower1, dest).flatMap {
            case Right(_) =>
              IO {
                assert(dest.exists(), "Destination file should be created")
                assert(dest.length() > 0, "Destination file should not be empty")
              }.flatMap { _ =>
                magick.identifyImageSize(dest).map {
                  case Right(size) =>
                    assert(size.width <= 1600, "Width should be <= max width (1600)")
                    assert(size.height <= 1600, "Height should be <= max height (1600)")
                    assert(size.width < 4966, "Image should be resized from original width")
                    assert(size.height < 3313, "Image should be resized from original height")
                  case Left(err) =>
                    fail(s"Failed to identify optimized image: ${err.getMessage}")
                }
              }
            case Left(err) =>
              IO(fail(s"optimizeImage failed: ${err.getMessage}"))
          }
        case Left(err) =>
          IO(fail(s"ImageMagick not available: ${err.getMessage}"))
      }
    } finally {
      deleteRecursively(tempDir.toFile)
    }
  }

}
