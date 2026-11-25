package com.donglq.corosdjioverlay

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class FitPoint(
    val epochSeconds: Long, val latitude: Double?, val longitude: Double?,
    val altitudeMeters: Double?, val speedMetersPerSecond: Double?,
    val distanceMeters: Double?, val heartRate: Int?, val cadence: Int?, val powerWatts: Int?
)
data class FitSummary(
    val points: List<FitPoint>, val startEpochSeconds: Long?, val endEpochSeconds: Long?,
    val totalDistanceMeters: Double?, val averageHeartRate: Int?, val maxHeartRate: Int?,
    val error: String? = null
) {
    val durationSeconds: Long? get() = if (startEpochSeconds != null && endEpochSeconds != null) endEpochSeconds - startEpochSeconds else null
}

object FitParser {
    private data class Field(val number: Int, val size: Int)
    private data class Definition(val global: Int, val little: Boolean, val fields: List<Field>, val developerSizes: List<Int>)

    fun parse(input: InputStream): FitSummary {
        val b = input.use { it.readBytes() }
        if (b.size < 14 || (b[0].toInt() and 255) < 12 || String(b, 8, 4, Charsets.US_ASCII) != ".FIT")
            return failure("File FIT không hợp lệ hoặc quá ngắn")
        val header = b[0].toInt() and 255
        val dataLength = u32(b, 4, true)
        if (dataLength > Int.MAX_VALUE || header.toLong() + dataLength > b.size) return failure("Kích thước dữ liệu FIT không hợp lệ")
        val end = header + dataLength.toInt()
        val defs = mutableMapOf<Int, Definition>()
        val points = mutableListOf<FitPoint>()
        var p = header
        var lastTimestamp: Long? = null
        while (p < end) {
            val h = b[p++].toInt() and 255
            if ((h and 0x80) != 0) {
                val local = (h ushr 5) and 3
                val d = defs[local] ?: return failure("FIT thiếu message definition")
                val compressedOffset = h and 31
                val (fields, next) = readFields(b, p, end, d, true)
                p = next
                val prev = lastTimestamp
                var ts = (prev?.and(-32L) ?: 0L) + compressedOffset
                if (prev != null && ts <= prev) ts += 32L
                lastTimestamp = ts
                points += point(d, fields, ts)
                continue
            }
            val local = h and 15
            if ((h and 0x40) != 0) {
                if (p + 5 > end) break
                p++ // reserved
                val arch = b[p++].toInt() and 255
                val little = arch == 0
                val global = u16(b, p, little); p += 2
                val count = b[p++].toInt() and 255
                val fields = ArrayList<Field>(count)
                repeat(count) {
                    if (p + 3 <= end) {
                        val n = b[p++].toInt() and 255
                        val size = b[p++].toInt() and 255
                        p++ // base type
                        fields += Field(n, size)
                    }
                }
                val dev = mutableListOf<Int>()
                if ((h and 0x20) != 0 && p < end) {
                    val n = b[p++].toInt() and 255
                    repeat(n) {
                        if (p + 3 <= end) {
                            p++ // developer data index
                            dev += (b[p++].toInt() and 255)
                            p++ // field definition number
                        }
                    }
                }
                defs[local] = Definition(global, little, fields, dev)
            } else {
                val d = defs[local] ?: return failure("FIT thiếu message definition")
                val (fields, next) = readFields(b, p, end, d, false)
                p = next
                if (d.global == 20) {
                    val ts = unsigned(fields[253], d.little)
                    if (ts != null) {
                        lastTimestamp = ts
                        points += point(d, fields, ts)
                    }
                }
            }
        }
        if (points.isEmpty()) return failure("Không tìm thấy bản ghi hoạt động (record) trong FIT")
        val hrs = points.mapNotNull { it.heartRate }
        return FitSummary(points, points.first().epochSeconds, points.last().epochSeconds,
            points.lastOrNull { it.distanceMeters != null }?.distanceMeters,
            hrs.takeIf { it.isNotEmpty() }?.average()?.toInt(), hrs.maxOrNull())
    }

    private fun readFields(b: ByteArray, start: Int, end: Int, d: Definition, compressed: Boolean): Pair<Map<Int, ByteArray>, Int> {
        var p = start
        val values = mutableMapOf<Int, ByteArray>()
        for (field in d.fields) {
            if (compressed && field.number == 253) continue // timestamp is carried in the record header
            if (p + field.size > end) return values to end
            values[field.number] = b.copyOfRange(p, p + field.size)
            p += field.size
        }
        val devBytes = d.developerSizes.sum()
        p = (p + devBytes).coerceAtMost(end)
        return values to p
    }

    private fun point(d: Definition, f: Map<Int, ByteArray>, timestamp: Long): FitPoint {
        fun uint(n: Int): Long? = unsigned(f[n], d.little)
        fun signed32(n: Int): Int? = f[n]?.takeIf { it.size == 4 }?.let {
            ByteBuffer.wrap(it).order(if (d.little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int
        }?.takeIf { it != Int.MIN_VALUE }
        fun semicircle(n: Int): Double? = signed32(n)?.let { it * (180.0 / 2147483648.0) }
        val altitude = uint(2)?.takeIf { it != 0xffffL && it != 0xffff_ffffL }
        val speed = uint(6)?.takeIf { it != 0xffffL && it != 0xffff_ffffL }
        val distance = uint(5)?.takeIf { it != 0xffff_ffffL }
        // FIT running cadence is stored per leg (steps/min for one foot).
        // Convert to total steps/min for display; include fractional_cadence
        // when present (FIT field 53, scale 128).
        val cadenceSpm = uint(4)?.takeIf { it < 255 }?.let { whole ->
            val fractional = uint(53)?.takeIf { it < 255 }?.div(128.0) ?: 0.0
            ((whole + fractional) * 2.0).roundToInt()
        }
        return FitPoint(timestamp + 631065600L, semicircle(0), semicircle(1),
            altitude?.let { it * 0.2 - 500.0 }, speed?.let { it / 1000.0 },
            distance?.let { it / 100.0 }, uint(3)?.takeIf { it < 255 }?.toInt(),
            cadenceSpm, uint(7)?.takeIf { it < 65535 }?.toInt())
    }

    private fun unsigned(raw: ByteArray?, little: Boolean): Long? = raw?.let {
        when (it.size) {
            1 -> it[0].toLong() and 255
            2 -> u16(it, 0, little).toLong()
            4 -> u32(it, 0, little).takeIf { value -> value != 0xffff_ffffL }
            else -> null
        }
    }
    private fun failure(message: String) = FitSummary(emptyList(), null, null, null, null, null, message)
    private fun u16(b: ByteArray, p: Int, little: Boolean): Int =
        if (little) (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
        else ((b[p].toInt() and 255) shl 8) or (b[p+1].toInt() and 255)
    private fun u32(b: ByteArray, p: Int, little: Boolean): Long =
        if (little) (b[p].toLong() and 255) or ((b[p+1].toLong() and 255) shl 8) or ((b[p+2].toLong() and 255) shl 16) or ((b[p+3].toLong() and 255) shl 24)
        else ((b[p].toLong() and 255) shl 24) or ((b[p+1].toLong() and 255) shl 16) or ((b[p+2].toLong() and 255) shl 8) or (b[p+3].toLong() and 255)
}
