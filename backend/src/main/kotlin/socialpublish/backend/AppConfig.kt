package socialpublish.backend

import socialpublish.backend.clients.bluesky.BlueskyConfig
import socialpublish.backend.clients.linkedin.LinkedInConfig
import socialpublish.backend.clients.llm.LlmConfig
import socialpublish.backend.clients.mastodon.MastodonConfig
import socialpublish.backend.clients.threads.ThreadsConfig
import socialpublish.backend.clients.twitter.TwitterConfig
import socialpublish.backend.modules.FilesConfig
import socialpublish.backend.server.ServerConfig

data class AppConfig(
    val server: ServerConfig,
    val files: FilesConfig,
    val bluesky: BlueskyConfig?,
    val mastodon: MastodonConfig?,
    val twitter: TwitterConfig?,
    val linkedin: LinkedInConfig?,
    val threads: ThreadsConfig?,
    val llm: LlmConfig?,
)
