package com.alexn.socialpublish.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

data class AppConfig(
    val dbPath: String,
    val httpPort: Int,
    val baseUrl: String,
    val serverAuthUsername: String,
    val serverAuthPassword: String,
    val serverAuthJwtSecret: String,
    val blueskyService: String,
    val blueskyUsername: String,
    val blueskyPassword: String,
    val mastodonHost: String,
    val mastodonAccessToken: String,
    val twitterOauth1ConsumerKey: String,
    val twitterOauth1ConsumerSecret: String,
    val uploadedFilesPath: String
)

class AppCliCommand : CliktCommand(name = "social-publish") {
    val dbPath: String by option("--db-path", help = "Path to the SQLite database file")
        .default(System.getenv("DB_PATH") ?: "/var/lib/social-publish/sqlite3.db")

    val httpPort: Int by option("--http-port", help = "Port to listen on")
        .int()
        .default(System.getenv("HTTP_PORT")?.toIntOrNull() ?: 3000)

    val baseUrl: String by option("--base-url", help = "Public URL of this server")
        .default(System.getenv("BASE_URL") ?: "http://localhost:3000")

    val serverAuthUsername: String by option("--server-auth-username", help = "Your username for this server")
        .default(System.getenv("SERVER_AUTH_USERNAME") ?: "admin")

    val serverAuthPassword: String by option("--server-auth-password", help = "Your password for this server")
        .default(System.getenv("SERVER_AUTH_PASSWORD") ?: "admin")

    val serverAuthJwtSecret: String by option("--server-auth-jwt-secret", help = "JWT secret for this server's authentication")
        .default(System.getenv("JWT_SECRET") ?: "change-me")

    val blueskyService: String by option("--bluesky-service", help = "URL of the BlueSky service")
        .default(System.getenv("BSKY_SERVICE") ?: "https://bsky.social")

    val blueskyUsername: String by option("--bluesky-username", help = "Username for the Bluesky authentication")
        .default(System.getenv("BSKY_USERNAME") ?: "")

    val blueskyPassword: String by option("--bluesky-password", help = "Password for the Bluesky authentication")
        .default(System.getenv("BSKY_PASSWORD") ?: "")

    val mastodonHost: String by option("--mastodon-host", help = "Host of the Mastodon service")
        .default(System.getenv("MASTODON_HOST") ?: "")

    val mastodonAccessToken: String by option("--mastodon-access-token", help = "Access token for the Mastodon service")
        .default(System.getenv("MASTODON_ACCESS_TOKEN") ?: "")

    val twitterOauth1ConsumerKey: String by option("--twitter-oauth1-consumer-key", help = "Twitter OAuth1 consumer key")
        .default(System.getenv("TWITTER_OAUTH1_CONSUMER_KEY") ?: "")

    val twitterOauth1ConsumerSecret: String by option("--twitter-oauth1-consumer-secret", help = "Twitter OAuth1 consumer secret")
        .default(System.getenv("TWITTER_OAUTH1_CONSUMER_SECRET") ?: "")

    val uploadedFilesPath: String by option("--uploaded-files-path", help = "Directory where uploaded files are stored and processed")
        .default(System.getenv("UPLOADED_FILES_PATH") ?: "/var/lib/social-publish/uploads")

    lateinit var config: AppConfig
        private set

    override fun run() {
        config = AppConfig(
            dbPath = dbPath,
            httpPort = httpPort,
            baseUrl = baseUrl,
            serverAuthUsername = serverAuthUsername,
            serverAuthPassword = serverAuthPassword,
            serverAuthJwtSecret = serverAuthJwtSecret,
            blueskyService = blueskyService,
            blueskyUsername = blueskyUsername,
            blueskyPassword = blueskyPassword,
            mastodonHost = mastodonHost,
            mastodonAccessToken = mastodonAccessToken,
            twitterOauth1ConsumerKey = twitterOauth1ConsumerKey,
            twitterOauth1ConsumerSecret = twitterOauth1ConsumerSecret,
            uploadedFilesPath = uploadedFilesPath
        )
    }
}
