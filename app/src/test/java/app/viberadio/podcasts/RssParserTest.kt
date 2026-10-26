package app.viberadio.podcasts

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssParserTest {

    private fun parse(xml: String) = RssParser.parse(xml.byteInputStream())

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Slow Radio</title>
            <item>
              <title>Two</title>
              <guid isPermaLink="false">ep-2</guid>
              <pubDate>Tue, 13 Oct 2026 09:00:00 GMT</pubDate>
              <itunes:duration>1:02:03</itunes:duration>
              <enclosure url="https://cdn.example/2.mp3" type="audio/mpeg" length="1"/>
            </item>
            <item>
              <title>Show notes only</title>
              <guid>notes</guid>
            </item>
            <item>
              <title>One</title>
              <pubDate>Mon, 5 Oct 2026 08:30 -0500</pubDate>
              <itunes:duration>754</itunes:duration>
              <enclosure url="https://cdn.example/1.mp3" type="audio/mpeg"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `reads title and audio items, dropping items without an enclosure`() {
        val result = parse(feed)
        assertEquals("Slow Radio", result.title)
        assertEquals(listOf("Two", "One"), result.items.map { it.title })
    }

    @Test
    fun `guid falls back to the enclosure url`() {
        val items = parse(feed).items
        assertEquals("ep-2", items[0].guid)
        assertEquals("https://cdn.example/1.mp3", items[1].guid)
    }

    @Test
    fun `reads itunes durations in both formats`() {
        val items = parse(feed).items
        assertEquals((3600 + 2 * 60 + 3) * 1000L, items[0].durationMs)
        assertEquals(754_000L, items[1].durationMs)
    }

    @Test
    fun `reads dates with and without seconds and weekday`() {
        val items = parse(feed).items
        assertEquals(Instant.parse("2026-10-13T09:00:00Z").toEpochMilli(), items[0].publishedMs)
        assertEquals(Instant.parse("2026-10-05T13:30:00Z").toEpochMilli(), items[1].publishedMs)
    }

    @Test
    fun `reads a lowercase pubdate`() {
        // As youtube-to-rss writes it.
        val item = parse(
            """
            <rss version="2.0">
              <channel>
                <title>Parish</title>
                <item>
                  <title>Sunday Mass</title>
                  <pubdate>Sun, 25 Oct 2026 00:00:00 -0500</pubdate>
                  <guid isPermaLink="false">abc123</guid>
                  <enclosure url="http://example.org/parish/abc123.mp3" type="audio/mpeg" length=""></enclosure>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        ).items.single()
        assertEquals(Instant.parse("2026-10-25T05:00:00Z").toEpochMilli(), item.publishedMs)
    }

    @Test
    fun `parses named US zones and ISO dates`() {
        assertEquals(Instant.parse("2026-01-02T15:04:05Z").toEpochMilli(), RssParser.parseDate("Fri, 02 Jan 2026 10:04:05 EST"))
        assertEquals(Instant.parse("2026-01-02T10:04:05Z").toEpochMilli(), RssParser.parseDate("2026-01-02T10:04:05Z"))
        assertNull(RssParser.parseDate("someday"))
    }

    @Test
    fun `rejects bad durations`() {
        assertNull(RssParser.parseDuration("soon"))
        assertNull(RssParser.parseDuration("1:2:3:4"))
        assertNull(RssParser.parseDuration("0"))
    }

    @Test(expected = FeedParseException::class)
    fun `html is not a feed`() {
        parse("<html><body>Not here</body></html>")
    }

    @Test
    fun `description prefers content encoded, then description, then the itunes summary`() {
        val xml = """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Notes</title>
                <item>
                  <title>Full</title>
                  <description>Short</description>
                  <content:encoded><![CDATA[<p>Long <b>notes</b></p>]]></content:encoded>
                  <enclosure url="https://cdn.example/1.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <title>Plain</title>
                  <description>Just the &lt;i&gt;description&lt;/i&gt;</description>
                  <itunes:summary>Summary</itunes:summary>
                  <enclosure url="https://cdn.example/2.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <title>Summary</title>
                  <description>  </description>
                  <itunes:summary>Only a summary</itunes:summary>
                  <enclosure url="https://cdn.example/3.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <title>None</title>
                  <enclosure url="https://cdn.example/4.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        assertEquals(
            listOf("Long notes", "Just the description", "Only a summary", null),
            parse(xml).items.map { it.description },
        )
    }

    @Test
    fun `plain text keeps paragraphs, breaks and list items and decodes entities`() {
        val html = "<p>First&nbsp;line<br/>second   line</p><p>Tom &amp; Jerry &#8212; &#x263A; &rsquo;</p>" +
            "<ul><li>one</li><li>two</li></ul><script>alert(1)</script>"
        assertEquals(
            "First line\nsecond line\n\nTom & Jerry \u2014 \u263a \u2019\n\n\u2022 one\n\u2022 two",
            RssParser.plainText(html),
        )
    }

    @Test
    fun `plain text leaves unknown entities alone and cuts long notes`() {
        assertEquals("a &bogus; b", RssParser.plainText("a &bogus; b"))
        val long = RssParser.plainText("x".repeat(RssParser.MAX_DESCRIPTION + 10))
        assertEquals(RssParser.MAX_DESCRIPTION, long.length)
        assertTrue(long.endsWith("\u2026"))
    }
}
