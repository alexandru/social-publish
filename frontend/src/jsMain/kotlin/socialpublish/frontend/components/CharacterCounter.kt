package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

private val UrlRegex = Regex("(https?://\\S+)")

private fun codePointLength(text: String): Int = js("Array.from(text).length") as Int

fun buildPostText(content: String, link: String): String {
    val parts = listOf(content, link).filter { it.isNotEmpty() }
    return parts.joinToString("\n\n")
}

fun countCharactersWithLinks(text: String, linkLength: Int = 25): Int {
    val matches = UrlRegex.findAll(text).toList()
    val textWithoutLinks = UrlRegex.replace(text, "")
    val baseCount = codePointLength(textWithoutLinks)
    return baseCount + (matches.size * linkLength)
}

@Composable
fun ServiceCharacterCounter(label: String, remaining: Int) {
    val classNames = if (remaining < 0) listOf("help", "is-danger") else listOf("help")
    P(attrs = { classes(*classNames.toTypedArray()) }) {
        Text("$label characters left: $remaining")
    }
}

@Composable
fun CharacterCounter(remaining: Int, maximum: Int) {
    val classNames = if (remaining < 0) listOf("help", "is-danger") else listOf("help")
    P(attrs = { classes(*classNames.toTypedArray()) }) {
        Text("Characters left: $remaining / $maximum")
    }
}
