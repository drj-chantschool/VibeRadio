package app.viberadio.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.TimeZone

/**
 * At startup, if the crash log ([CrashRecorder]) holds anything, copies it out and clears it, so
 * a crash that stops the app opening still reaches the user. Runs before the container is built.
 *
 * Where it goes, first that works: the diagnostics folder if one is set, else the Downloads folder
 * (Android 10 and later; no permission needed), else the app's folder on shared storage
 * (`Android/data/<package>/files`). The log is cleared only once a copy is written; any failure
 * leaves it for the next start. Never throws.
 */
class CrashExporter(private val context: Context, private val crashes: CrashRecorder) {

    fun exportAndClear(): Boolean = try {
        val text = crashes.read()
        if (text == null) {
            false
        } else {
            val now = System.currentTimeMillis()
            val name = CrashLog.exportName(now, TimeZone.getDefault().getOffset(now) / 60_000)
            val saved = toFolder(name, text) || toDownloads(name, text) || toAppFolder(name, text)
            if (saved) crashes.clear()
            saved
        }
    } catch (e: Throwable) {
        false
    }

    private fun toFolder(name: String, text: String): Boolean = runCatching {
        val tree = Uri.parse(PrefsDiagnosticsStore(context).read()?.folderUri ?: return false)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, "text/plain", name)
            ?: throw IOException("the folder refused $name")
        val out = context.contentResolver.openOutputStream(doc, "w") ?: throw IOException("couldn't open $name")
        out.use { it.write(text.encodeToByteArray()) }
        true
    }.getOrDefault(false)

    private fun toDownloads(name: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                },
            )
        }.getOrNull() ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("couldn't open $name")
            out.use { it.write(text.encodeToByteArray()) }
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun toAppFolder(name: String, text: String): Boolean = runCatching {
        val dir = context.getExternalFilesDir(null) ?: return false
        File(dir, name).writeText(text)
        true
    }.getOrDefault(false)
}
