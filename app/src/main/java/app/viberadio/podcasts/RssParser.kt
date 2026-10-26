package app.viberadio.podcasts

import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

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
 * (youtube-to-rss among them) write `<pubdate>`. Uses the JDK DOM parser, which Android also ships, so it
 * runs unchanged in JVM tests.
 */
object RssParser {

    private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"

    /** Longer show notes are cut here: the library is rewritten whole while an episode plays. */
    const val MAX_DESCRIPTION = 4000

    fun parse(input: InputStream): Feed {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Not every platform parser supports these; where it doesn't, it also doesn't fetch DTDs.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        val document = try {
            factory.newDocumentBuilder().parse(input)
        } catch (e: Exception) {
            throw FeedParseException("Not a readable feed", e)
        }
        val channel = document.documentElement?.children("channel")?.firstOrNull()
            ?: throw FeedParseException("Not an RSS feed: no <channel>")

        val title = channel.childText("title")?.takeIf { it.isNotBlank() } ?: "Untitled podcast"
        val items = channel.children("item").mapNotNull(::item)
        return Feed(title = title, items = items)
    }

    private fun item(item: Element): FeedItem? {
        val enclosure = item.children("enclosure").firstOrNull { el ->
            val type = el.getAttribute("type")
            el.getAttribute("url").isNotBlank() && (type.isBlank() || type.startsWith("audio/") || type.startsWith("video/"))
        } ?: return null
        val url = enclosure.getAttribute("url").trim()
        val guid = item.childText("guid")?.takeIf { it.isNotBlank() } ?: url
        return FeedItem(
            guid = guid,
            title = item.childText("title")?.takeIf { it.isNotBlank() } ?: "Untitled episode",
            publishedMs = item.childText("pubDate", ignoreCase = true)?.let(::parseDate) ?: 0L,
            audioUrl = url,
            durationMs = item.children("duration", ITUNES_NS).firstOrNull()?.textContent?.let(::parseDuration),
            description = description(item),
        )
    }

    /** The fullest notes the item has: content:encoded, then description, then itunes:summary. */
    private fun description(item: Element): String? =
        sequenceOf(
            item.children("encoded", CONTENT_NS).firstOrNull()?.textContent,
            item.childText("description"),
            item.children("summary", ITUNES_NS).firstOrNull()?.textContent,
        ).mapNotNull { it?.let(::plainText) }.firstOrNull { it.isNotEmpty() }

    /**
     * Show notes as plain text: paragraphs and line breaks kept, list items bulleted, every other
     * tag dropped, entities decoded, whitespace tidied, and cut at [MAX_DESCRIPTION] characters.
     */
    fun plainText(html: String): String {
        val text = html
            .replace(Regex("(?is)<(script|style)\\b.*?</\\1\\s*>"), "")
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

    private fun Element.children(localName: String, namespace: String? = null, ignoreCase: Boolean = false): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val name = node.localName ?: node.nodeName
            val matches = if (namespace == null) {
                node.namespaceURI.isNullOrEmpty() && name.equals(localName, ignoreCase)
            } else {
                node.namespaceURI == namespace && name.equals(localName, ignoreCase)
            }
            if (matches) out += node as Element
        }
        return out
    }

    private fun Element.childText(localName: String, ignoreCase: Boolean = false): String? =
        children(localName, ignoreCase = ignoreCase).firstOrNull()?.textContent?.trim()
}
