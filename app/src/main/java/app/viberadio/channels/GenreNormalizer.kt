package app.viberadio.channels

/**
 * Turns messy genre tags into a grouping key and a display name.
 *
 * - Multi-value tags ("Rock; Indie", "Jazz/Fusion") use the first value.
 * - "Hip-Hop", "hip hop" and "HipHop" share one key.
 * - Bare ID3v1 numeric codes ("17", "(17)") and placeholders ("Unknown", "<unknown>") count as untagged.
 */
object GenreNormalizer {

    data class Genre(val key: String, val displayName: String)

    private val separators = Regex("[;/,|\\u0000]")
    private val id3Numeric = Regex("^\\(?\\d+\\)?$")
    private val id3Prefix = Regex("^\\(\\d+\\)\\s*")
    private val placeholders = setOf("unknown", "<unknown>", "genre", "none", "misc", "other")

    fun normalize(raw: String?): Genre? {
        if (raw == null) return null
        val first = raw.split(separators).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (id3Numeric.matches(first)) return null
        val name = first.replace(id3Prefix, "").trim()
        if (name.isEmpty() || name.lowercase() in placeholders) return null
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        if (key.isEmpty()) return null
        return Genre(key = key, displayName = name)
    }
}
