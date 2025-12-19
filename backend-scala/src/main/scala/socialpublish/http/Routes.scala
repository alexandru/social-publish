package socialpublish.http

import cats.data.Kleisli
import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import socialpublish.api.{BlueskyApi, MastodonApi, TwitterApi}
import socialpublish.config.AppConfig
import socialpublish.db.PostsDatabase
import socialpublish.models.*
import socialpublish.services.FilesService
import org.typelevel.log4cats.Logger
import java.util.UUID

// Simplified Routes implementation
class Routes(
  config: AppConfig,
  bluesky: BlueskyApi,
  mastodon: MastodonApi,
  twitter: TwitterApi,
  files: FilesService,
  posts: PostsDatabase,
  logger: Logger[IO]
):
  
  private given EntityDecoder[IO, NewPostRequest] = jsonOf[IO, NewPostRequest]
  
  val routes: org.http4s.HttpRoutes[IO] = org.http4s.HttpRoutes.of[IO] {
    // Health check
    case GET -> Root / "ping" =>
      Ok("pong")
    
    // Bluesky posting (simplified - no auth for now)
    case req @ POST -> Root / "api" / "bluesky" / "post" =>
      req.as[NewPostRequest].flatMap { postReq =>
        bluesky.createPost(postReq).value.flatMap {
          case Right(response) => Ok(response.asJson)
          case Left(error) => InternalServerError(error.message)
        }
      }.handleErrorWith { err =>
        logger.error(err)("Failed to handle post") *>
        InternalServerError(err.getMessage)
      }
    
    // Mastodon posting (simplified - no auth for now)
    case req @ POST -> Root / "api" / "mastodon" / "post" =>
      req.as[NewPostRequest].flatMap { postReq =>
        mastodon.createPost(postReq).value.flatMap {
          case Right(response) => Ok(response.asJson)
          case Left(error) => InternalServerError(error.message)
        }
      }.handleErrorWith { err =>
        logger.error(err)("Failed to handle post") *>
        InternalServerError(err.getMessage)
      }
    
    // RSS feed
    case GET -> Root / "rss" =>
      posts.getAll.flatMap { allPosts =>
        val rssXml = buildRssFeed(allPosts)
        Ok(rssXml)
      }
    
    // File serving
    case GET -> Root / "files" / UUIDVar(uuid) =>
      files.getFile(uuid).flatMap {
        case Some(file) => Ok(file.bytes)
        case None => NotFound()
      }
  }
  
  private def buildRssFeed(posts: List[Post]): String =
    val items = posts.map { post =>
      val content = escapeXml(post.content)
      s"""<item>
         |  <title>$content</title>
         |  <guid>${post.uuid}</guid>
         |  <pubDate>${post.createdAt}</pubDate>
         |</item>""".stripMargin
    }.mkString("\n")
    
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<rss version="2.0">
       |  <channel>
       |    <title>Social Publish Feed</title>
       |    <link>${config.baseUrl}/rss</link>
       |    <description>Social media posts</description>
       |    $items
       |  </channel>
       |</rss>""".stripMargin
  
  private def escapeXml(text: String): String =
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
