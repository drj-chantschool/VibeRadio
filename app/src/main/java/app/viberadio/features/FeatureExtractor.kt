package app.viberadio.features

import java.io.Closeable

// Audio feature extraction (Similarity plan): each extractor fills one named block of a song's
// audio feature vector. Blocks are stored and versioned separately, so adding, removing or
// swapping one model only (re)computes its own block. Pure Kotlin: no Android types here.

/**
 * What one extractor produces: a stable [id] (never reused for a different meaning), a
 * [version] bumped whenever the numbers change meaning (new weights, new frontend), and one
 * name per dimension.
 */
data class FeatureBlock(
    val id: String,
    val version: Int,
    val names: List<String>,
    /**
     * How much this block counts in song similarity relative to the others; 0 keeps it stored
     * (and exported) without affecting neighbours.
     */
    val similarityWeight: Double = 1.0,
) {
    init {
        require(id.isNotBlank() && id.none { it == '@' || it == '\t' || it == '/' || it.isWhitespace() }) { "bad block id '$id'" }
        require(version >= 1 && names.isNotEmpty() && similarityWeight >= 0)
    }

    /** Where it's stored: the ID and the version, so a new version never reads old numbers. */
    val key: String get() = "$id@$version"

    val size: Int get() = names.size
}

/** One extractor's result for a song. */
class Extraction(
    /** The block's stored value, [FeatureBlock.size] long (usually the mean over [frames]). */
    val value: FloatArray,
    /** Per-frame (or per-patch) outputs, for extractors that read this one ([FeatureExtractor.upstream]). Not stored. */
    val frames: List<FloatArray> = listOf(value),
)

/**
 * Turns a song's excerpts (or another extractor's frames) into one [FeatureBlock].
 *
 * To add a model: implement this (or describe an ONNX model with a [ModelSpec]) and list it in
 * `AppContainer`'s extractors. Nothing else names a concrete extractor.
 */
interface FeatureExtractor : Closeable {
    val block: FeatureBlock

    /** The rate excerpts arrive at; [FeaturePipeline] resamples. Ignored when [upstream] is set. */
    val sampleRate: Int

    /** The [FeatureBlock.id] whose frames this reads instead of audio (a classifier head on an embedding), or null. */
    val upstream: String? get() = null

    /**
     * The block for one song, or null if this song can't be analysed by it. [clips] are at
     * [sampleRate]; [upstream] is that extractor's frames when [FeatureExtractor.upstream] is set.
     * Blocking and CPU-bound.
     */
    fun extract(clips: List<AudioClip>, upstream: List<FloatArray>?): Extraction?

    /** Releases a loaded model. The extractor may be used again afterwards (it reloads). */
    override fun close() = Unit
}

/**
 * Runs extractors over one song, upstream ones first, resampling the excerpts once per rate.
 * Extractors with a missing or failing upstream are skipped.
 */
class FeaturePipeline(val extractors: List<FeatureExtractor>) : Closeable {
    init {
        val ids = extractors.map { it.block.id }
        require(ids.size == ids.toSet().size) { "duplicate extractor IDs: $ids" }
    }

    val blocks: List<FeatureBlock> get() = extractors.map { it.block }

    /**
     * The blocks among [wanted] (by key) for one song, from its excerpts at their decoded rate.
     * Upstream extractors run as needed even if their own block isn't wanted.
     */
    fun run(clips: List<AudioClip>, wanted: Set<String>): Map<String, FloatArray> {
        if (clips.isEmpty()) return emptyMap()
        val byId = extractors.associateBy { it.block.id }
        val needed = LinkedHashSet<String>()
        fun need(id: String, depth: Int) {
            require(depth <= extractors.size) { "extractor cycle through '$id'" }
            val e = byId[id] ?: return
            e.upstream?.let { need(it, depth + 1) }
            needed += id
        }
        extractors.filter { it.block.key in wanted }.forEach { need(it.block.id, 0) }

        val resampled = HashMap<Int, List<AudioClip>>()
        val frames = HashMap<String, List<FloatArray>>()
        val out = LinkedHashMap<String, FloatArray>()
        for (id in needed) {
            val e = byId.getValue(id)
            val upstreamId = e.upstream
            val input = if (upstreamId != null) frames[upstreamId] ?: continue else null
            val audio = if (e.upstream == null) resampled.getOrPut(e.sampleRate) { clips.map { it.at(e.sampleRate) } } else emptyList()
            val result = runCatching { e.extract(audio, input) }.getOrNull() ?: continue
            if (result.value.size != e.block.size || result.value.any { it.isNaN() || it.isInfinite() }) continue
            frames[id] = result.frames
            if (e.block.key in wanted) out[e.block.key] = result.value
        }
        return out
    }

    override fun close() {
        extractors.forEach { runCatching { it.close() } }
    }
}

/** Mean of [rows] (all the same length), or null if there are none. */
fun meanOf(rows: List<FloatArray>): FloatArray? {
    if (rows.isEmpty()) return null
    val out = FloatArray(rows.first().size)
    for (row in rows) for (i in out.indices) out[i] += row[i]
    for (i in out.indices) out[i] /= rows.size
    return out
}
