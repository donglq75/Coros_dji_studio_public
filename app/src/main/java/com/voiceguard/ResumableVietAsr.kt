package com.voiceguard

import android.content.Context
import android.net.Uri
import io.vietasr.Pipeline
import io.vietasr.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

private const val RESUME_CHUNK_SEC = 60
private const val RESUME_OVERLAP_SEC = 2
private const val RESUME_RATE = 16000
private const val RESUME_CHUNK_SAMPLES = RESUME_CHUNK_SEC * RESUME_RATE
private const val RESUME_OVERLAP_SAMPLES = RESUME_OVERLAP_SEC * RESUME_RATE
private const val RESUME_CHECKPOINT = "vietasr_tuned_resume_checkpoint.json"
private const val RESUME_MODEL_DIR = "viet-asr-int8-46adacf"
private const val TUNED_CONFIG_ID = "beam8-vad045-silence900-pad1200-overlap2-v1"
private data class ResumeWavInfo(val dataStart: Long, val sampleCount: Long)

suspend fun runResumableVietAsr(context: Context, sourceUri: Uri, onProgress: (Float, String) -> Unit): JSONObject =
    withContext(Dispatchers.IO) {
        val wav = File(context.cacheDir, "vg_asr_tuned_input.wav")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(wav).use { output -> input.copyTo(output) }
        } ?: error("Không đọc được file WAV")
        val info = inspectResumeWav(wav)
        val fingerprint = resumeSha256(wav) + ":" + TUNED_CONFIG_ID
        val total = ((info.sampleCount + RESUME_CHUNK_SAMPLES - 1) / RESUME_CHUNK_SAMPLES).toInt()
        val checkpointFile = File(context.filesDir, RESUME_CHECKPOINT)
        var checkpoint = readResumeCheckpoint(checkpointFile)
        if (checkpoint == null || checkpoint.optString("fingerprint") != fingerprint) {
            checkpoint = JSONObject().put("version", 2).put("fingerprint", fingerprint)
                .put("chunkSeconds", RESUME_CHUNK_SEC).put("overlapSeconds", RESUME_OVERLAP_SEC)
                .put("sampleCount", info.sampleCount).put("configId", TUNED_CONFIG_ID)
                .put("completedChunks", JSONArray())
            writeResumeCheckpoint(checkpointFile, checkpoint)
        }
        val activeCheckpoint = checkpoint ?: error("Không khởi tạo được checkpoint VietASR")
        val saved = activeCheckpoint.optJSONArray("completedChunks") ?: JSONArray()
        val done = mutableSetOf<Int>()
        for (i in 0 until saved.length()) done.add(saved.optJSONObject(i)?.optInt("index", -1) ?: -1)
        onProgress(done.size.toFloat() / total.coerceAtLeast(1), "Đã khôi phục " + done.size + "/" + total + " đoạn.")
        val modelDir = File(context.filesDir, RESUME_MODEL_DIR)
        require(modelDir.exists()) { "Chưa tìm thấy mô hình VietASR trên máy." }

        // Controlled tuning preset: beam 8, slightly more sensitive VAD, shorter
        // adaptive pauses, and a wider speech pad. Original Studio preset is unchanged.
        val config = JSONObject()
            .put("decoding_method", "modified_beam_search")
            .put("max_active_paths", 8)
            .put("num_threads", 3)
            .put("vad_backend", "silero")
            .put("vad_threshold", 0.45)
            .put("min_silence_ms", 900)
            .put("adaptive_silence", true)
            .put("adaptive_short_ms", 4000)
            .put("adaptive_mid_silence_ms", 500)
            .put("adaptive_long_ms", 10000)
            .put("adaptive_long_silence_ms", 300)
            .put("speech_pad_ms", 1200)
            .put("max_utterance_ms", 30000)
            .put("emit_segments", true)
            .put("emit_words", true)
            .put("emit_voiced_regions", true)
            .put("model_dir", modelDir.absolutePath).toString()

        for (index in 0 until total) {
            if (index in done) continue
            val coreStart = index.toLong() * RESUME_CHUNK_SAMPLES
            val coreEnd = minOf(info.sampleCount, coreStart + RESUME_CHUNK_SAMPLES)
            val readStart = if (index == 0) 0L else (coreStart - RESUME_OVERLAP_SAMPLES).coerceAtLeast(0L)
            val readEnd = if (index == total - 1) info.sampleCount
                else (coreEnd + RESUME_OVERLAP_SAMPLES).coerceAtMost(info.sampleCount)
            val countSamples = (readEnd - readStart).toInt()
            var pipeline: Pipeline? = null
            var session: Session? = null
            try {
                val coreStartSec = coreStart.toDouble() / RESUME_RATE
                val coreEndSec = coreEnd.toDouble() / RESUME_RATE
                val readStartSec = readStart.toDouble() / RESUME_RATE
                val readEndSec = readEnd.toDouble() / RESUME_RATE
                onProgress(done.size.toFloat() / total.coerceAtLeast(1),
                    "Đang nhận dạng đoạn " + (index + 1) + "/" + total +
                        " · lõi " + coreStartSec.toInt() + "–" + coreEndSec.toInt() +
                        "s (đọc thêm " + RESUME_OVERLAP_SEC + "s ngữ cảnh)")
                pipeline = Pipeline.newPipeline().add("vietasr", config).build()
                session = pipeline.stream(16000f)
                RandomAccessFile(wav, "r").use { raf ->
                    raf.seek(info.dataStart + readStart * 2L)
                    var consumed = 0
                    while (consumed < countSamples) {
                        val n = minOf(RESUME_RATE * 2, countSamples - consumed)
                        session!!.accept(ShortArray(n) { resumeReadShort(raf) })
                        consumed += n
                    }
                }
                val result = session!!.finalResult()
                val native = JSONObject(result.toJson())
                shiftResumeTimes(native, readStartSec)
                val cropped = cropTunedChunkToCore(native, coreStartSec, coreEndSec)
                val item = JSONObject().put("index", index)
                    .put("coreStartSec", coreStartSec).put("coreEndSec", coreEndSec)
                    .put("readStartSec", readStartSec).put("readEndSec", readEndSec)
                    .put("text", cropped.optString("text", result.text.trim()))
                    .put("result", cropped)
                val current = activeCheckpoint.optJSONArray("completedChunks") ?: JSONArray()
                val merged = (0 until current.length()).mapNotNull { current.optJSONObject(it) }
                    .filter { it.optInt("index") != index }.toMutableList()
                merged.add(item)
                val ordered = JSONArray()
                merged.sortedBy { it.optInt("index") }.forEach { ordered.put(it) }
                activeCheckpoint.put("completedChunks", ordered).put("updatedAt", System.currentTimeMillis())
                writeResumeCheckpoint(checkpointFile, activeCheckpoint)
                done.add(index)
                onProgress(done.size.toFloat() / total.coerceAtLeast(1),
                    "Đã lưu " + done.size + "/" + total + " đoạn · " + coreEndSec.toInt() +
                        "/" + (info.sampleCount / RESUME_RATE) + "s")
            } finally {
                try { session?.close() } catch (_: Throwable) {}
                try { pipeline?.close() } catch (_: Throwable) {}
            }
        }
        val chunks = activeCheckpoint.optJSONArray("completedChunks") ?: JSONArray()
        val ordered = JSONArray()
        val texts = mutableListOf<String>()
        val mergedSegments = JSONArray()
        val mergedVoiced = JSONArray()
        (0 until chunks.length()).mapNotNull { chunks.optJSONObject(it) }
            .sortedBy { it.optInt("index") }.forEach { item ->
                ordered.put(item)
                val t = item.optString("text", "").trim()
                if (t.isNotBlank()) texts.add(t)
                val native = item.optJSONObject("result") ?: JSONObject()
                val segs = native.optJSONArray("segments") ?: JSONArray()
                for (i in 0 until segs.length()) segs.optJSONObject(i)?.let { mergedSegments.put(it) }
                val voiced = native.optJSONArray("voiced_regions") ?: JSONArray()
                for (i in 0 until voiced.length()) voiced.optJSONObject(i)?.let { mergedVoiced.put(it) }
            }
        onProgress(1f, "Đã nhận dạng và lưu đủ " + total + " đoạn.")
        JSONObject()
            .put("engine", "VietASR Android SDK 0.1.0 · tuned beam 8 · 60s chunks with 2s overlap")
            .put("configId", TUNED_CONFIG_ID)
            .put("config", JSONObject(config))
            .put("modelRevision", "46adacf22d379e3d6b860d5d08e9fb02cbc122ea")
            .put("runtime", "VietASR native C++ core + ONNX Runtime")
            .put("language", "vi").put("audioFormat", "PCM 16-bit mono 16000 Hz")
            .put("audioDurationSec", info.sampleCount.toDouble() / RESUME_RATE)
            .put("chunkSeconds", RESUME_CHUNK_SEC).put("overlapSeconds", RESUME_OVERLAP_SEC)
            .put("completedChunks", ordered).put("segments", mergedSegments)
            .put("voiced_regions", mergedVoiced).put("text", texts.joinToString(" "))
    }

