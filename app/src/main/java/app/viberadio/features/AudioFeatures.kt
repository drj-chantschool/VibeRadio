package app.viberadio.features

import app.viberadio.library.Track
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Every song's audio feature blocks, and the similarity index built from them (Similarity plan,
 * Phase 1 and 3). Songs only.
 *
 * [extractors] says which blocks exist right now: the built-in ones plus whatever models are
 * installed. A block is computed once per song (keyed on content hash, so it survives moves and
 * rescans) and recomputed only when its [FeatureBlock.key] changes. [analyse] fills in what's
 * missing, one song at a time, and is cancellable between songs.
 */
class AudioFeatures(
    private val store: AudioFeatureStore,
    /** The extractors in use; called once per [analyse] or [refresh], and closed after. */
    private val extractors: () -> List<FeatureExtractor>,
    /** Decodes a song's excerpts (mono, at its own rate), or null if it can't be read. Blocking. */
    private val decode: (Track, ExcerptPlan) -> List<AudioClip>?,
    /** The song's content hash, hashing the file if needed. */
    private val hashOf: suspend (Track) -> String?,
    /** The song's content hash if already known, without reading the file. */
    private val knownHash: (Track) -> String?,
    private val space: SimilaritySpace = SimilaritySpace(),
    private val plan: ExcerptPlan = ExcerptPlan(),
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Analysis progress, for Settings → Debug. */
    data class Status(
        val blocks: List<FeatureBlock> = emptyList(),
        /** Songs with every block / songs in the library at the last count. */
        val analysed: Int = 0,
        val total: Int = 0,
        val running: Boolean = false,
    )

    private val values = ConcurrentHashMap<String, MutableMap<String, FloatArray>>()
    private val mutex = Mutex()

    private val _index = MutableStateFlow(SimilarityIndex.EMPTY)

    /** Songs in one similarity space; rebuilt after each [analyse] and [refresh]. */
    val index: StateFlow<SimilarityIndex> = _index.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Hash → value for every stored block of the current extractors, for diagnostics. */
    fun snapshot(): Map<FeatureBlock, Map<String, FloatArray>> =
        _status.value.blocks.associateWith { block -> values[block.key].orEmpty().toMap() }

    /** Re-reads which blocks exist (a model installed or removed) and rebuilds the index. */
    suspend fun refresh(songs: List<Track>) = mutex.withLock {
        withContext(ioDispatcher) {
            val blocks = currentBlocks()
            rebuild(blocks, count(songs, blocks), songs.size)
        }
    }

    /**
     * Computes every missing block for [songs], then rebuilds the index. Cancelling stops
     * between songs; what was done is kept. Blocking work runs on the IO dispatcher.
     */
    suspend fun analyse(songs: List<Track>) = mutex.withLock {
        withContext(ioDispatcher) {
            val pipeline = FeaturePipeline(extractors())
            try {
                val blocks = pipeline.blocks
                load(blocks)
                var done = count(songs, blocks)
                _status.value = Status(blocks, done, songs.size, running = true)
                try {
                    for (track in songs) {
                        currentCoroutineContext().ensureActive()
                        val hash = runCatching { hashOf(track) }.getOrNull() ?: continue
                        val missing = blocks.filter { values[it.key]?.containsKey(hash) != true }
                        if (missing.isEmpty()) continue
                        val clips = runCatching { decode(track, plan) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                        val computed = pipeline.run(clips, missing.mapTo(HashSet()) { it.key })
                        for (block in missing) {
                            val value = computed[block.key] ?: continue
                            values.getOrPut(block.key) { ConcurrentHashMap() }[hash] = value
                            runCatching { store.put(block, hash, value) }
                        }
                        if (missing.all { computed.containsKey(it.key) }) {
                            done++
                            _status.update { it.copy(analysed = done) }
                        }
                    }
                } finally {
                    rebuild(blocks, count(songs, blocks), songs.size)
                }
            } finally {
                pipeline.close()
            }
        }
    }

    private fun currentBlocks(): List<FeatureBlock> {
        val list = extractors()
        list.forEach { runCatching { it.close() } }
        return list.map { it.block }.also(::load)
    }

    private fun load(blocks: List<FeatureBlock>) {
        for (block in blocks) {
            if (values.containsKey(block.key)) continue
            values[block.key] = ConcurrentHashMap(runCatching { store.read(block) }.getOrDefault(emptyMap()))
        }
    }

    /** How many of [songs] have every block, by their already-known hashes. */
    private fun count(songs: List<Track>, blocks: List<FeatureBlock>): Int {
        if (blocks.isEmpty()) return 0
        return songs.count { track ->
            val hash = runCatching { knownHash(track) }.getOrNull() ?: return@count false
            blocks.all { values[it.key]?.containsKey(hash) == true }
        }
    }

    private fun rebuild(blocks: List<FeatureBlock>, analysed: Int, total: Int) {
        _index.value = space.build(blocks.map { it to values[it.key].orEmpty() })
        _status.value = Status(blocks, analysed, total, running = false)
    }
}
