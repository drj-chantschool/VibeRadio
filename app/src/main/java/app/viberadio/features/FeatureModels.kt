package app.viberadio.features

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The installed models: files copied into app storage from the system file picker (Settings →
 * Debug → Add feature models). A model is an `.onnx` file plus a `.properties` manifest
 * ([ModelSpec]) and any labels file it names. Blocking; call off the main thread.
 */
class FeatureModels(val dir: File) {

    /** Valid models, and each manifest that isn't with the reason. */
    fun scan(): Pair<List<ModelSpec>, Map<String, String>> = ModelSpec.scan(dir)

    /** An extractor per valid model. Sessions load on first use. */
    fun extractors(): List<FeatureExtractor> = scan().first.map { OnnxModelExtractor(it, dir) }

    /** Copies the picked files in under their own names, replacing files of the same name. Returns how many were copied. */
    fun install(resolver: ContentResolver, uris: List<Uri>): Int {
        dir.mkdirs()
        var copied = 0
        for (uri in uris) {
            val name = displayName(resolver, uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: continue
            val target = File(dir, name)
            val temp = File(dir, "$name.part")
            val ok = runCatching {
                resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: error("unreadable")
                check(temp.renameTo(target) || (target.delete() && temp.renameTo(target)))
            }.isSuccess
            temp.delete()
            if (ok) copied++
        }
        return copied
    }

    /** Removes every installed model. Their stored blocks stay (unused) until a model with that ID and version returns. */
    fun removeAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
}
