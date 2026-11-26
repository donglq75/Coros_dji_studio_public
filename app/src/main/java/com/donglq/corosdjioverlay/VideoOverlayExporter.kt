package com.donglq.corosdjioverlay

import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.corosdjistudio.StudioSessionLog
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max


private fun legacyCorosOverlayOutputBaseName(originalName: String): String {
    val stem = originalName.substringBeforeLast('.', originalName)
    return "COROS_" + stem.replace(Regex("(?i)^DJI[_-]*"), "")
        .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.', '-')
        .take(60).ifBlank { "video" }
}

private fun fallbackOverlayOutputBaseName(originalName: String): String {
    val stem = originalName.substringBeforeLast('.', originalName)
    val safeStem = stem.replace(Regex("(?i)^DJI[_-]*"), "")
        .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.', '-')
        .take(60).ifBlank { "video" }
    return "${safeStem}_ovl"
}

internal fun overlayOutputBaseName(originalName: String, startEpochSeconds: Long? = null): String {
    val stem = originalName.substringBeforeLast('.', originalName)
    if (Regex("^\\d{6}_\\d{6}(?:_\\d{2})?$").matches(stem)) return "${stem}_ovl"

    // DJI originals commonly carry their capture timestamp as YYYYMMDD_HHMMSS.
    val djiStamp = Regex("(?<!\\d)(20\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01]))_([0-2]\\d[0-5]\\d[0-5]\\d)(?!\\d)")
        .find(originalName)
    val filenameEpoch = djiStamp?.let { match ->
        runCatching {
            java.time.LocalDateTime.parse(
                "${match.groupValues[1]}_${match.groupValues[2]}",
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
            ).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toEpochSecond()
        }.getOrNull()
    }
    val epoch = filenameEpoch ?: startEpochSeconds
    val timestampStem = epoch?.takeIf { it > 0L }?.let { value ->
        runCatching {
            Instant.ofEpochSecond(value).atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ofPattern("yyMMdd_HHmmss", Locale.US))
        }.getOrNull()
    }
    if (!timestampStem.isNullOrBlank()) return "${timestampStem}_ovl"
    return fallbackOverlayOutputBaseName(originalName)
}

internal fun hasMatchingOverlayOutput(
    originalName: String,
    existingNames: Set<String>,
    startEpochSeconds: Long? = null
): Boolean {
    val candidates = mutableSetOf(overlayOutputBaseName(originalName, startEpochSeconds))
    val stem = originalName.substringBeforeLast('.', originalName)
    if (!Regex("^\\d{6}_\\d{6}(?:_\\d{2})?$").matches(stem)) {
        candidates += legacyCorosOverlayOutputBaseName(originalName)
    }
    return existingNames.any { existing ->
        candidates.any { base ->
            Regex("^" + Regex.escape(base) + "(?:_\\d+)?\\.mp4$", RegexOption.IGNORE_CASE).matches(existing)
        }
    }
}

internal fun listProjectFileNames(context: Context, folderUri: Uri?): Set<String> {
    if (folderUri == null) return emptySet()
    return runCatching {
        val documentId = if (DocumentsContract.isTreeUri(folderUri))
            DocumentsContract.getTreeDocumentId(folderUri)
        else DocumentsContract.getDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, documentId)
        val names = mutableSetOf<String>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { names.add(it) }
        }
        names
    }.getOrDefault(emptySet())
}

data class VideoExportItem(
    val uri: Uri,
    val name: String,
    val startEpochSeconds: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0
)

