package socialpublish.frontend.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import socialpublish.frontend.models.ConfiguredServices

class AccountPageStateTest {
    @Test
    fun `toPatchBody omits unchanged sensitive fields`() {
        val state =
            SettingsFormState(
                blueskyService = "https://bsky.social",
                blueskyUsername = "alice.bsky.social",
                blueskyPasswordIsSet = true,
                blueskyPassword = "",
                mastodonHost = "https://mastodon.social",
                mastodonTokenIsSet = true,
                mastodonToken = "",
                twitterConsumerKey = "twitter-key",
                twitterConsumerSecretIsSet = true,
                twitterConsumerSecret = "",
                linkedinClientId = "linkedin-id",
                linkedinClientSecretIsSet = true,
                linkedinClientSecret = "",
                llmApiUrl = "https://llm.example.com/v1/chat/completions",
                llmApiKeyIsSet = true,
                llmApiKey = "",
                llmModel = "gpt-4o-mini",
            )

        val patch = state.toPatchBody()

        assertFalse(
            patch["bluesky"]!!.jsonObject.containsKey("password"),
            "bluesky.password must be omitted when unchanged",
        )
        assertFalse(
            patch["mastodon"]!!.jsonObject.containsKey("accessToken"),
            "mastodon.accessToken must be omitted when unchanged",
        )
        assertFalse(
            patch["twitter"]!!.jsonObject.containsKey("oauth1ConsumerSecret"),
            "twitter.oauth1ConsumerSecret must be omitted when unchanged",
        )
        assertFalse(
            patch["linkedin"]!!.jsonObject.containsKey("clientSecret"),
            "linkedin.clientSecret must be omitted when unchanged",
        )
        assertFalse(
            patch["llm"]!!.jsonObject.containsKey("apiKey"),
            "llm.apiKey must be omitted when unchanged",
        )
    }

    @Test
    fun `toPatchBody never serializes masked sentinel`() {
        val state =
            SettingsFormState(
                blueskyUsername = "alice.bsky.social",
                blueskyPassword = MASKED_SECRET_SENTINEL,
                mastodonHost = "https://mastodon.social",
                mastodonToken = MASKED_SECRET_SENTINEL,
                twitterConsumerKey = "twitter-key",
                twitterConsumerSecret = MASKED_SECRET_SENTINEL,
                linkedinClientId = "linkedin-id",
                linkedinClientSecret = MASKED_SECRET_SENTINEL,
                llmApiUrl = "https://llm.example.com/v1/chat/completions",
                llmApiKey = MASKED_SECRET_SENTINEL,
                llmModel = "gpt-4o-mini",
            )

        val patch = state.toPatchBody()
        val patchString = patch.toString()

        assertFalse(patchString.contains(MASKED_SECRET_SENTINEL))
    }

    @Test
    fun `toPatchBody writes explicit new secret values`() {
        val state =
            SettingsFormState(
                twitterConsumerKey = "twitter-key",
                twitterConsumerSecret = "new-secret",
                llmApiUrl = "https://llm.example.com/v1/chat/completions",
                llmApiKey = "sk-new",
                llmModel = "gpt-4o-mini",
            )

        val patch = state.toPatchBody()

        assertEquals(
            "new-secret",
            patch["twitter"]!!.jsonObject["oauth1ConsumerSecret"]?.jsonPrimitive?.content,
        )
        assertEquals("sk-new", patch["llm"]!!.jsonObject["apiKey"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toFormState keeps sensitive input values empty and only sets flags`() {
        val view =
            AccountSettingsView(
                bluesky =
                    BlueskySettingsView(
                        service = "https://bsky.social",
                        username = "alice.bsky.social",
                        password = MASKED_SECRET_SENTINEL,
                    ),
                mastodon =
                    MastodonSettingsView(
                        host = "https://mastodon.social",
                        accessToken = MASKED_SECRET_SENTINEL,
                    ),
                twitter =
                    TwitterSettingsView(
                        oauth1ConsumerKey = "twitter-key",
                        oauth1ConsumerSecret = MASKED_SECRET_SENTINEL,
                    ),
                linkedin =
                    LinkedInSettingsView(
                        clientId = "linkedin-id",
                        clientSecret = MASKED_SECRET_SENTINEL,
                    ),
                llm =
                    LlmSettingsView(
                        apiUrl = "https://llm.example.com/v1/chat/completions",
                        apiKey = MASKED_SECRET_SENTINEL,
                        model = "gpt-4o-mini",
                    ),
            )

        val state = view.toFormState()

        assertTrue(state.blueskyPasswordIsSet)
        assertTrue(state.mastodonTokenIsSet)
        assertTrue(state.twitterConsumerSecretIsSet)
        assertTrue(state.linkedinClientSecretIsSet)
        assertTrue(state.llmApiKeyIsSet)

        assertEquals("", state.blueskyPassword)
        assertEquals("", state.mastodonToken)
        assertEquals("", state.twitterConsumerSecret)
        assertEquals("", state.linkedinClientSecret)
        assertEquals("", state.llmApiKey)
    }

    @Test
    fun `settings merge preserves oauth readiness until status endpoints update`() {
        val current = ConfiguredServices(twitter = true, linkedin = false)
        val fromSettings =
            ConfiguredServices(
                mastodon = true,
                bluesky = true,
                twitter = false,
                linkedin = false,
                llm = true,
            )

        val merged = mergeConfiguredServicesFromSettings(current, fromSettings)

        assertTrue(merged.twitter)
        assertFalse(merged.linkedin)
        assertTrue(merged.mastodon)
        assertTrue(merged.bluesky)
        assertTrue(merged.llm)
    }

    @Test
    fun `oauth status handlers set readiness directly from authorization status`() {
        val initial = ConfiguredServices(twitter = false, linkedin = false, mastodon = true)

        val twitterConnected = applyTwitterAuthorizationStatus(initial, hasAuthorization = true)
        val linkedInConnected =
            applyLinkedInAuthorizationStatus(twitterConnected, hasAuthorization = true)

        assertTrue(twitterConnected.twitter)
        assertTrue(linkedInConnected.linkedin)
        assertTrue(linkedInConnected.mastodon)
    }
}
