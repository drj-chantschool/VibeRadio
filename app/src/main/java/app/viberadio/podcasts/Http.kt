package app.viberadio.podcasts

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Fetches and parses a feed. Blocking; call off the main thread. */
fun interface FeedFetcher {
    fun fetch(url: String): Feed
}

class HttpFeedFetcher : FeedFetcher {
    override fun fetch(url: String): Feed = Http.open(url).use(RssParser::parse)
}

/** Turns what people paste into a fetchable feed URL, or null if it can't be one. */
object FeedUrls {
    private val podcastSchemes = listOf("feed://", "itpc://", "pcast://", "podcast://")

    fun normalize(input: String): String? {
        var text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        podcastSchemes.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let {
            text = "https://" + text.substring(it.length)
        }
        if (!text.contains("://")) text = "https://$text"
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank() || !uri.host.contains('.')) return null
        return text
    }
}

/**
 * Plain HttpURLConnection, so the podcast side adds no dependencies. Follows redirects across
 * http/https (podcast hosts and their analytics prefixes do this constantly), which
 * HttpURLConnection won't do on its own.
 */
internal object Http {
    private const val MAX_REDIRECTS = 8
    private const val USER_AGENT = "VibeRadio (Android podcast player)"

    fun open(url: String): InputStream {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            when (code) {
                in 200..299 -> return connection.inputStream
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw IOException("HTTP $code without a location")
                    current = URL(current, location)
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }
}
