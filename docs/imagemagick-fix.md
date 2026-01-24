# ImageMagick Fix for "no decode delegate" Error

## Problem

The application was failing to resize images before uploading to social media platforms (Bluesky, Mastodon, LinkedIn, Twitter), resulting in errors like:

```
Failed to post to Bluesky: 400 Bad Request, 
{"error":"BlobTooLarge","message":"This file is too large. It is 2.99MB but the maximum size is 976.56KB."}
```

Root cause:
```
identify: no decode delegate for this image format
```

## Solution

The issue was that the Alpine Linux Docker image had ImageMagick installed but was missing the required delegate libraries for image format support (JPEG, PNG, WebP).

### Changes Made

1. **Updated `Dockerfile.jvm`** (line 78-88):
   - Added `libjpeg-turbo` - JPEG image format support
   - Added `libpng` - PNG image format support
   - Added `libwebp` - WebP image format support

2. **Created `Dockerfile.run-tests`**:
   - New Dockerfile for running tests in production-like environment
   - Uses same jlink-based minimal JRE as production
   - Includes all ImageMagick delegate libraries
   - Allows testing ImageMagick functionality in CI/CD

## Technical Details

### Why the Fix Works

In Alpine Linux, ImageMagick is distributed as:
- `imagemagick` - Core ImageMagick tools (magick, identify, convert)
- Delegate libraries - Separate packages for each image format

Without delegate libraries, ImageMagick cannot decode/encode image formats, leading to:
- "no decode delegate" errors
- Images not being resized
- Images uploaded at original size
- Social platforms rejecting oversized images

### Testing

The fix ensures:
1. ✓ ImageMagick can identify JPEG/PNG/WebP images
2. ✓ ImageMagick can resize images
3. ✓ ImageMagick can optimize images (quality, size constraints)
4. ✓ Images are processed before upload to social platforms
5. ✓ Images stay within platform size limits (e.g., Bluesky's 976.56KB)

### Running Tests in Docker

```bash
# Build test image
docker build -f Dockerfile.run-tests -t social-publish-tests:latest .

# Run tests
docker run --rm social-publish-tests:latest

# Or run specific tests
docker run --rm social-publish-tests:latest \
  ./gradlew :backend:test --tests "ImageMagickTest" --no-daemon
```

## Impact

- **Before**: Images uploaded at original size, often rejected by platforms
- **After**: Images automatically resized and optimized before upload
- **Privacy**: Images are processed server-side, preventing metadata leakage
- **Performance**: Smaller images = faster uploads

## References

- Alpine packages: https://pkgs.alpinelinux.org/packages
- ImageMagick delegates: https://imagemagick.org/script/formats.php
- Issue: Images not getting resized before uploading (Bluesky rejection)
