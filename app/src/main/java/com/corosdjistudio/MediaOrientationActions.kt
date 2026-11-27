package com.corosdjistudio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Effects
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
    private const val ARCHIVE_FOLDER = "luu_tru"

    private fun findOrCreateArchive(context: Context, sessionFolder: Uri): Uri {
        val folderId = DocumentsContract.getDocumentId(sessionFolder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(sessionFolder, folderId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == ARCHIVE_FOLDER &&
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(sessionFolder, cursor.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(
            context.contentResolver, sessionFolder,
            DocumentsContract.Document.MIME_TYPE_DIR, ARCHIVE_FOLDER
        ) ?: throw IOException("Không tạo được thư mục $ARCHIVE_FOLDER")
    }

    private fun uniqueName(context: Context, folder: Uri, original: String): String {
        val id = DocumentsContract.getDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, id)
        val existing = mutableSetOf<String>()
        context.contentResolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor -> while (cursor.moveToNext()) existing += cursor.getString(0) }
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ".mp4"
        var candidate = original
        var suffix = 2
        while (candidate in existing) candidate = "${stem}_${suffix++}${ext}"
        return candidate
    }

    private fun copyUriToDocument(context: Context, source: Uri, destination: Uri): Long {
        var copied = 0L
        context.contentResolver.openInputStream(source)?.use { input ->
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                }
                output.flush()
            } ?: throw IOException("Không mở được file đích")
        } ?: throw IOException("Không đọc được video nguồn")
        if (copied <= 0L) throw IOException("File sao lưu rỗng")
        return copied
    }

    private fun archiveCopy(context: Context, sessionFolder: Uri, item: MediaOrientationItem): Uri {
        val archive = findOrCreateArchive(context, sessionFolder)
        val archivedName = uniqueName(context, archive, item.name)
        val archived = DocumentsContract.createDocument(
            context.contentResolver, archive, "video/mp4", archivedName
        ) ?: throw IOException("Không tạo được bản lưu trữ cho ${item.name}")
        try {
            copyUriToDocument(context, item.uri, archived)
            return archived
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, archived) }
            throw error
        }
    }

    suspend fun archiveSelected(
        context: Context,
        sessionFolder: Uri,
        items: List<MediaOrientationItem>
    ): Pair<List<String>, List<String>> = withContext(Dispatchers.IO) {
        val archived = mutableListOf<String>()
        val failed = mutableListOf<String>()
        items.distinctBy { it.uri }.forEach { item ->
            var archiveUri: Uri? = null
            try {
                archiveUri = archiveCopy(context, sessionFolder, item)
                if (!DocumentsContract.deleteDocument(context.contentResolver, item.uri)) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, archiveUri!!) }
                    throw IOException("Đã sao lưu nhưng không thể chuyển file khỏi thư mục ngày")
                }
                archived += item.name
            } catch (error: Throwable) {
                failed += "${item.name}: ${error.localizedMessage ?: error.javaClass.simpleName}"
            }
        }
        archived to failed
    }

    /**
     * Bake an additional 180-degree rotation into a new MP4, preserve the source
     * in the permanent luu_tru folder, then replace the session-folder entry
     * using the original filename so OVL and VoiceGuard pick up the corrected clip.
     */
    suspend fun rotate180InSession(context: Context, sessionFolder: Uri, item: MediaOrientationItem): String =
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "orientation_${System.nanoTime()}.mp4")
            var archived: Uri? = null
            var replacement: Uri? = null
            var sourceDeleted = false
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
                if (!temp.isFile || temp.length() <= 0L) throw IOException("Không tạo được video đã xoay")

                // Save and verify the original before removing it from the active date folder.
                archived = archiveCopy(context, sessionFolder, item)
                if (!DocumentsContract.deleteDocument(context.contentResolver, item.uri))
                    throw IOException("Đã sao lưu bản gốc nhưng không thể thay thế file trong thư mục ngày")
                sourceDeleted = true

                replacement = DocumentsContract.createDocument(
                    context.contentResolver, sessionFolder, "video/mp4", item.name
                ) ?: throw IOException("Không tạo được video thay thế ${item.name}")
                temp.inputStream().use { input ->
                    context.contentResolver.openOutputStream(replacement, "w")?.use { output ->
                        input.copyTo(output, 1024 * 1024)
                        output.flush()
                    } ?: throw IOException("Không ghi được video đã xoay")
                }
                if (temp.length() <= 0L) throw IOException("Video đã xoay không hợp lệ")
                item.name
            } catch (error: Throwable) {
                replacement?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                // Roll back the active date folder from the archived original if replacement failed.
                if (sourceDeleted && archived != null) {
                    runCatching {
                        val restored = DocumentsContract.createDocument(
                            context.contentResolver, sessionFolder, "video/mp4", item.name
                        ) ?: throw IOException("Không tạo được bản gốc khôi phục")
                        try {
                            copyUriToDocument(context, archived!!, restored)
                        } catch (restoreError: Throwable) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, restored) }
                            throw restoreError
                        }
                    }
                }
                throw error
            } finally {
                temp.delete()
            }
        }
}
