package app.viberadio.features

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Where computed blocks survive restarts, per [FeatureBlock.key] and content hash. Blocking. */
interface AudioFeatureStore {
    /** Content hash → value for one block; values of the wrong size are dropped. */
    fun read(block: FeatureBlock): Map<String, FloatArray>
    fun put(block: FeatureBlock, hash: String, value: FloatArray)

    /** Every block key with something stored, including blocks no extractor makes any more. */
    fun keys(): Set<String>
}

/**
 * One tab-separated file per block key in [dir] (`dsp@1.tsv`), one `hash, base64 floats` line
 * per song, appended as songs are analysed; the last line for a hash wins. A new block version
 * gets a new file, so swapping a model never mixes old and new numbers. Pure JVM.
 */
class FileAudioFeatureStore(private val dir: File) : AudioFeatureStore {

    private fun file(key: String) = File(dir, "$key.tsv")

    @Synchronized
    override fun read(block: FeatureBlock): Map<String, FloatArray> {
        val f = file(block.key)
        if (!f.exists()) return emptyMap()
        val out = HashMap<String, FloatArray>()
        f.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val value = runCatching { decode(line.substring(tab + 1)) }.getOrNull() ?: return@forEachLine
            if (value.size == block.size) out[line.substring(0, tab)] = value
        }
        return out
    }

    @Synchronized
    override fun put(block: FeatureBlock, hash: String, value: FloatArray) {
        require('\t' !in hash && '\n' !in hash)
        dir.mkdirs()
        file(block.key).appendText("$hash\t${encode(value)}\n")
    }

    override fun keys(): Set<String> =
        dir.listFiles { f -> f.name.endsWith(".tsv") }?.mapTo(LinkedHashSet()) { it.name.removeSuffix(".tsv") }.orEmpty()

    companion object {
        fun encode(value: FloatArray): String {
            val bytes = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            value.forEach(bytes::putFloat)
            return Base64.getEncoder().encodeToString(bytes.array())
        }

        fun decode(text: String): FloatArray {
            val bytes = ByteBuffer.wrap(Base64.getDecoder().decode(text)).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.remaining() / 4) { bytes.getFloat() }
        }
    }
}
