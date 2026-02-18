package socialpublish.backend.clients.mastodon

import kotlinx.serialization.Serializable

@Serializable data class MastodonConfig(val host: String, val accessToken: String)
