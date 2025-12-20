package socialpublish.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import socialpublish.models.*
import socialpublish.db.Metas.given
import java.time.Instant
import java.util.UUID

trait FilesDatabase {
  def save(metadata: FileMetadata): IO[FileMetadata]
  def getByUUID(uuid: UUID): IO[Option[FileMetadata]]
  def getAll: IO[List[FileMetadata]]
}

object FilesDatabase {
  def apply(xa: Transactor[IO]): IO[FilesDatabase] =
    for {
      logger <- Slf4jLogger.create[IO]
      _ <- migrations.traverse_(m => m.run(xa, logger))
    } yield new FilesDatabaseImpl(xa)

  private val migrations = List(
    Migration(
      ddl = List(
        """CREATE TABLE IF NOT EXISTS files (
          |  uuid TEXT PRIMARY KEY NOT NULL,
          |  original_name TEXT NOT NULL,
          |  mime_type TEXT NOT NULL,
          |  size INTEGER NOT NULL,
          |  alt_text TEXT,
          |  width INTEGER,
          |  height INTEGER,
          |  created_at INTEGER NOT NULL
          |)""".stripMargin,
        "CREATE INDEX IF NOT EXISTS files_created_at_idx ON files (created_at DESC)"
      ),
      testIfApplied = sql"SELECT name FROM sqlite_master WHERE type='table' AND name='files'"
        .query[String]
        .option
        .map(_.isDefined)
    )
  )
}

private class FilesDatabaseImpl(xa: Transactor[IO]) extends FilesDatabase {

  override def save(metadata: FileMetadata): IO[FileMetadata] =
    sql"""INSERT INTO files (uuid, original_name, mime_type, size, alt_text, width, height, created_at)
          VALUES (
            ${metadata.uuid},
            ${metadata.originalName},
            ${metadata.mimeType},
            ${metadata.size},
            ${metadata.altText},
            ${metadata.width},
            ${metadata.height},
            ${metadata.createdAt.toEpochMilli}
          )"""
      .update.run
      .transact(xa)
      .as(metadata)

  override def getByUUID(uuid: UUID): IO[Option[FileMetadata]] =
    sql"""SELECT uuid, original_name, mime_type, size, alt_text, width, height, created_at
          FROM files WHERE uuid = $uuid"""
      .query[(String, String, String, Long, Option[String], Option[Int], Option[Int], Long)]
      .option
      .map(_.map { case (uuidStr, name, mime, size, alt, w, h, created) =>
        FileMetadata(
          UUID.fromString(uuidStr),
          name,
          mime,
          size,
          alt,
          w,
          h,
          Instant.ofEpochMilli(created)
        )
      })
      .transact(xa)

  override def getAll: IO[List[FileMetadata]] =
    sql"""SELECT uuid, original_name, mime_type, size, alt_text, width, height, created_at
          FROM files ORDER BY created_at DESC"""
      .query[(String, String, String, Long, Option[String], Option[Int], Option[Int], Long)]
      .to[List]
      .map(_.map { case (uuidStr, name, mime, size, alt, w, h, created) =>
        FileMetadata(
          UUID.fromString(uuidStr),
          name,
          mime,
          size,
          alt,
          w,
          h,
          Instant.ofEpochMilli(created)
        )
      })
      .transact(xa)
}
