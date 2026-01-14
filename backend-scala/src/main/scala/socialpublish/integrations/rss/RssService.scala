package socialpublish.integrations.rss

import cats.effect.IO
import socialpublish.db.PostsDatabase
import socialpublish.http.ServerConfig
import socialpublish.models.{NewPostRequest, NewPostResponse, Post, Result, Target}
import socialpublish.services.FilesService
import socialpublish.utils.TextUtils

import java.util.UUID
import cats.syntax.all.*

trait RssService {
  def createPost(request: NewPostRequest): Result[NewPostResponse]
  def generateFeed(
    targetFilter: Option[Target],
    linkFilter: Option[RssService.FilterMode],
    imageFilter: Option[RssService.FilterMode]
  ): IO[String]
  def getItem(uuid: UUID): IO[Option[Post]]
}

object RssService {
  enum FilterMode {
    case Include, Exclude
  }
}

class RssServiceImpl(
  server: ServerConfig,
  posts: PostsDatabase,
  files: FilesService
) extends RssService {

  import RssService.*

  override def createPost(request: NewPostRequest): Result[NewPostResponse] = {
    val contentStr =
      if request.cleanupHtml.getOrElse(false) then {
        TextUtils.convertHtml(request.content.value)
      } else {
        request.content.value
      }

    val tags = extractHashtags(request.content.value)

    for {
      post <- Result.liftIO(
        posts.create(
          content = contentStr,
          link = request.link,
          tags = tags,
          language = request.language,
          images = request.images.getOrElse(Nil),
          targets = request.targets.getOrElse(Nil)
        )
      )
      uri = s"${server.baseUrl}/rss/${post.uuid}"
    } yield NewPostResponse.Rss(uri)
  }

  override def generateFeed(
    targetFilter: Option[Target],
    linkFilter: Option[FilterMode],
    imageFilter: Option[FilterMode]
  ): IO[String] =
    posts.getAll.flatMap { allPosts =>
      val filtered = allPosts.filter { post =>
        val targetOk = targetFilter.forall(post.targets.contains)
        val linkOk = linkFilter match {
          case Some(FilterMode.Include) => post.link.nonEmpty
          case Some(FilterMode.Exclude) => post.link.isEmpty
          case None => true
        }
        val imageOk = imageFilter match {
          case Some(FilterMode.Include) => post.images.nonEmpty
          case Some(FilterMode.Exclude) => post.images.isEmpty
          case None => true
        }
        targetOk && linkOk && imageOk
      }
      buildRssFeed(filtered, targetFilter)
    }

  override def getItem(uuid: UUID): IO[Option[Post]] =
    posts.searchByUUID(uuid)

  private def buildRssFeed(posts: List[Post], targetFilter: Option[Target]): IO[String] = {
    val baseTitle = server.baseUrl.replaceFirst("^https?://", "")
    val title = targetFilter match {
      case Some(target) => s"Feed of $baseTitle - ${target.toString.toLowerCase}"
      case None => s"Feed of $baseTitle"
    }

    posts.traverse(buildRssItem).map { items =>
      val itemsXml = items.mkString("\n")
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
         |  <channel>
         |    <title>$title</title>
         |    <link>${server.baseUrl}/rss</link>
         |    <description>Social media posts</description>
         |    $itemsXml
         |  </channel>
         |</rss>""".stripMargin
    }
  }

  private def buildRssItem(post: Post): IO[String] = {
    val content = escapeXml(post.content)
    val linkValue = post.link.getOrElse(s"${server.baseUrl}/rss/${post.uuid}")
    val link = s"<link>${escapeXml(linkValue)}</link>"
    val guid = s"<guid>${post.uuid}</guid>"
    val pubDate = s"<pubDate>${post.createdAt}</pubDate>"
    val description = s"<description>$content</description>"
    val categories = (post.tags ++ post.targets.map(_.toString.toLowerCase))
      .map(tag => s"<category>${escapeXml(tag)}</category>")
      .mkString("\n")

    post.images.traverse(buildMediaElement).map { mediaElements =>
      val mediaXml = mediaElements.flatten.mkString("\n")
      s"""<item>
         |  <title>$content</title>
         |  $description
         |  $categories
         |  $link
         |  $guid
         |  $pubDate
         |  $mediaXml
         |</item>""".stripMargin
    }
  }

  private def buildMediaElement(uuid: UUID): IO[Option[String]] =
    files.getFileMetadata(uuid).map {
      case Some(metadata) =>
        val description = metadata.altText
          .map(text => s"<media:description>${escapeXml(text)}</media:description>")
          .getOrElse("")
        Some(
          s"""<media:content url="${server.baseUrl}/files/${metadata.uuid}" fileSize="${metadata.size}" type="${metadata.mimeType}">
             |  <media:rating scheme="urn:simple">nonadult</media:rating>
             |  $description
             |</media:content>""".stripMargin
        )
      case None => None
    }

  private def escapeXml(text: String): String =
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")

  private def extractHashtags(text: String): List[String] = {
    val pattern = """(?:^|\s)#(\w+)""".r
    pattern.findAllMatchIn(text).map(_.group(1)).toList
  }
}
