package com.voiceguard

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import com.corosdjistudio.StudioSessionLog
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MaskExporter {
    const val OUTPUT_FOLDER = "VoiceGuard_masked"
    const val ARCHIVE_FOLDER = "luu_tru"

    /** Tìm hoặc tạo thư mục con trong thư mục người dùng đã chọn. */
    fun ensureDir(ctx: Context, tree: Uri, folderName: String): Uri {
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == folderName && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        val created = DocumentsContract.createDocument(
            ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, folderName
        ) ?: throw IOException("Không tạo được thư mục $folderName (kiểm tra quyền ghi của thư mục đã chọn)")
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created))
    }

    /** Tạo/tìm thư mục con trực tiếp trong thư mục ngày, vẫn dùng quyền tree gốc. */
    fun ensureDirInFolder(ctx: Context, rootTree: Uri, parentDocumentId: String, folderName: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, parentDocumentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == folderName && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                    return DocumentsContract.buildDocumentUriUsingTree(rootTree, c.getString(0))
            }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(rootTree, parentDocumentId)
        val created = DocumentsContract.createDocument(ctx.contentResolver, parent,
            DocumentsContract.Document.MIME_TYPE_DIR, folderName)
            ?: throw IOException("Không tạo được thư mục $folderName")
        return DocumentsContract.buildDocumentUriUsingTree(rootTree, DocumentsContract.getDocumentId(created))
    }

    fun ensureOutputDir(ctx: Context, tree: Uri): Uri = ensureDir(ctx, tree, OUTPUT_FOLDER)

    /** Đường dẫn thật của thư mục đã chọn, ví dụ primary:Movies/DJI_Moved -> /storage/emulated/0/Movies/DJI_Moved. */
    fun treeToPath(tree: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val idx = docId.indexOf(':')
        if (idx < 0) return null
        val vol = docId.substring(0, idx)
        val rel = docId.substring(idx + 1)
        val base = if (vol == "primary") Environment.getExternalStorageDirectory().path else "/storage/$vol"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /** Tạo file .nomedia để thư viện ảnh / DJI Mimo không hiện các clip trong thư mục này. */
    fun ensureNoMedia(ctx: Context, tree: Uri, dir: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir))
        ctx.contentResolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(0) == ".nomedia") return
        }
        DocumentsContract.createDocument(ctx.contentResolver, dir, "application/octet-stream", ".nomedia")
    }

    /** Chuyển (không copy) clip gốc sang thư mục lưu giữ. */
    fun archiveOriginal(ctx: Context, tree: Uri, src: Uri, archiveDir: Uri) {
        val name = ctx.contentResolver.query(src, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video.mp4"
        StudioSessionLog.archiveSourceVideo(ctx, tree, src, name)
    }

    /** Thay âm thanh clip bằng nhạc, giữ nguyên hình, ghi thành file mới trong thư mục outDir. */
    suspend fun maskOne(ctx: Context, rootTree: Uri, src: Uri, name: String, music: MusicSource, segments: List<VoiceSegment>, outDir: Uri, logFolder: Uri = outDir): Uri {
        val tmp = File(ctx.cacheDir, "mask_tmp.mp4")
        tmp.delete()
        try {
            transform(ctx, src, music, segments, tmp)
            if (!tmp.exists() || tmp.length() == 0L) throw IOException("File xuất rỗng")

            // Đặt lại ngày giờ quay theo clip gốc (không quan trọng nếu lỗi)
            try {
                val t = originalTimeMillis(ctx, src)
                if (t > 0) Mp4Time.patchCreationTime(tmp, t)
            } catch (e: Throwable) {
            }

            val base = name.substringBeforeLast('.')
            // A document URI created with buildDocumentUriUsingTree still contains
            // the /tree/ segment, so isTreeUri() is true for it as well.
            // Prefer its document ID; only a bare tree URI should use the tree ID.
            // Giữ nguyên URI tree gốc đã được người dùng cấp quyền. Không dựng tree URI
            // mới từ ID thư mục ngày: Android SAF không cấp quyền tree cho URI con đó.
            val folderId = runCatching { DocumentsContract.getDocumentId(outDir) }
                .getOrElse { DocumentsContract.getTreeDocumentId(outDir) }
            val outputParent = DocumentsContract.buildDocumentUriUsingTree(rootTree, folderId)
            val outputName = base + "_masked.mp4"
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, folderId)
            ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == outputName) {
                        val existing = DocumentsContract.buildDocumentUriUsingTree(rootTree, cursor.getString(0))
                        DocumentsContract.deleteDocument(ctx.contentResolver, existing)
                        break
                    }
                }
            }
            val outUri = DocumentsContract.createDocument(
                ctx.contentResolver, outputParent, "video/mp4", outputName
            ) ?: throw IOException("Không tạo được file đầu ra")

            withContext(Dispatchers.IO) {
                val os = ctx.contentResolver.openOutputStream(outUri)
                    ?: throw IOException("Không mở được file đầu ra để ghi")
                os.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            }
            StudioSessionLog.recordMasked(ctx, logFolder, name, outputName)
            return outUri
        } finally {
            tmp.delete()
        }
    }

    /** Ngày giờ quay của clip gốc (ưu tiên metadata trong video, không có thì dùng ngày sửa file). */
    private fun originalTimeMillis(ctx: Context, src: Uri): Long {
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, src)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                if (d != null) {
                    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    val parsed = fmt.parse(d.substringBefore('.').substringBefore('Z'))
                    if (parsed != null && parsed.time > 0) return parsed.time
                }
            } finally {
                r.release()
            }
        } catch (e: Throwable) {
        }
        try {
            ctx.contentResolver.query(
                src, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) return c.getLong(0)
            }
        } catch (e: Throwable) {
        }
        return 0L
    }

    private suspend fun transform(ctx: Context, src: Uri, music: MusicSource, segments: List<VoiceSegment>, outFile: File) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(src))
                    .setEffects(Effects(listOf(MusicReplaceAudioProcessor(music, segments)), emptyList()))
                    .build()
                val transformer = Transformer.Builder(ctx)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
                transformer.start(edited, outFile.absolutePath)
            }
        }
    }
}
