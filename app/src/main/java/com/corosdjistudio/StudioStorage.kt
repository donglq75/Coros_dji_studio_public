package com.corosdjistudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StudioVideo(val uri: Uri, val name: String, val captureTimeMillis: Long, val sizeBytes: Long)
data class MoveReport(val moved: List<StudioVideo>, val failed: List<String>, val sourceNotDeleted: List<String>)

data class StudioDateFolder(val name: String, val uri: Uri, val videoCount: Int)

object StudioStorage {

    /**
     * Liệt kê các thư mục YYMMDD trong dji_studio, hỗ trợ chọn trực tiếp
     * dji_studio hoặc chọn thư mục cha chứa nó.
     */
    fun listDateFolders(context: Context, rootTree: Uri): List<StudioDateFolder> {
        val rootId = DocumentsContract.getTreeDocumentId(rootTree)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(rootTree, rootId)
        val rootName = displayName(context, rootDoc)
        val datePattern = Regex("\\d{6}")
        if (datePattern.matches(rootName)) {
            return listOf(StudioDateFolder(rootName, rootDoc, countVideos(context, rootTree, rootId)))
        }
        fun directories(parentId: String): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, parentId)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            context.contentResolver.query(children, cols, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) result += id to name
                }
            }
            return result
        }
        val studioId = if (rootName.equals("dji_studio", true)) rootId
            else directories(rootId).firstOrNull { it.second.equals("dji_studio", true) }?.first
                ?: return emptyList()
        return directories(studioId).filter { datePattern.matches(it.second) }
            .map { (id, name) ->
                StudioDateFolder(name, DocumentsContract.buildDocumentUriUsingTree(rootTree, id),
                    countVideos(context, rootTree, id))
            }.sortedByDescending { it.name }
    }

    private fun countVideos(context: Context, tree: Uri, folderId: String): Int {
        var count = 0
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(0)?.startsWith("video/") == true) count++
        }
        return count
    }

    private val dateInName = Regex("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])[_-]?([0-2]\\d[0-5]\\d[0-5]\\d)?")

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "DJI_video.mp4"
        }
        return DocumentFile.fromSingleUri(context, uri)?.name ?: "DJI_video.mp4"
    }

    private fun size(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
        }
        return -1L
    }

    private fun modified(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
        }
        return 0L
    }

    private fun captureTime(context: Context, uri: Uri, name: String): Long {
        dateInName.find(name)?.let { match ->
            val raw = match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + match.groupValues[4]
            val pattern = if (raw.length == 14) "yyyyMMddHHmmss" else "yyyyMMdd"
            runCatching { return SimpleDateFormat(pattern, Locale.US).parse(raw)?.time ?: 0L }
        }
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE).orEmpty()
                val digits = Regex("(20\\d{2})(\\d{2})(\\d{2})[T _-]?(\\d{2})(\\d{2})(\\d{2})").find(raw)
                if (digits != null) {
                    val value = digits.groupValues.drop(1).joinToString("")
                    return SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(value)?.time ?: 0L
                }
            } finally { retriever.release() }
        }
        return modified(context, uri).takeIf { it > 0L } ?: System.currentTimeMillis()
    }

    private fun childDirectory(context: Context, tree: Uri, parentId: String, name: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name && cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                    return cursor.getString(0)
            }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Không tạo được thư mục $name")
        return DocumentsContract.getDocumentId(created)
    }

    /**
     * Android SAF providers không phải lúc nào cũng trả Document ID chứa đầy đủ đường dẫn.
     * Vì vậy phải nhận diện cả tên thư mục ngày, không chỉ dò chuỗi dji_studio/yymmdd trong ID.
     */
    private fun isSelectedStudioDateFolder(rootName: String?, rootId: String): Boolean {
        if (rootName == null || !Regex("\\d{6}").matches(rootName)) return false
        val normalizedId = rootId.replace('\\', '/').trimEnd('/')
        val slash = normalizedId.lastIndexOf('/')
        // Với ID dạng đường dẫn, xác nhận thư mục cha là dji_studio.
        // Với ID opaque của một số SAF provider, tên thư mục sáu chữ số là tín hiệu còn lại.
        if (slash < 0) return true
        val parent = normalizedId.substring(0, slash)
        return parent.endsWith("/dji_studio", ignoreCase = true) ||
            parent.equals("dji_studio", ignoreCase = true)
    }

    /**
     * Trả về DOCUMENT URI của thư mục ngày, luôn nằm dưới tree URI gốc đã được Android cấp quyền.
     * Nếu người dùng chọn chính thư mục ngày dji_studio/yymmdd thì dùng ngay thư mục đó.
     */
    fun dateFolderDocument(context: Context, rootTree: Uri, epochMillis: Long): Uri {
        val rootId = DocumentsContract.getTreeDocumentId(rootTree)
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(rootTree, rootId)
        val rootName = context.contentResolver.query(
            rootDocument, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (isSelectedStudioDateFolder(rootName, rootId)) return rootDocument

        val studioId = if (rootName.equals("dji_studio", ignoreCase = true)) {
            rootId
        } else {
            childDirectory(context, rootTree, rootId, "dji_studio")
        }
        val date = SimpleDateFormat("yyMMdd", Locale.US).format(Date(epochMillis))
        val dateId = childDirectory(context, rootTree, studioId, date)
        return DocumentsContract.buildDocumentUriUsingTree(rootTree, dateId)
    }

    /** Destination for finished Mimo renders: dji_studio/product. */
    fun productFolderDocument(context: Context, rootTree: Uri): Uri {
        val rootId = DocumentsContract.getTreeDocumentId(rootTree)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(rootTree, rootId)
        val rootName = displayName(context, rootDoc)
        val studioId = if (rootName.equals("dji_studio", true)) rootId
            else childDirectory(context, rootTree, rootId, "dji_studio")
        val productId = childDirectory(context, rootTree, studioId, "product")
        return DocumentsContract.buildDocumentUriUsingTree(rootTree, productId)
    }

    /** Destination for Mimo exports whose MP4 does not expose a trustworthy capture date. */
    fun mimoExportFolderDocument(context: Context, rootTree: Uri): Uri {
        val rootId = DocumentsContract.getTreeDocumentId(rootTree)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(rootTree, rootId)
        val rootName = displayName(context, rootDoc)
        val studioId = if (rootName.equals("dji_studio", true)) rootId
            else childDirectory(context, rootTree, rootId, "dji_studio")
        val exportId = childDirectory(context, rootTree, studioId, "Mimo_Export")
        return DocumentsContract.buildDocumentUriUsingTree(rootTree, exportId)
    }

    // Một bộ phân giải duy nhất cho cả Overlay và VoiceGuard.
    fun dateFolder(context: Context, rootTree: Uri, epochMillis: Long): Uri {
        val folderDocument = dateFolderDocument(context, rootTree, epochMillis)
        val folderId = DocumentsContract.getDocumentId(folderDocument)
        return DocumentsContract.buildTreeDocumentUri(rootTree.authority!!, folderId)
    }

    // Nếu file nguồn đã nằm trong dji_studio/yymmdd thì dùng chính thư mục đó,
    // tránh tạo một cây dji_studio lồng bên trong.
    private fun existingStudioDateFolder(context: Context, rootTree: Uri, source: Uri): Uri? {
        return runCatching {
            val sourceId = DocumentsContract.getDocumentId(source)
            val rootId = DocumentsContract.getTreeDocumentId(rootTree)
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(rootTree, rootId)
            val rootName = displayName(context, rootDoc)
            if (Regex("\\d{6}").matches(rootName)) return@runCatching rootDoc

            fun search(parentId: String, parentName: String, depth: Int): Uri? {
                if (depth > 4) return null
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, parentId)
                val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                context.contentResolver.query(children, cols, null, null, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getString(0) ?: continue
                        val name = cur.getString(1) ?: continue
                        val mime = cur.getString(2) ?: continue
                        if (id == sourceId && Regex("\\d{6}").matches(parentName)) {
                            val parentFolderId = parentId
                            val parentFolder = DocumentsContract.buildDocumentUriUsingTree(rootTree, parentFolderId)
                            val grandParentIsStudio = parentName.isNotBlank() &&
                                (depth == 1 && rootName.equals("dji_studio", true) ||
                                 depth > 1 && isUnderStudio(context, rootTree, parentFolderId))
                            if (grandParentIsStudio) return parentFolder
                        }
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                            (name.equals("dji_studio", true) || Regex("\\d{6}").matches(name))) {
                            val found = search(id, name, depth + 1)
                            if (found != null) return found
                        }
                    }
                }
                return null
            }
            search(rootId, rootName, 0)
        }.getOrNull()
    }

    private fun isUnderStudio(context: Context, rootTree: Uri, dateFolderId: String): Boolean {
        // Với SAF provider opaque ID, xác nhận bằng cách duyệt từ root, không suy luận từ chuỗi ID.
        val rootId = DocumentsContract.getTreeDocumentId(rootTree)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(rootTree, rootId)
        val rootName = displayName(context, rootDoc)
        if (rootName.equals("dji_studio", true)) return true
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, rootId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == dateFolderId && c.getString(1).equals("dji_studio", true)) return true
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR &&
                    c.getString(1).equals("dji_studio", true)) {
                    val nested = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, c.getString(0))
                    context.contentResolver.query(nested, cols, null, null, null)?.use { n ->
                        while (n.moveToNext()) if (n.getString(0) == dateFolderId &&
                            Regex("\\d{6}").matches(n.getString(1) ?: "")) return true
                    }
                }
            }
        }
        return false
    }
    private fun uniqueName(context: Context, rootTree: Uri, folderDocument: Uri, original: String): String {
        val folderId = DocumentsContract.getDocumentId(folderDocument)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, folderId)
        val existing = mutableSetOf<String>()
        context.contentResolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor -> while (cursor.moveToNext()) existing += cursor.getString(0) }
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var candidate = original
        var suffix = 2
        while (candidate in existing) candidate = "${stem}_${(suffix++).toString().padStart(2, '0')}$ext"
        return candidate
    }

    suspend fun moveSelected(context: Context, sourceUris: List<Uri>, rootTree: Uri,
        onProgress: (Int, Int, String) -> Unit): MoveReport = withContext(Dispatchers.IO) {
        val sources = sourceUris.distinct().map { uri ->
            val name = displayName(context, uri)
            StudioVideo(uri, name, captureTime(context, uri, name), size(context, uri).coerceAtLeast(0L))
        }.sortedWith(compareBy<StudioVideo> { it.captureTimeMillis }.thenBy { it.name.lowercase(Locale.ROOT) })

        val moved = mutableListOf<StudioVideo>()
        val failed = mutableListOf<String>()
        val notDeleted = mutableListOf<String>()
        sources.forEachIndexed { index, source ->
            val sourceStartedAtNanos = System.nanoTime()
            onProgress(index, sources.size, source.name)
            var destination: Uri? = null
            try {
                val existingFolder = existingStudioDateFolder(context, rootTree, source.uri)
                val folderDocument = existingFolder ?: dateFolderDocument(context, rootTree, source.captureTimeMillis)
                if (existingFolder != null) {
                    // Đã xác nhận file nằm trong đúng thư mục ngày; ghi nhận vào nhật ký sản phẩm.
                    StudioSessionLog.recordOriginal(context, existingFolder, source.name)
                    moved += source
                    return@forEachIndexed
                }
                // Tên ngắn, đồng nhất theo thời điểm quay: YYMMDD_HHMMSS.mp4.
                // captureTime đã ưu tiên metadata DJI, sau đó mới dùng ngày sửa file.
                val shortBase = SimpleDateFormat("yyMMdd_HHmmss", Locale.US)
                    .format(Date(source.captureTimeMillis))
                val outputName = uniqueName(context, rootTree, folderDocument, "$shortBase.mp4")
                destination = DocumentsContract.createDocument(context.contentResolver, folderDocument,
                    context.contentResolver.getType(source.uri) ?: "video/mp4", outputName)
                    ?: throw IOException("Không tạo được file đích: $outputName")
                var copied = 0L
                context.contentResolver.openInputStream(source.uri)?.use { input ->
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
                } ?: throw IOException("Không đọc được file nguồn")
                if (copied <= 0L || (source.sizeBytes > 0L && copied != source.sizeBytes))
                    throw IOException("Dung lượng file sau khi chép không khớp")
                val target = StudioVideo(destination, outputName, source.captureTimeMillis, copied)
                val importDurationMs = ((System.nanoTime() - sourceStartedAtNanos) / 1_000_000L).coerceAtLeast(1L)
                StudioSessionLog.recordOriginal(context, folderDocument, outputName, importDurationMs)
                val deleted = runCatching { DocumentsContract.deleteDocument(context.contentResolver, source.uri) }.getOrDefault(false)
                if (!deleted) notDeleted += source.name
                moved += target
            } catch (error: Throwable) {
                destination?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
                failed += "${source.name}: ${error.localizedMessage ?: "lỗi không xác định"}"
            }
        }
        onProgress(sources.size, sources.size, "")
        MoveReport(moved, failed, notDeleted)
    }
}
