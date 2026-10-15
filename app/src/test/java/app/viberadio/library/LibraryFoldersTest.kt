package app.viberadio.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFoldersTest {

    private val counts = mapOf(
        "Music/Albums/Rock" to 10,
        "Music/Albums/Jazz" to 4,
        "Music/Albums" to 1,
        "Music/Singles" to 2,
        "Download" to 3,
    )
    private val known = FolderTree.known(counts)

    private fun node(path: String): FolderNode {
        fun find(nodes: List<FolderNode>): FolderNode? =
            nodes.firstNotNullOfOrNull { if (it.path == path) it else find(it.children) }
        return find(FolderTree.build(counts)) ?: error("no row for $path")
    }

    private fun state(path: String, e: FolderExclusions) = FolderTree.state(node(path), e, counts)

    private fun track(folder: String) =
        Track(id = folder.hashCode().toLong(), uri = "content://$folder", title = folder, artist = null, album = null, genre = null, durationMs = 60_000, kind = ContentKind.MUSIC, folder = folder)

    @Test
    fun `tree lists only folders with audio and those on the way, folding lone chains`() {
        val tree = FolderTree.build(mapOf("Music/Albums/Rock" to 5, "Music/Albums/Jazz" to 2, "Download/podcasts" to 1))
        assertEquals(listOf("Download/podcasts", "Music/Albums"), tree.map { it.name })
        assertEquals(listOf("Download/podcasts", "Music/Albums"), tree.map { it.path })
        val music = tree[1]
        assertEquals(listOf("Jazz", "Rock"), music.children.map { it.name })
        assertEquals(7, music.totalTracks)
        assertEquals(0, music.ownTracks)
    }

    @Test
    fun `tracks at the volume root get a row of their own`() {
        val tree = FolderTree.build(mapOf("" to 2, "Music" to 1))
        assertEquals(listOf("", "Music"), tree.map { it.path })
        val e = FolderTree.toggle(tree[0], FolderExclusions.NONE, mapOf("" to 2, "Music" to 1))
        assertTrue(e.excludes(""))
        assertFalse(e.excludes("Music"))
    }

    @Test
    fun `leaving a folder out drops it and everything under it, not look-alike siblings`() {
        val e = FolderExclusions.NONE.exclude("Music/Albums")
        assertTrue(e.excludes("Music/Albums"))
        assertTrue(e.excludes("Music/Albums/Rock"))
        assertTrue(e.excludes("Music/Albums/New one"))
        assertFalse(e.excludes("Music/Albums2"))
        assertFalse(e.excludes("Music/Singles"))
        val kept = e.filter(listOf(track("Music/Albums/Rock"), track("Music/Singles"), track("Music/Albums2")))
        assertEquals(listOf("Music/Singles", "Music/Albums2"), kept.map { it.folder })
    }

    @Test
    fun `states follow what is left out`() {
        val e = FolderExclusions.NONE.exclude("Music/Albums/Jazz")
        assertEquals(FolderState.Out, state("Music/Albums/Jazz", e))
        assertEquals(FolderState.Partly, state("Music/Albums", e))
        assertEquals(FolderState.Partly, state("Music", e))
        assertEquals(FolderState.In, state("Music/Singles", e))
        assertEquals(FolderState.In, state("Download", e))
    }

    @Test
    fun `putting back a subfolder of a left-out folder keeps the rest out`() {
        val out = FolderTree.toggle(node("Music"), FolderExclusions.NONE, counts)
        assertEquals(FolderState.Out, state("Music", out))
        val back = FolderTree.toggle(node("Music/Albums/Rock"), out, counts)
        assertFalse(back.excludes("Music/Albums/Rock"))
        assertTrue(back.excludes("Music/Albums/Jazz"))
        assertTrue(back.excludes("Music/Albums"))
        assertTrue(back.excludes("Music/Singles"))
        assertTrue(back.excludes("Music"))
        assertEquals(FolderState.Partly, state("Music", back))
        assertEquals(FolderState.In, state("Music/Albums/Rock", back))
    }

    @Test
    fun `a partly-in folder's checkbox puts all of it back, and an in folder's leaves it out`() {
        val partly = FolderExclusions.NONE.exclude("Music/Albums/Jazz")
        val all = FolderTree.toggle(node("Music"), partly, counts)
        assertEquals(FolderExclusions.NONE, all)
        val out = FolderTree.toggle(node("Music/Singles"), all, counts)
        assertEquals(setOf("Music/Singles"), out.subtrees)
    }

    @Test
    fun `leaving out a folder absorbs entries below it`() {
        val e = FolderExclusions.NONE.exclude("Music/Albums/Jazz").include("Music/Albums/Jazz", known).exclude("Music/Albums/Rock")
            .exclude("Music/Albums")
        assertEquals(setOf("Music/Albums"), e.subtrees)
        assertEquals(emptySet<String>(), e.filesOnly)
    }

    @Test
    fun `folders from MediaStore columns`() {
        assertEquals("Music/Rock", LibraryFolderPaths.fromRelativePath("Music/Rock/", "external_primary"))
        assertEquals("", LibraryFolderPaths.fromRelativePath(null, "external_primary"))
        assertEquals("1234-ABCD/Music", LibraryFolderPaths.fromRelativePath("Music/", "1234-ABCD"))
        assertEquals("Music/Rock", LibraryFolderPaths.fromFilePath("/storage/emulated/0/Music/Rock/a.mp3", "/storage/emulated/0"))
        assertEquals("", LibraryFolderPaths.fromFilePath("/storage/emulated/0/a.mp3", "/storage/emulated/0"))
        assertEquals("storage/1234-ABCD/Music", LibraryFolderPaths.fromFilePath("/storage/1234-ABCD/Music/a.mp3", "/storage/emulated/0"))
    }
}
