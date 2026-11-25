package com.voiceguard

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nối nhiều clip thành một file mp4 mà không giải mã/nén lại (chỉ chép các gói dữ liệu hình và tiếng
 * sang file mới với mốc thời gian nối tiếp nhau), nên giữ nguyên chất lượng 4K và rất nhanh.
 * Chỉ hợp lệ khi các clip cùng định dạng (cùng máy quay, cùng cài đặt); clip khác định dạng sẽ bắt đầu một file mới.
 */
object Concatenator {
    class Result(val parts: Int, val clips: Int, val formatSplits: Int, val skipped: List<String>, val mismatchDetails: List<String> = emptyList())

    private class Probe(val video: MediaFormat, val audio: MediaFormat?, val vIdx: Int, val aIdx: Int, val durationUs: Long)

    private fun probe(ctx: Context, uri: Uri): Probe? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var v: MediaFormat? = null
            var a: MediaFormat? = null
            var vi = -1
            var ai = -1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && v == null) {
                    v = f
                    vi = i
                } else if (mime.startsWith("audio/") && a == null) {
                    a = f
                    ai = i
                }
            }
            if (v == null) return null
            var dur = if (v.containsKey(MediaFormat.KEY_DURATION)) v.getLong(MediaFormat.KEY_DURATION) else 0L
            if (dur <= 0 && a != null && a.containsKey(MediaFormat.KEY_DURATION)) dur = a.getLong(MediaFormat.KEY_DURATION)
            return Probe(v, a, vi, ai, dur)
        } finally {
            ex.release()
        }
    }

    private class PartWriter(
        private val ctx: Context,
        outDir: Uri,
        displayName: String,
        val video: MediaFormat,
        val audio: MediaFormat?
    ) {
        private val outUri: Uri = DocumentsContract.createDocument(ctx.contentResolver, outDir, "video/mp4", displayName)
            ?: throw IOException("Không tạo được file đầu ra")
        private val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(outUri, "rw")
            ?: throw IOException("Không mở được file đầu ra để ghi")
        private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vTrack: Int
        val aTrack: Int
        var base = 0L
        var clips = 0
        private var closed = false

        init {
            if (video.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(video.getInteger(MediaFormat.KEY_ROTATION))
            }
            vTrack = muxer.addTrack(video)
            aTrack = if (audio != null) muxer.addTrack(audio) else -1
            muxer.start()
        }

        fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            muxer.writeSampleData(track, buf, info)
        }

        /** ok = true: hoàn tất file; ok = false: bỏ dở và xóa file. */
        fun finish(ok: Boolean) {
            if (closed) return
            closed = true
            var error: Exception? = null
            try {
                if (ok) muxer.stop()
            } catch (e: Exception) {
                error = e
            }
            try {
                muxer.release()
            } catch (e: Exception) {
            }
            try {
                pfd.close()
            } catch (e: Exception) {
            }
            if (!ok || error != null) {
                try {
                    DocumentsContract.deleteDocument(ctx.contentResolver, outUri)
                } catch (e: Exception) {
                }
            }
            if (error != null) throw IOException("Không hoàn tất được file ghép: ${error.message}")
        }
    }

    private fun mismatchDescription(w: PartWriter, p: Probe): String {
        val a = w.video
        val b = p.video
        val reasons = mutableListOf<String>()
        fun value(f: MediaFormat, key: String, fallback: String = "?"): String =
            if (f.containsKey(key)) runCatching { f.getString(key) ?: f.getInteger(key).toString() }.getOrDefault(fallback) else fallback
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME))
            reasons += "videoMime=${a.getString(MediaFormat.KEY_MIME)}/${b.getString(MediaFormat.KEY_MIME)}"
        if (a.getInteger(MediaFormat.KEY_WIDTH) != b.getInteger(MediaFormat.KEY_WIDTH) ||
            a.getInteger(MediaFormat.KEY_HEIGHT) != b.getInteger(MediaFormat.KEY_HEIGHT))
            reasons += "size=${a.getInteger(MediaFormat.KEY_WIDTH)}x${a.getInteger(MediaFormat.KEY_HEIGHT)}/${b.getInteger(MediaFormat.KEY_WIDTH)}x${b.getInteger(MediaFormat.KEY_HEIGHT)}"
        val ra = if (a.containsKey(MediaFormat.KEY_ROTATION)) a.getInteger(MediaFormat.KEY_ROTATION) else 0
        val rb = if (b.containsKey(MediaFormat.KEY_ROTATION)) b.getInteger(MediaFormat.KEY_ROTATION) else 0
        if (ra != rb) reasons += "rotation=$ra/$rb"
        val wa = w.audio
        val pa = p.audio
        if (wa == null && pa != null) reasons += "audioTrack=none/present"
        else if (wa != null && pa == null) reasons += "audioTrack=present/none"
        else if (wa != null && pa != null) {
            if (wa.getString(MediaFormat.KEY_MIME) != pa.getString(MediaFormat.KEY_MIME))
                reasons += "audioMime=${wa.getString(MediaFormat.KEY_MIME)}/${pa.getString(MediaFormat.KEY_MIME)}"
            val srA = if (wa.containsKey(MediaFormat.KEY_SAMPLE_RATE)) wa.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
            val srB = if (pa.containsKey(MediaFormat.KEY_SAMPLE_RATE)) pa.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
            if (srA != srB) reasons += "sampleRate=$srA/$srB"
            val chA = if (wa.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) wa.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
            val chB = if (pa.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) pa.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
            if (chA != chB) reasons += "channels=$chA/$chB"
        }
        return reasons.joinToString(", ").ifBlank { "khác thông số muxer chưa được phân loại" }
    }

    private fun matches(w: PartWriter, p: Probe): Boolean {
        val a = w.video
        val b = p.video
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME)) return false
        if (a.getInteger(MediaFormat.KEY_WIDTH) != b.getInteger(MediaFormat.KEY_WIDTH)) return false
        if (a.getInteger(MediaFormat.KEY_HEIGHT) != b.getInteger(MediaFormat.KEY_HEIGHT)) return false
        // MP4 rotation is container metadata. Never mux clips with different orientation hints
        // under the first clip's single MediaMuxer orientationHint.
        val rotationA = if (a.containsKey(MediaFormat.KEY_ROTATION)) a.getInteger(MediaFormat.KEY_ROTATION) else 0
        val rotationB = if (b.containsKey(MediaFormat.KEY_ROTATION)) b.getInteger(MediaFormat.KEY_ROTATION) else 0
        if (rotationA != rotationB) return false
        val wa = w.audio
        val pa = p.audio
        if (wa == null && pa != null) return false
        if (wa != null && pa != null) {
            if (wa.getString(MediaFormat.KEY_MIME) != pa.getString(MediaFormat.KEY_MIME)) return false
            if (wa.getInteger(MediaFormat.KEY_SAMPLE_RATE) != pa.getInteger(MediaFormat.KEY_SAMPLE_RATE)) return false
            if (wa.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != pa.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) return false
        }
        return true
    }

    private suspend fun appendClip(ctx: Context, uri: Uri, p: Probe, w: PartWriter, buf: ByteBuffer) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            ex.selectTrack(p.vIdx)
            val useAudio = p.aIdx >= 0 && w.aTrack >= 0
            if (useAudio) ex.selectTrack(p.aIdx)

            val info = MediaCodec.BufferInfo()
            var lastVideoUs = 0L
            var n = 0
            while (true) {
                val ti = ex.sampleTrackIndex
                if (ti < 0) break
                val t = ex.sampleTime
                val isVideo = ti == p.vIdx
                // Cắt phần tiếng dư ra ngoài độ dài hình để các clip không bị lệch dần
                if (!isVideo && p.durationUs > 0 && t >= p.durationUs) {
                    ex.advance()
                    continue
                }
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val flags =
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, w.base + t, flags)
                w.write(if (isVideo) w.vTrack else w.aTrack, buf, info)
                if (isVideo) lastVideoUs = t
                ex.advance()
                n++
                if (n % 200 == 0) currentCoroutineContext().ensureActive()
            }
            w.base += if (p.durationUs > 0) p.durationUs else lastVideoUs + 33_333L
        } finally {
            ex.release()
        }
    }

    /**
     * Nối các clip theo đúng thứ tự trong danh sách.
     * perPart > 0: mỗi file tối đa perPart clip; = 0: một file duy nhất (trừ khi định dạng thay đổi giữa chừng).
     */
    suspend fun concat(
        ctx: Context,
        files: List<VideoFile>,
        outDir: Uri,
        perPart: Int,
        stamp: String,
        outputPrefix: String = "VoiceGuard_ghep",
        onProgress: suspend (Int, Int, String) -> Unit
    ): Result {
        val buf = ByteBuffer.allocate(16 * 1024 * 1024)
        var writer: PartWriter? = null
        var parts = 0
        var done = 0
        var splits = 0
        val mismatchDetails = ArrayList<String>()
        val skipped = ArrayList<String>()
        try {
            for ((i, f) in files.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(i, files.size, f.name)

                val p = try {
                    probe(ctx, f.uri)
                } catch (e: Exception) {
                    null
                }
                if (p == null) {
                    skipped.add(f.name)
                    continue
                }

                val cur = writer
                if (cur != null) {
                    val mismatch = !matches(cur, p)
                    if (mismatch || (perPart > 0 && cur.clips >= perPart)) {
                        if (mismatch) {
                        splits++
                        mismatchDetails += "${f.name}: ${mismatchDescription(cur, p)}"
                        android.util.Log.w("StudioMergeFormat", mismatchDetails.last())
                    }
                        writer = null
                        cur.finish(true)
                    }
                }

                var w = writer
                if (w == null) {
                    parts++
                    val name = "${outputPrefix}_${stamp}_P" + parts.toString().padStart(2, '0') + ".mp4"
                    w = PartWriter(ctx, outDir, name, p.video, p.audio)
                    writer = w
                }

                appendClip(ctx, f.uri, p, w, buf)
                w.clips++
                done++
            }
            onProgress(files.size, files.size, "")
            val last = writer
            writer = null
            last?.finish(true)
        } catch (e: CancellationException) {
            writer?.finish(false)
            throw e
        } catch (e: Throwable) {
            writer?.finish(false)
            throw e
        }
        return Result(parts, done, splits, skipped, mismatchDetails)
    }

    /**
     * Remove MP4 container rotation metadata after the final video effects have
     * baked each input's orientation into its encoded frames. Media3 may retain
     * the source rotation hint even when a rotation effect is present; a single
     * stream-copy mux cannot represent different hints across concatenated groups.
     */
    private fun remuxWithoutRotation(source: File, destination: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(0)
            val trackMap = IntArray(extractor.trackCount) { -1 }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.setInteger(MediaFormat.KEY_ROTATION, 0)
                }
                trackMap[i] = muxer.addTrack(format)
                extractor.selectTrack(i)
            }
            if (extractor.trackCount == 0) throw IOException("Video chuẩn hóa không có track.")
            muxer.start()
            muxerStarted = true
            val buffer = ByteBuffer.allocate(64 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sourceTrack = extractor.sampleTrackIndex
                if (sourceTrack < 0) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(trackMap[sourceTrack], buffer, info)
                extractor.advance()
            }
            muxer.stop()
            muxerStarted = false
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
        if (!destination.isFile || destination.length() <= 0L) {
            throw IOException("Không tạo được MP4 đã loại bỏ metadata xoay.")
        }
    }

    /**
     * Re-encode the already ordered intermediate groups to one common MP4.
     * This is deliberately the final stage: fast stream-copy is retained for
     * checkpointed chunks, while codec/resolution/audio-layout differences are
     * normalized only once, avoiding the old P01..Pxx format-split result.
     */
    private suspend fun normalizeToSingleMp4(
        ctx: Context,
        inputs: List<VideoFile>,
        outDir: Uri,
        outputName: String,
        targetWidth: Int,
        targetHeight: Int,
        normalizeInputRotation: Boolean = false
    ): Uri {
        if (inputs.isEmpty()) throw IOException("Không có nhóm video hợp lệ để chuẩn hóa.")
        val temp = File(ctx.cacheDir, "studio_merge_" + System.currentTimeMillis() + ".mp4")
        val outputUri = DocumentsContract.createDocument(
            ctx.contentResolver, outDir, "video/mp4", outputName
        ) ?: throw IOException("Không tạo được file video sản phẩm.")
        var completed = false
        try {
            // Media3 may restore MediaItem metadata while building a Composition.
            // Supply explicit empty extras Bundles instead of leaving nullable extras
            // on SAF-backed items (Android 13+ uses typed Bundle.getParcelable()).
            val edited = inputs.map { file ->
                val safeItem = MediaItem.Builder()
                    .setUri(file.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setExtras(Bundle()).build()
                    )
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder().setExtras(Bundle()).build()
                    )
                    .build()
                val inputProbe = probe(ctx, file.uri)
                    ?: throw IOException("Không đọc được định dạng nhóm " + file.name + ".")
                val videoEffects = mutableListOf<androidx.media3.common.Effect>()
                if (normalizeInputRotation) {
                    val inputRotation = if (inputProbe.video.containsKey(MediaFormat.KEY_ROTATION))
                        inputProbe.video.getInteger(MediaFormat.KEY_ROTATION) else 0
                    if (inputRotation != 0) {
                        // Bake the container rotation into encoded frames and cancel the
                        // inherited orientation hint so every finalpass MP4 has rotation=0.
                        videoEffects += ScaleAndRotateTransformation.Builder()
                            .setRotationDegrees(inputRotation.toFloat())
                            .build()
                    }
                }
                val presentation = Presentation.createForWidthAndHeight(
                    targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT
                )
                videoEffects += presentation
                EditedMediaItem.Builder(safeItem)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()
            }
            val sequence = EditedMediaItemSequence.Builder(edited).build()
            val composition = Composition.Builder(sequence).build()

            withContext(Dispatchers.Main.immediate) {
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build()
                suspendCancellableCoroutine<Unit> { continuation ->
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            val detail = buildString {
                                append("Không chuẩn hóa được video ghép: Muxer/Transformer error")
                                append(" | code=").append(exportException.errorCode)
                                append(" | message=").append(exportException.message ?: "không có thông báo")
                                var cause: Throwable? = exportException.cause
                                var depth = 0
                                while (cause != null && depth < 5) {
                                    append(" | cause").append(depth + 1).append("=")
                                        .append(cause.javaClass.simpleName)
                                        .append(": ").append(cause.message ?: "không có thông báo")
                                    cause = cause.cause
                                    depth++
                                }
                            }
                            android.util.Log.e("StudioMergeMuxer", detail, exportException)
                            if (continuation.isActive) continuation.resumeWithException(
                                IOException(detail, exportException)
                            )
                        }
                    }
                    transformer.addListener(listener)
                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post {
                            runCatching { transformer.cancel() }
                        }
                    }
                    try {
                        transformer.start(composition, temp.absolutePath)
                    } catch (e: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }

            if (!temp.isFile || temp.length() <= 0L) {
                throw IOException("Bộ mã hóa không tạo được video đầu ra.")
            }
            // Final-pass files must have a uniform container rotation of zero.
            // Rotation has already been applied by the video effect; remuxing only
            // clears the inherited MP4 orientation hint without re-encoding samples.
            val finalSource = if (normalizeInputRotation) {
                val rotationFree = File(ctx.cacheDir, "studio_merge_rotation_free_" + System.currentTimeMillis() + ".mp4")
                try {
                    remuxWithoutRotation(temp, rotationFree)
                    rotationFree
                } catch (e: Throwable) {
                    rotationFree.delete()
                    throw e
                }
            } else null
            try {
                val copySource = finalSource ?: temp
                ctx.contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                    copySource.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
                } ?: throw IOException("Không mở được file sản phẩm để ghi.")
            } finally {
                finalSource?.delete()
            }
            completed = true
            return outputUri
        } finally {
            temp.delete()
            if (!completed) runCatching {
                DocumentsContract.deleteDocument(ctx.contentResolver, outputUri)
            }
        }
    }

    /**
     * Resumable product merge.
     *
     * Source clips are first muxed into durable groups in a hidden work folder.
     * A checkpoint is committed only after each group file has been finalized.
     * If Android kills the process, at most the active group is repeated; already
     * completed groups are reused. The final mux from those groups is cheap and
     * can safely be repeated if interrupted.
     */
    suspend fun concatResumable(
        ctx: Context,
        files: List<VideoFile>,
        outDir: Uri,
        stampCandidate: String,
        outputPrefix: String = "DJI_Studio_Product",
        chunkSize: Int = 8,
        onProgress: suspend (Int, Int, String) -> Unit
    ): Result {
        require(files.isNotEmpty()) { "Không có video để ghép." }
        require(chunkSize in 2..32) { "Kích thước nhóm ghép không hợp lệ." }

        val fingerprint = mergeFingerprint(files, outputPrefix, chunkSize)
        val checkpointFile = File(ctx.filesDir, "vg_merge_" + fingerprint + ".json")
        var checkpoint = readMergeCheckpoint(checkpointFile)
        if (checkpoint == null || checkpoint.optString("fingerprint") != fingerprint) {
            checkpoint = JSONObject()
                .put("fingerprint", fingerprint)
                .put("stamp", stampCandidate)
                .put("completedChunks", JSONArray())
                .put("skipped", JSONArray())
                .put("formatSplits", 0)
            writeMergeCheckpoint(checkpointFile, checkpoint)
        }
        val activeCheckpoint = checkpoint ?: throw IOException("Không khởi tạo được checkpoint ghép video.")
        val stamp = activeCheckpoint.optString("stamp").ifBlank { stampCandidate }
        val workName = ".vg_merge_work_" + fingerprint.take(16)
        val workDir = ensureWorkDirectory(ctx, outDir, workName)
        val workId = DocumentsContract.getDocumentId(workDir)
        val totalChunks = (files.size + chunkSize - 1) / chunkSize
        val completed = activeCheckpoint.optJSONArray("completedChunks") ?: JSONArray()
        val completedSet = (0 until completed.length()).map { completed.optInt(it, -1) }.toMutableSet()
        val skippedArray = activeCheckpoint.optJSONArray("skipped") ?: JSONArray()
        val skipped = (0 until skippedArray.length()).mapNotNull { skippedArray.optString(it).takeIf(String::isNotBlank) }.toMutableList()
        var formatSplits = activeCheckpoint.optInt("formatSplits", 0)

        for (chunkIndex in 0 until totalChunks) {
            currentCoroutineContext().ensureActive()
            if (chunkIndex in completedSet) {
                val expectedPrefix = "chunk_" + chunkIndex.toString().padStart(4, '0') + "_"
                val existing = listWorkVideos(ctx, outDir, workId).filter { it.name.startsWith(expectedPrefix) }
                if (existing.isNotEmpty()) {
                    val start = chunkIndex * chunkSize
                    val end = minOf(start + chunkSize, files.size)
                    onProgress(end, files.size, "Đã khôi phục nhóm " + (chunkIndex + 1) + "/" + totalChunks)
                    continue
                }
                completedSet.remove(chunkIndex)
            }

            val start = chunkIndex * chunkSize
            val end = minOf(start + chunkSize, files.size)
            val prefix = "chunk_" + chunkIndex.toString().padStart(4, '0')
            deleteWorkFilesWithPrefix(ctx, outDir, workId, prefix + "_")
            onProgress(start, files.size, "Đang ghép nhóm " + (chunkIndex + 1) + "/" + totalChunks)

            val chunkResult = concat(
                ctx,
                files.subList(start, end),
                workDir,
                0,
                stamp,
                outputPrefix = prefix
            ) { done, _, name ->
                onProgress(start + done, files.size, if (name.isBlank()) "Đang hoàn tất nhóm " + (chunkIndex + 1) else name)
            }
            if (chunkResult.clips <= 0) throw IOException("Nhóm " + (chunkIndex + 1) + " không tạo được video.")
            skipped.addAll(chunkResult.skipped)
            formatSplits += chunkResult.formatSplits
            completedSet.add(chunkIndex)
            activeCheckpoint.put("completedChunks", JSONArray().apply {
                completedSet.sorted().forEach { put(it) }
            })
            activeCheckpoint.put("skipped", JSONArray().apply { skipped.distinct().forEach { put(it) } })
            activeCheckpoint.put("formatSplits", formatSplits)
            writeMergeCheckpoint(checkpointFile, activeCheckpoint)
            onProgress(end, files.size, "Đã lưu tiến độ " + (chunkIndex + 1) + "/" + totalChunks + " nhóm")
        }

        val chunkVideos = listWorkVideos(ctx, outDir, workId)
            .filter { it.name.startsWith("chunk_") }
            .sortedBy { it.name }
        if (chunkVideos.isEmpty()) throw IOException("Không tìm thấy các phần ghép trung gian để hoàn tất.")

        // Normalize each short intermediate group separately. A single 116-minute
        // Transformer composition can exhaust device resources; per-group exports
        // are checkpointed so a process restart resumes at the next group.
        val targetFormat = probe(ctx, chunkVideos.first().uri)?.video
            ?: throw IOException("Không đọc được định dạng nhóm video đầu tiên.")
        val targetWidth = targetFormat.getInteger(MediaFormat.KEY_WIDTH).coerceAtLeast(2)
        val targetHeight = targetFormat.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(2)
        val normalizedPrefix = "normalized_"
        val normalizedDone = activeCheckpoint.optJSONArray("normalizedChunks") ?: JSONArray()
        val normalizedSet = (0 until normalizedDone.length())
            .map { normalizedDone.optInt(it, -1) }.toMutableSet()
        for (index in chunkVideos.indices) {
            currentCoroutineContext().ensureActive()
            val normalizedName = normalizedPrefix + index.toString().padStart(4, '0') + ".mp4"
            val existing = listWorkVideos(ctx, outDir, workId).firstOrNull { it.name == normalizedName }
            if (index in normalizedSet && existing != null && existing.size > 0L) {
                onProgress(files.size, files.size, "Đã khôi phục nhóm chuẩn hóa ${index + 1}/${chunkVideos.size}")
                continue
            }
            deleteWorkFilesWithPrefix(ctx, outDir, workId, normalizedName)
            onProgress(files.size, files.size, "Đang chuẩn hóa nhóm ${index + 1}/${chunkVideos.size}…")
            normalizeToSingleMp4(
                ctx, listOf(chunkVideos[index]), workDir, normalizedName, targetWidth, targetHeight
            )
            normalizedSet.add(index)
            activeCheckpoint.put("normalizedChunks", JSONArray().apply {
                normalizedSet.sorted().forEach { put(it) }
            })
            writeMergeCheckpoint(checkpointFile, activeCheckpoint)
        }

        val normalizedVideos = listWorkVideos(ctx, outDir, workId)
            .filter { it.name.startsWith(normalizedPrefix) }
            .sortedBy { it.name }
        if (normalizedVideos.size != chunkVideos.size) {
            throw IOException("Thiếu nhóm đã chuẩn hóa: ${normalizedVideos.size}/${chunkVideos.size}. Có thể bấm ghép lại để tiếp tục.")
        }

        // Do not send every normalized group through one enormous Transformer
        // composition. Some Android devices stop producing encoder samples during
        // long compositions (Media3 error 7002). Re-encode small batches, checkpoint
        // each completed batch, then stream-copy the uniformly encoded batch files.
        val finalName = outputPrefix + "_" + stamp + ".mp4"
        deleteOutputFilesWithPrefix(ctx, outDir, outputPrefix + "_" + stamp + "_P")
        deleteOutputFilesWithPrefix(ctx, outDir, outputPrefix + "_" + stamp + ".mp4")

        val finalBatchSize = 3
        val finalBatchCount = (normalizedVideos.size + finalBatchSize - 1) / finalBatchSize
        // Rebuild finalpass files when the rotation pipeline changes. Version 2
        // additionally strips the inherited MP4 orientation hint after encoding.
        if (activeCheckpoint.optInt("finalPassRotationFixVersion", 0) < 2) {
            activeCheckpoint.put("finalPassChunks", JSONArray())
            activeCheckpoint.put("finalPassRotationFixVersion", 2)
            writeMergeCheckpoint(checkpointFile, activeCheckpoint)
        }
        val finalDone = activeCheckpoint.optJSONArray("finalPassChunks") ?: JSONArray()
        val finalSet = (0 until finalDone.length()).map { finalDone.optInt(it, -1) }.toMutableSet()

        for (batchIndex in 0 until finalBatchCount) {
            currentCoroutineContext().ensureActive()
            val batchName = "finalpass_" + batchIndex.toString().padStart(4, '0') + ".mp4"
            val existing = listWorkVideos(ctx, outDir, workId).firstOrNull { it.name == batchName }
            if (batchIndex in finalSet && existing != null && existing.size > 0L) {
                onProgress(files.size, files.size, "Đã hoàn tất chuẩn hóa lượt cuối ${batchIndex + 1}/${finalBatchCount}")
                continue
            }
            deleteWorkFilesWithPrefix(ctx, outDir, workId, batchName)
            val start = batchIndex * finalBatchSize
            val end = minOf(start + finalBatchSize, normalizedVideos.size)
            onProgress(files.size, files.size, "Đang đồng bộ nhóm cuối ${batchIndex + 1}/${finalBatchCount}…")
            normalizeToSingleMp4(
                ctx, normalizedVideos.subList(start, end), workDir, batchName,
                targetWidth, targetHeight, normalizeInputRotation = true
            )
            finalSet.add(batchIndex)
            activeCheckpoint.put("finalPassChunks", JSONArray().apply {
                finalSet.sorted().forEach { put(it) }
            })
            writeMergeCheckpoint(checkpointFile, activeCheckpoint)
        }

        val finalBatchVideos = listWorkVideos(ctx, outDir, workId)
            .filter { it.name.startsWith("finalpass_") }
            .sortedBy { it.name }
        if (finalBatchVideos.size != finalBatchCount ||
            finalBatchVideos.any { it.size <= 0L }) {
            throw IOException("Thiếu nhóm chuẩn hóa lượt cuối: ${finalBatchVideos.size}/${finalBatchCount}. Có thể bấm ghép lại để tiếp tục.")
        }

        onProgress(files.size, files.size, "Đang nối các nhóm cuối thành một file sản phẩm…")
        val finalResult = concat(
            ctx, finalBatchVideos, outDir, 0, stamp, outputPrefix = outputPrefix
        ) { _, _, name ->
            onProgress(files.size, files.size, if (name.isBlank()) "Đang hoàn tất file sản phẩm…" else "Đang nối nhóm cuối: $name")
        }

        if (finalResult.parts != 1 || finalResult.clips != finalBatchVideos.size) {
            deleteOutputFilesWithPrefix(ctx, outDir, outputPrefix + "_" + stamp + "_P")
            throw IOException(
                "Các nhóm mã hóa lượt cuối vẫn chưa tương thích để nối trực tiếp (parts=${finalResult.parts}, groups=${finalResult.clips}/${finalBatchVideos.size}). Chi tiết: ${finalResult.mismatchDetails.joinToString("; ")}. Dữ liệu trung gian được giữ để chẩn đoán."
            )
        }

        val partName = outputPrefix + "_" + stamp + "_P01.mp4"
        val finalPart = VideoLister.listInFolder(ctx, outDir, DocumentsContract.getDocumentId(outDir), includeMasked = true)
            .firstOrNull { it.name == partName }
            ?: throw IOException("Không tìm thấy file ghép cuối sau khi hoàn tất.")
        DocumentsContract.renameDocument(ctx.contentResolver, finalPart.uri, finalName)
            ?: throw IOException("Không đổi được tên file sản phẩm cuối.")

        // Only remove resumable state after the single final output is safely written.
        deleteWorkDirectory(ctx, workDir, workId)
        checkpointFile.delete()
        File(checkpointFile.parentFile, checkpointFile.name + ".tmp").delete()
        onProgress(files.size, files.size, "")
        return Result(1, files.size - skipped.distinct().size, 0, skipped.distinct())
    }

    private fun mergeFingerprint(files: List<VideoFile>, outputPrefix: String, chunkSize: Int): String {
        val raw = buildString {
            // Invalidate pre-1.0.72 work groups: their muxed files may already contain
            // mixed rotation metadata from clips that were incorrectly grouped together.
            append("merge-v2-rotation|").append(outputPrefix).append('|').append(chunkSize).append('\n')
            files.forEach { append(it.name).append('|').append(it.size).append('|').append(it.modified).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun readMergeCheckpoint(file: File): JSONObject? {
        for (candidate in listOf(File(file.parentFile, file.name + ".tmp"), file)) {
            try {
                if (candidate.exists()) return JSONObject(candidate.readText(Charsets.UTF_8))
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun writeMergeCheckpoint(file: File, json: JSONObject) {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (file.exists() && !file.delete()) throw IOException("Không thay được checkpoint ghép video.")
        if (!temp.renameTo(file)) throw IOException("Không lưu được checkpoint ghép video.")
    }

    private fun ensureWorkDirectory(ctx: Context, parent: Uri, name: String): Uri {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        ctx.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name &&
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(
            ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IOException("Không tạo được thư mục tạm để ghép video.")
    }

    private fun listWorkVideos(ctx: Context, tree: Uri, folderId: String): List<VideoFile> =
        VideoLister.listInFolder(ctx, tree, folderId, includeMasked = true)

    private fun deleteWorkFilesWithPrefix(ctx: Context, tree: Uri, folderId: String, prefix: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val targets = mutableListOf<Uri>()
        ctx.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (name.startsWith(prefix) && cursor.getString(2)?.startsWith("video/") == true) {
                    targets += DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        }
        targets.forEach { runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, it) } }
    }

    private fun deleteOutputFilesWithPrefix(ctx: Context, folder: Uri, prefix: String) {
        val folderId = DocumentsContract.getDocumentId(folder)
        deleteWorkFilesWithPrefix(ctx, folder, folderId, prefix)
    }

    private fun deleteWorkDirectory(ctx: Context, tree: Uri, folderId: String) {
        deleteWorkFilesWithPrefix(ctx, tree, folderId, "")
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, folder) }
    }

}
