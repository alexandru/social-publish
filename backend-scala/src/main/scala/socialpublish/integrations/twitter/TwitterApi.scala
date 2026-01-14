package socialpublish.integrations.twitter

import cats.effect.*
import cats.syntax.all.*
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import socialpublish.integrations.twitter.TwitterEndpoints.*
import socialpublish.db.DocumentsDatabase
import socialpublish.http.ServerConfig
import socialpublish.models.*
import socialpublish.services.FilesService
import socialpublish.utils.TextUtils
import sttp.client4.Backend
import sttp.model.{MediaType, Part, Uri}
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp4.SttpClientInterpreter

import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Random

// Twitter API implementation with OAuth 1.0a
trait TwitterApi {
  def createPost(request: NewPostRequest): Result[NewPostResponse]
  def getAuthorizationUrl(accessToken: String): Result[String]
  def handleCallback(oauthToken: String, oauthVerifier: String): Result[Unit]
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
  override def createPost(request: NewPostRequest): Result[NewPostResponse] =
    Result.error(ApiError.validationError("Twitter integration is disabled", "twitter"))

  override def getAuthorizationUrl(accessToken: String): Result[String] =
    Result.error(ApiError.validationError("Twitter integration is disabled", "twitter"))

  override def handleCallback(oauthToken: String, oauthVerifier: String): Result[Unit] =
    Result.error(ApiError.validationError("Twitter integration is disabled", "twitter"))

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
  private val interpreter = SttpClientInterpreter()

  override def hasTwitterAuth: IO[Boolean] =
    restoreOauthTokenFromDb.map(_.isDefined)

  override def getAuthStatus: IO[Option[Long]] =
    docsDb.searchByKey("twitter-oauth-token").map(_.map(_.createdAt.getEpochSecond * 1000))

  override def getAuthorizationUrl(jwtAccessToken: String): Result[String] = {
    val callbackUrl =
      s"${server.baseUrl}/api/twitter/callback?access_token=${URLEncoder.encode(jwtAccessToken, "UTF-8")}"
    val oauthParams = generateOAuthParams(
      "POST",
      requestTokenURL,
      Map("oauth_callback" -> callbackUrl, "x_auth_access_type" -> "write"),
      None
    )
    val authHeader = buildAuthorizationHeader(oauthParams)

    val request = interpreter.toRequest(TwitterEndpoints.requestToken, Some(authBase))(
      (authHeader, callbackUrl, "write")
    )

    for {
      response <- Result.liftIO(sendRequest(request)).flatMap(Result.fromEither)
      params <- Result.fromEither(parseQueryString(response).toRight(
        ApiError.requestError(500, "Missing oauth_token", "twitter", response)
      ))
      token <- Result.fromOption(
        params.get("oauth_token"),
        ApiError.requestError(500, "No oauth_token in response", "twitter", response)
      )
      authUrl = s"$authorizeURL?oauth_token=$token"
    } yield authUrl
  }

  override def handleCallback(oauthToken: String, oauthVerifier: String): Result[Unit] = {
    val oauthParams = generateOAuthParams(
      "POST",
      accessTokenURL,
      Map("oauth_verifier" -> oauthVerifier, "oauth_token" -> oauthToken),
      None
    )
    val authHeader = buildAuthorizationHeader(oauthParams)
    val request = interpreter.toRequest(TwitterEndpoints.accessToken, Some(authBase))(
      (authHeader, oauthToken, oauthVerifier)
    )

    Result.liftIO(sendRequest(request)).flatMap(Result.fromEither).flatMap { body =>
      val params = parseQueryString(body).getOrElse(Map.empty)
      val token = AuthorizedToken(
        key = params.getOrElse("oauth_token", ""),
        secret = params.getOrElse("oauth_token_secret", "")
      )

      Result.liftIO(
        docsDb.createOrUpdate(
          kind = "twitter-oauth-token",
          payload = token.asJson.noSpaces,
          tags = List(DocumentTag("twitter-oauth-token", "key"))
        ).void
      )
    }
  }

