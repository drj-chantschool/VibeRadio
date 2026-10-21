package app.viberadio.features

import java.io.File
import java.util.Properties
import kotlin.math.exp

/**
 * A learned model (ONNX) described by a manifest, so models are swapped by dropping files in
 * the models folder rather than by code changes. One model fills one [FeatureBlock]: an
 * embedding (`dims=1280`) or a classifier's outputs (`labelsFile=…`, one label per line).
 *
 * Manifest: `<anything>.properties` next to the model, e.g. a Discogs-EffNet embedding:
 *
 * ```
 * id=effnet
 * version=1
 * model=discogs-effnet-bsdynamic-1.onnx
 * input=mel
 * sampleRate=16000
 * nFft=512
 * hop=256
 * nMels=96
 * scale=slaney
 * log=log10_1p_10000
 * patchFrames=128
 * patchHop=62
 * inputShape=-1,128,96
 * outputName=embeddings
 * dims=1280
 * ```
 *
 * and a classifier head reading it:
 *
 * ```
 * id=mood_happy
 * version=1
 * model=mood_happy-discogs-effnet-1.onnx
 * input=upstream
 * upstream=effnet
 * inputShape=-1,1280
 * labelsFile=mood_happy.txt
 * activation=none
 * ```
 *
 * `input` is `waveform` (raw samples: `sampleRate`, `window`, `hop` in samples), `mel` (a log-mel
 * frontend computed here, see [MelSpec]; patches of `patchFrames` frames every `patchHop`) or
 * `upstream` (another block's frames). In `inputShape`, −1 marks the batch dimension, filled
 * with the number of windows or patches; without one each window runs on its own. Only float
 * inputs and outputs are supported. Bump `version` whenever the model file or frontend changes.
 */
data class ModelSpec(
    val block: FeatureBlock,
    /** The model's file name, in the same folder as the manifest. */
    val modelFile: String,
    val input: ModelInput,
    /** The model input to feed; null = its first input. */
    val inputName: String?,
    val inputShape: List<Long>,
    /** The output to read; null = its first output. */
    val outputName: String?,
    val activation: Activation,
) {
    enum class Activation { NONE, SIGMOID, SOFTMAX }

    companion object {
        /** Parses a manifest, or explains why not. [labels] reads a labels file named in it. */
        fun parse(manifest: Properties, labels: (String) -> List<String>): Result<ModelSpec> = runCatching {
            fun str(key: String) = manifest.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            fun req(key: String) = str(key) ?: error("missing '$key'")
            fun int(key: String) = str(key)?.toIntOrNull()
            fun reqInt(key: String) = int(key) ?: error("'$key' must be a whole number")
            fun dbl(key: String) = str(key)?.toDoubleOrNull()

            val names = str("labelsFile")?.let { file -> labels(file).map { it.trim() }.filter { it.isNotEmpty() } }
                ?: str("labels")?.split('|')?.map { it.trim() }
                ?: int("dims")?.let { n -> (0 until n).map { "e$it" } }
                ?: error("needs 'dims', 'labels' or 'labelsFile'")
            val block = FeatureBlock(
                id = req("id"),
                version = reqInt("version"),
                names = names,
                similarityWeight = dbl("similarityWeight") ?: 1.0,
            )
            val input = when (req("input").lowercase()) {
                "waveform" -> ModelInput.Waveform(
                    sampleRate = reqInt("sampleRate"),
                    window = reqInt("window"),
                    hop = int("hop") ?: reqInt("window"),
                )
                "mel" -> ModelInput.Mel(
                    mel = MelSpec(
                        sampleRate = reqInt("sampleRate"),
                        nFft = reqInt("nFft"),
                        hop = reqInt("hop"),
                        nMels = reqInt("nMels"),
                        fMin = dbl("fMin") ?: 0.0,
                        fMax = dbl("fMax"),
                        scale = str("scale")?.let { MelScale.valueOf(it.uppercase()) } ?: MelScale.SLANEY,
                        areaNormalize = str("areaNormalize")?.toBooleanStrict() ?: true,
                        power = int("power") ?: 2,
                        log = str("log")?.let { LogCompression.valueOf(it.uppercase()) } ?: LogCompression.LOG10_1P_10000,
                    ),
                    patchFrames = reqInt("patchFrames"),
                    patchHop = int("patchHop") ?: reqInt("patchFrames"),
                )
                "upstream" -> ModelInput.Upstream(req("upstream"))
                else -> error("'input' must be waveform, mel or upstream")
            }
            val shape = req("inputShape").split(',').map { it.trim().toLong() }
            require(shape.count { it == -1L } <= 1 && shape.all { it == -1L || it > 0 }) { "bad inputShape $shape" }
            ModelSpec(
                block = block,
                modelFile = req("model"),
                input = input,
                inputName = str("inputName"),
                inputShape = shape,
                outputName = str("outputName"),
                activation = str("activation")?.let { Activation.valueOf(it.uppercase()) } ?: Activation.NONE,
            )
        }

        /** Every valid manifest in [dir] whose model file is there too; the rest with the reason. */
        fun scan(dir: File): Pair<List<ModelSpec>, Map<String, String>> {
            val specs = ArrayList<ModelSpec>()
            val problems = LinkedHashMap<String, String>()
            dir.listFiles { f -> f.name.endsWith(".properties") }.orEmpty().sortedBy { it.name }.forEach { file ->
                val props = Properties()
                val parsed = runCatching { file.inputStream().use { props.load(it) } }.mapCatching {
                    parse(props) { name -> File(dir, name).readLines() }.getOrThrow()
                }
                parsed.onSuccess { spec ->
                    when {
                        !File(dir, spec.modelFile).isFile -> problems[file.name] = "model file '${spec.modelFile}' missing"
                        specs.any { it.block.id == spec.block.id } -> problems[file.name] = "duplicate id '${spec.block.id}'"
                        else -> specs += spec
                    }
                }.onFailure { problems[file.name] = it.message ?: it.toString() }
            }
            return specs to problems
        }
    }
}

