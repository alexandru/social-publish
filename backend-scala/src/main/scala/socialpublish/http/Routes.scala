package socialpublish.http

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.multipart.Multipart
import org.http4s.headers.{`Content-Type`, Location}
import org.typelevel.ci.CIStringSyntax
import socialpublish.api.{BlueskyApi, MastodonApi, TwitterApi}
import socialpublish.db.PostsDatabase
import socialpublish.models.*
import socialpublish.services.FilesService
import org.typelevel.log4cats.Logger
import java.util.UUID

class Routes(
  server: ServerConfig,
  auth: AuthMiddleware,
  bluesky: BlueskyApi,
  mastodon: MastodonApi,
  twitter: TwitterApi,
  files: FilesService,
  posts: PostsDatabase,
  logger: Logger[IO]
) {

  // Public routes (no authentication required)
  val publicRoutes: HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      // Health check
      case GET -> Root / "ping" =>
        Ok("pong")

      // RSS feeds
      case GET -> Root / "rss" =>
        generateRssFeed(None)

      case GET -> Root / "rss" / "target" / target =>
        Target.values.find(_.toString.toLowerCase == target.toLowerCase) match {
          case Some(t) => generateRssFeed(Some(t))
          case None => NotFound()
        }

      case GET -> Root / "rss" / UUIDVar(uuid) =>
        getRssItem(uuid)

      // File serving
      case GET -> Root / "files" / UUIDVar(uuid) =>
        files.getFile(uuid).flatMap {
          case Some(file) =>
            Ok(file.bytes)
              .map(_.withContentType(`Content-Type`(MediaType.unsafeParse(file.mimeType))))
          case None =>
            NotFound()
        }

      // Authentication
      case req @ POST -> Root / "api" / "login" =>
        auth.login(req)
    }

  // Protected routes (authentication required)
  val protectedRoutes: HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "api" / "protected" =>
        auth.protectedRoute(req)

      // Twitter OAuth routes
      case req @ GET -> Root / "api" / "twitter" / "authorize" =>
        auth.middleware.run(req).flatMap {
          case Some(authedReq) =>
            extractAccessToken(authedReq) match {
              case Some(token) =>
                twitter.getAuthorizationUrl(token).value.flatMap {
                  case Right(url) => Found(Location(Uri.unsafeFromString(url)))
                  case Left(error) => InternalServerError(error.message)
                }
              case None =>
                IO.pure(Response[IO](
                  Status.Unauthorized
                ).withEntity(Json.obj(
                  "error" -> Json.fromString("Unauthorized")
                )))
            }
          case None =>
            IO.pure(Response[IO](
              Status.Unauthorized
            ).withEntity(Json.obj(
              "error" -> Json.fromString("Unauthorized")
            )))
        }

      case req @ GET -> Root / "api" / "twitter" / "callback" =>
        auth.middleware.run(req).flatMap {
          case Some(authedReq) =>
            (authedReq.params.get("oauth_token"), authedReq.params.get("oauth_verifier")) match {
              case (Some(token), Some(verifier)) =>
                twitter.handleCallback(token, verifier).value.flatMap {
                  case Right(_) =>
                    Found(Location(Uri.unsafeFromString("/account")))
                      .map(_.withHeaders(
                        Header.Raw(
                          ci"Cache-Control",
                          "no-store, no-cache, must-revalidate, private"
                        ),
                        Header.Raw(ci"Pragma", "no-cache"),
                        Header.Raw(ci"Expires", "0")
                      ))
                  case Left(error) =>
                    InternalServerError(error.message)
                }
              case _ =>
                BadRequest("Missing oauth_token or oauth_verifier")
            }
          case None =>
            IO.pure(Response[IO](
              Status.Unauthorized
            ).withEntity(Json.obj("error" -> Json.fromString("Unauthorized"))))
        }

      case req @ GET -> Root / "api" / "twitter" / "status" =>
        auth.middleware.run(req).flatMap {
          case Some(_) =>
            twitter.getAuthStatus.flatMap { createdAt =>
              Ok(Json.obj(
                "hasAuthorization" -> Json.fromBoolean(createdAt.isDefined),
                "createdAt" -> Json.fromLong(createdAt.getOrElse(0L))
              ))
            }
          case None =>
            IO.pure(Response[IO](
              Status.Unauthorized
            ).withEntity(Json.obj("error" -> Json.fromString("Unauthorized"))))
        }

      // Social media posting routes
      case req @ POST -> Root / "api" / "bluesky" / "post" =>
        handleAuthenticatedPost(req, bluesky.createPost)

      case req @ POST -> Root / "api" / "mastodon" / "post" =>
        handleAuthenticatedPost(req, mastodon.createPost)

      case req @ POST -> Root / "api" / "twitter" / "post" =>
        handleAuthenticatedPost(req, twitter.createPost)

      case req @ POST -> Root / "api" / "rss" / "post" =>
        handleAuthenticatedPost(req, createRssPost)

      // Multi-target posting
      case req @ POST -> Root / "api" / "multiple" / "post" =>
        auth.middleware.run(req).flatMap {
          case Some(authedReq) =>
            handleMultiplePost(authedReq)
          case None =>
            IO.pure(Response[IO](
              Status.Unauthorized
            ).withEntity(Json.obj("error" -> Json.fromString("Unauthorized"))))
        }

      // File upload
      case req @ POST -> Root / "api" / "files" / "upload" =>
        auth.middleware.run(req).flatMap {
          case Some(authedReq) =>
            handleFileUpload(authedReq)
          case None =>
            IO.pure(Response[IO](
              Status.Unauthorized
            ).withEntity(Json.obj("error" -> Json.fromString("Unauthorized"))))
        }
    }

  val routes: HttpRoutes[IO] = publicRoutes <+> protectedRoutes

  private def handleAuthenticatedPost(
    req: Request[IO],
    handler: NewPostRequest => Result[NewPostResponse]
  ): IO[Response[IO]] =
    auth.middleware.run(req).flatMap {
      case Some(authedReq) =>
        authedReq.as[NewPostRequest].flatMap { postReq =>
          handler(postReq).value.flatMap {
            case Right(response) => Ok(response.asJson)
            case Left(error) =>
              Status.fromInt(error.status) match {
                case Right(status) => IO.pure(Response[IO](
                    status
                  ).withEntity(Json.obj("error" -> Json.fromString(error.message))))
                case Left(_) => InternalServerError(error.message)
              }
          }
        }.handleErrorWith { err =>
          logger.error(err)("Failed to handle post") *>
            InternalServerError(err.getMessage)
        }
      case None =>
        IO.pure(Response[IO](
          Status.Unauthorized
        ).withEntity(Json.obj("error" -> Json.fromString("Unauthorized"))))
    }

  private def createRssPost(request: NewPostRequest): Result[NewPostResponse] = {
    val content =
      if request.cleanupHtml.getOrElse(
        false
      ) then socialpublish.utils.TextUtils.convertHtml(request.content)
      else
        request.content

    val tags = extractHashtags(request.content)

    for {
      post <- Result.liftIO(
        posts.create(
          content = content,
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

  private def handleMultiplePost(req: Request[IO]): IO[Response[IO]] =
    req.as[NewPostRequest].flatMap { postReq =>
      val handlers: List[(String, Result[NewPostResponse])] = List(
        "rss" -> createRssPost(postReq)
      ) ++ (postReq.targets.getOrElse(Nil).map {
        case Target.Mastodon => "mastodon" -> mastodon.createPost(postReq)
        case Target.Bluesky => "bluesky" -> bluesky.createPost(postReq)
        case Target.Twitter => "twitter" -> twitter.createPost(postReq)
      })

      handlers.traverse { case (name, result) =>
        result.value.map(either => (name, either))
      }.flatMap { results =>
        val errors = results.collect { case (name, Left(err)) => (name, err) }

        if errors.nonEmpty then {
          val errorModules = errors.map(_._1).mkString(", ")
          val status = errors.map(_._2.status).max
          Status.fromInt(status) match {
            case Right(s) =>
              IO.pure(Response[IO](s).withEntity(Json.obj(
                "error" -> Json.fromString(s"Failed to create post via $errorModules")
              )))
            case Left(_) => InternalServerError(s"Failed to create post via $errorModules")
          }
        } else {
          val successMap = results.collect { case (name, Right(resp)) =>
            name -> resp.asJson
          }.toMap
          Ok(Json.obj(successMap.toSeq*))
        }
      }
    }.handleErrorWith { err =>
      logger.error(err)("Failed to handle multiple post") *>
        BadRequest(Json.obj("error" -> Json.fromString(err.getMessage)))
    }

  private def handleFileUpload(req: Request[IO]): IO[Response[IO]] =
    req.decode[Multipart[IO]] { multipart =>
      multipart.parts.toList.traverse { part =>
        part.name match {
          case Some("files") =>
            part.headers.get[`Content-Type`] match {
              case Some(ct) =>
                part.body.compile.toVector.flatMap { bytes =>
                  val filename = part.filename.getOrElse("upload")
                  val mimeType = ct.mediaType.toString
                  val altText = None // Would extract from other parts if present

                  files.saveFile(filename, mimeType, bytes.toArray, altText).map { metadata =>
                    Some(Json.obj(
                      "uuid" -> Json.fromString(metadata.uuid.toString),
                      "filename" -> Json.fromString(metadata.originalName)
                    ))
                  }
                }
              case None =>
                IO.pure(None)
            }
          case _ =>
            IO.pure(None)
        }
      }.flatMap { results =>
        val uploads = results.flatten
        Ok(Json.obj("uploads" -> Json.arr(uploads*)))
      }
    }.handleErrorWith { err =>
      logger.error(err)("Failed to upload files") *>
        BadRequest(Json.obj("error" -> Json.fromString(err.getMessage)))
    }

  private def generateRssFeed(targetFilter: Option[Target]): IO[Response[IO]] =
    posts.getAll.flatMap { allPosts =>
      val filtered = targetFilter match {
        case Some(target) => allPosts.filter(_.targets.contains(target))
        case None => allPosts
      }

      val rssXml = buildRssFeed(filtered, targetFilter)
      Ok(rssXml).map(_.withContentType(`Content-Type`(MediaType.application.`rss+xml`)))
    }

  private def getRssItem(uuid: UUID): IO[Response[IO]] =
    posts.searchByUUID(uuid).flatMap {
      case Some(post) =>
        val itemXml = buildRssItem(post)
        Ok(itemXml).map(_.withContentType(`Content-Type`(MediaType.application.xml)))
      case None =>
        NotFound()
    }

  private def buildRssFeed(posts: List[Post], targetFilter: Option[Target]): String = {
    val items = posts.map(buildRssItem).mkString("\n")

    val title = targetFilter match {
      case Some(target) => s"Social Publish Feed - ${target.toString}"
      case None => "Social Publish Feed"
    }

    s"""<?xml version="1.0" encoding="UTF-8"?>
      |<rss version="2.0">
      |  <channel>
      |    <title>$title</title>
      |    <link>${server.baseUrl}/rss</link>
      |    <description>Social media posts</description>
      |    $items
      |  </channel>
      |</rss>""".stripMargin
  }

  private def buildRssItem(post: Post): String = {
    val content = escapeXml(post.content)
    val link = post.link.map(l => s"<link>${escapeXml(l)}</link>").getOrElse("")
    val guid = s"<guid>${post.uuid}</guid>"
    val pubDate = s"<pubDate>${post.createdAt}</pubDate>"

    s"""<item>
        |  <title>$content</title>
        |  $link
        |  $guid
        |  $pubDate
        |</item>""".stripMargin
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

  private def extractAccessToken(req: Request[IO]): Option[String] =
    req.uri.query.params.get("access_token")
      .orElse(req.headers.get(ci"Authorization").flatMap { header =>
        val value = header.head.value
        if value.startsWith("Bearer ") then Some(value.substring(7)) else None
      })

}
