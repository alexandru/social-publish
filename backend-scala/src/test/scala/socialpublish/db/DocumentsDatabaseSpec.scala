package socialpublish.db

import cats.effect.*
import munit.CatsEffectSuite
import socialpublish.models.*
import java.util.UUID

import socialpublish.testutils.DatabaseFixtures.*

class DocumentsDatabaseSpec extends CatsEffectSuite {

  test("create and retrieve document") {
    tempDocumentsDbResource.use { tdb =>
      val db = tdb.db
      val tags = List(DocumentTag("test", "tag"))
      for {
        doc <- db.createOrUpdate("test-kind", "{\"test\": \"data\"}", tags)
        retrieved <- db.searchByUUID(doc.uuid)
      } yield {
        assert(retrieved.isDefined)
        assertEquals(retrieved.get.kind, "test-kind")
        assertEquals(retrieved.get.payload, "{\"test\": \"data\"}")
        assertEquals(retrieved.get.tags, tags)
      }
    }
  }

  test("getAll returns documents of specified kind") {
    tempDocumentsDbResource.use { tdb =>
      val db = tdb.db
      for {
        _ <- db.createOrUpdate("kind-a", "{\"a\": 1}", Nil)
        _ <- db.createOrUpdate("kind-b", "{\"b\": 2}", Nil)
        _ <- db.createOrUpdate("kind-a", "{\"a\": 3}", Nil)
        docs <- db.getAll("kind-a", "created_at ASC")
      } yield {
        assertEquals(docs.length, 2)
        assert(docs.forall(_.kind == "kind-a"))
      }
    }
  }

  test("upsert and retrieve tags") {
    tempDocumentsDbResource.use { tdb =>
      val db = tdb.db
      val tag1 = DocumentTag("tag1", "type1")
      val tag2 = DocumentTag("tag2", "type2")
      for {
        doc <- db.createOrUpdate("test", "{}", Nil)
        _ <- db.upsertTag(doc.uuid, tag1)
        _ <- db.upsertTag(doc.uuid, tag2)
        tags <- db.getTagsForDocument(doc.uuid)
      } yield {
        assertEquals(tags.length, 2)
        assert(tags.contains(tag1))
        assert(tags.contains(tag2))
      }
    }
  }

  test("searchByUUID returns None for non-existent UUID") {
    tempDocumentsDbResource.use { tdb =>
      val db = tdb.db
      for {
        result <- db.searchByUUID(UUID.randomUUID())
      } yield assertEquals(result, None)
    }
  }
}
