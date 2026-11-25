package com.voiceguard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Một lát voice trong timeline và vị trí tương ứng trong video nguồn. */
data class VoiceTimelineSpan(
    val clipUri: String,
    val clipName: String,
    val clipStartSec: Float,
    val timelineStartSec: Float,
    val timelineEndSec: Float
)

data class VoiceTimeline(
    val file: File,
    val spans: List<VoiceTimelineSpan>,
    val durationSec: Float,
    val voiceClipCount: Int,
    val voiceSec: Float,
    val waveform: FloatArray
)

/**
 * Tạo WAV mono 16 kHz, giữ toàn bộ âm thanh và chỉ cắt phần im lặng dài.
 * Checkpoint được lưu sau từng video để có thể tiếp tục nếu Android dừng tiến trình.
 */
object VoiceTimelineBuilder {
    private const val RATE = 16000
    private const val GAP_SAMPLES = 8000 // 0.5 seconds between source videos
    private const val CHECKPOINT_VERSION = 4

    fun clearCheckpoint(context: Context) {
        File(context.filesDir, "voice_review_timeline.checkpoint").delete()
        File(context.filesDir, "voice_review_timeline.checkpoint.tmp").delete()
        File(context.filesDir, "voice_review_timeline.wav").delete()
    }

    fun hasCheckpoint(context: Context, clips: List<Clip>, minimumVoiceSeconds: Float, silenceThresholdSec: Float = 10f): Boolean {
        if (clips.isEmpty()) return false
        val checkpoint = readCheckpoint(File(context.filesDir, "voice_review_timeline.checkpoint")) ?: return false
        val wav = File(context.filesDir, "voice_review_timeline.wav")
        val cursor = checkpoint.optLong("cursorSamples", -1L)
        return checkpoint.optString("fingerprint") == fingerprint(clips, minimumVoiceSeconds, silenceThresholdSec) &&
            checkpoint.optInt("nextIndex", 0) in 1..clips.size &&
            cursor >= 0L && wav.exists() && wav.length() >= 44L + cursor * 2L
    }

