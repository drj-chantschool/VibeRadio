package app.viberadio.library

/**
 * Which folders are left out of the library (Settings → Audio → Library folders). Everything is
 * in by default, so new music shows up without a visit to settings.
 *
 * Folders are `/`-separated paths as [Track.folder] gives them. [subtrees] leaves out a folder and
 * everything under it, so a new subfolder of a left-out folder stays out. [filesOnly] leaves out
 * only the files directly in a folder; it appears when a subfolder of a left-out folder is put
 * back, so the rest of that folder stays out. Pure.
 */
data class FolderExclusions(
    val subtrees: Set<String> = emptySet(),
    val filesOnly: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = subtrees.isEmpty() && filesOnly.isEmpty()

    /** True when a track in [folder] is left out. */
    fun excludes(folder: String): Boolean =
        folder in filesOnly || subtrees.any { it == folder || isUnder(folder, it) }

    fun filter(tracks: List<Track>): List<Track> =
        if (isEmpty) tracks else tracks.filterNot { excludes(it.folder) }

    /** Leaves out [path] and everything under it. */
    fun exclude(path: String): FolderExclusions = FolderExclusions(
        subtrees = subtrees.filterNot { it == path || isUnder(it, path) }.toSet() + path,
        filesOnly = filesOnly.filterNot { it == path || isUnder(it, path) }.toSet(),
    )

    /**
     * Puts [path] and everything under it back. A left-out folder above it is split up: its own
     * files and its other subfolders ([known], the folders the tree shows) stay out.
     */
    fun include(path: String, known: Set<String>): FolderExclusions {
        val subtrees = subtrees.filterNot { it == path || isUnder(it, path) }.toMutableSet()
        val filesOnly = filesOnly.filterNot { it == path || isUnder(it, path) }.toMutableSet()
        for (ancestor in this.subtrees.filter { isUnder(path, it) }) {
            subtrees -= ancestor
            // Every folder from the left-out one down to the parent of [path] keeps its own files out,
            // and each of its subfolders that isn't on the way to [path] stays out whole.
            var at = ancestor
            while (at != path) {
                val next = path.substring(0, path.indexOf('/', at.length + 1).let { if (it < 0) path.length else it })
                filesOnly += at
                known.filter { parentOf(it) == at && it != next }.forEach { subtrees += it }
                at = next
            }
        }
        return FolderExclusions(subtrees.normalised(), filesOnly.filterNot { f -> subtrees.any { it == f || isUnder(f, it) } }.toSet())
    }

    private fun Set<String>.normalised(): Set<String> = filterNot { f -> any { it != f && isUnder(f, it) } }.toSet()

    companion object {
        val NONE = FolderExclusions()

        /** True when [folder] is strictly inside [ancestor]. */
        fun isUnder(folder: String, ancestor: String): Boolean =
            ancestor.isEmpty() && folder.isNotEmpty() || folder.length > ancestor.length + 1 && folder.startsWith(ancestor) && folder[ancestor.length] == '/'

        fun parentOf(folder: String): String? =
            if (folder.isEmpty()) null else folder.substringBeforeLast('/', missingDelimiterValue = "")
    }
}

/** How much of a folder is in the library. */
enum class FolderState { In, Partly, Out }

/**
 * One row of the folder tree. [path] is the folder the checkbox acts on; [name] may span several
 * levels ("Music/Albums") where folders holding nothing but one subfolder are folded together.
 */
data class FolderNode(
    val path: String,
    val name: String,
    /** Tracks directly in this folder (after folding, in [path]). */
    val ownTracks: Int,
    /** Tracks in this folder and everything under it. */
    val totalTracks: Int,
    val children: List<FolderNode>,
)

/**
 * The folder tree for Settings → Audio → Library folders: only folders that hold audio, and the
 * folders on the way to them. Built from the scan before anything is left out, so a left-out
 * folder can be put back. Pure.
 */
object FolderTree {

    /** Folder path → number of tracks directly in it. */
    fun counts(tracks: List<Track>): Map<String, Int> = tracks.groupingBy { it.folder }.eachCount()

