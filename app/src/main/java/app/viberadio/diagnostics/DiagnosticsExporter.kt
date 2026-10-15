package app.viberadio.diagnostics

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Writes a diagnostics bundle ([DiagnosticsBundle]) for Settings → Debug and the nightly job.
 *
 * Every export is staged in the app's cache (the latest one only), so it can be shared from
 * there, then copied into the diagnostics folder the user picked, through the Storage Access
 * Framework. The app holds no account or token: the folder's document provider (Google Drive,
 * local storage) does any uploading.
 */
class DiagnosticsExporter(
    context: Context,
    private val settings: DiagnosticsSettings,
    /** Gathers the data. Called on [ioDispatcher]. */
    private val snapshot: suspend () -> DiagnosticsSnapshot,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val context = context.applicationContext
    private val stagingRoot = File(this.context.cacheDir, STAGING_DIR)
    private val running = Mutex()

    /**
     * Builds a bundle, stages it and copies it to the diagnostics folder if one is set. One at a
     * time; never throws. The outcome is also recorded in [DiagnosticsSettings].
     */
    suspend fun export(): ExportOutcome = running.withLock {
        val outcome = withContext(ioDispatcher) {
            try {
                val snap = snapshot()
                val name = DiagnosticsBundle.folderName(snap.exportedAtMs, snap.utcOffsetMinutes)
                val files = DiagnosticsBundle.build(snap)
                stage(name, files)
                val folder = settings.await().folderUri
                when {
                    folder == null -> ExportOutcome(clock(), name, savedToFolder = false, error = NO_FOLDER)
                    else -> try {
                        writeTree(context.contentResolver, Uri.parse(folder), name, files)
                        ExportOutcome(clock(), name, savedToFolder = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ExportOutcome(clock(), name, savedToFolder = false, error = "Couldn't write to the folder: ${describe(e)}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportOutcome(clock(), bundle = null, savedToFolder = false, error = "Couldn't build the export: ${describe(e)}")
            }
        }
        settings.recordExport(outcome)
        outcome
    }

    /**
     * A share sheet for the latest staged bundle (every file, as separate attachments), or null
     * if there is none. Call [export] first for a fresh one.
     */
    fun shareIntent(): Intent? {
        val bundle = stagingRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: return null
        val files = bundle.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return null
        val authority = context.packageName + AUTHORITY_SUFFIX
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority, it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "VibeRadio diagnostics ${bundle.name}")
            clipData = ClipData.newRawUri(bundle.name, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    /** Replaces whatever was staged with this bundle. */
    private fun stage(name: String, files: List<BundleFile>) {
        stagingRoot.listFiles()?.forEach { it.deleteRecursively() }
        val dir = File(stagingRoot, name)
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Couldn't create $dir")
        files.forEach { File(dir, it.name).writeText(it.text) }
    }

    companion object {
        /** Under the cache directory; must match `res/xml/diagnostics_paths.xml`. */
        const val STAGING_DIR = "diagnostics"

        /** The FileProvider authority is the package name plus this; must match the manifest. */
        const val AUTHORITY_SUFFIX = ".diagnostics"

        const val NO_FOLDER = "No diagnostics folder chosen. The export is ready to share."

        private fun describe(e: Exception) = e.message ?: e.javaClass.simpleName

        /** Writes [files] into a new [folder] inside the picked tree. */
        fun writeTree(resolver: ContentResolver, treeUri: Uri, folder: String, files: List<BundleFile>) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val dir = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, folder)
                ?: throw IOException("the folder refused a new subfolder")
            for (file in files) {
                val doc = DocumentsContract.createDocument(resolver, dir, file.mimeType, file.name)
                    ?: throw IOException("the folder refused ${file.name}")
                val out = resolver.openOutputStream(doc, "w") ?: throw IOException("couldn't open ${file.name}")
                out.use { it.write(file.text.encodeToByteArray()) }
            }
        }

        /** The picked folder's name, for the settings row; null if the provider won't say. */
        fun folderName(resolver: ContentResolver, treeUri: Uri): String? = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
