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
import sttp.model.Part
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.swagger.bundle.SwaggerInterpreter

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

  private val authInput = sttp.tapir.auth.bearer[String]().map(AuthInputs.apply)(_.token)

  private val secureEndpoint: Endpoint[AuthInputs, Unit, ErrorOut, Unit, Any] =
    endpoint.securityIn(authInput).errorOut(errorOutput)

  private def apiEndpoints: List[ServerEndpoint[Any, IO]] =
    publicEndpoints ++ protectedEndpoints

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

  private val pingEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("ping")
      .out(stringBody)
      .serverLogicSuccess(_ => IO.pure("pong"))

  private val rssEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("rss")
      .out(header[String]("Content-Type"))
      .out(stringBody)
      .serverLogicSuccess(_ =>
        generateRssFeed(None).map { rssXml =>
          (rssContentType.toString(), rssXml)
        }
      )

  private val rssTargetEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("rss" / "target" / path[String]("target"))
      .out(header[String]("Content-Type"))
      .out(stringBody)
      .errorOut(errorOutput)
      .serverLogic { target =>
        Target.values
          .find(_.toString.equalsIgnoreCase(target))
          .map(targetValue =>
            generateRssFeed(Some(targetValue)).map { rssXml =>
              Right((rssContentType.toString(), rssXml))
            }
          )
          .getOrElse(IO.pure(Left((StatusCode.NotFound, ErrorResponse("Target not found")))))
      }

  private val rssItemEndpoint: ServerEndpoint[Any, IO] =
    endpoint.get
      .in("rss" / path[UUID]("uuid"))
      .out(header[String]("Content-Type"))
      .out(stringBody)
      .errorOut(errorOutput)
      .serverLogic { uuid =>
        getRssItem(uuid).map {
          case Some(item) => Right((MediaType.ApplicationXml.toString(), item))
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
      .in(multipartBody[FileUploadForm])
      .out(jsonBody[FileUploadResponse])
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
        request.targets.getOrElse(Nil).map {
          case Target.Mastodon => "mastodon" -> mastodon.createPost(request)
          case Target.Bluesky => "bluesky" -> bluesky.createPost(request)
          case Target.Twitter => "twitter" -> twitter.createPost(request)
        }

    handlers.traverse { case (name, result) =>
      result.value.map(either => (name, either))
    }.map { results =>
      val errors = results.collect { case (name, Left(error)) => (name, error) }
      if errors.nonEmpty then {
        val errorModules = errors.map(_._1).mkString(", ")
        val status = errors.map(_._2.status).max
        Left((StatusCode(status), ErrorResponse(s"Failed to create post via $errorModules")))
      } else {
        val payload = results.collect { case (name, Right(response)) => name -> response }.toMap
        Right(MultiPostResponse(payload))
      }
    }
  }

  private def handleFileUpload(form: FileUploadForm): IO[Either[ErrorOut, FileUploadResponse]] = {
    val uploads = form.files.toList.collect {
      case part if part.name == "files" => part
    }

    uploads.traverse { part =>
      part.contentType match {
        case Some(contentType) =>
          val filename = part.fileName.getOrElse("upload")
          files.saveFile(filename, contentType.toString, part.body, None).map { metadata =>
            FileUploadItem(metadata.uuid, metadata.originalName)
          }
        case None =>
          IO.raiseError(new IllegalArgumentException("Missing content type"))
      }
    }.map(items => Right(FileUploadResponse(items))).handleErrorWith { err =>
      logger.error(err)("Failed to upload files") *>
        IO.pure(Left((StatusCode.BadRequest, ErrorResponse(err.getMessage))))
    }
  }

  private def createRssPost(request: NewPostRequest): Result[NewPostResponse] = {
    val content =
      if request.cleanupHtml.getOrElse(false) then {
        socialpublish.utils.TextUtils.convertHtml(request.content)
      } else {
        request.content
      }

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

  private def generateRssFeed(targetFilter: Option[Target]): IO[String] =
    posts.getAll.map { allPosts =>
      val filtered = targetFilter match {
        case Some(target) => allPosts.filter(_.targets.contains(target))
        case None => allPosts
      }
      buildRssFeed(filtered, targetFilter)
    }

  private def getRssItem(uuid: UUID): IO[Option[String]] =
    posts.searchByUUID(uuid).map {
      case Some(post) => Some(buildRssItem(post))
      case None => None
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

  private def toErrorOutput(error: ApiError): ErrorOut =
    (StatusCode(error.status), ErrorResponse(error.message))

}
