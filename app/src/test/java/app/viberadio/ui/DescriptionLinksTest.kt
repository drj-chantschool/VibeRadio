package app.viberadio.ui

import app.viberadio.ui.DescriptionLinks.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionLinksTest {

    @Test
    fun `labelled links become their label`() {
        assertEquals(
            listOf(Span("See "), Span("the map", "https://maps.example/a?b=1&c=2"), Span(" first.")),
            DescriptionLinks.parse("See [the map](https://maps.example/a?b=1&c=2) first."),
        )
    }

    @Test
    fun `a link with no label shows its address`() {
        assertEquals(listOf(Span("mailto:hi@x.example", "mailto:hi@x.example")), DescriptionLinks.parse("[](mailto:hi@x.example)"))
    }

    @Test
    fun `bare addresses are links, without the sentence's punctuation`() {
        assertEquals(
            listOf(
                Span("More at "),
                Span("https://x.example/notes", "https://x.example/notes"),
                Span(". Or "),
                Span("www.y.example", "https://www.y.example"),
                Span("!"),
            ),
            DescriptionLinks.parse("More at https://x.example/notes. Or www.y.example!"),
        )
    }

    @Test
    fun `text without links is one plain span, and other brackets are left alone`() {
        assertEquals(listOf(Span("Part [1] (of 2)")), DescriptionLinks.parse("Part [1] (of 2)"))
        assertEquals(emptyList<Span>(), DescriptionLinks.parse(""))
    }
}