/** What a model is fed, one unit (window, patch or upstream frame) at a time. */
sealed interface ModelInput {
    data class Waveform(val sampleRate: Int, val window: Int, val hop: Int) : ModelInput
    data class Mel(val mel: MelSpec, val patchFrames: Int, val patchHop: Int) : ModelInput
    data class Upstream(val blockId: String) : ModelInput
}

/** Pure model plumbing: units in, rows out. The runtime call itself is in [OnnxModelExtractor]. */
object ModelFrontend {

    /** The rate a model wants its audio at; 0 for an upstream model. */
    fun sampleRate(input: ModelInput): Int = when (input) {
        is ModelInput.Waveform -> input.sampleRate
        is ModelInput.Mel -> input.mel.sampleRate
        is ModelInput.Upstream -> 0
    }

    /** The units to feed for one song; a clip shorter than one unit is zero-padded to one. */
    fun units(input: ModelInput, clips: List<AudioClip>, upstream: List<FloatArray>?): List<FloatArray> = when (input) {
        is ModelInput.Upstream -> upstream.orEmpty()
        is ModelInput.Waveform -> clips.flatMap { clip -> windows(clip.samples.size, input.window, input.hop).map { start -> clip.samples.copyRange(start, input.window) } }
        is ModelInput.Mel -> clips.flatMap { clip ->
            val frames = MelSpectrogram.of(clip, input.mel)
            windows(frames.size, input.patchFrames, input.patchHop).map { start ->
                FloatArray(input.patchFrames * input.mel.nMels).also { patch ->
                    for (f in 0 until input.patchFrames) frames.getOrNull(start + f)?.copyInto(patch, f * input.mel.nMels)
                }
            }
        }
    }

    private fun windows(length: Int, size: Int, hop: Int): List<Int> =
        if (length <= size) listOf(0) else (0..length - size step hop).toList()

    private fun FloatArray.copyRange(start: Int, size: Int): FloatArray =
        FloatArray(size).also { out -> copyInto(out, 0, start, minOf(this.size, start + size)) }

    /** One model call: its input shape and data. */
    class Batch(val shape: LongArray, val data: FloatArray)

    /**
     * [units] grouped into calls that fit [shape]: all at once if it has a −1 dimension
     * (filled with the count, at most [maxBatch]), else one per call.
     */
    fun batches(shape: List<Long>, units: List<FloatArray>, maxBatch: Int = 32): List<Batch> {
        if (units.isEmpty()) return emptyList()
        val unitSize = units.first().size
        val fixed = shape.filter { it != -1L }.fold(1L) { a, b -> a * b }
        require(fixed == unitSize.toLong()) { "inputShape $shape doesn't hold a unit of $unitSize values" }
        if (-1L !in shape) return units.map { Batch(shape.toLongArray(), it) }
        return units.chunked(maxBatch).map { chunk ->
            val data = FloatArray(chunk.size * unitSize)
            chunk.forEachIndexed { i, unit -> unit.copyInto(data, i * unitSize) }
            Batch(shape.map { if (it == -1L) chunk.size.toLong() else it }.toLongArray(), data)
        }
    }

    /** A model's flat output as rows of [dims], each through [activation]. */
    fun rows(output: FloatArray, dims: Int, activation: ModelSpec.Activation): List<FloatArray> {
        require(output.isNotEmpty() && output.size % dims == 0) { "output of ${output.size} values isn't rows of $dims" }
        return (0 until output.size / dims).map { r ->
            val row = output.copyOfRange(r * dims, (r + 1) * dims)
            when (activation) {
                ModelSpec.Activation.NONE -> Unit
                ModelSpec.Activation.SIGMOID -> for (i in row.indices) row[i] = (1.0 / (1.0 + exp(-row[i].toDouble()))).toFloat()
                ModelSpec.Activation.SOFTMAX -> {
                    val max = row.max()
                    var sum = 0.0
                    for (i in row.indices) {
                        row[i] = exp((row[i] - max).toDouble()).toFloat()
                        sum += row[i]
                    }
                    for (i in row.indices) row[i] = (row[i] / sum).toFloat()
                }
            }
            row
        }
    }
}
