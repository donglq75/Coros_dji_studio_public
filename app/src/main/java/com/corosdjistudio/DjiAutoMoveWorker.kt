package com.corosdjistudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * SAF-safe integration of DJI Mover.
 * Watches MediaStore changes, has a 15-minute recovery scan, and only moves files
 * after their size has remained unchanged across scans.
 */
class DjiAutoMoveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return Result.success()
        val rootRaw = inputData.getString(KEY_ROOT) ?: prefs.getString(KEY_ROOT, null)
            ?: return Result.failure()
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            prefs.edit().putString(KEY_STATUS, "Cần cấp quyền truy cập tất cả tệp để theo dõi DCIM/DJI.").apply()
            return Result.success()
        }
        return try {
            val moved = withContext(Dispatchers.IO) { scanAndMove(Uri.parse(rootRaw), prefs) }
            prefs.edit().putString(KEY_STATUS, "DJI Mover: đã chuyển ${moved.first} video; đang chờ ${moved.second} video tải hoàn tất.").apply()
            if (moved.second > 0) enqueueRetry(applicationContext, rootRaw)
            arm(applicationContext, rootRaw)
            Result.success()
        } catch (e: Throwable) {
            prefs.edit().putString(KEY_STATUS, "DJI Mover gặp lỗi: ${e.localizedMessage ?: "không xác định"}").apply()
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun scanAndMove(rootTree: Uri, prefs: android.content.SharedPreferences): Pair<Int, Int> {
        val dcim = File(Environment.getExternalStorageDirectory(), "DCIM")
        val dirs = dcim.listFiles { f -> f.isDirectory && f.name.startsWith("DJI", true) } ?: emptyArray()
        var moved = 0
        var pending = 0
        val now = System.currentTimeMillis()
        dirs.forEach { dir ->
            dir.walkTopDown().filter { f ->
                f.isFile && !f.name.startsWith(".") && f.extension.lowercase(Locale.ROOT) in VIDEO_EXT
            }.forEach { source ->
                val key = "seen_" + source.absolutePath.hashCode().toUInt().toString(16)
                val size = source.length()
                val previous = prefs.getLong(key, -1L)
                if (size <= 0L || previous != size || now - source.lastModified() < MIN_AGE_MS) {
                    prefs.edit().putLong(key, size).apply()
                    pending++
                    return@forEach
                }
                if (!readableVideo(source)) {
                    pending++
                    return@forEach
                }
                try {
                    val didMove = moveOne(source, rootTree)
                    prefs.edit().remove(key).apply()
                    if (didMove) moved++
                } catch (_: Throwable) {
                    pending++
                }
            }
        }
        return moved to pending
    }

    private fun readableVideo(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L
        } catch (_: Throwable) { false }
        finally { runCatching { retriever.release() } }
    }

    private fun moveOne(source: File, rootTree: Uri): Boolean = synchronized(MOVE_LOCK) {
        // Different WorkManager triggers can overlap; serialize the check/create/copy/delete
        // sequence so concurrent scans cannot manufacture _02 and provider-added (1) names.
        if (!source.exists()) return@synchronized false
        // Mimo exports are generated from clips already stored in Studio. Their 13-digit
        // filename suffix is an export timestamp, NOT the original recording date.
        if (source.name.startsWith("compose_video_", ignoreCase = true)) {
            return@synchronized moveMimoExport(source, rootTree)
        }
        // DJI originals carry the authoritative local capture stamp: YYYYMMDD_HHMMSS.
        val stamp = DJI_TIMESTAMP.find(source.name)
            ?: throw IOException("Tên video DJI không có timestamp YYYYMMDD_HHMMSS: ${source.name}")
        val shortBase = "${stamp.groupValues[1].takeLast(2)}${stamp.groupValues[2]}${stamp.groupValues[3]}_${stamp.groupValues[4]}"
        val capture = captureTime(source)
        val folder = StudioStorage.dateFolderDocument(applicationContext, rootTree, capture)
        val folderId = DocumentsContract.getDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, folderId)
        val existing = mutableSetOf<String>()
        applicationContext.contentResolver.query(children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) existing += c.getString(0)
        }
        val dot = source.name.lastIndexOf('.')
        val ext = if (dot > 0) source.name.substring(dot).lowercase(Locale.ROOT) else ".mp4"
        val stem = shortBase
        var name = "$stem$ext"
        var suffix = 2
        while (name in existing) name = "${stem}_${suffix.toString().padStart(2, '0')}${ext}".also { suffix++ }
        val target = DocumentsContract.createDocument(applicationContext.contentResolver, folder,
            "video/mp4", name) ?: throw IOException("Không tạo được file đích")
        val importStartedAtNanos = System.nanoTime()
        try {
            var copied = 0L
            source.inputStream().buffered().use { input ->
                applicationContext.contentResolver.openOutputStream(target, "w")?.buffered()?.use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                    }
                    output.flush()
                } ?: throw IOException("Không mở được file đích")
            }
            if (copied != source.length() || copied <= 0L) throw IOException("Dung lượng bản sao không khớp")
            val importDurationMs = ((System.nanoTime() - importStartedAtNanos) / 1_000_000L).coerceAtLeast(1L)
            StudioSessionLog.recordOriginal(applicationContext, folder, name, importDurationMs)
            if (!source.delete()) throw IOException("Đã chép xong nhưng không xóa được file nguồn")
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, target) }
            throw e
        }
        true
    }

    private fun moveMimoExport(source: File, rootTree: Uri): Boolean {
        // Mimo compose filename suffix is the render/export epoch in milliseconds.
        // Prefer it over MP4 DATE metadata, which may be copied from source clips.
        val renderedAt = mimoRenderTime(source)
        val folder = StudioStorage.productFolderDocument(applicationContext, rootTree)
        val folderId = DocumentsContract.getDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootTree, folderId)
        val existing = mutableSetOf<String>()
        applicationContext.contentResolver.query(children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) existing += c.getString(0)
        }
        val stem = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date(renderedAt))
        var name = "$stem.mp4"
        var suffix = 2
        while (name in existing) name = "${stem}_${suffix.toString().padStart(2, '0')}.mp4".also { suffix++ }
        val target = DocumentsContract.createDocument(applicationContext.contentResolver, folder,
            "video/mp4", name) ?: throw IOException("Không tạo được file Mimo đích")
        try {
            var copied = 0L
            source.inputStream().buffered().use { input ->
                applicationContext.contentResolver.openOutputStream(target, "w")?.buffered()?.use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                    }
                    output.flush()
                } ?: throw IOException("Không mở được file Mimo đích")
            }
            if (copied != source.length() || copied <= 0L)
                throw IOException("Dung lượng bản sao Mimo không khớp")
            if (!source.delete()) throw IOException("Đã chép Mimo xong nhưng không xóa được file nguồn")
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, target) }
            throw e
        }
        return true
    }

    /** Mimo's compose_video_<epoch-ms>.mp4 suffix records the render time.
     * Fall back to MP4 DATE metadata, then file modified time if the suffix is absent/invalid. */
    private fun mimoRenderTime(file: File): Long {
        val suffix = Regex("^compose_video_(\\d{13})$", RegexOption.IGNORE_CASE)
            .find(file.nameWithoutExtension)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val minEpoch = 946684800000L // 2000-01-01
        val maxEpoch = 4102444800000L // 2100-01-01
        if (suffix != null && suffix in minEpoch until maxEpoch) return suffix
        return mimoCaptureTime(file)
            ?: file.lastModified().takeIf { it in minEpoch until maxEpoch }
            ?: System.currentTimeMillis()
    }

    /** Fallback only: use explicit MP4 DATE metadata when Mimo's render timestamp is absent. */
    private fun mimoCaptureTime(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.trim().orEmpty()
            if (raw.isBlank()) return null
            val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            if (instant != null) return instant.toEpochMilli()
            val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyyMMdd'T'HHmmss", "yyyyMMddHHmmss")
            for (pattern in patterns) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(raw)?.time
                }.getOrNull()
                if (parsed != null) return parsed
            }
            null
        } catch (_: Throwable) { null }
        finally { runCatching { retriever.release() } }
    }

    private fun captureTime(file: File): Long {
        val m = DATE.find(file.name)
        if (m != null) {
            val raw = m.groupValues.drop(1).joinToString("")
            val pattern = if (raw.length >= 14) "yyyyMMddHHmmss" else "yyyyMMdd"
            runCatching { return SimpleDateFormat(pattern, Locale.US).parse(raw.take(if (raw.length >= 14) 14 else 8))?.time ?: file.lastModified() }
        }
        return file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
    }

    companion object {
        const val PREFS = "dji_mover"
        const val KEY_ENABLED = "auto_enabled"
        const val KEY_ROOT = "studio_root"
        const val KEY_STATUS = "status"
        private const val CHANNEL = "dji_mover_channel"
        private const val NOTIFICATION_ID = 8421
        private val MOVE_LOCK = Any()
        private const val MIN_AGE_MS = 20_000L
        private val VIDEO_EXT = setOf("mp4", "mov", "m4v")
        private val DJI_TIMESTAMP = Regex("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])_([0-2]\\d[0-5]\\d[0-5]\\d)(?!\\d)")
        private val DATE = Regex("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?:_?([0-2]\\d[0-5]\\d[0-5]\\d))?")

        fun start(context: Context, root: Uri) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_ROOT, root.toString()).putBoolean(KEY_ENABLED, true).apply()
            val periodic = PeriodicWorkRequestBuilder<DjiAutoMoveWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_ROOT to root.toString())).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("dji_mover_periodic",
                ExistingPeriodicWorkPolicy.UPDATE, periodic)
            enqueueScan(context, root.toString())
            arm(context, root.toString())
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            WorkManager.getInstance(context).cancelUniqueWork("dji_mover_periodic")
            WorkManager.getInstance(context).cancelUniqueWork("dji_mover_watch")
            WorkManager.getInstance(context).cancelUniqueWork("dji_mover_retry")
            WorkManager.getInstance(context).cancelUniqueWork("dji_mover_scan")
        }

        fun scanNow(context: Context, root: Uri) {
            enqueueScan(context, root.toString())
        }

        private fun enqueueScan(context: Context, root: String) {
            val req = OneTimeWorkRequestBuilder<DjiAutoMoveWorker>()
                .setInputData(workDataOf(KEY_ROOT to root)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("dji_mover_scan", ExistingWorkPolicy.REPLACE, req)
        }

        private fun enqueueRetry(context: Context, root: String) {
            val req = OneTimeWorkRequestBuilder<DjiAutoMoveWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS).setInputData(workDataOf(KEY_ROOT to root)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("dji_mover_retry", ExistingWorkPolicy.REPLACE, req)
        }

        private fun arm(context: Context, root: String) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(3, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(15, TimeUnit.SECONDS)
                .build()
            val request = OneTimeWorkRequestBuilder<DjiAutoMoveWorker>()
                .setConstraints(constraints).setInputData(workDataOf(KEY_ROOT to root)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("dji_mover_watch", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
