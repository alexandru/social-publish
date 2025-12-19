package socialpublish.db

import cats.effect.IO
import cats.syntax.all.*
import io.circe.parser.*
import io.circe.syntax.*
import socialpublish.models.*
import java.util.UUID

trait PostsDatabase:
  def create(content: String, link: Option[String], tags: List[String], 
             language: Option[String], images: List[UUID], targets: List[Target]): IO[Post]
  def getAll: IO[List[Post]]
  def searchByUUID(uuid: UUID): IO[Option[Post]]

class PostsDatabaseImpl(docsDb: DocumentsDatabase) extends PostsDatabase:
  
  override def create(
    content: String, 
    link: Option[String], 
    tags: List[String],
    language: Option[String],
    images: List[UUID],
    targets: List[Target]
  ): IO[Post] =
    import io.circe.generic.auto.*
    
    val payload = Map(
      "content" -> content.asJson,
      "link" -> link.asJson,
      "tags" -> tags.asJson,
      "language" -> language.asJson,
      "images" -> images.map(_.toString).asJson
    ).asJson.noSpaces
    
    val docTags = targets.map(t => DocumentTag(t.toString.toLowerCase, "target"))
    
    docsDb.createOrUpdate("post", payload, docTags).map { doc =>
      Post(
        uuid = doc.uuid,
        content = content,
        link = link,
        tags = tags,
        language = language,
        images = images,
        targets = targets,
        createdAt = doc.createdAt
      )
    }
  
  override def getAll: IO[List[Post]] =
    docsDb.getAll("post", "created_at DESC").flatMap { docs =>
      docs.traverse(docToPost)
    }
  
  override def searchByUUID(uuid: UUID): IO[Option[Post]] =
    docsDb.searchByUUID(uuid).flatMap {
      case Some(doc) => docToPost(doc).map(Some(_))
      case None => IO.pure(None)
    }
  
  private def docToPost(doc: Document): IO[Post] =
    import io.circe.generic.auto.*
    
    IO.fromEither(parse(doc.payload)).flatMap { json =>
      IO.fromEither(
        for
          content <- json.hcursor.get[String]("content")
          link <- json.hcursor.get[Option[String]]("link")
          tags <- json.hcursor.get[Option[List[String]]]("tags")
          language <- json.hcursor.get[Option[String]]("language")
          imagesStr <- json.hcursor.get[Option[List[String]]]("images")
        yield
          val images = imagesStr.getOrElse(Nil).map(UUID.fromString)
          val targets = doc.tags
            .filter(_.kind == "target")
            .map(t => Target.valueOf(t.name.capitalize))
          
          Post(
            uuid = doc.uuid,
            content = content,
            link = link,
            tags = tags.getOrElse(Nil),
            language = language,
            images = images,
            targets = targets,
            createdAt = doc.createdAt
          )
      )
    }