@OptIn(UnstableApi::class)
class VideoOverlayBatchExporter(
    private val context: Context,
    private val videos: List<VideoExportItem>,
    private val fit: FitSummary,
    private val offsetSeconds: Long,
    private val sessionId: String,
    private val outputTreeUri: Uri?,
    private val showPace: Boolean, private val showCadence: Boolean, private val showHeartRate: Boolean,
    private val showDistance: Boolean, private val showElapsed: Boolean,
    private val showAltitude: Boolean, private val showRoute: Boolean,
    private val textScale: Float, private val textColorName: String, private val textFontName: String,
    private val italicText: Boolean,
    private val onProgress: (String) -> Unit,
    private val onFinished: (String) -> Unit
) {
    private var index = 0
    private var transformer: Transformer? = null
    private var activeOverlay: TelemetryBitmapOverlay? = null
    private val outputDirectory = File(context.cacheDir, "coros_dji_export").apply { mkdirs() }
    private var completed = 0
    private var skipped = 0
    private var failed = 0
    private val journal = context.getSharedPreferences("coros_export_journal", Context.MODE_PRIVATE)
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun videoKey(item: VideoExportItem): String = digest(item.uri.toString() + "|" + item.name)

    // Stable regardless of SharedPreferences StringSet iteration order.
    private val sessionKey = "done_" + digest(
        videos.map { videoKey(it) }.sorted().joinToString("|") +
            "|offset=$offsetSeconds|fitStart=${fit.startEpochSeconds}|fitEnd=${fit.endEpochSeconds}|session=$sessionId"
    )
    private val doneKeys = journal.getStringSet(sessionKey, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun start() {
        while (index < videos.size && doneKeys.contains(videoKey(videos[index]))) index++
        if (index > 0) onProgress("Khôi phục nhật ký: đã có ${doneKeys.size}/${videos.size} video hoàn tất. Tiếp tục từ video ${index + 1}.")
        exportNext()
    }

    private fun exportNext() {
        while (index < videos.size && doneKeys.contains(videoKey(videos[index]))) index++
        if (index >= videos.size) {
            onFinished("Kết thúc: đã lưu $completed video${if (skipped > 0) ", bỏ qua $skipped video không có FIT/GPS" else ""}${if (failed > 0) ", lỗi $failed video (có thể chạy lại)" else ""}. Thư mục dji_studio/YYMMDD (cùng thư mục video gốc).")
            return
        }
        val item = videos[index]
        val clipStart = item.startEpochSeconds + offsetSeconds
        val clipEnd = clipStart + max(1L, item.durationMs / 1000L)
        val clipPoints = fit.points.filter { it.epochSeconds in clipStart..clipEnd }
        if (clipPoints.isEmpty()) {
            skipped++
            doneKeys.add(videoKey(item))
            journal.edit().putStringSet(sessionKey, doneKeys.toSet()).apply()
            index++
            onProgress("Tiến độ ${doneKeys.size}/${videos.size} • Bỏ qua ${item.name}: không có bản ghi FIT trong thời gian video.")
            exportNext()
            return
        }

        val output = File(outputDirectory, "coros_dji_${index + 1}_${System.currentTimeMillis()}.mp4")
        output.delete()
        val orientationLabel = when (item.rotationDegrees) {
            90 -> "xoay 90°"
            180 -> "xoay 180°"
            270 -> "xoay 270°"
            else -> if (item.height > item.width) "portrait" else "landscape"
        }
        onProgress("Đang render video ${index + 1}/${videos.size}: ${item.name} • $orientationLabel")
        val overlay = TelemetryBitmapOverlay(
            item.width.coerceAtLeast(640),
            item.height.coerceAtLeast(360),
            fit.points, clipStart, showPace, showCadence, showHeartRate, showDistance, showElapsed, showAltitude, showRoute,
            textScale, textColorName, textFontName, italicText
        )
        activeOverlay = overlay
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        if (item.rotationDegrees != 0) {
            // Apply each source clip's own orientation before drawing telemetry. The final
            // muxer cannot represent different per-clip rotation hints in one MP4.
            videoEffects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(((360 - item.rotationDegrees) % 360).toFloat())
                .build()
        }
        videoEffects += OverlayEffect(listOf(overlay))
        val effects = Effects(emptyList(), videoEffects)
        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(item.uri))
            .setEffects(effects)
            .build()

        val renderStartedAtMs = android.os.SystemClock.elapsedRealtime()
        transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            // Encode portrait frames in portrait dimensions instead of rotating the encoded
            // pixels and relying on a per-file MP4 orientation tag. The downstream batch
            // muxer can only write one orientation hint for the entire output file.
            .setPortraitEncodingEnabled(true)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    Thread {
                        val rotationFreeOutput = File(outputDirectory, "rotation_free_${System.currentTimeMillis()}.mp4")
                        rotationFreeOutput.delete()
                        val saveResult = runCatching {
                            remuxWithoutRotation(output, rotationFreeOutput)
                            publishToProject(rotationFreeOutput, item.name, item.startEpochSeconds)
                        }.also { rotationFreeOutput.delete() }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            saveResult.onSuccess { savedUri ->
                                val savedName = runCatching {
                                    context.contentResolver.query(savedUri,
                                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                                        if (it.moveToFirst()) it.getString(0) else null
                                    }
                                }.getOrNull() ?: overlayOutputBaseName(item.name, item.startEpochSeconds) + ".mp4"
                                outputTreeUri?.let {
                                    val renderDurationMs = (android.os.SystemClock.elapsedRealtime() - renderStartedAtMs).coerceAtLeast(1L)
                                    StudioSessionLog.recordOverlay(context, it, item.name, savedName, renderDurationMs)
                                }
                                completed++
                                doneKeys.add(videoKey(item))
                                journal.edit().putStringSet(sessionKey, doneKeys.toSet()).apply()
                                output.delete()
                                overlay.release()
                                activeOverlay = null
                                transformer = null
                                onProgress("Tiến độ ${doneKeys.size}/${videos.size} • Đã lưu video ${index + 1}/${videos.size}: $savedUri • nhật ký đã chốt")
                                index++
                                exportNext()
                            }.onFailure { error ->
                                output.delete()
                                overlay.release()
                                activeOverlay = null
                                transformer = null
                                failed++
                                onProgress("Lỗi lưu ${item.name}: ${error.localizedMessage ?: "không rõ"}. Bỏ qua clip này và tiếp tục.")
                                index++
                                exportNext()
                            }
                        }
                    }.start()
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        output.delete()
                        overlay.release()
                        activeOverlay = null
                        transformer = null
                        failed++
                        onProgress("Lỗi render ${item.name}: ${exportException.localizedMessage ?: "không rõ"}. Bỏ qua clip này và tiếp tục.")
                        index++
                        exportNext()
                    }
                }
            })
            .build()
        transformer?.start(editedItem, output.absolutePath)
    }

    /**
     * The per-clip ScaleAndRotateTransformation has already baked source orientation into frames.
     * Strip any rotation hint Media3 preserved so downstream concatenation sees rotation=0 for every
     * overlay file and never inherits one clip's orientation for its neighbours.
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
            if (extractor.trackCount == 0) throw IOException("Video overlay không có track.")
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
            throw IOException("Không tạo được MP4 overlay đã chuẩn hóa orientation.")
        }
    }

    private fun publishToProject(source: File, originalName: String, startEpochSeconds: Long): Uri {
        val folderUri = outputTreeUri ?: error("Chưa chọn thư mục dự án")
        val base = overlayOutputBaseName(originalName, startEpochSeconds)

        val isTree = DocumentsContract.isTreeUri(folderUri)
        val treeFolder = if (isTree) {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
                ?: error("Không truy cập được thư mục dự án")
        } else null

        val candidateBases = setOf(base, legacyCorosOverlayOutputBaseName(originalName))
        fun isPreviousOverlay(name: String): Boolean = candidateBases.any { candidate ->
            Regex("^${Regex.escape(candidate)}(?:_\\d+)?\\.mp4$", RegexOption.IGNORE_CASE).matches(name)
        }
        var targetUri: Uri? = null
        if (treeFolder != null) {
            if (!treeFolder.canWrite()) error("Thư mục dự án không có quyền ghi")
            // This method is only reached for clips selected for rendering. If the user
            // explicitly enabled re-rendering, replace prior overlay variants for this clip.
            treeFolder.listFiles().filter { isPreviousOverlay(it.name ?: "") }.forEach { old ->
                if (!old.delete()) throw IOException("Không thay thế được overlay cũ: ${old.name}")
            }
            targetUri = treeFolder.createFile("video/mp4", "$base.mp4")?.uri
        } else {
            val folderId = DocumentsContract.getDocumentId(folderUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, folderId)
            val existing = mutableListOf<Pair<String, String>>()
            context.contentResolver.query(children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) existing += cursor.getString(idColumn) to cursor.getString(nameColumn)
            }
            existing.filter { isPreviousOverlay(it.second) }.forEach { (id, name) ->
                val oldUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, id)
                if (!DocumentsContract.deleteDocument(context.contentResolver, oldUri)) {
                    throw IOException("Không thay thế được overlay cũ: $name")
                }
            }
            targetUri = DocumentsContract.createDocument(context.contentResolver, folderUri, "video/mp4", "$base.mp4")
        }
        val target = targetUri ?: error("Không tạo được tệp đích trong thư mục dự án")
        try {
            context.contentResolver.openOutputStream(target, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("Không mở được tệp đích")
            return target
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw t
        }
    }
}

@OptIn(UnstableApi::class)
private class TelemetryBitmapOverlay(
    private val width: Int,
    private val height: Int,
    private val points: List<FitPoint>,
    private val clipStartEpoch: Long,
    private val showPace: Boolean, private val showCadence: Boolean, private val showHeartRate: Boolean,
    private val showDistance: Boolean, private val showElapsed: Boolean,
    private val showAltitude: Boolean, private val showRoute: Boolean,
    private val textScale: Float, private val textColorName: String, private val textFontName: String,
    private val italicText: Boolean
) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val isPortrait = height > width
    private val scale = if (isPortrait) minOf(width / 1080f, height / 1920f)
        else minOf(width / 1920f, height / 1080f)
    private val margin = (if (isPortrait) 38f else 54f) * scale
    private val chosenTextColor = when (textColorName) {
        "yellow" -> android.graphics.Color.rgb(255, 224, 90)
        "cyan" -> android.graphics.Color.rgb(90, 220, 255)
        "green" -> android.graphics.Color.rgb(190, 245, 110)
        else -> android.graphics.Color.WHITE
    }
    private val chosenTypeface = android.graphics.Typeface.create(
        textFontName,
        if (italicText) android.graphics.Typeface.BOLD_ITALIC else android.graphics.Typeface.BOLD
    )
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = chosenTextColor
        textSize = (if (isPortrait) 44f else 76f) * scale * textScale.coerceIn(0.75f, 1.5f)
        typeface = chosenTypeface
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(190, 235, 120)
        textSize = (if (isPortrait) 18f else 25f) * scale * textScale.coerceIn(0.75f, 1.5f)
        typeface = chosenTypeface
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (if (isPortrait) 6f else 10f) * scale
        textSize = textPaint.textSize
        typeface = textPaint.typeface
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(190, 245, 70)
        style = Paint.Style.STROKE
        strokeWidth = (if (isPortrait) 4f else 7f) * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = (if (isPortrait) 9f else 15f) * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gpsPoints = points.filter {
        it.latitude != null && it.longitude != null &&
            it.latitude!! in -90.0..90.0 && it.longitude!! in -180.0..180.0
    }
    private val gpsSegments = buildGpsSegments()
    private val routePoints = gpsSegments.flatten()
    // Project longitude by cos(latitude), then fit the route inside its map viewport
    // with one shared scale. Independent X/Y normalization would stretch the track.
    private val meanLatitudeRad = Math.toRadians(routePoints.map { it.latitude!! }.average().takeIf { it.isFinite() } ?: 0.0)
    private fun projectedX(point: FitPoint): Double = point.longitude!! * cos(meanLatitudeRad)
    private val minX = routePoints.minOfOrNull { projectedX(it) } ?: 0.0
    private val maxX = routePoints.maxOfOrNull { projectedX(it) } ?: 0.0
    private val minY = routePoints.minOfOrNull { it.latitude!! } ?: 0.0
    private val maxY = routePoints.maxOfOrNull { it.latitude!! } ?: 0.0
    private val routePath = buildRoutePath()

    override fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
        super.release()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val epoch = clipStartEpoch + presentationTimeUs / 1_000_000L
        val fitStart = points.firstOrNull()?.epochSeconds ?: epoch
        val fitEnd = points.lastOrNull()?.epochSeconds ?: epoch
        val point = if (epoch in fitStart..fitEnd) points.minByOrNull { abs(it.epochSeconds - epoch) } else null
        drawMetrics(point, epoch)
        if (showRoute) drawRoute(epoch)
        return bitmap
    }

    private fun drawMetrics(point: FitPoint?, epoch: Long) {
        val pace = point?.speedMetersPerSecond?.takeIf { it > 0.3 }?.let {
            val sec = (1000.0 / it).toInt()
            String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
        } ?: "--:--"
        val cadence = point?.cadence?.takeIf { it > 0 }?.let { "$it spm" } ?: "--"
        val hr = point?.heartRate?.let { "$it" } ?: "--"
        val distance = point?.distanceMeters?.let { String.format(Locale.US, "%.2f", it / 1000.0) } ?: "--"
        val altitude = point?.altitudeMeters?.let { "${it.toInt()} m" } ?: "--"
        val elapsed = max(0L, epoch - (points.firstOrNull()?.epochSeconds ?: epoch))
        val elapsedText = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)

        val top = margin + textPaint.textSize + labelPaint.textSize + 12f * scale
        val bottom = height - margin - labelPaint.textSize
        if (isPortrait) {
            // Preserve the established portrait layout and its spacing.
            if (showPace) drawMetric("pace", "$pace /km", margin, top)
            if (showCadence) drawMetric("cadence", cadence, width * 0.26f, top)
            if (showHeartRate) drawMetric("hr", "$hr bpm", width * 0.51f, top)
            if (showElapsed) drawMetric("time", elapsedText, width * 0.76f, top)
            if (showDistance) drawMetric("distance", "$distance km", margin, bottom)
            if (showAltitude) drawMetric("elevation", altitude, width * 0.43f, bottom)
        } else {
            // Landscape hierarchy: distance/time in the upper corners;
            // four compact telemetry values along the lower-left, leaving
            // the lower-right route map clear. No filled panel is drawn.
            if (showDistance) drawMetric("distance", "$distance km", margin, top)
            if (showElapsed) {
                val elapsedWidth = textPaint.measureText(elapsedText)
                drawMetric("time", elapsedText, width - margin - elapsedWidth, top)
            }
            val rowWidth = width * 0.64f
            val cell = rowWidth / 4f
            if (showPace) drawMetric("pace", "$pace /km", margin, bottom)
            if (showCadence) drawMetric("cadence", cadence, margin + cell, bottom)
            if (showHeartRate) drawMetric("hr", "$hr bpm", margin + cell * 2f, bottom)
            if (showAltitude) drawMetric("elevation", altitude, margin + cell * 3f, bottom)
        }
    }

    private fun drawMetric(label: String, value: String, x: Float, y: Float) {
        canvas.drawText(label, x, y - textPaint.textSize * 0.98f, labelPaint)
        canvas.drawText(value, x, y, outlinePaint)
        canvas.drawText(value, x, y, textPaint)
    }

    private fun distanceMeters(a: FitPoint, b: FitPoint): Double {
        val lat1 = Math.toRadians(a.latitude!!)
        val lat2 = Math.toRadians(b.latitude!!)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude!! - a.longitude!!)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 6_371_000.0 * 2.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun buildGpsSegments(): List<List<FitPoint>> {
        val segments = mutableListOf<MutableList<FitPoint>>()
        gpsPoints.forEach { point ->
            val current = segments.lastOrNull()
            val previous = current?.lastOrNull()
            val gap = previous?.let { point.epochSeconds - it.epochSeconds } ?: 0L
            val jump = previous?.let { distanceMeters(it, point) } ?: 0.0
            if (current == null || gap <= 0L || gap > 10L || jump > 60.0) {
                segments.add(mutableListOf(point))
            } else {
                current.add(point)
            }
        }
        return segments.filter { it.size >= 2 }
    }

    private fun buildRoutePath(): Path {
        val path = Path()
        if (routePoints.size < 2) return path
        val mapWidth = width * (if (isPortrait) 0.21f else 0.285f)
        val mapHeight = height * (if (isPortrait) 0.115f else 0.285f)
        val left = width - margin - mapWidth
        val top = height - margin - mapHeight
        gpsSegments.forEach { segment ->
            segment.forEachIndexed { index, p ->
                val (x, y) = routePosition(p, left, top, mapWidth, mapHeight)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        return path
    }

    private fun drawRoute(epoch: Long) {
        if (routePoints.size < 2) return
        canvas.drawPath(routePath, routeOutline)
        canvas.drawPath(routePath, routePaint)
        val fitStart = points.firstOrNull()?.epochSeconds ?: return
        val fitEnd = points.lastOrNull()?.epochSeconds ?: return
        if (epoch !in fitStart..fitEnd) return
        val currentSegment = gpsSegments.minByOrNull { segment ->
            segment.minOf { abs(it.epochSeconds - epoch) }
        } ?: return
        val currentIndex = currentSegment.indices.minByOrNull { abs(currentSegment[it].epochSeconds - epoch) } ?: return
        val current = currentSegment[currentIndex]
        val next = currentSegment.getOrNull((currentIndex + 1).coerceAtMost(currentSegment.lastIndex))
            ?: currentSegment.getOrNull((currentIndex - 1).coerceAtLeast(0)) ?: return
        val mapWidth = width * (if (isPortrait) 0.21f else 0.285f)
        val mapHeight = height * (if (isPortrait) 0.115f else 0.285f)
        val left = width - margin - mapWidth
        val top = height - margin - mapHeight
        val (x, y) = routePosition(current, left, top, mapWidth, mapHeight)
        val (nx, ny) = routePosition(next, left, top, mapWidth, mapHeight)
        val angle = Math.toDegrees(atan2((ny - y).toDouble(), (nx - x).toDouble())).toFloat()
        // Prominent directional marker; no filled black dot behind it.
        val arrow = Path().apply {
            val markerScale = scale * if (isPortrait) 0.65f else 1f
            moveTo(30f * markerScale, 0f)
            lineTo(-20f * markerScale, -18f * markerScale)
            lineTo(-12f * markerScale, 0f)
            lineTo(-20f * markerScale, 18f * markerScale)
            close()
        }
        canvas.save()
        // Arrow path is centered at (0,0): translate to GPS point before rotating.
        canvas.translate(x, y)
        canvas.rotate(angle)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (if (isPortrait) 11f else 18f) * scale
            strokeJoin = Paint.Join.ROUND
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (if (isPortrait) 7f else 10f) * scale
            strokeJoin = Paint.Join.ROUND
        }
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(255, 35, 35)
            style = Paint.Style.FILL
        }
        canvas.drawPath(arrow, shadow)
        canvas.drawPath(arrow, edge)
        canvas.drawPath(arrow, red)
        canvas.restore()
    }

    private fun routePosition(point: FitPoint, left: Float, top: Float, mapWidth: Float, mapHeight: Float): Pair<Float, Float> {
        val spanX = (maxX - minX).coerceAtLeast(1e-9)
        val spanY = (maxY - minY).coerceAtLeast(1e-9)
        val fitScale = minOf(mapWidth / spanX.toFloat(), mapHeight / spanY.toFloat())
        val drawnWidth = spanX.toFloat() * fitScale
        val drawnHeight = spanY.toFloat() * fitScale
        val offsetX = (mapWidth - drawnWidth) / 2f
        val offsetY = (mapHeight - drawnHeight) / 2f
        val x = left + offsetX + ((projectedX(point) - minX).toFloat() * fitScale)
        val y = top + offsetY + ((maxY - point.latitude!!).toFloat() * fitScale)
        return x to y
    }
}
