package app.viberadio.podcasts

import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** A parsed RSS feed, before it is merged into a [PodcastLibrary]. */
data class Feed(val title: String, val items: List<FeedItem>)

data class FeedItem(
    val guid: String,
    val title: String,
    val publishedMs: Long,
    val audioUrl: String,
    val durationMs: Long?,
    /** Show notes as plain text, null when the item has none. */
    val description: String? = null,
)

class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads podcast RSS 2.0 feeds. Items without an audio enclosure are dropped; a missing GUID
 * falls back to the enclosure URL. `pubDate` is matched ignoring case, since some generators
 * (youtube-to-rss among them) write `<pubdate>`. Uses the JDK SAX parser, which Android also ships, so it
 * runs unchanged in JVM tests.
 */
object RssParser {

    private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"

    /** Longer show notes are cut here, so a feed with a long back catalogue stays small in memory and on disk. */
    const val MAX_DESCRIPTION = 4000

    fun parse(input: InputStream): Feed {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Not every platform parser supports these; where it doesn't, it also doesn't fetch DTDs.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        val handler = FeedHandler()
        try {
            factory.newSAXParser().parse(input, handler)
        } catch (e: Exception) {
            throw FeedParseException("Not a readable feed", e)
        }
        if (!handler.sawChannel) throw FeedParseException("Not an RSS feed: no <channel>")
        val title = handler.title?.trim()?.takeIf { it.isNotBlank() } ?: "Untitled podcast"
        return Feed(title = title, items = handler.items)
    }

    /**
     * Streams the feed rather than building a DOM: a back catalogue of tens of thousands of items
     * makes a DOM far bigger than the app's heap. Reads what the DOM version read: the root's first
     * `<channel>`, its own `<title>` and `<item>`s, and in each item the first of each field among
     * its own children, as the text of everything inside it. Only those fields' text is kept, one
     * item at a time.
     */
    private class FeedHandler : DefaultHandler() {
        var sawChannel = false
        var title: String? = null
        val items = ArrayList<FeedItem>()

        private var depth = 0
        private var inChannel = false
        private var item: ItemFields? = null

        /** The field being read, the depth of its element and its text so far; null while skipping. */
        private var field: String? = null
        private var fieldDepth = 0
        private val text = StringBuilder()

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            depth++
            if (field != null) return
            val name = localName.ifEmpty { qName }
            val plain = uri.isEmpty()
            when (depth) {
                2 -> if (plain && name == "channel" && !sawChannel) {
                    sawChannel = true
                    inChannel = true
                }
                3 -> if (inChannel && plain) when (name) {
                    "title" -> if (title == null) read("title")
                    "item" -> item = ItemFields()
                }
                4 -> item?.let { fields ->
                    val key = when {
                        plain && name == "enclosure" -> {
                            if (fields.audioUrl == null) fields.enclosure(attributes)
                            null
                        }
                        plain && name.equals("pubDate", ignoreCase = true) -> "pubDate"
                        plain && name in ITEM_FIELDS -> name
                        uri == ITUNES_NS && name == "duration" -> "duration"
                        uri == ITUNES_NS && name == "summary" -> "summary"
                        uri == CONTENT_NS && name == "encoded" -> "encoded"
                        else -> null
                    }
                    if (key != null && key !in fields.text) read(key)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (field != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val key = field
            if (key != null && depth == fieldDepth) {
                val value = text.toString()
                if (fieldDepth == 3) title = value else item?.text?.put(key, value)
                field = null
                text.setLength(0)
            } else if (key == null) {
                when (depth) {
                    2 -> inChannel = false
                    3 -> item?.let { fields ->
                        fields.toFeedItem()?.let(items::add)
                        item = null
                    }
                }
            }
            depth--
        }

        private fun read(key: String) {
            field = key
            fieldDepth = depth
            text.setLength(0)
        }
    }

    /** Field names read from an item's own children without a namespace (`pubDate` is matched apart). */
    private val ITEM_FIELDS = setOf("guid", "title", "description")

    private class ItemFields {
        /** The first usable audio enclosure's URL. */
        var audioUrl: String? = null
        /** Each field's text, first occurrence only. */
        val text = HashMap<String, String>()

        fun enclosure(attributes: Attributes) {
            val url = attributes.getValue("", "url") ?: attributes.getValue("url").orEmpty()
            val type = attributes.getValue("", "type") ?: attributes.getValue("type").orEmpty()
            if (url.isNotBlank() && (type.isBlank() || type.startsWith("audio/") || type.startsWith("video/"))) audioUrl = url.trim()
        }

        fun toFeedItem(): FeedItem? {
            val url = audioUrl ?: return null
            return FeedItem(
                guid = text["guid"]?.trim()?.takeIf { it.isNotBlank() } ?: url,
                title = text["title"]?.trim()?.takeIf { it.isNotBlank() } ?: "Untitled episode",
                publishedMs = text["pubDate"]?.trim()?.let(::parseDate) ?: 0L,
                audioUrl = url,
                durationMs = text["duration"]?.let(::parseDuration),
                description = description(),
            )
        }

        /** The fullest notes the item has: content:encoded, then description, then itunes:summary. */
        private fun description(): String? =
            sequenceOf(text["encoded"], text["description"]?.trim(), text["summary"])
                .mapNotNull { it?.let(::plainText) }.firstOrNull { it.isNotEmpty() }
    }

    /**
     * Show notes as plain text: paragraphs and line breaks kept, list items bulleted, web and mail
     * links kept as `[text](url)` (the page makes them tappable), every other tag dropped, entities
     * decoded, whitespace tidied, and cut at [MAX_DESCRIPTION] characters.
     */
    fun plainText(html: String): String {
        val text = html
            .replace(Regex("(?is)<(script|style)\\b.*?</\\1\\s*>"), "")
            .replace(ANCHOR) { link(it.groupValues[2], it.groupValues[3]) }
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)<li\\b[^>]*>"), "\n\u2022 ")
            .replace(Regex("(?i)</?(p|div|ul|ol|h[1-6]|blockquote)\\b[^>]*>"), "\n\n")
            .replace(Regex("<[^>]*>"), "")
            .let(::decodeEntities)
            .replace('\u00a0', ' ')
            .lines()
            .joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return if (text.length <= MAX_DESCRIPTION) text else text.take(MAX_DESCRIPTION - 1).trimEnd() + "\u2026"
    }

