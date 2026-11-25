package com.voiceguard

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class VideoFile(val uri: Uri, val name: String, val modified: Long, val size: Long)

object VideoLister {
    private val COLS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE
    )

    /** Liệt kê video trực tiếp trong thư mục đã chọn bằng một truy vấn duy nhất. */
    fun list(ctx: Context, tree: Uri, includeMasked: Boolean = false): List<VideoFile> =
        listInFolder(ctx, tree, DocumentsContract.getTreeDocumentId(tree), includeMasked)

    /** Dùng quyền của tree gốc nhưng liệt kê một thư mục con theo document ID. */
    fun listInFolder(ctx: Context, tree: Uri, folderDocumentId: String, includeMasked: Boolean = false): List<VideoFile> =
        query(ctx, tree, folderDocumentId, includeMasked)

    /** Liệt kê video trong thư mục con của thư mục đích (không cần tree URI riêng). */
    fun listSubfolderInFolder(ctx: Context, tree: Uri, parentId: String, folderName: String): List<VideoFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        var subId: String? = null
        ctx.contentResolver.query(children, COLS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == folderName && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subId = c.getString(0)
                    break
                }
            }
        }
        val id = subId ?: return emptyList()
        return query(ctx, tree, id, true)
    }

    /** Liệt kê video trong thư mục con của tree gốc. */
    fun listSubfolder(ctx: Context, tree: Uri, folderName: String): List<VideoFile> =
        listSubfolderInFolder(ctx, tree, DocumentsContract.getTreeDocumentId(tree), folderName)

    private fun query(ctx: Context, tree: Uri, parentDocId: String, includeMasked: Boolean): List<VideoFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<VideoFile>()
        ctx.contentResolver.query(children, COLS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: continue
                if (!mime.startsWith("video/")) continue
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: "clip"
                // File bắt đầu bằng dấu chấm là file ẩn; Android đổi tên clip đã xóa thành ".trashed-..." và giữ lại ~30 ngày
                if (name.startsWith(".")) continue
                // Bản đã che nhạc do app tạo ra: không quét lại (trừ khi cần để ghép)
                if (!includeMasked && name.contains("_masked", ignoreCase = true)) continue
                out.add(
                    VideoFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        name = name,
                        modified = c.getLong(3),
                        size = c.getLong(4)
                    )
                )
            }
        }
        return out
    }
}