    /** Every folder in the tree: those with tracks and all their ancestors. */
    fun known(counts: Map<String, Int>): Set<String> = buildSet {
        for (folder in counts.keys) {
            var at: String? = folder
            while (at != null && add(at)) at = FolderExclusions.parentOf(at)
        }
    }

    /** The top-level rows, sorted by name (case-insensitive). */
    fun build(counts: Map<String, Int>): List<FolderNode> {
        val known = known(counts)
        val children = known.filter { it.isNotEmpty() }.groupBy { FolderExclusions.parentOf(it)!! }
        fun node(path: String, name: String): FolderNode {
            val kids = children[path].orEmpty()
            val own = counts[path] ?: 0
            // A folder with no tracks of its own and one subfolder folds into it.
            if (own == 0 && kids.size == 1 && path.isNotEmpty()) {
                val only = kids.single()
                return node(only, name + "/" + only.substringAfterLast('/'))
            }
            val nodes = kids.map { node(it, it.substringAfterLast('/')) }.sortedBy { it.name.lowercase() }
            return FolderNode(path, name, own, own + nodes.sumOf { it.totalTracks }, nodes)
        }
        // Tracks at the volume root (folder "") show as a row of their own; the rest are its children's rows.
        val top = children[""].orEmpty().map { node(it, it) }.sortedBy { it.name.lowercase() }
        val rootOwn = counts[""] ?: 0
        return if (rootOwn > 0) listOf(FolderNode("", "/", rootOwn, rootOwn, emptyList())) + top else top
    }

    /** In when no track under [node] is left out, Out when all are, Partly otherwise. */
    fun state(node: FolderNode, exclusions: FolderExclusions, counts: Map<String, Int>): FolderState {
        val folders = counts.keys.filter { it == node.path || (node.children.isNotEmpty() && FolderExclusions.isUnder(it, node.path)) }
        val out = folders.count { exclusions.excludes(it) }
        return when (out) {
            0 -> FolderState.In
            folders.size -> FolderState.Out
            else -> FolderState.Partly
        }
    }

    /** The checkbox on [node]: leaves it out when it's all in, otherwise puts all of it back. */
    fun toggle(node: FolderNode, exclusions: FolderExclusions, counts: Map<String, Int>): FolderExclusions {
        // The volume-root row stands for its own files only.
        if (node.path.isEmpty()) {
            val out = "" in exclusions.filesOnly
            return exclusions.copy(filesOnly = if (out) exclusions.filesOnly - "" else exclusions.filesOnly + "")
        }
        return if (state(node, exclusions, counts) == FolderState.In) {
            exclusions.exclude(node.path)
        } else {
            exclusions.include(node.path, known(counts))
        }
    }
}

/** Turns MediaStore's folder columns into [Track.folder]. Pure. */
object LibraryFolderPaths {

    /** The main shared-storage volume's name on API 29+ (`MediaStore.VOLUME_EXTERNAL_PRIMARY`). */
    const val PRIMARY_VOLUME = "external_primary"

    /** API 29+: RELATIVE_PATH ("Music/Rock/") and VOLUME_NAME. Other volumes are prefixed with their name. */
    fun fromRelativePath(relativePath: String?, volume: String?): String {
        val folder = clean(relativePath.orEmpty())
        return if (volume == null || volume == PRIMARY_VOLUME) folder else listOf(volume, folder).filter { it.isNotEmpty() }.joinToString("/")
    }

    /** API 26–28: the file's absolute path, made relative to [primaryRoot] when it's on the main volume. */
    fun fromFilePath(path: String?, primaryRoot: String): String {
        val dir = path?.substringBeforeLast('/', missingDelimiterValue = "").orEmpty()
        val root = primaryRoot.trimEnd('/')
        return clean(if (dir == root || dir.startsWith("$root/")) dir.substring(root.length) else dir)
    }

    private fun clean(folder: String): String = folder.split('/').filter { it.isNotEmpty() }.joinToString("/")
}
