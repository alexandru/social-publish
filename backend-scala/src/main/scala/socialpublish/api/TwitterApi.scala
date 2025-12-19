package socialpublish.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.generic.auto.*
import org.http4s.*
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import socialpublish.config.AppConfig
import socialpublish.db.DocumentsDatabase
import socialpublish.models.*
import socialpublish.services.FilesService
import socialpublish.utils.TextUtils
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// Twitter API implementation with OAuth 1.0a
// This is a simplified stub - full OAuth1 implementation would be more complex
trait TwitterApi:
  def createPost(request: NewPostRequest): Result[NewPostResponse]
  def getAuthorizationUrl(accessToken: String): Result[String]
  def handleCallback(oauthToken: String, oauthVerifier: String): Result[Unit]

object TwitterApi:
  def apply(
    config: AppConfig,
    client: Client[IO],
    files: FilesService,
    docsDb: DocumentsDatabase,
    logger: Logger[IO]
  ): TwitterApi =
    new TwitterApiImpl(config, client, files, docsDb, logger)

private class TwitterApiImpl(
  config: AppConfig,
  client: Client[IO],
  files: FilesService,
  docsDb: DocumentsDatabase,
  logger: Logger[IO]
) extends TwitterApi:
  
  override def createPost(request: NewPostRequest): Result[NewPostResponse] =
    // Simplified - would need full OAuth1 signing
    Result.liftIO(logger.warn("Twitter posting not fully implemented")) *>
    Result.success(NewPostResponse.Twitter("stub-id"))
  
  override def getAuthorizationUrl(accessToken: String): Result[String] =
    // Simplified OAuth flow
    Result.success("https://api.twitter.com/oauth/authorize?oauth_token=stub")
  
  override def handleCallback(oauthToken: String, oauthVerifier: String): Result[Unit] =
    Result.liftIO(logger.info(s"Twitter callback received: $oauthToken"))
  
  private def prepareText(request: NewPostRequest): String =
    val content = if request.cleanupHtml.getOrElse(false) then
      TextUtils.convertHtml(request.content)
    else
      request.content.trim()
    
    request.link match
      case Some(link) => s"$content\n\n$link"
      case None => content
