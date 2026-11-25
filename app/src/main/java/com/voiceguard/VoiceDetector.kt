package com.voiceguard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import kotlin.math.sqrt

/** Silero VAD v5: trả về các khoảng voice theo timeline của clip. */
class VoiceDetector(context: Context) {
    companion object {
        const val SPEECH_PROB = 0.4f
        private const val CHUNK = 512
        private const val CONTEXT = 64
        private const val RATE = 16000L
        private const val MERGE_GAP_SEC = 0.18f
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var srIsScalar = true
    var usingFallback = false
        private set

    init {
        try {
            val bytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }
            val s = env.createSession(bytes, OrtSession.SessionOptions())
            if (!s.inputNames.contains("state")) {
                s.close()
                throw IllegalStateException("Model không phải Silero VAD v5")
            }
            val srInfo = s.inputInfo["sr"]?.info as? TensorInfo
            srIsScalar = srInfo?.shape?.isEmpty() ?: true
            session = s
        } catch (e: Throwable) {
            Log.w("VoiceDetector", "Không dùng được Silero: ${e.message}")
            session = null
            usingFallback = true
        }
    }

    fun close() { runCatching { session?.close() } }

    /** Chỉ giữ các đoạn voice riêng lẻ có thời lượng >= minSegmentSec; không cộng dồn cả clip. */
    fun speechSegments(audio: FloatArray, minSegmentSec: Float = 0.5f): List<VoiceSegment> {
        val flags = try {
            val s = session ?: return energySegments(audio, minSegmentSec)
            sileroFlags(s, audio)
        } catch (e: Throwable) {
            Log.w("VoiceDetector", "Silero lỗi, chuyển sang dò năng lượng: ${e.message}")
            session = null
            usingFallback = true
            return energySegments(audio, minSegmentSec)
        }
        return mergeFlags(flags, minSegmentSec)
    }

    fun speechSeconds(audio: FloatArray): Float =
        speechSegments(audio, 0f).sumOf { it.durationSec.toDouble() }.toFloat()

    @Suppress("UNCHECKED_CAST")
    private fun sileroFlags(s: OrtSession, audio: FloatArray): BooleanArray {
        val count = audio.size / CHUNK
        val flags = BooleanArray(count)
        var state = Array(2) { Array(1) { FloatArray(128) } }
        var context = FloatArray(CONTEXT)
        var pos = 0
        var index = 0
        while (pos + CHUNK <= audio.size) {
            val input = FloatArray(CONTEXT + CHUNK)
            System.arraycopy(context, 0, input, 0, CONTEXT)
            System.arraycopy(audio, pos, input, CONTEXT, CHUNK)
            context = audio.copyOfRange(pos + CHUNK - CONTEXT, pos + CHUNK)
            val inT = OnnxTensor.createTensor(env, arrayOf(input))
            val stT = OnnxTensor.createTensor(env, state)
            val srT = if (srIsScalar) OnnxTensor.createTensor(env, RATE) else OnnxTensor.createTensor(env, longArrayOf(RATE))
            try {
                val res = s.run(mapOf("input" to inT, "state" to stT, "sr" to srT))
                try {
                    val prob = (res.get(0).value as Array<FloatArray>)[0][0]
                    state = res.get(1).value as Array<Array<FloatArray>>
                    flags[index++] = prob >= SPEECH_PROB
                } finally { res.close() }
            } finally { inT.close(); stT.close(); srT.close() }
            pos += CHUNK
        }
        return flags
    }

    private fun energySegments(audio: FloatArray, minSegmentSec: Float): List<VoiceSegment> {
        val frame = 512
        val flags = BooleanArray(audio.size / frame)
        for (i in flags.indices) {
            var sum = 0.0
            for (j in 0 until frame) { val v = audio[i * frame + j]; sum += v * v }
            flags[i] = sqrt(sum / frame) > 0.02
        }
        return mergeFlags(flags, minSegmentSec, frame)
    }

    private fun mergeFlags(flags: BooleanArray, minSegmentSec: Float, frameSamples: Int = CHUNK): List<VoiceSegment> {
        if (flags.isEmpty()) return emptyList()
        val frameSec = frameSamples / 16000f
        val maxGap = (MERGE_GAP_SEC / frameSec).toInt()
        val raw = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var lastSpeech = -1
        for (i in flags.indices) {
            if (flags[i]) {
                if (start < 0) start = i
                lastSpeech = i
            } else if (start >= 0 && i - lastSpeech > maxGap) {
                raw += start to (lastSpeech + 1)
                start = -1
                lastSpeech = -1
            }
        }
        if (start >= 0) raw += start to (lastSpeech + 1)
        return raw.map { (a, b) -> VoiceSegment(a * frameSec, b * frameSec) }
            .filter { it.durationSec >= minSegmentSec }
    }
}
