package com.voiceguard

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Đưa các file *_masked ra thư mục clip và đặt lại "giờ" của chúng (ngày sửa file + ngày quay trong metadata)
 * sao cho khi sắp theo thời gian, chúng nằm đúng chỗ giữa các clip khác theo thứ tự tên file.
 * Cần quyền "Truy cập tất cả các tệp" (MANAGE_EXTERNAL_STORAGE).
 */
object TimeFixer {
    class Result(val moved: Int, val maskedTotal: Int, val fixed: Int, val failed: Int, val patched: Int)

    private class Item(val file: File, val key: String, val masked: Boolean)

    private fun isVideo(f: File) = f.isFile && !f.name.startsWith(".") &&
        (f.extension.equals("mp4", true) || f.extension.equals("mov", true))

    private fun isMasked(f: File) = f.name.substringBeforeLast('.').endsWith("_masked")

    private fun keyOf(f: File) = f.name.substringBeforeLast('.').removeSuffix("_masked")

    fun run(ctx: Context, folder: File, onProgress: (Int, Int, String) -> Unit): Result {
        // 1. Đưa file _masked từ thư mục con VoiceGuard_masked ra thư mục ngoài (Mimo không liệt kê thư mục con)
        var moved = 0
        val sub = File(folder, MaskExporter.OUTPUT_FOLDER)
        sub.listFiles()?.filter { isVideo(it) }?.forEach {
            val dst = File(folder, it.name)
            if (!dst.exists() && it.renameTo(dst)) moved++
        }

        // 2. Xếp tất cả video theo tên; bản _masked đứng đúng chỗ của clip gốc cùng tên
        val items = (folder.listFiles() ?: emptyArray())
            .filter { isVideo(it) }
            .map { Item(it, keyOf(it), isMasked(it)) }
            .sortedWith(compareBy<Item> { it.key }.thenBy { it.masked })

        val maskedTotal = items.count { it.masked }
        var fixed = 0
        var failed = 0
        var patched = 0
        var done = 0
        val touched = ArrayList<String>()

        // 3. Mỗi chuỗi clip _masked liên tiếp nằm giữa 2 clip thường: chia đều thời gian giữa 2 clip đó
        var i = 0
        while (i < items.size) {
            if (!items[i].masked) {
                i++
                continue
            }
            var j = i
            while (j + 1 < items.size && items[j + 1].masked) j++
            val n = j - i + 1
            val prev = items.getOrNull(i - 1)?.file
            val next = items.getOrNull(j + 1)?.file

            val modTimes = interpolate(
                prev?.lastModified()?.takeIf { it > 0 },
                next?.lastModified()?.takeIf { it > 0 },
                n
            )
            val prevMeta = prev?.let { embeddedMillis(it) }
            val nextMeta = next?.let { embeddedMillis(it) }
            val metaTimes = if (prevMeta != null || nextMeta != null) interpolate(prevMeta, nextMeta, n) else null

            for (k in 0 until n) {
                val f = items[i + k].file
                onProgress(done, maskedTotal, f.name)
                if (metaTimes != null) {
                    try {
                        Mp4Time.patchCreationTime(f, metaTimes[k])
                        patched++
                    } catch (e: Throwable) {
                    }
                }
                // setLastModified phải làm sau cùng vì ghi metadata ở trên cũng làm đổi ngày sửa file
                if (f.setLastModified(modTimes[k])) {
                    fixed++
                    touched.add(f.absolutePath)
                } else {
                    failed++
                }
                done++
            }
            i = j + 1
        }

        if (touched.isNotEmpty()) {
            MediaScannerConnection.scanFile(ctx, touched.toTypedArray(), null, null)
        }
        return Result(moved, maskedTotal, fixed, failed, patched)
    }

    private fun interpolate(prev: Long?, next: Long?, n: Int): LongArray {
        val out = LongArray(n)
        for (k in 0 until n) {
            out[k] = when {
                prev != null && next != null && next > prev -> prev + (next - prev) * (k + 1) / (n + 1)
                prev != null -> prev + (k + 1) * 1000L
                next != null -> next - (n - k) * 1000L
                else -> System.currentTimeMillis() + k * 1000L
            }
        }
        return out
    }

    private fun embeddedMillis(f: File): Long? {
        return try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return null
                val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.parse(d.substringBefore('.').substringBefore('Z'))?.time?.takeIf { it > 0 }
            } finally {
                r.release()
            }
        } catch (e: Throwable) {
            null
        }
    }
}
