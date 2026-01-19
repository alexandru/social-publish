package socialpublish.integrations.twitter

import cats.effect.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import socialpublish.integrations.twitter.TwitterModels.*
import socialpublish.db.DocumentsDatabase
import socialpublish.http.ServerConfig
import socialpublish.models.*
import socialpublish.services.{FilesService, ProcessedFile}
import socialpublish.utils.TextUtils
import sttp.client4.*
import sttp.client4.circe.*
import sttp.model.{MediaType, Uri}

import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Random

// Twitter API implementation with OAuth 1.0a
trait TwitterApi {
  def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse]
  def getAuthorizationUrl(accessToken: String)(using Raise[IO, ApiError]): IO[String]
  def handleCallback(oauthToken: String, oauthVerifier: String)(using Raise[IO, ApiError]): IO[Unit]
  def hasTwitterAuth: IO[Boolean]
  def getAuthStatus: IO[Option[Long]]
}

object TwitterApi {

  def apply(
    server: ServerConfig,
    config: TwitterConfig,
    backend: Backend[IO],
    files: FilesService,
    docsDb: DocumentsDatabase
  ): TwitterApi =
    config match {
      case enabled: TwitterConfig.Enabled =>
        new TwitterApiImpl(server, enabled, backend, files, docsDb)
      case TwitterConfig.Disabled =>
        new DisabledTwitterApi()
    }

}

private class DisabledTwitterApi() extends TwitterApi {
  override def createPost(request: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse] =
    ApiError.validationError("Twitter integration is disabled", "twitter")
      .raise[IO, NewPostResponse]

  override def getAuthorizationUrl(accessToken: String)(using Raise[IO, ApiError]): IO[String] =
    ApiError.validationError("Twitter integration is disabled", "twitter")
      .raise[IO, String]

  override def handleCallback(oauthToken: String, oauthVerifier: String)(using
    Raise[IO, ApiError]
  ): IO[Unit] =
    ApiError.validationError("Twitter integration is disabled", "twitter")
      .raise[IO, Unit]

  override def hasTwitterAuth: IO[Boolean] =
    IO.pure(false)

  override def getAuthStatus: IO[Option[Long]] =
    IO.pure(None)
}

private case class AuthorizedToken(
  key: String,
  secret: String
) derives Codec.AsObject

