package socialpublish.db

import cats.effect.*
import munit.CatsEffectSuite
import doobie.*
import socialpublish.models.*
import java.util.UUID

class DocumentsDatabaseSpec extends CatsEffectSuite {

  // Use a named in-memory database that persists across connections in the same test
  val transactor: FunFixture[DocumentsDatabase] = FunFixture[DocumentsDatabase](
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
      // Initialize the database and return it
      DocumentsDatabase(xa).unsafeRunSync()
    },
    teardown = { _ => () }
  )

  transactor.test("create and retrieve document") { db =>
    val tags = List(DocumentTag("test", "tag"))
    for {
      doc <- db.createOrUpdate("test-kind", """{"test": "data"}""", tags)
      retrieved <- db.searchByUUID(doc.uuid)
    } yield {
      assert(retrieved.isDefined)
      assertEquals(retrieved.get.kind, "test-kind")
      assertEquals(retrieved.get.payload, """{"test": "data"}""")
      assertEquals(retrieved.get.tags, tags)
    }
  }

  transactor.test("getAll returns documents of specified kind") { db =>
    for {
      _ <- db.createOrUpdate("kind-a", """{"a": 1}""", Nil)
      _ <- db.createOrUpdate("kind-b", """{"b": 2}""", Nil)
      _ <- db.createOrUpdate("kind-a", """{"a": 3}""", Nil)
      docs <- db.getAll("kind-a", "created_at ASC")
    } yield {
      assertEquals(docs.length, 2)
      assert(docs.forall(_.kind == "kind-a"))
    }
  }

  transactor.test("upsert and retrieve tags") { db =>
    val tag1 = DocumentTag("tag1", "type1")
    val tag2 = DocumentTag("tag2", "type2")
    for {
      doc <- db.createOrUpdate("test", """{}""", Nil)
      _ <- db.upsertTag(doc.uuid, tag1)
      _ <- db.upsertTag(doc.uuid, tag2)
      tags <- db.getTagsForDocument(doc.uuid)
    } yield {
      assertEquals(tags.length, 2)
      assert(tags.contains(tag1))
      assert(tags.contains(tag2))
    }
  }

  transactor.test("searchByUUID returns None for non-existent UUID") { db =>
    for {
      result <- db.searchByUUID(UUID.randomUUID())
    } yield assertEquals(result, None)
  }
}
