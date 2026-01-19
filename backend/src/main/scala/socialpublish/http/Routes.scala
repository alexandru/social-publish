package socialpublish.http

import cats.data.OptionT
import cats.effect.*
import cats.mtl.Handle
import cats.syntax.all.*
import io.circe.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.headers.Location
import org.http4s.multipart.Multipart
import org.typelevel.log4cats.Logger
import socialpublish.integrations.bluesky.BlueskyApi
import socialpublish.integrations.mastodon.MastodonApi
import socialpublish.integrations.rss.RssService
import socialpublish.integrations.twitter.TwitterApi
import socialpublish.models.*
import socialpublish.services.FilesService

import java.nio.file.{Files as JavaFiles, Path, Paths}

class Routes(
  auth: AuthMiddleware,
  bluesky: BlueskyApi,
  mastodon: MastodonApi,
  twitter: TwitterApi,
  rss: RssService,
  files: FilesService,
  logger: Logger[IO]
) {

  private val apiErrorHandle = Handle[IO, ApiError]

  private val rssMediaType = new MediaType("application", "rss+xml")

  val httpRoutes: HttpRoutes[IO] = publicRoutes <+> protectedRoutes <+> staticRoutes

  private def publicRoutes: HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "ping" =>
        Ok("pong")

      case GET -> Root / "rss" :? FilterByLinksParam(filterLinks) +& FilterByImagesParam(
            filterImages
          ) =>
        rss
          .generateFeed(
            targetFilter = None,
            linkFilter = parseFilter(filterLinks),
            imageFilter = parseFilter(filterImages)
          )
          .flatMap { rssXml =>
            Ok(rssXml).map(_.withContentType(`Content-Type`(rssMediaType)))
          }

      case GET -> Root / "rss" / "target" / target :? FilterByLinksParam(
            filterLinks
          ) +& FilterByImagesParam(
            filterImages
          ) =>
        Target.values
          .find(_.toString.equalsIgnoreCase(target))
          .map { targetValue =>
            rss
              .generateFeed(
                targetFilter = Some(targetValue),
                linkFilter = parseFilter(filterLinks),
                imageFilter = parseFilter(filterImages)
              )
              .flatMap { rssXml =>
                Ok(rssXml).map(_.withContentType(`Content-Type`(rssMediaType)))
              }
          }
          .getOrElse(NotFound(ErrorResponse("Target not found")))

      case GET -> Root / "rss" / UUIDVar(uuid) =>
        rss.getItem(uuid).flatMap {
          case Some(item) => Ok(item)
          case None => NotFound(ErrorResponse("RSS item not found"))
        }

      case GET -> Root / "files" / UUIDVar(uuid) =>
        files.getFile(uuid).flatMap {
          case Some(file) =>
            Ok(file.bytes).map(
              _.withContentType(
                `Content-Type`(MediaType.unsafeParse(file.mimeType))
              )
            )
          case None =>
            NotFound(ErrorResponse("File not found"))
        }

      case req @ POST -> Root / "api" / "login" =>
        req.as[LoginRequest].flatMap { credentials =>
          handleResult(auth.login(credentials)).flatMap {
            case Right(response) => Ok(response)
            case Left(error) => errorResponse(error)
          }
        }
    }

  private def protectedRoutes: HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "api" / "protected" =>
        withAuth(req) { context =>
          Ok(auth.protectedResponse(context.user))
        }

      case req @ GET -> Root / "api" / "twitter" / "authorize" =>
        withAuth(req) { context =>
          handleResult(twitter.getAuthorizationUrl(context.token)).flatMap {
            case Right(url) =>
              Found(Location(Uri.unsafeFromString(url)))
            case Left(error) =>
              errorResponse(error)
          }
        }

      case req @ GET -> Root / "api" / "twitter" / "callback" :? OAuthTokenParam(
            oauthToken
          ) +& OAuthVerifierParam(oauthVerifier) =>
        withAuth(req) { _ =>
          (oauthToken, oauthVerifier) match {
            case (Some(token), Some(verifier)) =>
              handleResult(twitter.handleCallback(token, verifier)).flatMap {
                case Right(_) =>
                  Found(Location(Uri.unsafeFromString("/account")))
                    .map(
                      _.putHeaders(
                        Header.Raw(
                          org.typelevel.ci.CIString("Cache-Control"),
                          "no-store, no-cache, must-revalidate, private"
                        ),
                        Header.Raw(org.typelevel.ci.CIString("Pragma"), "no-cache"),
                        Header.Raw(org.typelevel.ci.CIString("Expires"), "0")
                      )
                    )
                case Left(error) =>
                  errorResponse(error)
              }
            case _ =>
              BadRequest(ErrorResponse("Missing oauth_token or oauth_verifier"))
          }
        }

      case req @ GET -> Root / "api" / "twitter" / "status" =>
        withAuth(req) { _ =>
          twitter.getAuthStatus.flatMap { createdAt =>
            Ok(
              TwitterAuthStatusResponse(
                hasAuthorization = createdAt.isDefined,
                createdAt = createdAt.getOrElse(0L)
              )
            )
          }
        }

      case req @ POST -> Root / "api" / "bluesky" / "post" =>
        withAuth(req) { _ =>
          req.as[NewPostRequest].flatMap { request =>
            handleResult(bluesky.createPost(request)).flatMap {
              case Right(response) => Ok(response)
              case Left(error) => errorResponse(error)
            }
          }
        }

      case req @ POST -> Root / "api" / "mastodon" / "post" =>
        withAuth(req) { _ =>
          req.as[NewPostRequest].flatMap { request =>
            handleResult(mastodon.createPost(request)).flatMap {
              case Right(response) => Ok(response)
              case Left(error) => errorResponse(error)
            }
          }
        }

      case req @ POST -> Root / "api" / "twitter" / "post" =>
        withAuth(req) { _ =>
          req.as[NewPostRequest].flatMap { request =>
            handleResult(twitter.createPost(request)).flatMap {
              case Right(response) => Ok(response)
              case Left(error) => errorResponse(error)
            }
          }
        }

      case req @ POST -> Root / "api" / "rss" / "post" =>
        withAuth(req) { _ =>
          req.as[NewPostRequest].flatMap { request =>
            handleResult(rss.createPost(request)).flatMap {
              case Right(response) => Ok(response)
              case Left(error) => errorResponse(error)
            }
          }
        }

      case req @ POST -> Root / "api" / "multiple" / "post" =>
        withAuth(req) { _ =>
          req.as[NewPostRequest].flatMap { request =>
            handleMultiplePost(request).flatMap {
              case Right(response) => Ok(response)
              case Left(error) => errorResponse(error)
            }
          }
        }

      case req @ POST -> Root / "api" / "files" / "upload" =>
        withAuth(req) { _ =>
          req.decode[Multipart[IO]] { multipart =>
            handleFileUpload(multipart).flatMap {
              case Right(metadata) => Ok(metadata)
              case Left(error) => errorResponse(error)
            }
          }
        }
    }

  private val publicRoot = Paths.get("public").toAbsolutePath.normalize
  private val spaPaths = Set("login", "form", "account")
  private val reservedStaticPrefixes =
    Set("api", "rss", "files", "openapi", "docs", "swagger", "ping")

  private def staticRoutes: HttpRoutes[IO] =
    HttpRoutes[IO] { request =>
      val segments = request.pathInfo.segments.map(_.decoded()).toList
      segments.headOption match {
        case Some(head) if reservedStaticPrefixes.contains(head) =>
          OptionT.none
        case _ =>
          OptionT.liftF(serveStaticFile(segments))
      }
    }

  private def withAuth(req: Request[IO])(f: AuthContext => IO[Response[IO]]): IO[Response[IO]] = {
    val inputs = extractAuthInputs(req)
    handleResult(auth.authenticate(inputs)).flatMap {
      case Right(context) => f(context)
      case Left(error) => errorResponse(error)
    }
  }

  private def extractAuthInputs(req: Request[IO]): AuthInputs = {
    val authHeader = req.headers.get[headers.Authorization].map(_.credentials.renderString)
    val queryToken = req.params.get("access_token")
    val cookieToken = req.cookies.find(_.name == "access_token").map(_.content)
    AuthInputs(authHeader, queryToken, cookieToken)
  }

  private def handleResult[A](result: IO[A]): IO[Either[ApiError, A]] =
    apiErrorHandle.attempt(result)

  private def errorResponse(error: ApiError): IO[Response[IO]] = {
    val status = Status.fromInt(error.status).getOrElse(Status.InternalServerError)
    Response[IO](status)
      .withEntity(ErrorResponse(error.message))
      .pure[IO]
  }

  private def parseFilter(value: Option[String]): Option[RssService.FilterMode] =
    value.flatMap {
      case mode if mode.equalsIgnoreCase("include") => Some(RssService.FilterMode.Include)
      case mode if mode.equalsIgnoreCase("exclude") => Some(RssService.FilterMode.Exclude)
      case _ => None
    }

  private def handleMultiplePost(request: NewPostRequest)
    : IO[Either[ApiError, MultiPostResponse]] = {
    val handlers: List[(String, IO[NewPostResponse])] =
      List("rss" -> rss.createPost(request)) ++
        request.targets.getOrElse(Nil).flatMap {
          case Target.Mastodon => Some("mastodon" -> mastodon.createPost(request))
          case Target.Bluesky => Some("bluesky" -> bluesky.createPost(request))
          case Target.Twitter => Some("twitter" -> twitter.createPost(request))
          case Target.LinkedIn => None
        }

    handlers.traverse { case (name, result) =>
      apiErrorHandle.attempt(result).map(either => (name, either))
    }.map { results =>
      val errors = results.collect { case (name, Left(error)) => (name, error) }
      val successes = results.collect { case (name, Right(response)) => name -> response }.toMap

      if errors.nonEmpty && successes.isEmpty then {
        val errorModules = errors.map(_._1).mkString(", ")
        val status = errors.map(_._2.status).max
        Left(ApiError.requestError(status, s"Failed to create post via $errorModules", "multiple"))
      } else {
        Right(MultiPostResponse(successes))
      }
    }
  }

  private def handleFileUpload(multipart: Multipart[IO]): IO[Either[ApiError, FileMetadata]] = {
    val allowedTypes = Set("image/png", "image/jpeg")

    multipart.parts.find(_.name.contains("file")) match {
      case None =>
        IO.pure(Left(ApiError.validationError("Missing file part", "upload")))
      case Some(filePart) =>
        filePart.contentType match {
          case Some(contentType) if allowedTypes.contains(contentType.mediaType.show.toLowerCase) =>
            val filename = filePart.filename.getOrElse("upload")

            // Extract alt text from multipart
            val altTextIO: IO[Option[String]] =
              multipart.parts.find(_.name.contains("altText")) match {
                case Some(part) =>
                  part.body.through(fs2.text.utf8.decode).compile.string.attempt
                    .map(_.toOption.filter(_.nonEmpty))
                case None =>
                  IO.pure(None)
              }

            for {
              altText <- altTextIO
              bytes <- filePart.body.compile.to(Array)
              result <- files
                .saveFile(filename, bytes, altText)
                .map(Right(_))
                .handleErrorWith { err =>
                  logger.error(err)("Failed to upload file") *>
                    IO.pure(Left(ApiError.validationError(err.getMessage, "upload")))
                }
            } yield result
          case Some(contentType) =>
            IO.pure(
              Left(ApiError.validationError(
                s"Unsupported content type: ${contentType.mediaType.show}",
                "upload"
              ))
            )
          case None =>
            IO.pure(Left(ApiError.validationError("Missing content type", "upload")))
        }
    }
  }

  private def serveStaticFile(segments: List[String]): IO[Response[IO]] =
    resolveStaticPath(segments).flatMap {
      case None =>
        NotFound(ErrorResponse("File not found"))
      case Some(path) =>
        IO.blocking {
          if !JavaFiles.exists(path) || JavaFiles.isDirectory(path) then {
            None
          } else {
            val bytes = JavaFiles.readAllBytes(path)
            val contentType = Option(JavaFiles.probeContentType(path))
              .flatMap(MediaType.parse(_).toOption)
              .getOrElse(MediaType.application.`octet-stream`)
            Some((contentType, bytes))
          }
        }.flatMap {
          case Some((contentType, bytes)) =>
            Ok(bytes).map(_.withContentType(`Content-Type`(contentType)))
          case None =>
            NotFound(ErrorResponse("File not found"))
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

  // Query parameter matchers
  private object FilterByLinksParam
      extends OptionalQueryParamDecoderMatcher[String]("filterByLinks")
  private object FilterByImagesParam
      extends OptionalQueryParamDecoderMatcher[String]("filterByImages")
  private object OAuthTokenParam extends OptionalQueryParamDecoderMatcher[String]("oauth_token")
  private object OAuthVerifierParam
      extends OptionalQueryParamDecoderMatcher[String]("oauth_verifier")

}
