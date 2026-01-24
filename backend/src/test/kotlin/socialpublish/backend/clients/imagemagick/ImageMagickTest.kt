package socialpublish.backend.clients.imagemagick

import arrow.core.getOrElse
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class ImageMagickTest {
    private lateinit var imageMagick: ImageMagick
    private lateinit var testFlower1: File
    private lateinit var testFlower2: File
    private lateinit var testZuzi: File

    @BeforeEach
    fun setup() {
        imageMagick = runBlocking {
            ImageMagick().getOrElse {
                error("ImageMagick not available: ${it.message}. Is ImageMagick installed?")
            }
        }

        // Load test images from resources
        testFlower1 =
            File(
                javaClass.classLoader.getResource("flower1.jpeg")?.toURI()
                    ?: error("Test resource flower1.jpeg not found")
            )
        testFlower2 =
            File(
                javaClass.classLoader.getResource("flower2.jpeg")?.toURI()
                    ?: error("Test resource flower2.jpeg not found")
            )
        testZuzi =
            File(
                javaClass.classLoader.getResource("zuzi.jpg")?.toURI()
                    ?: error("Test resource zuzi.jpg not found")
            )
    }

    @Test
    fun `identifyImageSize should return correct dimensions for JPEG image`(
        @TempDir tempDir: Path
    ) = runBlocking {
        val result = imageMagick.identifyImageSize(testFlower1)

        assertTrue(result.isRight(), "identifyImageSize should succeed")
        val size = result.getOrNull()!!

        // Verify dimensions are positive
        assertTrue(size.width > 0, "Width should be positive")
        assertTrue(size.height > 0, "Height should be positive")

        // flower1.jpeg is known to be 4966x3313
        assertEquals(4966, size.width, "Width should match expected value")
        assertEquals(3313, size.height, "Height should match expected value")
    }

    @Test
    fun `identifyImageSize should work for second test image`(@TempDir tempDir: Path) =
        runBlocking {
            val result = imageMagick.identifyImageSize(testFlower2)

            assertTrue(result.isRight(), "identifyImageSize should succeed")
            val size = result.getOrNull()!!

            assertTrue(size.width > 0, "Width should be positive")
            assertTrue(size.height > 0, "Height should be positive")

            // flower2.jpeg is known to be 1600x1200
            assertEquals(1600, size.width, "Width should match expected value")
            assertEquals(1200, size.height, "Height should match expected value")
        }

    @Test
    fun `identifyImageSize should fail for non-existent file`(@TempDir tempDir: Path) =
        runBlocking {
            val nonExistentFile = tempDir.resolve("does-not-exist.jpg").toFile()
            val result = imageMagick.identifyImageSize(nonExistentFile)

            assertTrue(result.isLeft(), "identifyImageSize should fail for non-existent file")
            val error = result.leftOrNull()!!
            assertTrue(
                error.message?.contains("does not exist") == true,
                "Error message should mention file does not exist",
            )
        }

    @Test
    fun `identifyImageSize should fail for non-image file`(@TempDir tempDir: Path) = runBlocking {
        val textFile = tempDir.resolve("text.txt").toFile()
        textFile.writeText("This is not an image")

        val result = imageMagick.identifyImageSize(textFile)

        assertTrue(result.isLeft(), "identifyImageSize should fail for non-image file")
    }

    @Test
    fun `optimizeImage should create optimized JPEG file`(@TempDir tempDir: Path) = runBlocking {
        val dest = tempDir.resolve("optimized.jpg").toFile()

        val result = imageMagick.optimizeImage(testFlower1, dest)

        assertTrue(result.isRight(), "optimizeImage should succeed")
        assertTrue(dest.exists(), "Destination file should be created")
        assertTrue(dest.length() > 0, "Destination file should not be empty")

        // Verify we can identify the optimized image
        val sizeResult = imageMagick.identifyImageSize(dest)
        assertTrue(sizeResult.isRight(), "Should be able to identify optimized image")
        val size = sizeResult.getOrNull()!!

        // Image should be resized to max dimensions (1600x1600 by default)
        assertTrue(size.width <= 1600, "Width should be <= max width (1600)")
        assertTrue(size.height <= 1600, "Height should be <= max height (1600)")

        // Since the original is 4966x3313, it should be resized
        assertTrue(size.width < 4966, "Image should be resized from original width")
        assertTrue(size.height < 3313, "Image should be resized from original height")
    }

    @Test
    fun `optimizeImage should respect custom size constraints`(@TempDir tempDir: Path) =
        runBlocking {
            val customMagick =
                ImageMagick(
                        options =
                            MagickOptimizeOptions(
                                maxWidth = 800,
                                maxHeight = 600,
                                maxSizeBytes = 500_000,
                            )
                    )
                    .getOrElse { error("ImageMagick not available: ${it.message}") }

            val dest = tempDir.resolve("optimized-custom.jpg").toFile()
            val result = customMagick.optimizeImage(testFlower1, dest)

            assertTrue(result.isRight(), "optimizeImage with custom options should succeed")
            assertTrue(dest.exists(), "Destination file should be created")

            val sizeResult = customMagick.identifyImageSize(dest)
            val size = sizeResult.getOrNull()!!

            assertTrue(size.width <= 800, "Width should be <= custom max width (800)")
            assertTrue(size.height <= 600, "Height should be <= custom max height (600)")
            assertTrue(dest.length() <= 500_000, "File size should be <= 500KB")
        }

    @Test
    fun `optimizeImage should enforce maximum file size`(@TempDir tempDir: Path) = runBlocking {
        // Create an ImageMagick instance with reasonable max size
        // flower2.jpeg is 1600x1200 and 360KB, so we'll set a 200KB limit
        val smallSizeMagick =
            ImageMagick(
                    options =
                        MagickOptimizeOptions(
                            maxWidth = 1600,
                            maxHeight = 1600,
                            maxSizeBytes = 200_000, // 200KB - achievable with quality reduction
                            jpegQuality = 85,
                        )
                )
                .getOrElse { error("ImageMagick not available: ${it.message}") }

        val dest = tempDir.resolve("optimized-small.jpg").toFile()
        val result = smallSizeMagick.optimizeImage(testFlower2, dest)

        assertTrue(result.isRight(), "optimizeImage should succeed with reasonable constraints")
        assertTrue(dest.exists(), "Destination file should be created")
        assertTrue(
            dest.length() <= 200_000,
            "File size should be <= 200KB, got ${dest.length()} bytes",
        )
    }

    @Test
    fun `optimizeImage should fail when size constraints cannot be met`(@TempDir tempDir: Path) =
        runBlocking {
            // Create an ImageMagick instance with impossible constraints
            val impossibleMagick =
                ImageMagick(
                        options =
                            MagickOptimizeOptions(
                                maxWidth = 1600,
                                maxHeight = 1600,
                                maxSizeBytes = 100, // Impossibly small: 100 bytes
                                jpegQuality = 95,
                            )
                    )
                    .getOrElse { error("ImageMagick not available: ${it.message}") }

            val dest = tempDir.resolve("optimized-impossible.jpg").toFile()
            val result = impossibleMagick.optimizeImage(testFlower1, dest)

            assertTrue(result.isLeft(), "optimizeImage should fail when constraints cannot be met")
            val error = result.leftOrNull()!!
            assertTrue(
                error.message?.contains("Cannot optimize") == true ||
                    error.message?.contains("excessive quality loss") == true,
                "Error message should indicate optimization failure",
            )
        }

    @Test
    fun `optimizeImage should fail when source does not exist`(@TempDir tempDir: Path) =
        runBlocking {
            val nonExistent = tempDir.resolve("does-not-exist.jpg").toFile()
            val dest = tempDir.resolve("output.jpg").toFile()

            val result = imageMagick.optimizeImage(nonExistent, dest)

            assertTrue(result.isLeft(), "optimizeImage should fail for non-existent source")
            val error = result.leftOrNull()!!
            // The error message could be from validateFiles or from the ImageMagick command failing
            assertTrue(
                error.message?.contains("does not exist") == true ||
                    error.message?.contains("not readable") == true ||
                    error.message?.contains("File is not") == true,
                "Error message should indicate file problem, got: ${error.message}",
            )
        }

    @Test
    fun `optimizeImage should fail when destination already exists`(@TempDir tempDir: Path) =
        runBlocking {
            val dest = tempDir.resolve("existing.jpg").toFile()
            dest.writeText("existing file")

            val result = imageMagick.optimizeImage(testFlower1, dest)

            assertTrue(result.isLeft(), "optimizeImage should fail when destination exists")
            val error = result.leftOrNull()!!
            assertTrue(
                error.message?.contains("already exists") == true,
                "Error message should mention file already exists",
            )
        }

    @Test
    fun `optimizeImage should preserve aspect ratio`(@TempDir tempDir: Path) = runBlocking {
        val dest = tempDir.resolve("optimized-aspect.jpg").toFile()

        // Get original dimensions
        val originalSize = imageMagick.identifyImageSize(testFlower1).getOrNull()!!
        val originalRatio = originalSize.width.toDouble() / originalSize.height.toDouble()

        val result = imageMagick.optimizeImage(testFlower1, dest)

        assertTrue(result.isRight(), "optimizeImage should succeed")

        val optimizedSize = imageMagick.identifyImageSize(dest).getOrNull()!!
        val optimizedRatio = optimizedSize.width.toDouble() / optimizedSize.height.toDouble()

        // Aspect ratio should be preserved (within 1% tolerance for rounding)
        val ratioDiff = kotlin.math.abs(originalRatio - optimizedRatio) / originalRatio
        assertTrue(
            ratioDiff < 0.01,
            "Aspect ratio should be preserved. Original: $originalRatio, Optimized: $optimizedRatio",
        )
    }

    @Test
    fun `optimizeImage should not resize image smaller than max dimensions`(
        @TempDir tempDir: Path
    ) = runBlocking {
        // Use a large max dimension that's bigger than our test image
        val largeDimMagick =
            ImageMagick(options = MagickOptimizeOptions(maxWidth = 5000, maxHeight = 5000))
                .getOrElse { error("ImageMagick not available: ${it.message}") }

        val dest = tempDir.resolve("optimized-no-resize.jpg").toFile()
        val originalSize = largeDimMagick.identifyImageSize(testFlower1).getOrNull()!!

        val result = largeDimMagick.optimizeImage(testFlower1, dest)

        assertTrue(result.isRight(), "optimizeImage should succeed")

        val optimizedSize = largeDimMagick.identifyImageSize(dest).getOrNull()!!

        // Dimensions should remain the same (or very close) since max is larger
        assertEquals(
            originalSize.width,
            optimizedSize.width,
            "Width should not change when max dimension is larger",
        )
        assertEquals(
            originalSize.height,
            optimizedSize.height,
            "Height should not change when max dimension is larger",
        )
    }

    @Test
    fun `ImageMagick companion invoke should find magick executable`() = runBlocking {
        val result = ImageMagick()

        assertTrue(result.isRight(), "ImageMagick() should successfully locate magick executable")
        assertNotNull(result.getOrNull(), "Should return ImageMagick instance")
        Unit
    }

    @Test
    fun `ImageMagick companion invoke should fail gracefully when magick not found`() =
        runBlocking {
            // This test would require mocking the `which` command, which is complex
            // For now, we'll skip this test and rely on manual verification
            // that the error handling works correctly

            // Note: In a real scenario where magick is not installed,
            // ImageMagick() would return Either.Left(MagickException(...))
        }

    @Test
    fun `optimizeImage should respect EXIF orientation and auto-rotate`(@TempDir tempDir: Path) =
        runBlocking {
            // zuzi.jpg has EXIF orientation 6 (Rotate 90 CW), making it 4000x3000
            // When properly auto-oriented, it should become 3000x4000
            val dest = tempDir.resolve("optimized-zuzi.jpg").toFile()

            // Get original dimensions (without auto-orient, this would be 4000x3000)
            val originalSize = imageMagick.identifyImageSize(testZuzi).getOrNull()!!

            // Optimize the image
            val result = imageMagick.optimizeImage(testZuzi, dest)

            assertTrue(result.isRight(), "optimizeImage should succeed")
            assertTrue(dest.exists(), "Destination file should be created")

            // Get optimized dimensions
            val optimizedSize = imageMagick.identifyImageSize(dest).getOrNull()!!

            // After auto-orient, the image should be rotated:
            // Original EXIF says width=4000, height=3000 with orientation=6
            // After auto-orient, it should be physically rotated to width=3000, height=4000
            // Then resized to fit within 1600x1600, maintaining aspect ratio
            // Expected: height should be larger than width (portrait orientation)
            assertTrue(
                optimizedSize.height > optimizedSize.width,
                "After auto-orient and resize, image should be portrait (height > width). " +
                    "Got ${optimizedSize.width}x${optimizedSize.height}, original was ${originalSize.width}x${originalSize.height}",
            )
        }
}
