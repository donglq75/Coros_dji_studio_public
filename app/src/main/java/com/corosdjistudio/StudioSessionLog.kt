package com.corosdjistudio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.database.Cursor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import com.voiceguard.VideoFile
import com.voiceguard.VideoLister
import java.io.IOException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.security.MessageDigest

data class StudioConsolidationReport(
    val products: Int,
    val archived: Int,
    val errors: List<String>,
    val originals: Int = 0,
    val overlayCount: Int = 0,
    val importDurationMs: Long = 0L,
    val overlayDurationMs: Long = 0L,
    val maskedCount: Int = 0,
    val maskedDurationMs: Long = 0L,
    val archivedOriginals: Int = 0,
    val archivedCleaned: Int = 0
)

/**
 * Nhật ký sản phẩm của một thư mục ngày. Mỗi nguồn có thể có bản gốc, bản Overlay
 * và bản đã che VG. Khi tổng hợp, ưu tiên VG > Overlay > bản gốc.
 */
object StudioSessionLog {
    private const val PREFS = "studio_product_log"
    private const val ARCHIVE = "luu_tru"

    /**
     * Chuẩn hóa tree/document URI của cùng một thư mục SAF để VG, Overlay và Tổng hợp
     * luôn đọc/ghi chung một nhật ký.
     */
    private fun canonicalTree(folder: Uri): Uri {
        // Keep the exact SAF URI returned by OpenDocumentTree / buildDocumentUriUsingTree.
        // Rebuilding a bare /tree/<id> URI drops the /document/<id> part on some
        // DocumentsProviders and causes "Invalid URI" when querying child documents.
        return folder
    }

    private fun folderDocumentId(folder: Uri): String =
        runCatching { DocumentsContract.getDocumentId(folder) }
            .getOrElse { DocumentsContract.getTreeDocumentId(folder) }

    private fun key(folder: Uri): String {
        val normalized = canonicalTree(folder)
        val identity = normalized.authority + "|" + folderDocumentId(folder)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "folder_$digest"
    }

