package socialpublish.db

import cats.effect.*
import munit.CatsEffectSuite
import socialpublish.models.*
import java.util.UUID

import socialpublish.testutils.DatabaseFixtures.*

class PostsDatabaseSpec extends CatsEffectSuite {

  test("create and retrieve post") {
    tempPostsDbResource.use { tdb =>
      val postsDb = tdb.db
      val content = "Test post content"
      val link = Some("https://example.com")
      val tags = List("scala", "testing")
      val language = Some("en")
      val images = List(UUID.randomUUID())
      val targets = List(Target.Bluesky, Target.Mastodon)

      for {
        post <- postsDb.create(content, link, tags, language, images, targets)
        retrieved <- postsDb.searchByUUID(post.uuid)
      } yield {
        assert(retrieved.isDefined)
        val retrievedPost = retrieved.get
        assertEquals(retrievedPost.content, content)
        assertEquals(retrievedPost.link, link)
        assertEquals(retrievedPost.tags, tags)
        assertEquals(retrievedPost.language, language)
        assertEquals(retrievedPost.images, images)
        assertEquals(retrievedPost.targets.toSet, targets.toSet)
      }
    }
  }

  test("getAll returns all posts") {
    tempPostsDbResource.use { tdb =>
      val postsDb = tdb.db
      for {
        _ <- postsDb.create("Post 1", None, Nil, None, Nil, List(Target.Bluesky))
        _ <- postsDb.create("Post 2", None, Nil, None, Nil, List(Target.Mastodon))
        _ <- postsDb.create("Post 3", None, Nil, None, Nil, List(Target.Twitter))
        allPosts <- postsDb.getAll
      } yield {
        assertEquals(allPosts.length, 3)
        assert(allPosts.exists(_.content == "Post 1"))
        assert(allPosts.exists(_.content == "Post 2"))
        assert(allPosts.exists(_.content == "Post 3"))
      }
    }
  }

  test("searchByUUID returns None for non-existent post") {
    tempPostsDbResource.use { tdb =>
      val postsDb = tdb.db
      for {
        result <- postsDb.searchByUUID(UUID.randomUUID())
      } yield assertEquals(result, None)
    }
  }
}
