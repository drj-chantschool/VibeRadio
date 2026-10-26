package app.viberadio.ui

/**
 * Finds the links in an episode description: the `[text](url)` links the feed parser keeps from
 * the show notes' anchors, and bare web addresses typed into the text. Pure, so it's tested on
 * the JVM; [EpisodeScreen] turns the spans into tappable links.
 */
object DescriptionLinks {

    /** A run of the description; [url] set when it's a link. */
    data class Span(val text: String, val url: String? = null)

    private val LINK = Regex(
        """\[([^\[\]]*)]\(((?:https?://|mailto:)[^\s)]+)\)""" + "|" + """\b(?i:https?://|www\.)[^\s<>()\[\]"]+""",
    )

    /** Punctuation that ends a sentence rather than an address: "see https://x.example." */
    private const val TRAILING = ".,;:!?'’\""

    fun parse(text: String): List<Span> {
        val spans = ArrayList<Span>()
        var at = 0
        fun plain(end: Int) {
            if (end > at) spans += Span(text.substring(at, end))
        }
        for (match in LINK.findAll(text)) {
            val labelled = match.groups[2]
            if (labelled != null) {
                plain(match.range.first)
                val label = match.groupValues[1].trim()
                spans += Span(label.ifEmpty { labelled.value }, labelled.value)
                at = match.range.last + 1
            } else {
                val address = match.value.trimEnd { it in TRAILING }
                plain(match.range.first)
                val url = if (address.startsWith("www.", ignoreCase = true)) "https://$address" else address
                spans += Span(address, url)
                at = match.range.first + address.length
            }
        }
        plain(text.length)
        return spans
    }
}
