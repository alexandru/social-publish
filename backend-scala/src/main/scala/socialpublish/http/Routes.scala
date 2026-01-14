package socialpublish.http

import cats.effect.*
import cats.syntax.all.*
import io.circe.Printer
import io.circe.syntax.*
import org.typelevel.log4cats.Logger
import socialpublish.integrations.bluesky.BlueskyApi
import socialpublish.integrations.mastodon.MastodonApi
import socialpublish.integrations.twitter.TwitterApi
import socialpublish.db.PostsDatabase
import socialpublish.models.*
import socialpublish.services.FilesService
import sttp.apispec.openapi.Server
import sttp.apispec.openapi.circe.*
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.*
import sttp.tapir.DecodeResult
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import java.nio.file.{Files as JavaFiles, Path, Paths}
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

  private type ErrorOut = (StatusCode, ErrorResponse)

  private val errorOutput: EndpointOutput[ErrorOut] = statusCode.and(jsonBody[ErrorResponse])

  private val authInput: EndpointInput[AuthInputs] =
    header[Option[String]]("Authorization")
      .and(query[Option[String]]("access_token"))
      .and(header[Option[String]]("Cookie"))
      .map { case (authHeader, queryToken, cookieHeader) =>
        AuthInputs(authHeader, queryToken, extractCookieToken(cookieHeader))
      }(inputs => (inputs.authHeader, inputs.accessTokenQuery, None))

  private val secureEndpoint: Endpoint[AuthInputs, Unit, ErrorOut, Unit, Any] =
    endpoint.securityIn(authInput).errorOut(errorOutput)

  private def apiEndpoints: List[ServerEndpoint[Any, IO]] =
    publicEndpoints ++ protectedEndpoints :+ staticFilesEndpoint

  private def swaggerEndpoints: List[ServerEndpoint[Any, IO]] =
    SwaggerInterpreter()
      .fromServerEndpoints[IO](apiEndpoints, "Social Publish API", "1.0.0")

  private def openApiJson: String = {
    val docs = OpenAPIDocsInterpreter()
      .toOpenAPI(apiEndpoints.map(_.endpoint), "Social Publish API", "1.0.0")
      .servers(List(Server(server.baseUrl)))
    Printer.spaces2.print(docs.asJson)
  }

  private def openApiEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("openapi")
      .out(header[String]("Content-Type"))
      .out(stringBody)
      .serverLogicSuccess(_ =>
        IO.pure(("application/json", openApiJson))
      )

  def endpoints: List[ServerEndpoint[Any, IO]] =
    apiEndpoints ++ swaggerEndpoints :+ openApiEndpoint

  private def publicEndpoints: List[ServerEndpoint[Any, IO]] =
    List(
      pingEndpoint,
      rssEndpoint,
      rssTargetEndpoint,
      rssItemEndpoint,
      fileEndpoint,
      loginEndpoint
    )

  private def protectedEndpoints: List[ServerEndpoint[Any, IO]] =
    List(
      protectedEndpoint,
      twitterAuthorizeEndpoint,
      twitterCallbackEndpoint,
      twitterStatusEndpoint,
      blueskyPostEndpoint,
      mastodonPostEndpoint,
      twitterPostEndpoint,
      rssPostEndpoint,
      multiplePostEndpoint,
      uploadFileEndpoint
    )

  private val rssContentType = MediaType.unsafeParse("application/rss+xml")

  private enum FilterMode {
    case Include, Exclude
  }

  private def parseFilter(value: Option[String]): Option[FilterMode] =
    value.flatMap {
      case mode if mode.equalsIgnoreCase("include") => Some(FilterMode.Include)
      case mode if mode.equalsIgnoreCase("exclude") => Some(FilterMode.Exclude)
      case _ => None
    }

  private val pingEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("ping")
      .out(stringBody)
      .serverLogicSuccess(_ => IO.pure("pong"))

  private val rssEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("rss")
      .in(query[Option[String]]("filterByLinks"))
      .in(query[Option[String]]("filterByImages"))
      .out(header[String]("Content-Type"))
      .out(stringBody)
      .serverLogicSuccess { case (filterLinks, filterImages) =>
        generateRssFeed(
          targetFilter = None,
          linkFilter = parseFilter(filterLinks),
          imageFilter = parseFilter(filterImages)
        ).map { rssXml =>
          (rssContentType.toString(), rssXml)
        }
      }

  private val rssTargetEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("rss" / "target" / path[String]("target"))
      .in(query[Option[String]]("filterByLinks"))
      .in(query[Option[String]]("filterByImages"))
      .out(header[String]("Content-Type"))
      .out(stringBody)
      .errorOut(errorOutput)
      .serverLogic { case (target, filterLinks, filterImages) =>
        Target.values
          .find(_.toString.equalsIgnoreCase(target))
          .map(targetValue =>
            generateRssFeed(
              targetFilter = Some(targetValue),
              linkFilter = parseFilter(filterLinks),
              imageFilter = parseFilter(filterImages)
            ).map { rssXml =>
              Right((rssContentType.toString(), rssXml))
            }
          )
          .getOrElse(IO.pure(Left((StatusCode.NotFound, ErrorResponse("Target not found")))))
      }

  private val rssItemEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("rss" / path[UUID]("uuid"))
      .out(jsonBody[Post])
      .errorOut(errorOutput)
      .serverLogic { uuid =>
        getRssItem(uuid).map {
          case Some(item) => Right(item)
          case None => Left((StatusCode.NotFound, ErrorResponse("RSS item not found")))
        }
      }

  private val fileEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("files" / path[UUID]("uuid"))
      .out(header[String]("Content-Type"))
      .out(byteArrayBody)
      .errorOut(errorOutput)
      .serverLogic { uuid =>
        files.getFile(uuid).map {
          case Some(file) =>
            Right((file.mimeType, file.bytes))
          case None =>
            Left((StatusCode.NotFound, ErrorResponse("File not found")))
        }
      }

  private val loginEndpoint: ServerEndpoint[Any, IO] =
    endpoint.post
      .in("api" / "login")
      .in(jsonBody[LoginRequest])
      .out(jsonBody[LoginResponse])
      .errorOut(errorOutput)
      .serverLogic { credentials =>
        auth.login(credentials).map(_.leftMap(toErrorOutput))
      }

  private val publicRoot = Paths.get("public").toAbsolutePath.normalize
  private val spaPaths = Set("login", "form", "account")
  private val reservedStaticPrefixes =
    Set("api", "rss", "files", "openapi", "docs", "swagger", "ping")

  private val staticPathInput: EndpointInput[List[String]] =
    paths.mapDecode { segments =>
      segments.headOption match {
        case Some(head) if reservedStaticPrefixes.contains(head) =>
          DecodeResult.Missing
        case _ =>
          DecodeResult.Value(segments)
      }
    }(identity)

  private val staticFilesEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in(staticPathInput)
      .out(header[String]("Content-Type"))
      .out(byteArrayBody)
      .errorOut(errorOutput)
      .serverLogic { segments =>
        serveStaticFile(segments)
      }

  private val protectedEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.get
      .in("api" / "protected")
      .out(jsonBody[ProtectedResponse])
      .serverSecurityLogic(authenticate)
      .serverLogicSuccess(context => _ => IO.pure(auth.protectedResponse(context.user)))

  private val twitterAuthorizeEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.get
      .in("api" / "twitter" / "authorize")
      .out(statusCode(StatusCode.Found))
      .out(header[String]("Location"))
      .serverSecurityLogic(authenticate)
      .serverLogic { context => _ =>
        twitter.getAuthorizationUrl(context.token).value.map {
          case Right(url) => Right(url)
          case Left(error) => Left(toErrorOutput(error))
        }
      }

  private val twitterCallbackEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.get
      .in("api" / "twitter" / "callback")
      .in(query[String]("oauth_token"))
      .in(query[String]("oauth_verifier"))
      .out(statusCode(StatusCode.Found))
      .out(headers)
      .serverSecurityLogic(authenticate)
      .serverLogic { _ => (token, verifier) =>
        twitter.handleCallback(token, verifier).value.map {
          case Right(_) =>
            val responseHeaders = List(
              Header("Location", "/account"),
              Header("Cache-Control", "no-store, no-cache, must-revalidate, private"),
              Header("Pragma", "no-cache"),
              Header("Expires", "0")
            )
            Right(responseHeaders)
          case Left(error) => Left(toErrorOutput(error))
        }
      }

  private val twitterStatusEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.get
      .in("api" / "twitter" / "status")
      .out(jsonBody[TwitterAuthStatusResponse])
      .serverSecurityLogic(authenticate)
      .serverLogicSuccess(_ =>
        _ =>
          twitter.getAuthStatus.map { createdAt =>
            TwitterAuthStatusResponse(
              hasAuthorization = createdAt.isDefined,
              createdAt = createdAt.getOrElse(0L)
            )
          }
      )

  private val blueskyPostEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.post
      .in("api" / "bluesky" / "post")
      .in(jsonBody[NewPostRequest])
      .out(jsonBody[NewPostResponse])
      .serverSecurityLogic(authenticate)
      .serverLogic { _ => request =>
        handleResult(bluesky.createPost(request))
      }

  private val mastodonPostEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.post
      .in("api" / "mastodon" / "post")
      .in(jsonBody[NewPostRequest])
      .out(jsonBody[NewPostResponse])
      .serverSecurityLogic(authenticate)
      .serverLogic { _ => request =>
        handleResult(mastodon.createPost(request))
      }

  private val twitterPostEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.post
      .in("api" / "twitter" / "post")
      .in(jsonBody[NewPostRequest])
      .out(jsonBody[NewPostResponse])
      .serverSecurityLogic(authenticate)
      .serverLogic { _ => request =>
        handleResult(twitter.createPost(request))
      }

  private val rssPostEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.post
      .in("api" / "rss" / "post")
      .in(jsonBody[NewPostRequest])
      .out(jsonBody[NewPostResponse])
      .serverSecurityLogic(authenticate)
      .serverLogic { _ => request =>
        handleResult(createRssPost(request))
      }

  private val multiplePostEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.post
      .in("api" / "multiple" / "post")
      .in(jsonBody[NewPostRequest])
      .out(jsonBody[MultiPostResponse])
      .serverSecurityLogic(authenticate)
      .serverLogic { _ => request =>
        handleMultiplePost(request)
      }

  private val uploadFileEndpoint: ServerEndpoint[Any, IO] =
    secureEndpoint.post
      .in("api" / "files" / "upload")
      .in(FileUploadForm.body)
      .out(jsonBody[FileMetadata])
      .serverSecurityLogic(authenticate)
      .serverLogic { _ => form =>
        handleFileUpload(form)
      }

  private def authenticate(inputs: AuthInputs): IO[Either[ErrorOut, AuthContext]] =
    auth.authenticate(inputs).map(_.leftMap(toErrorOutput))

  private def handleResult[A](result: Result[A]): IO[Either[ErrorOut, A]] =
    result.value.map(_.leftMap(toErrorOutput))

  private def handleMultiplePost(request: NewPostRequest)
    : IO[Either[ErrorOut, MultiPostResponse]] = {
    val handlers: List[(String, Result[NewPostResponse])] =
      List("rss" -> createRssPost(request)) ++
        request.targets.getOrElse(Nil).flatMap {
          case Target.Mastodon => Some("mastodon" -> mastodon.createPost(request))
          case Target.Bluesky => Some("bluesky" -> bluesky.createPost(request))
          case Target.Twitter => Some("twitter" -> twitter.createPost(request))
          case Target.LinkedIn => None
        }

    handlers.traverse { case (name, result) =>
      result.value.map(either => (name, either))
    }.map { results =>
      // Collect errors but also keep successes
      val errors = results.collect { case (name, Left(error)) => (name, error) }
      val successes = results.collect { case (name, Right(response)) => name -> response }.toMap
      
      if errors.nonEmpty && successes.isEmpty then {
        // If ALL failed, return error
        val errorModules = errors.map(_._1).mkString(", ")
        val status = errors.map(_._2.status).max
        Left((StatusCode(status), ErrorResponse(s"Failed to create post via $errorModules")))
      } else {
        // If at least one succeeded (or mixed results), return success with what worked
        // Ideally we would return partial failure info, but the current MultiPostResponse 
        // structure is just Map[String, NewPostResponse]. 
        // For now, we return what succeeded.
        Right(MultiPostResponse(successes))
      }
    }
  }

  private def handleFileUpload(form: FileUploadForm): IO[Either[ErrorOut, FileMetadata]] = {
    val part = form.file
    val allowedTypes = Set("image/png", "image/jpeg")

    part.contentType match {
      case Some(contentType) if allowedTypes.contains(contentType.toString.toLowerCase) =>
        val filename = part.fileName.getOrElse("upload")
        files
          .saveFile(filename, contentType.toString, part.body, form.altText)
          .map(Right(_))
          .handleErrorWith { err =>
            logger.error(err)("Failed to upload file") *>
              IO.pure(Left((StatusCode.BadRequest, ErrorResponse(err.getMessage))))
          }
      case Some(contentType) =>
        IO.pure(Left((
          StatusCode.BadRequest,
          ErrorResponse(s"Unsupported content type: ${contentType.toString}")
        )))
      case None =>
        IO.pure(Left((StatusCode.BadRequest, ErrorResponse("Missing content type"))))
    }
  }

  private def serveStaticFile(segments: List[String]): IO[Either[ErrorOut, (String, Array[Byte])]] =
    resolveStaticPath(segments).flatMap {
      case None =>
        IO.pure(Left((StatusCode.NotFound, ErrorResponse("File not found"))))
      case Some(path) =>
        IO.blocking {
          if !JavaFiles.exists(path) || JavaFiles.isDirectory(path) then {
            Left((StatusCode.NotFound, ErrorResponse("File not found")))
          } else {
            val bytes = JavaFiles.readAllBytes(path)
            val contentType = Option(JavaFiles.probeContentType(path))
              .getOrElse("application/octet-stream")
            Right((contentType, bytes))
          }
        }
    }

  private def resolveStaticPath(segments: List[String]): IO[Option[Path]] =
    IO.blocking {
      val normalizedSegments = segments.filter(_.nonEmpty)
      val target = normalizedSegments.headOption match {
        case None => publicRoot.resolve("index.html")
        case Some(head) if spaPaths.contains(head) => publicRoot.resolve("index.html")
        case _ => publicRoot.resolve(normalizedSegments.mkString("/"))
      }
      val normalized = target.normalize
      if normalized.startsWith(publicRoot) then Some(normalized) else None
    }

  private def createRssPost(request: NewPostRequest): Result[NewPostResponse] = {
    val contentStr =
      if request.cleanupHtml.getOrElse(false) then {
        socialpublish.utils.TextUtils.convertHtml(request.content.value)
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

  private def generateRssFeed(
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

  private def getRssItem(uuid: UUID): IO[Option[Post]] =
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

  private def extractCookieToken(headerValue: Option[String]): Option[String] =
    headerValue.flatMap { header =>
      header.split(';').toList.map(_.trim).collectFirst {
        case entry if entry.startsWith("access_token=") =>
          entry.stripPrefix("access_token=")
      }
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

  private def toErrorOutput(error: ApiError): ErrorOut =
    (StatusCode(error.status), ErrorResponse(error.message))

}
