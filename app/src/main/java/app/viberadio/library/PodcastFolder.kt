package app.viberadio.library

/**
 * Settings → Podcasts → Podcast folder: one library folder whose audio is a podcast show rather
 * than songs. Everything in it, subfolders included, is left out of the song channels and handed
 * to the podcast pipeline as one show, queued oldest first like any other. [path] is a folder as
 * [Track.folder] gives it; null means no folder is treated this way. Pure.
 */
data class PodcastFolder(val path: String?) {

    /** True when a track in [folder] belongs to the podcast folder rather than the songs. */
    fun contains(folder: String): Boolean =
        path != null && (folder == path || FolderExclusions.isUnder(folder, path))

    /** The tracks in the podcast folder: the show's episodes. */
    fun episodes(tracks: List<Track>): List<Track> = if (path == null) emptyList() else tracks.filter { contains(it.folder) }

    /** The tracks outside it: what the song pipeline sees. */
    fun songs(tracks: List<Track>): List<Track> = if (path == null) tracks else tracks.filterNot { contains(it.folder) }

    /** The show's title: the folder's own name ("Podcasts", or "Talks" for "Audio/Talks"). */
    val title: String? get() = path?.substringAfterLast('/')

    companion object {
        /** Android's standard podcast folder, so podcasts saved by other apps land on the podcast page. */
        const val DEFAULT_PATH = "Podcasts"

        val DEFAULT = PodcastFolder(DEFAULT_PATH)
        val NONE = PodcastFolder(null)

        /**
         * Tidies a stored or chosen path: no leading, trailing or doubled slashes. Blank (the
         * volume root, which would take the whole library) means none.
         */
        fun of(path: String?): PodcastFolder =
            PodcastFolder(path?.split('/')?.filter { it.isNotBlank() }?.joinToString("/")?.takeIf { it.isNotEmpty() })

        /**
         * What identifies an episode across scans: where it is, what it's called and how big it
         * is. MediaStore IDs can change on a rescan; these don't unless the file does.
         */
        fun episodeKey(track: Track): String = "${track.folder}\n${track.title}\n${track.sizeBytes}"
    }
}
