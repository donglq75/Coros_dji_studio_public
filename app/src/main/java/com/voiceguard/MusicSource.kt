package com.voiceguard

import android.content.Context
import android.net.Uri

/** Nhạc đã giải mã sẵn; đổi sang tần số/số kênh khớp với audio của clip khi cần. */
class MusicSource private constructor(private val raw: RawPcm) {
    private val cache = HashMap<Int, ShortArray>()

    @Synchronized
    fun pcmFor(rate: Int, channels: Int): ShortArray {
        val key = rate * 16 + channels
        return cache.getOrPut(key) { convert(raw, rate, channels) }
    }

    companion object {
        private const val GAIN = 0.8f

        /** Chỉ đọc tối đa maxSec giây đầu của bài nhạc (clip chỉ dài 15-30s, nhạc sẽ lặp lại nếu ngắn hơn). */
        fun load(ctx: Context, uri: Uri, maxSec: Int = 90): MusicSource? {
            val raw = AudioDecoder.decodeRaw(ctx, uri, maxSec) ?: return null
            if (raw.samples.size < raw.channels * raw.sampleRate / 2) return null // quá ngắn/rỗng
            return MusicSource(raw)
        }

        private fun convert(raw: RawPcm, rate: Int, channels: Int): ShortArray {
            val srcCh = raw.channels
            val frames = raw.samples.size / srcCh
            val outFrames = (frames.toLong() * rate / raw.sampleRate).toInt()
            val out = ShortArray(outFrames * channels)
            val ratio = raw.sampleRate.toDouble() / rate

            fun sample(frame: Int, ch: Int): Float {
                if (channels == 1 && srcCh > 1) {
                    var sum = 0f
                    for (c in 0 until srcCh) sum += raw.samples[frame * srcCh + c]
                    return sum / srcCh
                }
                val sc = if (srcCh == 1) 0 else minOf(ch, srcCh - 1)
                return raw.samples[frame * srcCh + sc].toFloat()
            }

            for (i in 0 until outFrames) {
                val pos = i * ratio
                val i0 = pos.toInt().coerceAtMost(frames - 1)
                val i1 = (i0 + 1).coerceAtMost(frames - 1)
                val frac = (pos - i0).toFloat()
                for (c in 0 until channels) {
                    val v = (sample(i0, c) * (1f - frac) + sample(i1, c) * frac) * GAIN
                    out[i * channels + c] = v.coerceIn(-32768f, 32767f).toInt().toShort()
                }
            }
            return out
        }
    }
}