  override def createPost(postReq: NewPostRequest): Result[NewPostResponse] =
    for {
      token <- Result.liftIO(restoreOauthTokenFromDb).flatMap {
        case Some(t) => Result.success(t)
        case None => Result.error(ApiError.unauthorized("Missing Twitter OAuth token"))
      }

      mediaIds <- postReq.images match {
        case Some(uuids) => uuids.traverse(uuid => uploadMedia(token, uuid))
        case None => Result.success(List.empty[String])
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

      request = interpreter.toRequest(TwitterEndpoints.createTweet, Some(apiBase))(
        authHeader -> tweetData
      )

      response <- Result.liftIO(sendRequest(request)).flatMap(Result.fromEither)
    } yield NewPostResponse.Twitter(response.data.id)

  private def uploadMedia(token: AuthorizedToken, uuid: UUID): Result[String] = {
    for {
      fileOpt <- Result.liftIO(files.getFile(uuid))
      file <- Result.fromOption(fileOpt, ApiError.notFound(s"File not found: $uuid"))

      oauthParams = generateOAuthParams("POST", mediaUploadURL, Map.empty, Some(token))
      authHeader = buildAuthorizationHeader(oauthParams)

      mediaPart =
        Part("media", file.bytes)
          .fileName(file.originalName)
          .contentType(MediaType.unsafeParse(file.mimeType))

      request = interpreter.toRequest(TwitterEndpoints.uploadMedia, Some(uploadBase))(
        authHeader -> TwitterMediaUploadForm(mediaPart)
      )

      response <- Result.liftIO(sendRequest(request)).flatMap(Result.fromEither)

      _ <- file.altText match {
        case Some(alt) =>
          Result.liftIO {
            val oauthParamsAlt = generateOAuthParams("POST", altTextURL, Map.empty, Some(token))
            val authHeaderAlt = buildAuthorizationHeader(oauthParamsAlt)
            val altTextRequest = AltTextRequest(
              media_id = response.media_id_string,
              alt_text = AltTextPayload(alt)
            )

            val altRequest = interpreter.toRequest(TwitterEndpoints.createAltText, Some(apiBase))(
              authHeaderAlt -> altTextRequest
            )

            sendRequest(altRequest).void
          }
        case None =>
          Result.success(())
      }
    } yield response.media_id_string
  }

  private def sendRequest[I, O](
    request: sttp.client4.Request[DecodeResult[Either[String, O]]]
  ): IO[Either[ApiError, O]] =
    backend.send(request).map { response =>
      decodeApiResponse(response.body, response.code.code)
    }

  private def decodeApiResponse[A](
    decoded: DecodeResult[Either[String, A]],
    status: Int
  ): Either[ApiError, A] =
    decoded match {
      case DecodeResult.Value(Right(value)) =>
        Right(value)
      case DecodeResult.Value(Left(errorBody)) =>
        Left(ApiError.requestError(status, "Twitter request failed", "twitter", errorBody))
      case DecodeResult.Error(original, error) =>
        Left(ApiError.caughtException(s"Twitter decode failure: $original", "twitter", error))
      case DecodeResult.Missing =>
        Left(ApiError.requestError(status, "Twitter response missing body", "twitter"))
      case DecodeResult.Mismatch(_, _) =>
        Left(ApiError.requestError(status, "Twitter response mismatch", "twitter"))
      case DecodeResult.InvalidValue(errors) =>
        Left(ApiError.requestError(
          status,
          s"Twitter invalid response: ${errors.toList.mkString(", ")}",
          "twitter"
        ))
      case DecodeResult.Multiple(errors) =>
        Left(ApiError.requestError(
          status,
          s"Twitter multiple responses: ${errors.toList.mkString(", ")}",
          "twitter"
        ))
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