    private val ANCHOR = Regex("(?is)<a\\b[^>]*?\\bhref\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a\\s*>")

    /** `[label](url)` for a web or mail link, else just the label. Brackets, spaces and `)` escaped so it parses back. */
    private fun link(href: String, label: String): String {
        val url = href.trim().replace(" ", "%20").replace(")", "%29")
        if (!LINK_SCHEME.containsMatchIn(url)) return label
        return "[${label.replace("[", "").replace("]", "")}]($url)"
    }

    private val LINK_SCHEME = Regex("(?i)^(https?://|mailto:)\\S")

    private fun decodeEntities(text: String): String =
        Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);").replace(text) { match ->
            val name = match.groupValues[1]
            val code = when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)
                name.startsWith("#") -> name.drop(1).toIntOrNull()
                else -> NAMED_ENTITIES[name]
            }
            if (code == null || !Character.isValidCodePoint(code)) match.value else String(Character.toChars(code))
        }

    private val NAMED_ENTITIES = mapOf(
        "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code,
        "nbsp" to 0xa0, "ndash" to 0x2013, "mdash" to 0x2014, "hellip" to 0x2026,
        "lsquo" to 0x2018, "rsquo" to 0x2019, "ldquo" to 0x201c, "rdquo" to 0x201d,
    )

    /** RFC 822 as feeds actually write it: optional weekday and seconds, named US zones, or ISO 8601. */
    fun parseDate(raw: String): Long? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val zoned = ZONE_NAMES.entries.fold(text) { acc, (name, offset) ->
            acc.replace(Regex("\\b$name$"), offset)
        }
        runCatching { return ZonedDateTime.parse(zoned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        return null
    }

    /** itunes:duration is seconds ("3600", "3600.5"), "MM:SS" or "HH:MM:SS". */
    fun parseDuration(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0.0
        for (part in parts) {
            val value = part.trim().toDoubleOrNull() ?: return null
            if (value < 0) return null
            seconds = seconds * 60 + value
        }
        return (seconds * 1000).toLong().takeIf { it > 0 }
    }

    private val ZONE_NAMES = mapOf(
        "UT" to "GMT", "UTC" to "GMT", "Z" to "GMT",
        "EST" to "-0500", "EDT" to "-0400",
        "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600",
        "PST" to "-0800", "PDT" to "-0700",
    )
}
