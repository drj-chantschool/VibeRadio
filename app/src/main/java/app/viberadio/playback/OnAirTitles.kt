package app.viberadio.playback

/** One title and artist as a Bluetooth device shows them. */
data class TitleLine(val title: String, val artist: String?)

/** The podcast episode on air: its [title], its [show], and the [chapter] it is in, when it has chapters. */
data class EpisodeTitles(val title: String, val show: String?, val chapter: String?)

/**
 * What a Bluetooth device (headphones, a car's display) is told is playing. The device only has
 * one title, so it is switched between everything on air, a step every [INTERVAL_MS]: the song,
 * the episode over it, the episode's current chapter. The primary stream's title wears the
 * channel's short tag. Pure.
 */
object OnAirTitles {
    const val INTERVAL_MS = 4_000L

    /** Between the channel tag and the title. */
    private const val TAG_SEPARATOR = ": "

    /**
     * The titles to switch between, in order, or empty when there is nothing to name. The primary
     * stream is the song when there is one (a podcast over music is the second stream), otherwise
     * the episode playing by itself (a music podcast, or a podcast-only channel). [tag] is the
     * channel's short name.
     */
    fun lines(tag: String?, song: TitleLine?, episode: EpisodeTitles?): List<TitleLine> {
        val episodeLines = episode?.let {
            listOfNotNull(
                TitleLine(it.title, it.show),
                it.chapter?.let { chapter -> TitleLine(chapter, it.title) },
            )
        }.orEmpty()
        if (song == null) return episodeLines.mapIndexed { index, line -> if (index == 0) line.tagged(tag) else line }
        return listOf(song.tagged(tag)) + episodeLines
    }

    /** The line to show at [step], counting from 0 and wrapping. */
    fun at(lines: List<TitleLine>, step: Int): TitleLine? = if (lines.isEmpty()) null else lines[Math.floorMod(step, lines.size)]

    private fun TitleLine.tagged(tag: String?): TitleLine =
        if (tag.isNullOrBlank()) this else copy(title = tag + TAG_SEPARATOR + title)
}
