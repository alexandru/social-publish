package socialpublish.integrations.rss

import cats.effect.IO
import munit.CatsEffectSuite
import socialpublish.db.PostsDatabase
import socialpublish.http.ServerConfig
import socialpublish.models.{Content, NewPostRequest, NewPostResponse, Post, Target, FileMetadata}
import socialpublish.services.{FilesService, ProcessedFile}

import java.time.Instant
import java.util.UUID
import java.nio.file.Path
import java.nio.file.Paths

class RssServiceSpec extends CatsEffectSuite {

  private val serverConfig = {
    {
      import org.mindrot.jbcrypt.BCrypt
      val hashed = BCrypt.hashpw("admin", BCrypt.gensalt())
      ServerConfig(
        port = 8080,
        baseUrl = "http://localhost:8080",
        authUser = "admin",
        authPass = hashed,
        jwtSecret = "secret"
      )
    }
  }

  test("createPost creates a new post in the database") {
    val mockPostsDb = new MockPostsDatabase()
    val mockFilesService = new MockFilesService()
    val service = new RssServiceImpl(serverConfig, mockPostsDb, mockFilesService)

    val request = NewPostRequest(
      content = Content.unsafe("Hello RSS"),
      targets = None,
      link = None,
      language = None,
      cleanupHtml = None,
      images = None
    )

    for {
      result <- service.createPost(request)
      _ = result match {
        case response: NewPostResponse.Rss =>
          assert(response.uri.startsWith(s"${serverConfig.baseUrl}/rss/"))
        case _ =>
          fail("Expected RSS response")
      }
      posts <- mockPostsDb.getAll
      _ = assertEquals(posts.length, 1)
      _ = assertEquals(posts.head.content, "Hello RSS")
    } yield ()
  }

  test("generateFeed returns XML with items") {
    val mockPostsDb = new MockPostsDatabase()
    val mockFilesService = new MockFilesService()
    val service = new RssServiceImpl(serverConfig, mockPostsDb, mockFilesService)

    val post1 = Post(
      uuid = UUID.randomUUID(),
      content = "Post 1",
      link = None,
      tags = Nil,
      language = None,
      images = Nil,
      targets = Nil,
      createdAt = Instant.now()
    )

    for {
      _ <- mockPostsDb.add(post1)
      xml <- service.generateFeed(None, None, None)
      _ = assert(xml.contains("<title>Post 1</title>"))
      _ = assert(xml.contains("<description>Post 1</description>"))
      _ = assert(xml.contains(s"<guid>${serverConfig.baseUrl}/rss/${post1.uuid}</guid>"))
      // Verify RFC-1123 date format (basic check for GMT)
      _ = assert(xml.contains("GMT</pubDate>"))
    } yield ()
  }

  test("generateFeed includes media elements") {
    val mockPostsDb = new MockPostsDatabase()
    val mockFilesService = new MockFilesService()
    val service = new RssServiceImpl(serverConfig, mockPostsDb, mockFilesService)

    val imageId = UUID.randomUUID()
    val post = Post(
      uuid = UUID.randomUUID(),
      content = "Post with image",
      link = None,
      tags = Nil,
      language = None,
      images = List(imageId),
      targets = Nil,
      createdAt = Instant.now()
    )

    for {
      _ <- mockPostsDb.add(post)
      xml <- service.generateFeed(None, None, None)
      // Check media:content attributes
      _ = assert(xml.contains(s"""url="${serverConfig.baseUrl}/files/$imageId""""))
      _ = assert(xml.contains("""type="image/jpeg""""))
      _ = assert(xml.contains("""fileSize="1024""""))
      // Check media description in CDATA
      _ = assert(xml.contains("<![CDATA[An image]]>"))
    } yield ()
  }

  // Mock implementations
  class MockPostsDatabase extends PostsDatabase {
    private var posts: List[Post] = Nil

    def add(post: Post): IO[Unit] =
      IO {
        posts = post :: posts
      }

    override def create(
      content: String,
      link: Option[String],
      tags: List[String],
      language: Option[String],
      images: List[UUID],
      targets: List[Target]
    ): IO[Post] = {
      val post = Post(
        uuid = UUID.randomUUID(),
        content = content,
        link = link,
        tags = tags,
        language = language,
        images = images,
        targets = targets,
        createdAt = Instant.now()
      )
      add(post).as(post)
    }

    override def getAll: IO[List[Post]] =
      IO.pure(posts)
    override def searchByUUID(uuid: UUID): IO[Option[Post]] =
      IO.pure(posts.find(_.uuid == uuid))
  }

  class MockFilesService extends FilesService {
    override def saveFile(
      filename: String,
      bytes: Array[Byte],
      altText: Option[String]
    ): IO[FileMetadata] =
      ???
    override def getFile(uuid: UUID): IO[Option[ProcessedFile]] =
      ???
    override def getFileMetadata(uuid: UUID): IO[Option[FileMetadata]] =
      IO.pure(
        Some(
          FileMetadata(
            uuid = uuid,
            originalName = "image.jpg",
            mimeType = "image/jpeg",
            size = 1024L,
            altText = Some("An image"),
            width = Some(800),
            height = Some(600),
            hash = Some("dummy-hash"),
            createdAt = Instant.now()
          )
        )
      )
    override def getFilePath(uuid: UUID): Path =
      Paths.get("/tmp", uuid.toString)
  }
}
