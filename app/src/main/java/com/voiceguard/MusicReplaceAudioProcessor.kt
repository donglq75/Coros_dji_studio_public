package com.voiceguard

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Giữ nguyên audio gốc ngoài các khoảng voice; thay đúng các khoảng đã phát hiện bằng nhạc, không fade. */
class MusicReplaceAudioProcessor(
    private val music: MusicSource,
    private val segments: List<VoiceSegment>
) : BaseAudioProcessor() {
    private var pcm: ShortArray = ShortArray(0)
    private var musicPos = 0
    private var framePosition = 0L
    private var segmentIndex = 0
    private var sampleRate = 0
    private var channels = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        pcm = music.pcmFor(sampleRate, channels)
        musicPos = 0
        framePosition = 0L
        segmentIndex = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val count = inputBuffer.remaining() / 2
        if (count == 0 || channels <= 0) return
        val out = replaceOutputBuffer(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        val frames = count / channels
        for (frame in 0 until frames) {
            val time = framePosition.toDouble() / sampleRate
            while (segmentIndex < segments.size && time >= segments[segmentIndex].endSec) segmentIndex++
            val masked = segmentIndex < segments.size &&
                time >= segments[segmentIndex].startSec && time < segments[segmentIndex].endSec
            repeat(channels) {
                val original = inputBuffer.getShort()
                if (masked && pcm.isNotEmpty()) {
                    out.putShort(pcm[musicPos])
                    musicPos = (musicPos + 1) % pcm.size
                } else {
                    out.putShort(original)
                }
            }
            framePosition++
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }
}