    fun build(
        context: Context,
        clips: List<Clip>,
        minimumVoiceSeconds: Float,
        silenceThresholdSec: Float = 10f,
        onProgress: (Int, Int, String) -> Unit
    ): VoiceTimeline {
        if (clips.isEmpty()) throw IllegalStateException("Chưa có video voice để tạo timeline")
        val output = File(context.filesDir, "voice_review_timeline.wav")
        val checkpointFile = File(context.filesDir, "voice_review_timeline.checkpoint")
        val fingerprint = fingerprint(clips, minimumVoiceSeconds, silenceThresholdSec)

        var nextIndex = 0
        var cursorSamples = 0L
        var voiceSamples = 0L
        val spans = mutableListOf<VoiceTimelineSpan>()

        val checkpoint = readCheckpoint(checkpointFile)
        if (checkpoint != null && checkpoint.optString("fingerprint") == fingerprint && output.exists()) {
            nextIndex = checkpoint.optInt("nextIndex", 0).coerceIn(0, clips.size)
            cursorSamples = checkpoint.optLong("cursorSamples", 0L).coerceAtLeast(0L)
            voiceSamples = checkpoint.optLong("voiceSamples", 0L).coerceAtLeast(0L)
            val savedSpans = checkpoint.optJSONArray("spans") ?: JSONArray()
            for (i in 0 until savedSpans.length()) {
                val s = savedSpans.optJSONObject(i) ?: continue
                val savedName = s.optString("name")
                val currentClip = clips.firstOrNull { it.name == savedName }
                spans += VoiceTimelineSpan(
                    currentClip?.uri ?: s.optString("uri"), savedName,
                    s.optDouble("clipStart", 0.0).toFloat(),
                    s.optDouble("timelineStart", 0.0).toFloat(),
                    s.optDouble("timelineEnd", 0.0).toFloat()
                )
            }
            // Bỏ phần dữ liệu dở dang nếu Android dừng giữa lúc xử lý một video.
            RandomAccessFile(output, "rw").use { it.setLength(44L + cursorSamples * 2L) }
        } else {
            checkpointFile.delete()
            output.delete()
            RandomAccessFile(output, "rw").use { it.setLength(44L) }
        }

        // Publish the restored checkpoint immediately. When all clips are already
        // present, the remaining work is only rebuilding the in-memory waveform.
        onProgress(nextIndex, clips.size,
            if (nextIndex >= clips.size) "Đang dựng waveform…" else "Đang tiếp tục từ video " + (nextIndex + 1) + "…")

        try {
            RandomAccessFile(output, "rw").use { wav ->
                wav.seek(44L + cursorSamples * 2L)
                for (index in nextIndex until clips.size) {
                    val clip = clips[index]
                    onProgress(index, clips.size, clip.name)
                    val audio = AudioDecoder.decodeTo16kMono(context, Uri.parse(clip.uri))
                    if (audio != null && audio.isNotEmpty()) {
                        val keptRanges = retainedAudioRanges(audio, silenceThresholdSec)
                        keptRanges.forEachIndexed { rangeIndex, range ->
                            val from = range.first.coerceIn(0, audio.size)
                            val to = range.second.coerceIn(from, audio.size)
                            if (to <= from) return@forEachIndexed
                            val timelineStart = cursorSamples.toFloat() / RATE
                            for (i in from until to) {
                                val sample = (audio[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                                wav.write(sample and 0xff)
                                wav.write((sample shr 8) and 0xff)
                            }
                            val count = (to - from).toLong()
                            cursorSamples += count
                            voiceSamples += count
                            spans += VoiceTimelineSpan(
                                clip.uri, clip.name, from.toFloat() / RATE,
                                timelineStart, cursorSamples.toFloat() / RATE
                            )
                            if (rangeIndex == keptRanges.lastIndex) {
                                repeat(GAP_SAMPLES) { wav.write(0); wav.write(0) }
                                cursorSamples += GAP_SAMPLES
                            }
                        }
                    }
                    // Đồng bộ WAV trước khi ghi mốc hoàn tất video.
                    wav.fd.sync()
                    saveCheckpoint(
                        checkpointFile, fingerprint, index + 1, cursorSamples, voiceSamples, spans
                    )
                    onProgress(index + 1, clips.size, clip.name)
                }
            }
        } finally {
            // No long-lived native detector is required for silence-only trimming.
        }

        val dataBytes = cursorSamples * 2L
        if (dataBytes <= 0L) throw IllegalStateException("Không tạo được voice timeline; hãy quét lại video")
        RandomAccessFile(output, "rw").use { wav ->
            wav.seek(0L)
            wav.writeBytes("RIFF")
            writeIntLe(wav, (36L + dataBytes).toInt())
            wav.writeBytes("WAVE")
            wav.writeBytes("fmt ")
            writeIntLe(wav, 16)
            writeShortLe(wav, 1)
            writeShortLe(wav, 1)
            writeIntLe(wav, RATE)
            writeIntLe(wav, RATE * 2)
            writeShortLe(wav, 2)
            writeShortLe(wav, 16)
            wav.writeBytes("data")
            writeIntLe(wav, dataBytes.toInt())
        }

        val waveform = FloatArray(240)
        RandomAccessFile(output, "r").use { reader ->
            val dataSamples = cursorSamples.coerceAtLeast(1L)
            // The waveform is only a visual overview: sample evenly within each
            // bin instead of decoding every PCM sample after process restoration.
            val samplesPerBin = 128
            for (bin in waveform.indices) {
                val from = dataSamples * bin / waveform.size
                val to = (dataSamples * (bin + 1) / waveform.size)
                    .coerceAtLeast(from + 1).coerceAtMost(dataSamples)
                val count = (to - from).coerceAtLeast(1L)
                val stride = (count / samplesPerBin).coerceAtLeast(1L)
                var peak = 0f
                var sampleIndex = from
                while (sampleIndex < to) {
                    reader.seek(44L + sampleIndex * 2L)
                    val lo = reader.read()
                    val hi = reader.read()
                    if (lo < 0 || hi < 0) break
                    val sample = ((hi shl 8) or lo).toShort()
                    peak = maxOf(peak, kotlin.math.abs(sample.toInt() / 32768f))
                    sampleIndex += stride
                }
                waveform[bin] = peak
            }
        }
        // Keep the completed checkpoint so a newly opened VG screen can rebuild
        // the in-memory timeline and clip timestamp mapping without decoding every video again.
        onProgress(clips.size, clips.size, "")
        return VoiceTimeline(
            output, spans, cursorSamples.toFloat() / RATE,
            spans.map { it.clipUri }.distinct().size, voiceSamples.toFloat() / RATE, waveform
        )
    }


    /**
     * Keep the complete audio stream and remove only the middle of quiet runs
     * longer than the selected threshold. A 250 ms handle is retained at each
     * edge so consonants, breaths and room tails are not cut abruptly.
     */
    private fun retainedAudioRanges(audio: FloatArray, thresholdSec: Float): List<Pair<Int, Int>> {
        if (audio.isEmpty()) return emptyList()
        val frameSamples = 320 // 20 ms at 16 kHz
        val quietLimit = 0.008 // approximately -42 dBFS
        val maxQuietFrames = (thresholdSec.coerceAtLeast(1f) * RATE / frameSamples).toInt()
        val padSamples = RATE / 4
        val quietRuns = mutableListOf<Pair<Int, Int>>()
        var quietStart = -1
        val frameCount = (audio.size + frameSamples - 1) / frameSamples
        for (frame in 0 until frameCount) {
            val from = frame * frameSamples
            val to = minOf(from + frameSamples, audio.size)
            var energy = 0.0
            for (i in from until to) {
                val sample = audio[i].toDouble()
                energy += sample * sample
            }
            val rms = kotlin.math.sqrt(energy / (to - from))
            if (rms <= quietLimit) {
                if (quietStart < 0) quietStart = from
            } else if (quietStart >= 0) {
                quietRuns += quietStart to from
                quietStart = -1
            }
        }
        if (quietStart >= 0) quietRuns += quietStart to audio.size

        val cuts = quietRuns.mapNotNull { (start, end) ->
            if (end - start <= maxQuietFrames * frameSamples) null
            else {
                val cutStart = if (start == 0) start else (start + padSamples).coerceAtMost(end)
                val cutEnd = if (end == audio.size) end else (end - padSamples).coerceAtLeast(start)
                if (cutEnd > cutStart) cutStart to cutEnd else null
            }
        }.sortedBy { it.first }

        if (cuts.isEmpty()) return listOf(0 to audio.size)
        val kept = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        cuts.forEach { (cutStart, cutEnd) ->
            if (cutStart > cursor) kept += cursor to cutStart
            cursor = maxOf(cursor, cutEnd)
        }
        if (cursor < audio.size) kept += cursor to audio.size
        return kept.filter { it.second - it.first >= frameSamples }
    }

    private fun fingerprint(clips: List<Clip>, threshold: Float, silenceThresholdSec: Float): String {
        val raw = buildString {
            append("v").append(CHECKPOINT_VERSION).append('|').append(threshold).append('|').append(silenceThresholdSec).append('|')
            // URI SAF có thể thay đổi sau khi Android tạo lại process.
            clips.forEach { append(it.name).append('|').append(it.voiceSec).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun readCheckpoint(file: File): JSONObject? {
        // Khôi phục cả bản tạm nếu process dừng giữa lúc thay checkpoint.
        for (candidate in listOf(File(file.parentFile, file.name + ".tmp"), file)) {
            try {
                if (candidate.exists()) return JSONObject(candidate.readText())
            } catch (_: Exception) { }
        }
        return null
    }

    private fun saveCheckpoint(
        file: File, fingerprint: String, nextIndex: Int, cursorSamples: Long,
        voiceSamples: Long, spans: List<VoiceTimelineSpan>
    ) {
        val array = JSONArray()
        spans.forEach { s ->
            array.put(JSONObject().put("uri", s.clipUri).put("name", s.clipName)
                .put("clipStart", s.clipStartSec).put("timelineStart", s.timelineStartSec)
                .put("timelineEnd", s.timelineEndSec))
        }
        val json = JSONObject().put("fingerprint", fingerprint).put("nextIndex", nextIndex)
            .put("cursorSamples", cursorSamples).put("voiceSamples", voiceSamples).put("spans", array)
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun writeIntLe(out: RandomAccessFile, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    private fun writeShortLe(out: RandomAccessFile, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }
}