private class TwitterApiImpl(
  server: ServerConfig,
  config: TwitterConfig.Enabled,
  backend: Backend[IO],
  files: FilesService,
  docsDb: DocumentsDatabase
) extends TwitterApi {

  private val requestTokenURL = s"${config.authBaseUrl}/oauth/request_token"
  private val authorizeURL = s"${config.authBaseUrl}/oauth/authorize"
  private val accessTokenURL = s"${config.authBaseUrl}/oauth/access_token"
  private val createTweetURL = s"${config.apiBaseUrl}/2/tweets"
  private val mediaUploadURL = s"${config.uploadBaseUrl}/1.1/media/upload.json"
  private val altTextURL = s"${config.apiBaseUrl}/1.1/media/metadata/create.json"

  private val apiBase = Uri.unsafeParse(config.apiBaseUrl)
  private val uploadBase = Uri.unsafeParse(config.uploadBaseUrl)
  private val authBase = Uri.unsafeParse(config.authBaseUrl)

  override def hasTwitterAuth: IO[Boolean] =
    restoreOauthTokenFromDb.map(_.isDefined)

  override def getAuthStatus: IO[Option[Long]] =
    docsDb.searchByKey("twitter-oauth-token").map(_.map(_.createdAt.getEpochSecond * 1000))

  override def getAuthorizationUrl(jwtAccessToken: String)(using
    Raise[IO, ApiError]
  ): IO[String] = {
    val callbackUrl =
      s"${server.baseUrl}/api/twitter/callback?access_token=${URLEncoder.encode(jwtAccessToken, "UTF-8")}"
    val oauthParams = generateOAuthParams(
      "POST",
      requestTokenURL,
      Map("oauth_callback" -> callbackUrl, "x_auth_access_type" -> "write"),
      None
    )
    val authHeader = buildAuthorizationHeader(oauthParams)

    val request = basicRequest
      .post(authBase.addPath("oauth", "request_token"))
      .header("Authorization", authHeader)
      .body(s"oauth_callback=${URLEncoder.encode(callbackUrl, "UTF-8")}&x_auth_access_type=write")
      .contentType("application/x-www-form-urlencoded")
      .response(asStringAlways)

    for {
      response <- backend.send(request)
      _ <- if response.code.isSuccess then IO.unit
      else ApiError.requestError(
        response.code.code,
        s"Twitter request token failed: ${response.body}",
        "twitter"
      )
        .raise[IO, Unit]
      params <- Raise[IO, ApiError].fromEither(parseQueryString(response.body).toRight(
        ApiError.requestError(500, "Missing oauth_token", "twitter", response.body)
      ))
      token <- params.get("oauth_token") match {
        case Some(value) => IO.pure(value)
        case None =>
          ApiError.requestError(500, "No oauth_token in response", "twitter", response.body)
            .raise[IO, String]
      }
      authUrl = s"$authorizeURL?oauth_token=$token"
    } yield authUrl
  }

  override def handleCallback(oauthToken: String, oauthVerifier: String)(using
    Raise[IO, ApiError]
  ): IO[Unit] = {
    val oauthParams = generateOAuthParams(
      "POST",
      accessTokenURL,
      Map("oauth_verifier" -> oauthVerifier, "oauth_token" -> oauthToken),
      None
    )
    val authHeader = buildAuthorizationHeader(oauthParams)

    val request = basicRequest
      .post(authBase.addPath("oauth", "access_token"))
      .header("Authorization", authHeader)
      .body(
        s"oauth_token=${URLEncoder.encode(oauthToken, "UTF-8")}&oauth_verifier=${URLEncoder.encode(oauthVerifier, "UTF-8")}"
      )
      .contentType("application/x-www-form-urlencoded")
      .response(asStringAlways)

    backend.send(request).flatMap { response =>
      if !response.code.isSuccess then {
        ApiError.requestError(
          response.code.code,
          s"Twitter access token failed: ${response.body}",
          "twitter"
        )
          .raise[IO, Unit]
      } else {
        val params = parseQueryString(response.body).getOrElse(Map.empty)
        val token = AuthorizedToken(
          key = params.getOrElse("oauth_token", ""),
          secret = params.getOrElse("oauth_token_secret", "")
        )

        docsDb.createOrUpdate(
          kind = "twitter-oauth-token",
          payload = token.asJson.noSpaces,
          tags = List(DocumentTag("twitter-oauth-token", "key"))
        ).void
      }
    }
  }

  override def createPost(postReq: NewPostRequest)(using Raise[IO, ApiError]): IO[NewPostResponse] =
    for {
      tokenOpt <- restoreOauthTokenFromDb
      token <- tokenOpt match {
        case Some(t) => IO.pure(t)
        case None => ApiError.unauthorized("Missing Twitter OAuth token").raise[IO, AuthorizedToken]
      }

      mediaIds <- postReq.images match {
        case Some(uuids) => uuids.traverse(uuid => uploadMedia(token, uuid))
        case None => IO.pure(List.empty[String])
      }

      content =
        if postReq.cleanupHtml.getOrElse(false) then {
          TextUtils.convertHtml(postReq.content.value)
        } else {
          postReq.content.value.trim()
        }

      text = postReq.link match {
        case Some(link) => s"$content\n\n$link"
        case None => content
      }

      tweetData =
        if mediaIds.nonEmpty then {
          CreateTweetRequest(text, Some(MediaIds(mediaIds)))
        } else {
          CreateTweetRequest(text, None)
        }

      oauthParams = generateOAuthParams("POST", createTweetURL, Map.empty, Some(token))
      authHeader = buildAuthorizationHeader(oauthParams)

      request = basicRequest
        .post(apiBase.addPath("2", "tweets"))
        .header("Authorization", authHeader)
        .body(tweetData.asJson.noSpaces)
        .contentType("application/json")
        .response(asJson[TweetResponse])

      response <- backend.send(request).flatMap { response =>
        response.body match {
          case Right(value) => IO.pure(value)
          case Left(error) =>
            ApiError.requestError(response.code.code, s"Twitter tweet failed: $error", "twitter")
              .raise[IO, TweetResponse]
        }
      }
    } yield NewPostResponse.Twitter(response.data.id)

  private def uploadMedia(token: AuthorizedToken, uuid: UUID)(using
    Raise[IO, ApiError]
  ): IO[String] = {
    for {
      fileOpt <- files.getFile(uuid)
      file <- fileOpt.fold(
        ApiError.notFound(s"File not found: $uuid").raise[IO, ProcessedFile]
      )(IO.pure)

      oauthParams = generateOAuthParams("POST", mediaUploadURL, Map.empty, Some(token))
      authHeader = buildAuthorizationHeader(oauthParams)

      mediaPart = multipart("media", file.bytes)
        .fileName(file.originalName)
        .contentType(MediaType.unsafeParse(file.mimeType))

      request = basicRequest
        .post(uploadBase.addPath("1.1", "media", "upload.json"))
        .header("Authorization", authHeader)
        .multipartBody(mediaPart)
        .response(asJson[TwitterMediaUploadResponse])

      response <- backend.send(request).flatMap { response =>
        response.body match {
          case Right(value) => IO.pure(value)
          case Left(error) =>
            ApiError.requestError(
              response.code.code,
              s"Twitter media upload failed: ${error.getMessage}",
              "twitter"
            )
              .raise[IO, TwitterMediaUploadResponse]
        }
      }

      _ <- file.altText match {
        case Some(alt) =>
          val oauthParamsAlt = generateOAuthParams("POST", altTextURL, Map.empty, Some(token))
          val authHeaderAlt = buildAuthorizationHeader(oauthParamsAlt)
          val altTextPayload = AltTextRequest(
            media_id = response.media_id_string,
            alt_text = AltTextPayload(alt)
          )

          val altRequest = basicRequest
            .post(apiBase.addPath("1.1", "media", "metadata", "create.json"))
            .header("Authorization", authHeaderAlt)
            .body(altTextPayload.asJson.noSpaces)
            .contentType("application/json")
            .response(asStringAlways)

          backend.send(altRequest).void
        case None =>
          IO.unit
      }
    } yield response.media_id_string
  }

  private def restoreOauthTokenFromDb: IO[Option[AuthorizedToken]] =
    docsDb.searchByKey("twitter-oauth-token").flatMap {
      case Some(doc) =>
        IO.fromEither(decode[AuthorizedToken](doc.payload)).map(Some(_))
          .handleErrorWith(_ => IO.pure(None))
      case None =>
        IO.pure(None)
    }

  private def generateOAuthParams(
    method: String,
    url: String,
    queryParams: Map[String, String],
    token: Option[AuthorizedToken]
  ): Map[String, String] = {
    val nonce = Random.alphanumeric.take(32).mkString
    val timestamp = (System.currentTimeMillis() / 1000).toString

    val baseParams = Map(
      "oauth_consumer_key" -> config.oauth1ConsumerKey,
      "oauth_nonce" -> nonce,
      "oauth_signature_method" -> "HMAC-SHA1",
      "oauth_timestamp" -> timestamp,
      "oauth_version" -> "1.0"
    )

    val withToken = token match {
      case Some(t) => baseParams + ("oauth_token" -> t.key)
      case None => baseParams
    }

    val signature = generateSignature(method, url, withToken ++ queryParams, token)
    withToken + ("oauth_signature" -> signature)
  }

  private def generateSignature(
    method: String,
    url: String,
    params: Map[String, String],
    token: Option[AuthorizedToken]
  ): String = {
    val sortedParams = params.toSeq.sortBy(_._1)
    val paramString = sortedParams.map { case (k, v) =>
      s"${percentEncode(k)}=${percentEncode(v)}"
    }.mkString("&")

    val baseUrl = url.split('?')(0)
    val baseString = Seq(method, percentEncode(baseUrl), percentEncode(paramString)).mkString("&")

    val signingKey = token match {
      case Some(t) =>
        s"${percentEncode(config.oauth1ConsumerSecret)}&${percentEncode(t.secret)}"
      case None => s"${percentEncode(config.oauth1ConsumerSecret)}&"
    }

    val mac = Mac.getInstance("HmacSHA1")
    mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"))
    val signatureBytes = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8))
    Base64.getEncoder.encodeToString(signatureBytes)
  }

  private def buildAuthorizationHeader(oauthParams: Map[String, String]): String = {
    val headerValue = oauthParams.toSeq.sortBy(_._1).map { case (k, v) =>
      s"""$k="${percentEncode(v)}""""
    }.mkString(", ")
    s"OAuth $headerValue"
  }

  private def percentEncode(s: String): String =
    URLEncoder.encode(s, "UTF-8")
      .replace("+", "%20")
      .replace("*", "%2A")
      .replace("%7E", "~")

  private def parseQueryString(s: String): Option[Map[String, String]] =
    if s.trim.isEmpty then None
    else {
      Some(s.split('&').map { pair =>
        val parts = pair.split('=')
        if parts.length == 2 then parts(0) -> parts(1)
        else parts(0) -> ""
      }.toMap)
    }

}