private fun cropTunedChunkToCore(root: JSONObject, coreStart: Double, coreEnd: Double): JSONObject {
    val sourceSegments = root.optJSONArray("segments") ?: JSONArray()
    val keptSegments = JSONArray()
    val texts = mutableListOf<String>()
    for (i in 0 until sourceSegments.length()) {
        val segment = sourceSegments.optJSONObject(i) ?: continue
        val words = segment.optJSONArray("words")
        if (words != null) {
            val keptWords = JSONArray()
            val wordTexts = mutableListOf<String>()
            var first = Double.POSITIVE_INFINITY
            var last = 0.0
            for (j in 0 until words.length()) {
                val word = words.optJSONObject(j) ?: continue
                val start = word.optDouble("start", Double.NaN)
                if (!start.isNaN() && start >= coreStart && start < coreEnd) {
                    keptWords.put(word)
                    val token = word.optString("word", word.optString("text", "")).trim()
                    if (token.isNotBlank()) wordTexts.add(token)
                    first = minOf(first, start)
                    last = maxOf(last, word.optDouble("end", start))
                }
            }
            if (keptWords.length() > 0) {
                val copy = JSONObject(segment.toString())
                    .put("words", keptWords)
                    .put("text", wordTexts.joinToString(" "))
                    .put("start", first).put("end", last)
                keptSegments.put(copy)
                texts.add(copy.optString("text"))
            }
        } else {
            val start = segment.optDouble("start", Double.NaN)
            if (!start.isNaN() && start >= coreStart && start < coreEnd) {
                keptSegments.put(segment)
                val text = segment.optString("text", "").trim()
                if (text.isNotBlank()) texts.add(text)
            }
        }
    }
    root.put("segments", keptSegments).put("text", texts.joinToString(" "))
    val sourceVoiced = root.optJSONArray("voiced_regions")
    if (sourceVoiced != null) {
        val keptVoiced = JSONArray()
        for (i in 0 until sourceVoiced.length()) {
            val region = sourceVoiced.optJSONObject(i) ?: continue
            val start = region.optDouble("start", Double.NaN)
            if (!start.isNaN() && start >= coreStart && start < coreEnd) keptVoiced.put(region)
        }
        root.put("voiced_regions", keptVoiced)
    }
    return root
}

