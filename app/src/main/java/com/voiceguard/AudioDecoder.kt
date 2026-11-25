package com.voiceguard

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM 16-bit xen kẽ các kênh. */
class RawPcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

object AudioDecoder {
    private const val TARGET_RATE = 16000

    /** Giải mã audio của video thành mono 16kHz (float -1..1). Trả về null nếu clip không có audio. */
    fun decodeTo16kMono(context: Context, uri: Uri): FloatArray? {
        val raw = decodeRaw(context, uri, 0) ?: return null
        return toMono16k(raw.samples, raw.channels, raw.sampleRate)
    }

    /**
     * Giải mã track audio đầu tiên của file (video hoặc nhạc) thành PCM 16-bit.
     * maxSec > 0: chỉ giải mã tối đa chừng đó giây đầu. Trả về null nếu không có audio.
     */
    fun decodeRaw(context: Context, uri: Uri, maxSec: Int): RawPcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val bos = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    idle = 0
                    val out = dec.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.get(chunk)
                        bos.write(chunk)
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (maxSec > 0 && sampleRate > 0 && channels > 0 &&
                        bos.size().toLong() >= maxSec.toLong() * sampleRate * channels * 2
                    ) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (inputDone) {
                    idle++
                    if (idle > 300) break // tránh treo nếu decoder không trả EOS
                }
            }

            if (sampleRate <= 0 || channels <= 0) return null
            val bytes = bos.toByteArray()
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return RawPcm(shorts, sampleRate, channels)
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            try {
                codec?.release()
            } catch (e: Exception) {
            }
            extractor.release()
        }
    }

    private fun toMono16k(pcm: ShortArray, channels: Int, rate: Int): FloatArray {
        val frames = pcm.size / channels
        if (frames == 0) return FloatArray(0)
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += pcm[f * channels + c]
            mono[f] = sum / channels / 32768f
        }
        if (rate == TARGET_RATE) return mono

        val outLen = (frames.toLong() * TARGET_RATE / rate).toInt()
        val out = FloatArray(outLen)
        val ratio = rate.toDouble() / TARGET_RATE
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = (pos - i0).toFloat()
            out[i] = mono[i0] * (1f - frac) + mono[i1] * frac
        }
        return out
    }
}
