package app.viberadio.features

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs the ONNX model a [ModelSpec] describes (ONNX Runtime, CPU). The session is loaded on
 * first use and released on [close], so models only hold memory while songs are analysed.
 * Everything but the runtime call is in [ModelFrontend].
 */
class OnnxModelExtractor(private val spec: ModelSpec, private val dir: File) : FeatureExtractor {

    override val block: FeatureBlock get() = spec.block

    override val sampleRate: Int = ModelFrontend.sampleRate(spec.input).takeIf { it > 0 } ?: SpectralFeatures.RATE

    override val upstream: String? = (spec.input as? ModelInput.Upstream)?.blockId

    private var session: OrtSession? = null

    @Synchronized
    private fun session(): OrtSession = session ?: environment()
        .createSession(File(dir, spec.modelFile).absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
        .also { session = it }

    @Synchronized
    override fun extract(clips: List<AudioClip>, upstream: List<FloatArray>?): Extraction? {
        val units = ModelFrontend.units(spec.input, clips, upstream)
        if (units.isEmpty()) return null
        val s = session()
        val env = environment()
        val inputName = spec.inputName ?: s.inputNames.first()
        val rows = ArrayList<FloatArray>()
        for (batch in ModelFrontend.batches(spec.inputShape, units)) {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(batch.data), batch.shape).use { tensor ->
                s.run(mapOf(inputName to tensor)).use { result ->
                    val value = spec.outputName?.let { name -> result.get(name).orElse(null) } ?: result.get(0)
                    val buffer = (value as OnnxTensor).floatBuffer
                    val output = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    rows += ModelFrontend.rows(output, spec.block.size, spec.activation)
                }
            }
        }
        val value = meanOf(rows) ?: return null
        return Extraction(value, rows)
    }

    /** The runtime, with its telemetry off (its Android initializer is also removed from the manifest). */
    private fun environment(): OrtEnvironment =
        OrtEnvironment.getEnvironment().also { env -> runCatching { env.setTelemetry(false) } }

    @Synchronized
    override fun close() {
        session?.close()
        session = null
    }
}
