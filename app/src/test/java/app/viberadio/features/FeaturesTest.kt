package app.viberadio.features

import app.viberadio.library.Track
import app.viberadio.track
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FeaturesTest {

    /** An extractor that returns fixed numbers and counts its calls. */
    private class Fake(
        id: String,
        dims: Int = 2,
        override val sampleRate: Int = 16_000,
        override val upstream: String? = null,
        version: Int = 1,
        weight: Double = 1.0,
        val result: (List<AudioClip>, List<FloatArray>?) -> Extraction? = { _, _ -> Extraction(FloatArray(dims) { it.toFloat() }) },
    ) : FeatureExtractor {
        override val block = FeatureBlock(id, version, (0 until dims).map { "$id$it" }, weight)
        var calls = 0
        var closed = 0
        var rates = mutableListOf<Int>()
        override fun extract(clips: List<AudioClip>, upstream: List<FloatArray>?): Extraction? {
            calls++
            rates += clips.map { it.sampleRate }
            return result(clips, upstream)
        }
        override fun close() {
            closed++
        }
    }

    private val clip = AudioClip(FloatArray(4_410), 44_100)

    // Blocks

    @Test
    fun `block keys carry the version`() {
        assertEquals("dsp@1", FeatureBlock("dsp", 1, listOf("a")).key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `block ids can't contain separators`() {
        FeatureBlock("a@b", 1, listOf("x"))
    }

    // Pipeline

    @Test
    fun `pipeline resamples once per rate and runs only what's wanted`() {
        val a = Fake("a")
        val b = Fake("b", sampleRate = 22_050)
        val c = Fake("c")
        val out = FeaturePipeline(listOf(a, b, c)).run(listOf(clip), setOf("a@1", "b@1"))
        assertEquals(setOf("a@1", "b@1"), out.keys)
        assertEquals(listOf(16_000), a.rates)
        assertEquals(listOf(22_050), b.rates)
        assertEquals(0, c.calls)
    }

    @Test
    fun `a head runs on its upstream's frames, and pulls the upstream in when needed`() {
        val embedding = Fake("emb", result = { _, _ -> Extraction(floatArrayOf(1f, 3f), listOf(floatArrayOf(0f, 2f), floatArrayOf(2f, 4f))) })
        var seen: List<FloatArray>? = null
        val head = Fake("head", dims = 1, upstream = "emb", result = { clips, frames ->
            assertTrue(clips.isEmpty())
            seen = frames
            Extraction(floatArrayOf(0.5f))
        })
        val out = FeaturePipeline(listOf(embedding, head)).run(listOf(clip), setOf("head@1"))
        assertEquals(setOf("head@1"), out.keys)
        assertEquals(2, seen!!.size)
        assertEquals(1, embedding.calls)
    }

    @Test
    fun `a head without its upstream is skipped`() {
        val head = Fake("head", upstream = "missing")
        assertEquals(emptyMap<String, FloatArray>(), FeaturePipeline(listOf(head)).run(listOf(clip), setOf("head@1")))
        assertEquals(0, head.calls)
    }

    @Test
    fun `wrong-sized, non-finite or failing results are dropped`() {
        val short = Fake("short", dims = 3, result = { _, _ -> Extraction(floatArrayOf(1f)) })
        val nan = Fake("nan", result = { _, _ -> Extraction(floatArrayOf(1f, Float.NaN)) })
        val boom = Fake("boom", result = { _, _ -> error("boom") })
        val fine = Fake("fine")
        val out = FeaturePipeline(listOf(short, nan, boom, fine)).run(listOf(clip), setOf("short@1", "nan@1", "boom@1", "fine@1"))
        assertEquals(setOf("fine@1"), out.keys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extractor ids are unique`() {
        FeaturePipeline(listOf(Fake("a"), Fake("a", version = 2)))
    }

    // Store

    private fun tempDir(): File = Files.createTempDirectory("features").toFile()

    @Test
    fun `store round trips, last write wins, versions kept apart`() {
        val dir = tempDir()
        val store = FileAudioFeatureStore(dir)
        val v1 = FeatureBlock("emb", 1, listOf("x", "y"))
        val v2 = FeatureBlock("emb", 2, listOf("x", "y"))
        store.put(v1, "h1", floatArrayOf(1f, 2f))
        store.put(v1, "h1", floatArrayOf(3f, -4.5f))
        store.put(v1, "h2", floatArrayOf(0f, 1e-7f))
        val read = FileAudioFeatureStore(dir).read(v1)
        assertArrayEquals(floatArrayOf(3f, -4.5f), read.getValue("h1"), 0f)
        assertArrayEquals(floatArrayOf(0f, 1e-7f), read.getValue("h2"), 0f)
        assertEquals(emptyMap<String, FloatArray>(), store.read(v2))
        assertEquals(setOf("emb@1"), store.keys())
    }

    @Test
    fun `store drops values of the wrong size and junk lines`() {
        val dir = tempDir()
        val block = FeatureBlock("b", 1, listOf("x", "y"))
        FileAudioFeatureStore(dir).put(FeatureBlock("b", 1, listOf("x", "y", "z")), "h1", floatArrayOf(1f, 2f, 3f))
        File(dir, "b@1.tsv").appendText("garbage\nh2\t!!!\n")
        assertEquals(emptyMap<String, FloatArray>(), FileAudioFeatureStore(dir).read(block))
    }

    // Similarity

    private val small = FeatureBlock("s", 1, listOf("x", "y"))

    @Test
    fun `nearest songs come first, never the song itself`() {
        // Centred on the origin, so standardising doesn't reorder anything.
        val points = mapOf(
            "a" to floatArrayOf(1f, 0f), "c" to floatArrayOf(-1f, 0f),
            "d" to floatArrayOf(0f, 1f), "e" to floatArrayOf(0f, -1f),
            "b" to floatArrayOf(0.9f, 0.2f), "f" to floatArrayOf(-0.9f, -0.2f),
        )
        val index = SimilaritySpace().build(listOf(small to points))
        assertEquals(listOf("b", "d", "f", "c"), index.nearest("a", listOf("a", "b", "c", "d", "f"), 5).map { it.hash })
        assertEquals(listOf("b"), index.nearest("a", listOf("b", "c", "d"), 1).map { it.hash })
        assertEquals(emptyList<Neighbour>(), index.nearest("zzz", listOf("a"), 3))
        assertEquals(2.0, index.distance("a", "c")!!, 1e-6)
        assertNull(index.distance("a", "zzz"))
    }

    @Test
    fun `only songs with every weighted block are indexed`() {
        val other = FeatureBlock("o", 1, listOf("z"))
        val ignored = FeatureBlock("i", 1, listOf("z"), similarityWeight = 0.0)
        val index = SimilaritySpace().build(
            listOf(
                small to mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0f, 1f), "c" to floatArrayOf(1f, 1f)),
                other to mapOf("a" to floatArrayOf(1f), "b" to floatArrayOf(2f)),
                ignored to mapOf("a" to floatArrayOf(1f)),
            ),
        )
        assertEquals(2, index.size)
        assertFalse("c" in index)
    }

    @Test
    fun `blocks count by weight, not by size`() {
        // "a" and "b" agree on the big block and disagree on the small one; "c" the other way round.
        val big = FeatureBlock("big", 1, (0 until 100).map { "e$it" })
        val bigValues = mapOf(
            "a" to FloatArray(100) { if (it < 50) 1f else 0f },
            "b" to FloatArray(100) { if (it < 50) 1f else 0f },
            "c" to FloatArray(100) { if (it < 50) 0f else 1f },
        )
        val smallValues = mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0f, 1f), "c" to floatArrayOf(1f, 0f))
        val even = SimilaritySpace(projectTo = 200).build(listOf(big to bigValues, small to smallValues))
        assertEquals(even.distance("a", "b")!!, even.distance("a", "c")!!, 1e-6)
        val bigFirst = SimilaritySpace(projectTo = 200, weights = mapOf("big" to 3.0)).build(listOf(big to bigValues, small to smallValues))
        assertTrue(bigFirst.distance("a", "b")!! < bigFirst.distance("a", "c")!!)
    }

    @Test
    fun `projection keeps who's nearest`() {
        val random = java.util.Random(3)
        val big = FeatureBlock("big", 1, (0 until 512).map { "e$it" })
        val base = FloatArray(512) { random.nextGaussian().toFloat() }
        val values = (0 until 30).associate { i -> "s$i" to FloatArray(512) { random.nextGaussian().toFloat() } } +
            ("twin" to FloatArray(512) { base[it] + 0.1f * random.nextGaussian().toFloat() }) + ("base" to base)
        val index = SimilaritySpace(projectTo = 64).build(listOf(big to values))
        assertEquals("twin", index.nearest("base", values.keys, 1).single().hash)
    }

    // Model manifests

    private fun props(vararg pairs: Pair<String, String>) = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    @Test
    fun `a mel embedding manifest parses`() {
        val spec = ModelSpec.parse(
            props(
                "id" to "effnet", "version" to "1", "model" to "effnet.onnx", "input" to "mel",
                "sampleRate" to "16000", "nFft" to "512", "hop" to "256", "nMels" to "96", "scale" to "slaney",
                "log" to "log10_1p_10000", "patchFrames" to "128", "patchHop" to "62",
                "inputShape" to "-1,128,96", "outputName" to "embeddings", "dims" to "1280",
            ),
        ) { error("no labels") }.getOrThrow()
        assertEquals("effnet@1", spec.block.key)
        assertEquals(1280, spec.block.size)
        assertEquals(listOf(-1L, 128L, 96L), spec.inputShape)
        val mel = spec.input as ModelInput.Mel
        assertEquals(96, mel.mel.nMels)
        assertEquals(LogCompression.LOG10_1P_10000, mel.mel.log)
        assertEquals(16_000, ModelFrontend.sampleRate(spec.input))
    }

    @Test
    fun `a head manifest reads its labels file`() {
        val spec = ModelSpec.parse(
            props(
                "id" to "mood", "version" to "2", "model" to "mood.onnx", "input" to "upstream", "upstream" to "effnet",
                "inputShape" to "-1,1280", "labelsFile" to "mood.txt", "activation" to "softmax", "similarityWeight" to "2",
            ),
        ) { name -> assertEquals("mood.txt", name); listOf("happy", "", "sad") }.getOrThrow()
        assertEquals(listOf("happy", "sad"), spec.block.names)
        assertEquals(2.0, spec.block.similarityWeight, 0.0)
        assertEquals(ModelInput.Upstream("effnet"), spec.input)
        assertEquals(ModelSpec.Activation.SOFTMAX, spec.activation)
    }

    @Test
    fun `bad manifests say why`() {
        val missing = ModelSpec.parse(props("id" to "x", "version" to "1")) { emptyList() }
        assertTrue(missing.exceptionOrNull()!!.message!!.contains("dims"))
        val shape = ModelSpec.parse(
            props("id" to "x", "version" to "1", "model" to "m", "input" to "upstream", "upstream" to "y", "inputShape" to "-1,-1", "dims" to "2"),
        ) { emptyList() }
        assertTrue(shape.isFailure)
    }

    @Test
    fun `scan keeps manifests whose model is there`() {
        val dir = tempDir()
        File(dir, "ok.properties").writeText("id=ok\nversion=1\nmodel=ok.onnx\ninput=waveform\nsampleRate=16000\nwindow=16000\ninputShape=1,16000\ndims=4\n")
        File(dir, "ok.onnx").writeText("model")
        File(dir, "gone.properties").writeText("id=gone\nversion=1\nmodel=gone.onnx\ninput=waveform\nsampleRate=16000\nwindow=16000\ninputShape=1,16000\ndims=4\n")
        File(dir, "bad.properties").writeText("id=bad\n")
        val (specs, problems) = ModelSpec.scan(dir)
        assertEquals(listOf("ok"), specs.map { it.block.id })
        assertEquals(setOf("gone.properties", "bad.properties"), problems.keys)
    }

    // Model plumbing

    @Test
    fun `waveform units are windows, short clips padded to one`() {
        val input = ModelInput.Waveform(sampleRate = 10, window = 4, hop = 2)
        val units = ModelFrontend.units(input, listOf(AudioClip(FloatArray(8) { it.toFloat() }, 10), AudioClip(floatArrayOf(9f), 10)), null)
        assertEquals(listOf(listOf(0f, 1f, 2f, 3f), listOf(2f, 3f, 4f, 5f), listOf(4f, 5f, 6f, 7f), listOf(9f, 0f, 0f, 0f)), units.map { it.toList() })
    }

    @Test
    fun `mel units are patches of frames by bands`() {
        val input = ModelInput.Mel(MelSpec(sampleRate = 16_000, nFft = 512, hop = 256, nMels = 8), patchFrames = 10, patchHop = 5)
        val units = ModelFrontend.units(input, listOf(AudioClip(FloatArray(16_000), 16_000)), null)
        // 61 frames → patches at 0, 5, … 50.
        assertEquals(11, units.size)
        units.forEach { assertEquals(80, it.size) }
    }

    @Test
    fun `batches fill the batch dimension, or run one at a time without one`() {
        val units = List(5) { i -> FloatArray(6) { i.toFloat() } }
        val batched = ModelFrontend.batches(listOf(-1L, 2L, 3L), units, maxBatch = 4)
        assertEquals(listOf(listOf(4L, 2L, 3L), listOf(1L, 2L, 3L)), batched.map { it.shape.toList() })
        assertEquals(4f, batched[1].data[0])
        val single = ModelFrontend.batches(listOf(1L, 6L), units)
        assertEquals(5, single.size)
        try {
            ModelFrontend.batches(listOf(-1L, 5L), units)
            fail("a unit of 6 doesn't fit rows of 5")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `outputs split into rows with their activation`() {
        val rows = ModelFrontend.rows(floatArrayOf(0f, 0f, 1000f, 0f), 2, ModelSpec.Activation.SOFTMAX)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), rows[0], 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f), rows[1], 1e-6f)
        assertEquals(0.5f, ModelFrontend.rows(floatArrayOf(0f), 1, ModelSpec.Activation.SIGMOID).single()[0], 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 2f), meanOf(listOf(floatArrayOf(0f, 1f), floatArrayOf(2f, 3f))), 0f)
        assertNull(meanOf(emptyList()))
    }

    // Analysis

    private class Library(val songs: List<Track>) {
        val hashes = songs.associate { it.uri to "hash-${it.id}" }
        val decoded = mutableListOf<String>()
        val known = HashSet<String>()
        fun decode(track: Track): List<AudioClip>? {
            decoded += track.uri
            return if (track.title.endsWith("broken")) null else listOf(AudioClip(FloatArray(1_600), 16_000))
        }
    }

    private fun features(dir: File, library: Library, extractors: () -> List<FeatureExtractor>) = AudioFeatures(
        store = FileAudioFeatureStore(dir),
        extractors = extractors,
        decode = { track, _ -> library.decode(track) },
        hashOf = { track -> library.hashes[track.uri]?.also { library.known += track.uri } },
        knownHash = { track -> if (track.uri in library.known) library.hashes[track.uri] else null },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `analysis computes each block once per song and keeps it across restarts`() = runBlocking {
        val dir = tempDir()
        val songs = List(3) { track("Rock") } + track("Rock").copy(title = "broken")
        val library = Library(songs)
        var a = Fake("a", result = { _, _ -> Extraction(floatArrayOf(1f, 2f)) })
        val features = features(dir, library) { listOf(a) }
        features.analyse(songs)
        assertEquals(4, library.decoded.size)
        assertEquals(1, a.closed)
        assertEquals(AudioFeatures.Status(listOf(a.block), analysed = 3, total = 4, running = false), features.status.value)
        assertEquals(3, features.index.value.size)

        // A new block for every song; the old one isn't recomputed, and survives a restart.
        library.decoded.clear()
        a = Fake("a", result = { _, _ -> Extraction(floatArrayOf(1f, 2f)) })
        val b = Fake("b", dims = 1, result = { _, _ -> Extraction(floatArrayOf(3f)) })
        val restarted = features(dir, library) { listOf(a, b) }
        restarted.analyse(songs)
        assertEquals(4, library.decoded.size)
        assertEquals(0, a.calls)
        assertEquals(3, b.calls)
        assertEquals(setOf(a.block, b.block), restarted.snapshot().keys)
        assertEquals(3, restarted.snapshot().getValue(b.block).size)
    }

    @Test
    fun `refresh loads stored blocks without analysing`() = runBlocking {
        val dir = tempDir()
        val songs = List(2) { track("Rock") }
        val library = Library(songs)
        features(dir, library) { listOf(Fake("a")) }.analyse(songs)
        library.decoded.clear()
        val fresh = features(dir, library) { listOf(Fake("a")) }
        fresh.refresh(songs)
        assertEquals(0, library.decoded.size)
        assertEquals(2, fresh.index.value.size)
        assertEquals(2, fresh.status.value.analysed)
    }
}
