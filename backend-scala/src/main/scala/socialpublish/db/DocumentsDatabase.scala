package socialpublish.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import socialpublish.models.*
import java.time.Instant
import java.util.UUID

// UUID Meta instance for Doobie
given Meta[UUID] = Meta[String].timap(UUID.fromString)(_.toString)

trait DocumentsDatabase:
  def createOrUpdate(kind: String, payload: String, tags: List[DocumentTag]): IO[Document]
  def getAll(kind: String, orderBy: String): IO[List[Document]]
  def searchByUUID(uuid: UUID): IO[Option[Document]]
  def upsertTag(docUuid: UUID, tag: DocumentTag): IO[Unit]
  def getTagsForDocument(docUuid: UUID): IO[List[DocumentTag]]

object DocumentsDatabase:
  def apply(xa: Transactor[IO]): IO[DocumentsDatabase] =
    for
      logger <- Slf4jLogger.create[IO]
      _ <- migrations.traverse_(m => m.run(xa, logger))
    yield new DocumentsDatabaseImpl(xa)
  
  private val migrations = List(
    Migration(
      ddl = List(
        """CREATE TABLE IF NOT EXISTS documents (
          |  uuid TEXT PRIMARY KEY NOT NULL,
          |  kind TEXT NOT NULL,
          |  payload TEXT NOT NULL,
          |  created_at INTEGER NOT NULL
          |)""".stripMargin,
        "CREATE INDEX IF NOT EXISTS documents_kind_idx ON documents (kind)",
        "CREATE INDEX IF NOT EXISTS documents_created_at_idx ON documents (created_at DESC)"
      ),
      testIfApplied = sql"SELECT name FROM sqlite_master WHERE type='table' AND name='documents'"
        .query[String]
        .option
        .map(_.isDefined)
    ),
    Migration(
      ddl = List(
        """CREATE TABLE IF NOT EXISTS document_tags (
          |  document_uuid TEXT NOT NULL,
          |  name TEXT NOT NULL,
          |  kind TEXT NOT NULL,
          |  PRIMARY KEY (document_uuid, name, kind),
          |  FOREIGN KEY (document_uuid) REFERENCES documents(uuid) ON DELETE CASCADE
          |)""".stripMargin,
        "CREATE INDEX IF NOT EXISTS document_tags_document_uuid_idx ON document_tags (document_uuid)"
      ),
      testIfApplied = sql"SELECT name FROM sqlite_master WHERE type='table' AND name='document_tags'"
        .query[String]
        .option
        .map(_.isDefined)
    )
  )

private class DocumentsDatabaseImpl(xa: Transactor[IO]) extends DocumentsDatabase:
  
  override def createOrUpdate(kind: String, payload: String, tags: List[DocumentTag]): IO[Document] =
    val uuid = UUID.randomUUID()
    val now = Instant.now()
    val doc = Document(uuid, kind, payload, tags, now)
    
    (for
      _ <- sql"""INSERT INTO documents (uuid, kind, payload, created_at) 
                 VALUES ($uuid, $kind, $payload, ${now.toEpochMilli})"""
        .update.run
      _ <- tags.traverse_(tag => upsertTagQuery(uuid, tag).update.run)
    yield doc).transact(xa)
  
  override def getAll(kind: String, orderBy: String): IO[List[Document]] =
    val order = if orderBy.toLowerCase.contains("desc") then fr"DESC" else fr"ASC"
    (sql"SELECT uuid, kind, payload, created_at FROM documents WHERE kind = $kind ORDER BY created_at " ++ order)
      .query[(String, String, String, Long)]
      .to[List]
      .flatMap { rows =>
        rows.traverse { case (uuidStr, k, p, createdMs) =>
          val uuid = UUID.fromString(uuidStr)
          getTagsForDocumentQuery(uuid).to[List].map { tags =>
            Document(uuid, k, p, tags, Instant.ofEpochMilli(createdMs))
          }
        }
      }
      .transact(xa)
  
  override def searchByUUID(uuid: UUID): IO[Option[Document]] =
    sql"SELECT uuid, kind, payload, created_at FROM documents WHERE uuid = $uuid"
      .query[(String, String, String, Long)]
      .option
      .flatMap {
        case Some((uuidStr, kind, payload, createdMs)) =>
          getTagsForDocumentQuery(uuid).to[List].map { tags =>
            Some(Document(UUID.fromString(uuidStr), kind, payload, tags, Instant.ofEpochMilli(createdMs)))
          }
        case None => ConnectionIO.pure(None)
      }
      .transact(xa)
  
  override def upsertTag(docUuid: UUID, tag: DocumentTag): IO[Unit] =
    upsertTagQuery(docUuid, tag).update.run.transact(xa).void
  
  override def getTagsForDocument(docUuid: UUID): IO[List[DocumentTag]] =
    getTagsForDocumentQuery(docUuid).to[List].transact(xa)
  
  private def upsertTagQuery(docUuid: UUID, tag: DocumentTag): Fragment =
    sql"""INSERT OR REPLACE INTO document_tags (document_uuid, name, kind) 
          VALUES ($docUuid, ${tag.name}, ${tag.kind})"""
  
  private def getTagsForDocumentQuery(docUuid: UUID): Query0[DocumentTag] =
    sql"SELECT name, kind FROM document_tags WHERE document_uuid = $docUuid"
      .query[(String, String)]
      .map { case (name, kind) => DocumentTag(name, kind) }

case class Migration(ddl: List[String], testIfApplied: ConnectionIO[Boolean]):
  def run(xa: Transactor[IO], logger: Logger[IO]): IO[Unit] =
    testIfApplied.transact(xa).flatMap { applied =>
      if !applied then
        logger.info(s"Running migration...") *>
        ddl.traverse_ { statement =>
          logger.info(s"Executing: $statement") *>
          Fragment.const(statement).update.run.transact(xa).void
        }
      else
        logger.info("Migration already applied, skipping")
    }
