package socialpublish.frontend.components

import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterCounterTest {

    @Test
    fun testBuildPostTextCombinesContentAndLink() {
        val result = buildPostText("Hello", explainingLink())
        assertEquals("Hello\n\n${explainingLink()}", result)
    }

    @Test
    fun testBuildPostTextWithOnlyContent() {
        val result = buildPostText("Hello", "")
        assertEquals("Hello", result)
    }

    @Test
    fun testCountCharactersWithLinksUsesLinkLength() {
        val text = "Hello https://example.com"
        assertEquals(31, countCharactersWithLinks(text))
    }

    @Test
    fun testCountCharactersWithLinksHandlesMultipleLinks() {
        val text = "See https://example.com and https://example.org"
        val expected = 9 + (25 * 2)
        assertEquals(expected, countCharactersWithLinks(text))
    }

    @Test
    fun testCountCharactersWithLinksCountsUnicodeAsSingleChar() {
        val text = "😛😛😛"
        assertEquals(3, countCharactersWithLinks(text))
    }

    private fun explainingLink(): String = "https://example.com"
}
