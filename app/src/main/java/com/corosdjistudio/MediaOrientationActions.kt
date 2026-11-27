package com.corosdjistudio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.effect.ScaleAndRotateTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MediaOrientationActions {
    suspend fun deleteSelected(context: Context, items: List<MediaOrientationItem>): Pair<List<String>, List<String>> =
        withContext(Dispatchers.IO) {
            val deleted = mutableListOf<String>()
            val failed = mutableListOf<String>()
            items.distinctBy { it.uri }.forEach { item ->
                try {
                    if (DocumentsContract.deleteDocument(context.contentResolver, item.uri)) deleted += item.name
                    else failed += item.name
                } catch (error: Throwable) {
                    failed += "${item.name}: ${error.localizedMessage ?: error.javaClass.simpleName}"
                }
            }
            deleted to failed
        }

    /**
     * Exports a separately encoded copy with an additional 180-degree video effect.
     * The original SAF document is never overwritten or deleted.
     */
    suspend fun rotate180Copy(context: Context, sessionFolder: Uri, item: MediaOrientationItem): String =
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "orientation_${System.nanoTime()}.mp4")
            var destination: Uri? = null
            try {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val transformer = Transformer.Builder(context)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    if (continuation.isActive) continuation.resumeWithException(exportException)
                                }
                            }).build()
                        continuation.invokeOnCancellation { transformer.cancel() }
                        val effect = ScaleAndRotateTransformation.Builder()
                            .setRotationDegrees(180f)
                            .build()
                        val edited = EditedMediaItem.Builder(MediaItem.fromUri(item.uri))
                            .setEffects(Effects(emptyList(), listOf(effect)))
                            .build()
                        transformer.start(edited, temp.absolutePath)
                    }
                }

                val rootId = DocumentsContract.getDocumentId(sessionFolder)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(sessionFolder, rootId)
                var outputFolder: Uri? = null
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "Studio_Xoay_180" &&
                            cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            outputFolder = DocumentsContract.buildDocumentUriUsingTree(sessionFolder, cursor.getString(0))
                            break
                        }
                    }
                }
                val folder = outputFolder ?: DocumentsContract.createDocument(
                    context.contentResolver, sessionFolder,
                    DocumentsContract.Document.MIME_TYPE_DIR, "Studio_Xoay_180"
                ) ?: throw IOException("Không tạo được thư mục Studio_Xoay_180")

                val dot = item.name.lastIndexOf('.')
                val stem = if (dot > 0) item.name.substring(0, dot) else item.name
                val ext = if (dot > 0) item.name.substring(dot) else ".mp4"
                val base = "${stem}_xoay180"
                var name = base + ext
                var suffix = 2
                while (true) {
                    val folderId = DocumentsContract.getDocumentId(folder)
                    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(sessionFolder, folderId)
                    var exists = false
                    context.contentResolver.query(childUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) if (cursor.getString(0) == name) exists = true
                    }
                    if (!exists) break
                    name = "${base}_${suffix++}${ext}"
                }
                destination = DocumentsContract.createDocument(context.contentResolver, folder, "video/mp4", name)
                    ?: throw IOException("Không tạo được file đầu ra $name")
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
                    output.flush()
                } ?: throw IOException("Không ghi được file đầu ra")
                name
            } catch (error: Throwable) {
                destination?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                throw error
            } finally {
                temp.delete()
            }
        }
}
