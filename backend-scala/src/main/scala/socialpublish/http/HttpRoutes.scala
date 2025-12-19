package socialpublish.http

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.headers.*
import org.http4s.server.middleware.Logger as ServerLogger
import org.typelevel.log4cats.Logger
import pdi.jwt.{JwtCirce, JwtAlgorithm, JwtClaim}
import socialpublish.api.{BlueskyApi, MastodonApi, TwitterApi}
import socialpublish.config.AppConfig
import socialpublish.db.PostsDatabase
import socialpublish.models.*
import socialpublish.services.FilesService
import java.time.Instant
import java.util.UUID
import java.util.Base64

class HttpRoutes(
  config: AppConfig,
  bluesky: BlueskyApi,
  mastodon: MastodonApi,
  twitter: TwitterApi,
  files: FilesService,
  posts: PostsDatabase,
  logger: Logger[IO]
):
  
  private given EntityDecoder[IO, NewPostRequest] = jsonOf[IO, NewPostRequest]
  
  // Authentication middleware
  private def authenticated(routes: AuthedRoutes[String, IO]): HttpRoutes[IO] =
    val authUser: Kleisli[IO, Request[IO], Either[String, String]] = Kleisli { request =>
      // Try JWT from Authorization Bearer header
      request.headers.get(ci"Authorization").flatMap { header =>
        val value = header.head.value
        if value.startsWith("Bearer ") then
          val token = value.substring(7)
          verifyJwt(token)
        else None
      }
      // Try Basic Auth
      .orElse {
        request.headers.get(ci"Authorization").flatMap { header =>
          val value = header.head.value
          if value.startsWith("Basic ") then
            val decoded = new String(Base64.getDecoder.decode(value.substring(6)))
            val parts = decoded.split(":", 2)
            if parts.length == 2 && 
               parts(0) == config.serverAuthUsername && 
               parts(1) == config.serverAuthPassword then
              Some(parts(0))
            else None
          else None
        }
      } match
        case Some(user) => IO.pure(Right(user))
        case None => IO.pure(Left("Unauthorized"))
    }
    
    org.http4s.server.AuthMiddleware(authUser)(routes)
  
  private def verifyJwt(token: String): Option[String] =
    JwtCirce.decode(token, config.serverAuthJwtSecret, Seq(JwtAlgorithm.HS256)).toOption
      .flatMap(_.subject)
  
  private def generateJwt(username: String): String =
    val claim = JwtClaim(
      subject = Some(username),
      issuedAt = Some(Instant.now.getEpochSecond),
      expiration = Some(Instant.now.plusSeconds(86400 * 7).getEpochSecond) // 7 days
    )
    JwtCirce.encode(claim, config.serverAuthJwtSecret, JwtAlgorithm.HS256)
  
  // Public routes
  val publicRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // Health checks
    case GET -> Root / "ping" =>
      Ok("pong")
    
    // Login
    case req @ POST -> Root / "api" / "login" =>
      req.headers.get(ci"Authorization") match
        case Some(header) if header.head.value.startsWith("Basic ") =>
          val decoded = new String(Base64.getDecoder.decode(header.head.value.substring(6)))
          val parts = decoded.split(":", 2)
          if parts.length == 2 && 
             parts(0) == config.serverAuthUsername && 
             parts(1) == config.serverAuthPassword then
            val token = generateJwt(parts(0))
            Ok(Json.obj("token" -> Json.fromString(token)))
          else
            Unauthorized("Invalid credentials")
        case _ =>
          Unauthorized("Missing credentials")
    
    // RSS feed (public for now)
    case GET -> Root / "rss" =>
      generateRssFeed(None)
    
    case GET -> Root / "rss" / "target" / target =>
      generateRssFeed(Some(target))
    
    case GET -> Root / "rss" / UUIDVar(uuid) =>
      getRssItem(uuid)
    
    // Serve uploaded files
    case GET -> Root / "files" / UUIDVar(uuid) =>
      serveFile(uuid)
  }
  
  // Authenticated routes
  val authedRoutes: AuthedRoutes[String, IO] = AuthedRoutes.of {
    // Protected test endpoint
    case GET -> Root / "api" / "protected" as user =>
      Ok(Json.obj("user" -> Json.fromString(user), "message" -> Json.fromString("Success")))
    
    // Bluesky posting
    case req @ POST -> Root / "api" / "bluesky" / "post" as _ =>
      handlePostRequest(req.req, bluesky.createPost)
    
    // Mastodon posting
    case req @ POST -> Root / "api" / "mastodon" / "post" as _ =>
      handlePostRequest(req.req, mastodon.createPost)
    
    // Twitter posting
    case req @ POST -> Root / "api" / "twitter" / "post" as _ =>
      handlePostRequest(req.req, twitter.createPost)
    
    // Multiple targets posting
    case req @ POST -> Root / "api" / "multiple" / "post" as _ =>
      handleMultiplePost(req.req)
    
    // RSS posting (saves to database for RSS feed)
    case req @ POST -> Root / "api" / "rss" / "post" as _ =>
      handleRssPost(req.req)
    
    // Twitter OAuth
    case GET -> Root / "api" / "twitter" / "authorize" as _ =>
      // Would need to extract access token from cookies
      twitter.getAuthorizationUrl("stub-token").value.flatMap {
        case Right(url) => Found(Location(Uri.unsafeFromString(url)))
        case Left(err) => InternalServerError(err.message)
      }
    
    case GET -> Root / "api" / "twitter" / "callback" :? OAuthTokenMatcher(token) +& OAuthVerifierMatcher(verifier) as _ =>
      twitter.handleCallback(token, verifier).value.flatMap {
        case Right(_) => Found(Location(Uri.unsafeFromString("/account")))
        case Left(err) => InternalServerError(err.message)
      }
    
    case GET -> Root / "api" / "twitter" / "status" as _ =>
      Ok(Json.obj("connected" -> Json.fromBoolean(false)))
  }
  
  private object OAuthTokenMatcher extends QueryParamDecoderMatcher[String]("oauth_token")
  private object OAuthVerifierMatcher extends QueryParamDecoderMatcher[String]("oauth_verifier")
  
  private def handlePostRequest(
    req: Request[IO],
    createPost: NewPostRequest => Result[NewPostResponse]
  ): IO[Response[IO]] =
    req.as[NewPostRequest].flatMap { postReq =>
      createPost(postReq).value.flatMap {
        case Right(response) => Ok(response.asJson)
        case Left(error) => Status(error.status)(Json.obj(
          "error" -> Json.fromString(error.message),
          "module" -> Json.fromString(error.module)
        ))
      }
    }.handleErrorWith { err =>
      logger.error(err)("Failed to handle post request") *>
      InternalServerError(err.getMessage)
    }
  
  private def handleMultiplePost(req: Request[IO]): IO[Response[IO]] =
    req.as[NewPostRequest].flatMap { postReq =>
      val targets = postReq.targets.getOrElse(List(Target.Mastodon, Target.Bluesky))
      
      val results = targets.traverse { target =>
        val api = target match
          case Target.Bluesky => bluesky.createPost(postReq)
          case Target.Mastodon => mastodon.createPost(postReq)
          case Target.Twitter => twitter.createPost(postReq)
          case Target.LinkedIn => Result.error(ApiError.validationError("LinkedIn not supported"))
        
        api.value.map(result => (target, result))
      }
      
      results.flatMap { results =>
        val successes = results.collect { case (t, Right(r)) => (t, r) }
        val failures = results.collect { case (t, Left(e)) => (t, e) }
        
        if failures.isEmpty then
          Ok(Json.obj("results" -> successes.asJson))
        else
          Status(207)(Json.obj( // Multi-Status
            "successes" -> successes.asJson,
            "failures" -> failures.map { case (t, e) => 
              Json.obj("target" -> t.asJson, "error" -> e.message.asJson)
            }.asJson
          ))
      }
    }
  
  private def handleRssPost(req: Request[IO]): IO[Response[IO]] =
    req.as[NewPostRequest].flatMap { postReq =>
      val uuid = UUID.randomUUID()
      val rssUrl = s"${config.baseUrl}/rss/$uuid"
      
      posts.create(
        content = postReq.content,
        link = postReq.link,
        tags = List.empty,
        language = postReq.language,
        images = postReq.images.getOrElse(Nil),
        targets = List(Target.LinkedIn) // RSS items are typically for LinkedIn via IFTTT
      ).flatMap { post =>
        Ok(NewPostResponse.Rss(rssUrl).asJson)
      }
    }.handleErrorWith { err =>
      logger.error(err)("Failed to create RSS post") *>
      InternalServerError(err.getMessage)
    }
  
  private def generateRssFeed(targetFilter: Option[String]): IO[Response[IO]] =
    posts.getAll.flatMap { allPosts =>
      val filtered = targetFilter match
        case Some(t) => allPosts.filter(_.targets.exists(_.toString.equalsIgnoreCase(t)))
        case None => allPosts
      
      val rssXml = buildRssFeed(filtered)
      Ok(rssXml).map(_.withContentType(`Content-Type`(MediaType.application.`rss+xml`)))
    }
  
  private def getRssItem(uuid: UUID): IO[Response[IO]] =
    posts.searchByUUID(uuid).flatMap {
      case Some(post) => Ok(buildRssItem(post))
      case None => NotFound()
    }
  
  private def buildRssFeed(posts: List[Post]): String =
    val items = posts.map(buildRssItem).mkString("\n")
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<rss version="2.0">
       |  <channel>
       |    <title>Social Publish Feed</title>
       |    <link>${config.baseUrl}/rss</link>
       |    <description>Social media posts</description>
       |    $items
       |  </channel>
       |</rss>""".stripMargin
  
  private def buildRssItem(post: Post): String =
    val description = escapeXml(post.content + post.link.map(l => s"\n\n$l").getOrElse(""))
    s"""<item>
       |  <title>${escapeXml(post.content.take(100))}</title>
       |  <link>${config.baseUrl}/rss/${post.uuid}</link>
       |  <guid isPermaLink="false">${post.uuid}</guid>
       |  <description>$description</description>
       |  <pubDate>${post.createdAt}</pubDate>
       |</item>""".stripMargin
  
  private def escapeXml(text: String): String =
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
  
  private def serveFile(uuid: UUID): IO[Response[IO]] =
    files.getFile(uuid).flatMap {
      case Some(file) =>
        Ok(file.bytes)
          .map(_.withContentType(`Content-Type`(MediaType.unsafeParse(file.mimeType))))
      case None =>
        NotFound()
    }
  
  // Combine all routes
  def routes: org.http4s.HttpRoutes[IO] =
    publicRoutes <+> authenticated(authedRoutes)
