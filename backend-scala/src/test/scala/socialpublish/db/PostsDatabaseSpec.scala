package socialpublish.db

import cats.effect.*
import munit.CatsEffectSuite
import doobie.*
import socialpublish.models.*
import java.util.UUID

class PostsDatabaseSpec extends CatsEffectSuite:
  
  val fixture: FunFixture[PostsDatabase] = FunFixture[PostsDatabase](
    setup = { testOptions =>
      // Use test name to create unique database for each test
      val dbName = testOptions.name.hashCode.abs.toString
      val xa = Transactor.fromDriverManager[IO](
        driver = "org.sqlite.JDBC",
        url = s"jdbc:sqlite:file:memdb$dbName?mode=memory&cache=shared",
        user = "",
        password = "",
        logHandler = None
      )
      (for
        docsDb <- DocumentsDatabase(xa)
        postsDb = new PostsDatabaseImpl(docsDb)
      yield postsDb).unsafeRunSync()
    },
    teardown = { _ => () }
  )
  
  fixture.test("create and retrieve post") { postsDb =>
    val content = "Test post content"
    val link = Some("https://example.com")
    val tags = List("scala", "testing")
    val language = Some("en")
    val images = List(UUID.randomUUID())
    val targets = List(Target.Bluesky, Target.Mastodon)
    
    for
      post <- postsDb.create(content, link, tags, language, images, targets)
      retrieved <- postsDb.searchByUUID(post.uuid)
    yield
      assert(retrieved.isDefined)
      val retrievedPost = retrieved.get
      assertEquals(retrievedPost.content, content)
      assertEquals(retrievedPost.link, link)
      assertEquals(retrievedPost.tags, tags)
      assertEquals(retrievedPost.language, language)
      assertEquals(retrievedPost.images, images)
      assertEquals(retrievedPost.targets.toSet, targets.toSet)
  }
  
  fixture.test("getAll returns all posts") { postsDb =>
    for
      _ <- postsDb.create("Post 1", None, Nil, None, Nil, List(Target.Bluesky))
      _ <- postsDb.create("Post 2", None, Nil, None, Nil, List(Target.Mastodon))
      _ <- postsDb.create("Post 3", None, Nil, None, Nil, List(Target.Twitter))
      allPosts <- postsDb.getAll
    yield
      assertEquals(allPosts.length, 3)
      assert(allPosts.exists(_.content == "Post 1"))
      assert(allPosts.exists(_.content == "Post 2"))
      assert(allPosts.exists(_.content == "Post 3"))
  }
  
  fixture.test("searchByUUID returns None for non-existent post") { postsDb =>
    for
      result <- postsDb.searchByUUID(UUID.randomUUID())
    yield
      assertEquals(result, None)
  }
