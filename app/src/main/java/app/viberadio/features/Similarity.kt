package app.viberadio.features

import java.util.Random
import kotlin.math.sqrt

/** Another song and how far it is: 0 = same direction in feature space, up to 2 = opposite. */
data class Neighbour(val hash: String, val distance: Double)

/**
 * Songs as unit vectors in one combined feature space, for nearest-neighbour lookups. Built by
 * [SimilaritySpace]; immutable, so the learner can read it while a new one is built.
 */
class SimilarityIndex internal constructor(private val vectors: Map<String, FloatArray>) {

    val size: Int get() = vectors.size

    operator fun contains(hash: String) = hash in vectors

    /** Cosine distance between two songs, or null if either isn't in the index. */
    fun distance(a: String, b: String): Double? {
        val x = vectors[a] ?: return null
        val y = vectors[b] ?: return null
        return 1.0 - dot(x, y)
    }

    /**
     * Up to [k] songs among [among] closest to [hash], nearest first; never [hash] itself.
     * Empty if [hash] isn't in the index. Brute force: O(|among| × dimensions).
     */
    fun nearest(hash: String, among: Collection<String>, k: Int): List<Neighbour> {
        val x = vectors[hash] ?: return emptyList()
        if (k <= 0) return emptyList()
        val best = ArrayList<Neighbour>(k + 1)
        for (other in among) {
            if (other == hash) continue
            val y = vectors[other] ?: continue
            val d = 1.0 - dot(x, y)
            if (best.size == k && d >= best.last().distance) continue
            var i = best.size
            while (i > 0 && best[i - 1].distance > d) i--
            best.add(i, Neighbour(other, d))
            if (best.size > k) best.removeAt(best.size - 1)
        }
        return best
    }

    companion object {
        val EMPTY = SimilarityIndex(emptyMap())

        private fun dot(x: FloatArray, y: FloatArray): Double {
            var s = 0.0
            for (i in x.indices) s += x[i] * y[i]
            return s
        }
    }
}

/**
 * How blocks combine into one similarity space.
 *
 * Each block is standardised over the library (z-score per dimension), large blocks are
 * randomly projected down to [projectTo] dimensions (distances survive, Johnson–Lindenstrauss),
 * each is scaled to unit length, then weighted by √[FeatureBlock.similarityWeight]. So a
 * 1280-dimension embedding counts as much as a 12-dimension chroma block at equal weight, and
 * the combined cosine is the weighted mean of the per-block cosines. Only songs that have every
 * weighted block are indexed; the rest have no neighbours until they're analysed.
 */
class SimilaritySpace(
    private val projectTo: Int = 64,
    /** Overrides a block's own [FeatureBlock.similarityWeight], by block ID. */
    private val weights: Map<String, Double> = emptyMap(),
) {
    fun build(blocks: List<Pair<FeatureBlock, Map<String, FloatArray>>>): SimilarityIndex {
        val used = blocks.filter { (block, values) -> weightOf(block) > 0 && values.isNotEmpty() }
        if (used.isEmpty()) return SimilarityIndex.EMPTY
        val hashes = used.map { it.second.keys }.reduce { a, b -> a intersect b }
        if (hashes.isEmpty()) return SimilarityIndex.EMPTY
        val parts = used.map { (block, values) -> block to transform(block, hashes.associateWith { values.getValue(it) }) }
        val totalWeight = parts.sumOf { weightOf(it.first) }
        val combined = hashes.associateWith { hash ->
            val out = ArrayList<Float>()
            for ((block, transformed) in parts) {
                val scale = sqrt(weightOf(block) / totalWeight).toFloat()
                transformed.getValue(hash).forEach { out += it * scale }
            }
            out.toFloatArray()
        }
        return SimilarityIndex(combined)
    }

    private fun weightOf(block: FeatureBlock) = weights[block.id] ?: block.similarityWeight

    /** Standardised, projected and unit-length vectors for one block. */
    private fun transform(block: FeatureBlock, values: Map<String, FloatArray>): Map<String, FloatArray> {
        val dims = block.size
        val n = values.size
        val mean = DoubleArray(dims)
        val sq = DoubleArray(dims)
        for (v in values.values) for (i in 0 until dims) {
            mean[i] += v[i]
            sq[i] += v[i].toDouble() * v[i]
        }
        for (i in 0 until dims) mean[i] /= n
        val std = DoubleArray(dims) { i -> sqrt((sq[i] / n - mean[i] * mean[i]).coerceAtLeast(0.0)).takeIf { it > 1e-6 } ?: 1.0 }
        val projection = if (dims > projectTo) projection(block.key, dims, projectTo) else null
        return values.mapValues { (_, v) ->
            val z = FloatArray(dims) { i -> ((v[i] - mean[i]) / std[i]).toFloat() }
            val p = projection?.let { m -> FloatArray(projectTo) { r -> var s = 0f; for (i in 0 until dims) s += m[r][i] * z[i]; s } } ?: z
            normalise(p)
        }
    }

    companion object {
        /** A fixed Gaussian projection per block key, so rebuilds give the same space. */
        private fun projection(seed: String, from: Int, to: Int): Array<FloatArray> {
            val random = Random(seed.hashCode().toLong())
            return Array(to) { FloatArray(from) { random.nextGaussian().toFloat() } }
        }

        private fun normalise(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x * x
            val norm = sqrt(s)
            // A song exactly at the library mean has no direction; it stays a zero vector (distance 1 from all).
            if (norm < 1e-9) return v
            return FloatArray(v.size) { (v[it] / norm).toFloat() }
        }
    }
}
