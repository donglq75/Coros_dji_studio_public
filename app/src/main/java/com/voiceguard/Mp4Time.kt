package com.voiceguard

import java.io.File
import java.io.RandomAccessFile

/**
 * Ghi lại thời điểm tạo (mvhd/tkhd/mdhd) trong file mp4 để clip đã che nhạc mang đúng ngày giờ quay của clip gốc,
 * nhờ đó thư viện ảnh / DJI Mimo có thể xếp nó đúng chỗ trong dòng thời gian (nếu chúng dựa vào metadata này).
 */
object Mp4Time {
    private const val EPOCH_DIFF = 2_082_844_800L // giây từ 1904-01-01 đến 1970-01-01

    fun patchCreationTime(file: File, unixMillis: Long) {
        val t = unixMillis / 1000 + EPOCH_DIFF
        RandomAccessFile(file, "rw").use { raf ->
            val len = raf.length()
            var pos = 0L
            while (pos + 8 <= len) {
                raf.seek(pos)
                val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4)
                raf.readFully(type)
                var header = 8L
                var size = size32
                if (size32 == 1L) {
                    size = raf.readLong()
                    header = 16L
                } else if (size32 == 0L) {
                    size = len - pos
                }
                if (size < header) return
                if (String(type, Charsets.ISO_8859_1) == "moov") {
                    patchContainer(raf, pos + header, pos + size, t)
                    return
                }
                pos += size
            }
        }
    }

    private fun patchContainer(raf: RandomAccessFile, start: Long, end: Long, t: Long) {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            raf.readFully(type)
            var header = 8L
            var size = size32
            if (size32 == 1L) {
                size = raf.readLong()
                header = 16L
            } else if (size32 == 0L) {
                size = end - pos
            }
            if (size < header) return
            when (String(type, Charsets.ISO_8859_1)) {
                "mvhd", "tkhd", "mdhd" -> writeTimes(raf, pos + header, t)
                "trak", "mdia" -> patchContainer(raf, pos + header, pos + size, t)
            }
            pos += size
        }
    }

    private fun writeTimes(raf: RandomAccessFile, bodyPos: Long, t: Long) {
        raf.seek(bodyPos)
        val version = raf.readUnsignedByte()
        raf.seek(bodyPos + 4) // bỏ qua version (1 byte) + flags (3 byte)
        if (version == 1) {
            raf.writeLong(t) // creation
            raf.writeLong(t) // modification
        } else {
            raf.writeInt(t.toInt())
            raf.writeInt(t.toInt())
        }
    }
}