fun hasAsrCheckpoint(context: Context): Boolean = File(context.filesDir, RESUME_CHECKPOINT).exists()
fun clearAsrCheckpoint(context: Context) { File(context.filesDir, RESUME_CHECKPOINT).delete() }

fun checkpointStatus(context: Context, wav: File): String? {
    val cp = readResumeCheckpoint(File(context.filesDir, RESUME_CHECKPOINT)) ?: return null
    val done = cp.optJSONArray("completedChunks")?.length() ?: 0
    val total = ((cp.optLong("sampleCount") + RESUME_CHUNK_SAMPLES - 1) / RESUME_CHUNK_SAMPLES).toInt()
    return if (total > 0) "Có checkpoint nhận dạng: " + done + "/" + total +
        " đoạn. Khi tiếp tục, VG sẽ tự xác minh WAV và chỉ xử lý phần còn thiếu." else null
}

private fun readResumeCheckpoint(file: File): JSONObject? {
    for (f in listOf(File(file.parentFile, file.name + ".tmp"), file)) {
        try { if (f.exists()) return JSONObject(f.readText(Charsets.UTF_8)) } catch (_: Throwable) {}
    }
    return null
}
private fun writeResumeCheckpoint(file: File, json: JSONObject) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(tmp).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
    if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
}
private fun shiftResumeTimes(v: Any?, offset: Double) {
    when (v) {
        is JSONObject -> v.keys().asSequence().toList().forEach { key ->
            val child = v.opt(key)
            if ((key == "start" || key == "end") && child is Number) v.put(key, child.toDouble() + offset)
            else shiftResumeTimes(child, offset)
        }
        is JSONArray -> for (i in 0 until v.length()) shiftResumeTimes(v.opt(i), offset)
    }
}
private fun inspectResumeWav(file: File): ResumeWavInfo {
    RandomAccessFile(file, "r").use { r ->
        val riff = ByteArray(4); r.readFully(riff); require(String(riff) == "RIFF") { "WAV không hợp lệ" }
        r.skipBytes(4); val wave = ByteArray(4); r.readFully(wave); require(String(wave) == "WAVE")
        var fmt = 0; var ch = 0; var rate = 0; var bits = 0; var data = -1L; var size = 0L
        while (r.filePointer + 8 <= r.length()) {
            val id = ByteArray(4); r.readFully(id); val n = Integer.toUnsignedLong(resumeReadInt(r)); val end = r.filePointer + n
            when (String(id)) {
                "fmt " -> { fmt = resumeReadUShort(r); ch = resumeReadUShort(r); rate = resumeReadInt(r); r.skipBytes(6); bits = resumeReadUShort(r) }
                "data" -> { data = r.filePointer; size = n }
            }
            if (data >= 0 && fmt != 0) break
            r.seek(end + (n and 1L))
        }
        require(fmt == 1 && ch == 1 && rate == RESUME_RATE && bits == 16) { "WAV cần PCM 16-bit mono 16 kHz" }
        require(data >= 0 && size > 0 && size % 2L == 0L) { "Không tìm thấy dữ liệu PCM" }
        return ResumeWavInfo(data, size / 2L)
    }
}
private fun resumeSha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input -> val b = ByteArray(262144); while (true) { val n=input.read(b); if(n<0) break; md.update(b,0,n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}
private fun resumeReadInt(r: RandomAccessFile): Int {
    val a=r.read(); val b=r.read(); val c=r.read(); val d=r.read()
    return (a and 255) or ((b and 255) shl 8) or ((c and 255) shl 16) or ((d and 255) shl 24)
}
private fun resumeReadUShort(r: RandomAccessFile): Int = (r.read() and 255) or ((r.read() and 255) shl 8)
private fun resumeReadShort(r: RandomAccessFile): Short = resumeReadUShort(r).toShort()