    private fun read(context: Context, folder: Uri): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(folder), "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun save(context: Context, folder: Uri, data: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(folder), data.toString()).apply()
    }

    @Synchronized
    fun recordOriginal(context: Context, folder: Uri, originalName: String, importDurationMs: Long = 0L) {
        val data = read(context, folder)
        val entry = findOrCreate(data, originalName)
        entry.put("original", originalName)
        entry.put("imported", true)
        if (importDurationMs > 0L) entry.put("importDurationMs", importDurationMs)
        entry.put("updatedAt", System.currentTimeMillis())
        save(context, folder, data)
    }

    @Synchronized
    fun recordOverlay(context: Context, folder: Uri, originalName: String, outputName: String, durationMs: Long = 0L) {
        val data = read(context, folder)
        val entry = findOrCreate(data, originalName)
        entry.put("original", originalName)
        entry.put("overlay", outputName)
        if (durationMs > 0L) entry.put("overlayDurationMs", durationMs)
        entry.put("updatedAt", System.currentTimeMillis())
        save(context, folder, data)
    }

    @Synchronized
    fun recordMasked(context: Context, folder: Uri, overlayName: String, outputName: String, durationMs: Long = 0L) {
        val data = read(context, folder)
        var entry: JSONObject? = null
        for (i in 0 until data.length()) {
            val candidate = data.optJSONObject(i) ?: continue
            if (candidate.optString("overlay") == overlayName ||
                candidate.optString("original") == overlayName) {
                entry = candidate
                break
            }
        }
        if (entry == null) {
            // Subtitle export may be the first VG operation for this source.
            // Keep the source name as the record key for Home consolidation.
            entry = JSONObject().put("original", overlayName)
            data.put(entry)
        }
        val target = entry ?: throw IllegalStateException("Không tạo được nhật ký cho video VG")
        target.put("masked", outputName)
        if (durationMs > 0L) target.put("maskedDurationMs", durationMs)
        target.put("updatedAt", System.currentTimeMillis())
        save(context, folder, data)
    }

    /** Move a successfully processed source clip into the permanent luu_tru folder. */
    @Synchronized
    fun archiveSourceVideo(context: Context, folder: Uri, sourceUri: Uri, sourceName: String): String {
        val tree = canonicalTree(folder)
        val rootId = folderDocumentId(folder)
        val sourceId = runCatching { DocumentsContract.getDocumentId(sourceUri) }
            .getOrElse { throw IOException("Không xác định được video nguồn: " + sourceName) }
        val parentId = findParentDocumentId(context, tree, rootId, sourceId, 4)
            ?: throw IOException("Không tìm thấy video nguồn trong thư mục phiên: " + sourceName)
        val archiveDir = findOrCreateDirectory(context, tree, rootId, ARCHIVE)
        val reserved = children(context, archiveDir).map { it.name }.toMutableSet()
        val targetName = uniqueName(sourceName) { it in reserved }
        val sourceParent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val moved = DocumentsContract.moveDocument(context.contentResolver, sourceUri, sourceParent, archiveDir)
            ?: throw IOException("Không chuyển được " + sourceName + " vào luu_tru")
        if (targetName != sourceName) {
            val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, moved, targetName) }.getOrNull()
            return if (renamed != null) targetName else sourceName
        }
        return sourceName
    }

    private fun findParentDocumentId(context: Context, tree: Uri, parentId: String, targetId: String, depth: Int): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val directories = mutableListOf<String>()
        context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: ""
                val mime = cursor.getString(2) ?: ""
                if (id == targetId) return parentId
                if (depth > 0 && mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    name !in setOf("luu_tru", "Studio_Luu_tru", "VoiceGuard_giu_goc")) directories += id
            }
        }
        if (depth <= 0) return null
        for (childId in directories) {
            val found = findParentDocumentId(context, tree, childId, targetId, depth - 1)
            if (found != null) return found
        }
        return null
    }
    private fun findOrCreate(data: JSONArray, originalName: String): JSONObject {
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            if (entry.optString("original") == originalName) return entry
        }
        return JSONObject().put("original", originalName).also { data.put(it) }
    }

    private data class Child(val name: String, val uri: Uri, val mime: String, val parentUri: Uri)

    private fun children(context: Context, folder: Uri): List<Child> {
        val tree = canonicalTree(folder)
        val result = mutableListOf<Child>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        fun scan(parentId: String, parentUri: Uri, depth: Int) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    if (mime.startsWith("video/") && !name.startsWith(".")) {
                        result += Child(name, DocumentsContract.buildDocumentUriUsingTree(tree, childId), mime, parentUri)
                    } else if (depth == 0 && mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                        name in setOf("VoiceGuard_masked", "VG_Subtitle_Masked")) {
                        val childFolder = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                        scan(childId, childFolder, depth + 1)
                    }
                }
            }
        }
        val id = folderDocumentId(folder)
        val folderDocument = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        scan(id, folderDocument, 0)
        return result
    }

    private fun overlayBase(originalName: String): String {
        val stem = originalName.substringBeforeLast('.', originalName)
        if (Regex("^\\d{6}_\\d{6}(?:_\\d{2})?$").matches(stem)) return "${stem}_ovl"
        return "COROS_" + stem.replace(Regex("(?i)^DJI[_-]*"), "")
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.', '-')
            .take(60).ifBlank { "video" }
    }

    private fun canonicalProductName(name: String): String {
        val match = Regex("^COROS_(\\d{6}_\\d{6}.*\\.mp4)$", RegexOption.IGNORE_CASE)
            .matchEntire(name) ?: return name
        return match.groupValues[1]
    }

    private fun uniqueName(name: String, exists: (String) -> Boolean): String {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = if (extension.isEmpty()) stem + "__" + index
                else stem + "__" + index + "." + extension
            if (!exists(candidate)) return candidate
            index++
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }
    private fun isMasked(name: String) =
        name.substringBeforeLast('.').endsWith("_masked", ignoreCase = true)

    /**
     * DJI Mimo có thể sắp xếp theo thời gian sửa đổi. SAF không cung cấp API
     * ghi COLUMN_LAST_MODIFIED, nên với bộ nhớ trong dùng document ID của
     * ExternalStorageProvider để cập nhật mtime thật của file sau khi tổng hợp.
     */
    private fun setCaptureModifiedTime(context: Context, name: String, uri: Uri): Boolean {
        val match = Regex("^(\\d{6}_\\d{6})(?:_.*)?\\.mp4$", RegexOption.IGNORE_CASE).matchEntire(name)
            ?: return false
        val captureTime = runCatching {
            SimpleDateFormat("yyMMdd_HHmmss", Locale.US).parse(match.groupValues[1])?.time
        }.getOrNull() ?: return false

        return runCatching {
            val documentId = DocumentsContract.getDocumentId(uri)
            val colon = documentId.indexOf(':')
            if (colon <= 0 || !documentId.substring(0, colon).equals("primary", true)) return@runCatching false
            val relativePath = documentId.substring(colon + 1).replace('/', File.separatorChar)
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val target = File(root, relativePath).canonicalFile
            if (!target.path.startsWith(root.path + File.separator) || !target.isFile) return@runCatching false
            if (!target.setLastModified(captureTime) || target.lastModified() != captureTime) {
                return@runCatching false
            }

            // DJI Mimo and Android Files may sort/read MediaStore metadata rather
            // than stat() mtime. Rescan after setting the filesystem timestamp.
            val latch = CountDownLatch(1)
            var scannedUri: Uri? = null
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf("video/mp4")
            ) { _, contentUri ->
                scannedUri = contentUri
                latch.countDown()
            }
            if (!latch.await(8, TimeUnit.SECONDS)) return@runCatching false
            val mediaUri = scannedUri ?: return@runCatching false
            val modifiedSeconds = context.contentResolver.query(
                mediaUri,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            } ?: return@runCatching false
            modifiedSeconds == captureTime / 1000L
        }.getOrDefault(false)
    }

    /**
     * Thứ tự ghép phải lấy từ video NGUỒN, không lấy từ tên sản phẩm sau OVL/VG.
     * OVL/VG có thể thêm/bớt hậu tố hoặc đổi tên; timestamp DJI YYMMDD_HHMMSS
     * trong tên gốc là khóa thứ tự ổn định. Với tên không có timestamp, giữ thứ
     * tự record đã được ghi trong nhật ký thay vì tự sắp theo tên đầu ra.
     */
    private fun captureOrderKey(sourceName: String, fallbackIndex: Int): String {
        val match = Regex("^(\\d{6})_(\\d{6})(?:_(\\d{2}))?", RegexOption.IGNORE_CASE)
            .find(sourceName)
        return if (match != null) {
            "0" + match.groupValues[1] + match.groupValues[2] +
                match.groupValues[3].padStart(2, '0')
        } else {
            "1" + fallbackIndex.toString().padStart(8, '0')
        }
    }

    /** Trả về đúng một sản phẩm đã chọn cho mỗi nguồn, theo đúng thứ tự quay nguồn. */
    @Synchronized
    fun listConsolidatedProducts(context: Context, folder: Uri): List<VideoFile> {
        val records = read(context, folder)
        val productOrder = linkedMapOf<String, String>()
        for (i in 0 until records.length()) {
            val item = records.optJSONObject(i) ?: continue
            val product = item.optString("product").takeIf { it.isNotBlank() } ?: continue
            val original = item.optString("original").ifBlank { product }
            productOrder[product] = captureOrderKey(original, i)
        }
        if (productOrder.isEmpty()) return emptyList()
        return VideoLister.listInFolder(
            context, canonicalTree(folder), folderDocumentId(folder), includeMasked = true
        ).filter { it.name in productOrder }
            .distinctBy { it.uri.toString() }
            .sortedWith(compareBy<VideoFile> { productOrder[it.name] ?: "2" + it.name.lowercase(Locale.ROOT) }
                .thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /**
     * Dọn riêng Studio_Luu_tru khi thư mục ngày đã quá retentionDays.
     * Dựa trên ngày YYMMDD của thư mục, không dựa vào mtime của video gốc
     * (mtime cũ có thể khiến tư liệu vừa chuyển vào lưu trữ bị xóa nhầm).
     * Không đụng tới các video sản phẩm ở thư mục ngày hoặc thư mục product.
     */
    /** Permanent archive: intentionally never auto-deleted. */
    fun cleanupExpiredArchive(context: Context, folder: Uri, retentionDays: Int = 7): Int = 0

    /**
     * Chọn đúng một sản phẩm cho mỗi video nguồn. Bản không dùng được chuyển vào
     * Studio_Luu_tru; không xóa tự động để tránh mất dữ liệu.
     */
    fun consolidate(context: Context, folder: Uri): StudioConsolidationReport {
        val tree = canonicalTree(folder)
        val folderId = folderDocumentId(folder)
        val folderDocument = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        val files = children(context, folder)
        val byName = files.associateBy { it.name }
        val records = read(context, folder)
        val handled = mutableSetOf<String>()
        val keep = mutableSetOf<String>()
        val archive = mutableSetOf<String>()
        val archiveOriginals = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        var products = 0

        val sources = linkedMapOf<String, JSONObject>()
        for (i in 0 until records.length()) {
            val item = records.optJSONObject(i) ?: continue
            val original = item.optString("original")
            if (original.isNotBlank()) sources[original] = item
        }

        // Bổ sung các video đã có từ các phiên bản cũ chưa ghi nhật ký.
        files.filter {
            !it.name.startsWith("COROS_", true) &&
                !Regex("^\\d{6}_\\d{6}(?:_\\d{2})?_ovl(?:_\\d+)?\\.mp4$", RegexOption.IGNORE_CASE).matches(it.name) &&
                !isMasked(it.name) &&
                !it.name.equals(ARCHIVE, true) && !it.name.equals("Studio_Luu_tru", true) && !it.name.equals("VoiceGuard_giu_goc", true)
        }.forEach { child ->
            if (!sources.containsKey(child.name)) {
                val entry = JSONObject().put("original", child.name)
                sources[child.name] = entry
                records.put(entry)
            }
        }

        for ((original, record) in sources) {
            val originalName = record.optString("original", original).ifBlank { original }
            val base = overlayBase(originalName)
            val overlayName = record.optString("overlay").takeIf { it.isNotBlank() }
            val maskedName = record.optString("masked").takeIf { it.isNotBlank() }

            val originalFile = byName[originalName]
            val overlayFile = overlayName?.let { byName[it] }
                ?: files.firstOrNull { it.name.matches(Regex("^" + Regex.escape(base) + "(?:_\\d+)?\\.mp4$", RegexOption.IGNORE_CASE)) }
            val legacyMaskedNames = listOf(originalName, overlayName.orEmpty())
                .filter { it.isNotBlank() }
                .map { it.substringBeforeLast('.', it) + "_masked.mp4" }
            val maskedFile = maskedName?.let { byName[it] }
                ?: files.firstOrNull { it.name in legacyMaskedNames }
                ?: files.firstOrNull { it.name.matches(Regex("^" + Regex.escape(base) + "(?:_\\d+)?_masked\\.mp4$", RegexOption.IGNORE_CASE)) }

            val chosen = maskedFile ?: overlayFile ?: originalFile
            if (chosen != null) {
                keep += chosen.name
                products++
                record.put("product", chosen.name)
                if (maskedFile != null) record.put("masked", maskedFile.name)
                record.put("productType", when (chosen) {
                    maskedFile -> "VG"
                    overlayFile -> "OVERLAY"
                    else -> "ORIGINAL"
                })
            }

            listOfNotNull(originalFile, overlayFile, maskedFile).distinctBy { it.name }.forEach {
                handled += it.name
                if (chosen == null || it.name != chosen.name) {
                    if (originalFile != null && it.name == originalFile.name) archiveOriginals += it.name
                    else archive += it.name
                }
            }
        }

        // File _masked cũ có thể là đầu vào của VG mà bản Overlay gốc không còn.
        files.filter { isMasked(it.name) && it.name !in handled }.forEach { masked ->
            keep += masked.name
            handled += masked.name
            products++
        }

        var archived = 0
        var archivedOriginals = 0
        val sourceOriginalNames = sources.keys.toSet()
        // Kéo bản sản phẩm VG cũ từ thư mục con về cùng thư mục ngày.
        files.filter { it.name in keep && it.parentUri != folderDocument }.forEach { child ->
            try {
                DocumentsContract.moveDocument(context.contentResolver, child.uri, child.parentUri, folderDocument)
                    ?: throw IOException("SAF không di chuyển được sản phẩm về thư mục ngày")
            } catch (e: Throwable) {
                errors += "${child.name}: ${e.localizedMessage ?: "không thể đưa về thư mục ngày"}"
            }
        }

        // Move all superseded originals and OVL/VG intermediates into the same permanent luu_tru folder.
        val filesToArchive = files.filter { (it.name in archiveOriginals || it.name in archive) && it.name !in keep }
        if (filesToArchive.isNotEmpty()) {
            try {
                val archiveUri = findOrCreateDirectory(context, tree, folderId, ARCHIVE)
                val reservedNames = children(context, archiveUri).map { it.name }.toMutableSet()
                filesToArchive.forEach { child ->
                    try {
                        val targetName = uniqueName(child.name) { it in reservedNames }
                        val moved = DocumentsContract.moveDocument(context.contentResolver, child.uri, child.parentUri, archiveUri)
                            ?: throw IOException("SAF không di chuyển được file vào luu_tru")
                        if (targetName != child.name) {
                            DocumentsContract.renameDocument(context.contentResolver, moved, targetName)
                                ?: throw IOException("Đã chuyển file nhưng không đổi được tên lưu trữ")
                        }
                        reservedNames += targetName
                        archived++
                        if (child.name in archiveOriginals) archivedOriginals++
                    } catch (e: Throwable) {
                        errors += child.name + ": " + (e.localizedMessage ?: "không thể lưu vào luu_tru")
                    }
                }
            } catch (e: Throwable) {
                errors += "Không tạo được thư mục luu_tru: " + (e.localizedMessage ?: "lỗi")
            }
        }
        // Chuẩn hóa tên sản phẩm cũ có tiền tố COROS_ nếu phần còn lại bắt đầu
        // bằng timestamp YYMMDD_HHMMSS. Không đoán tên cho file không nhận diện được.
        val normalizedKeep = mutableSetOf<String>()
        val postMoveFiles = children(context, folder)
        val reservedNames = postMoveFiles.map { it.name }.toMutableSet()
        for (oldName in keep) {
            val child = postMoveFiles.firstOrNull { it.name == oldName }
            if (child == null) { normalizedKeep += oldName; continue }
            val targetName = canonicalProductName(oldName)
            if (targetName == oldName) { normalizedKeep += oldName; continue }
            try {
                val occupied = targetName in reservedNames && targetName != oldName
                val safeTarget = if (!occupied) targetName else uniqueName(targetName) { candidate -> candidate in reservedNames }
                val renamed = DocumentsContract.renameDocument(context.contentResolver, child.uri, safeTarget)
                    ?: throw IOException("SAF không đổi được tên file")
                val actualName = queryDisplayName(context, renamed) ?: safeTarget
                reservedNames.remove(oldName)
                reservedNames += actualName
                normalizedKeep += actualName
                sources.values.firstOrNull { it.optString("product") == oldName }?.put("product", actualName)
            } catch (e: Throwable) {
                normalizedKeep += oldName
                errors += oldName + ": không chuẩn hóa được tên (" + (e.localizedMessage ?: "lỗi SAF") + ")"
            }
        }
        keep.clear()
        keep.addAll(normalizedKeep)

        // Đặt lại mtime của các video sản phẩm theo thời điểm quay trong tên file.
        // Thực hiện sau move/archive/rename để thao tác SAF không ghi đè thời gian vừa đặt.
        val finalFiles = children(context, folder).filter { it.name in keep }
        finalFiles.forEach { child ->
            if (!setCaptureModifiedTime(context, child.name, child.uri)) {
                errors += "${child.name}: không cập nhật được thời gian sửa đổi theo thời điểm quay"
            }
        }

        val originals = sources.size
        val overlayCount = sources.values.count { it.optString("overlay").isNotBlank() }
        val maskedCount = sources.values.count { it.optString("masked").isNotBlank() }
        val importDurationMs = sources.values.sumOf { it.optLong("importDurationMs", 0L) }
        val overlayDurationMs = sources.values.sumOf { it.optLong("overlayDurationMs", 0L) }
        val maskedDurationMs = sources.values.sumOf { it.optLong("maskedDurationMs", 0L) }
        save(context, folder, records)
        val archivedCleaned = cleanupExpiredArchive(context, folder, retentionDays = 7)
        return StudioConsolidationReport(
            products = products,
            archived = archived,
            errors = errors,
            originals = originals,
            overlayCount = overlayCount,
            importDurationMs = importDurationMs,
            overlayDurationMs = overlayDurationMs,
            maskedCount = maskedCount,
            maskedDurationMs = maskedDurationMs,
            archivedOriginals = archivedOriginals,
            archivedCleaned = archivedCleaned
        )
    }

    private fun findOrCreateDirectory(context: Context, folder: Uri, parentId: String, name: String): Uri {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name &&
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(
            context.contentResolver, folder, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IOException("Không tạo được thư mục $name")
    }
}
