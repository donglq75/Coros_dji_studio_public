package com.donglq.corosdjioverlay

import com.corosdjistudio.BatchReviewActivity

import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.corosdjistudio.StudioTheme

class OverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudioTheme { SyncHome(this) }
        }
    }
}

private data class VideoInfo(val epochSeconds: Long?, val durationMs: Long?, val width: Int?, val height: Int?, val fps: String?, val rotationDegrees: Int = 0)
private data class SelectedVideo(val uri: Uri, val name: String, val info: VideoInfo)

private fun findFitFileInFolder(context: Context, folderUri: Uri): Pair<Uri, String>? {
    return runCatching {
        val parentId = DocumentsContract.getDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (name.endsWith(".fit", ignoreCase = true)) {
                    return@runCatching DocumentsContract.buildDocumentUriUsingTree(folderUri, id) to name
                }
            }
            null
        }
    }.getOrNull()
}

@OptIn(UnstableApi::class)
@Composable
private fun SyncHome(activity: OverlayActivity) {
    var videoName by remember { mutableStateOf("Chưa chọn video") }
    var videos by remember { mutableStateOf<List<SelectedVideo>>(emptyList()) }
    var alreadyRenderedVideoUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var includeAlreadyRendered by remember { mutableStateOf(false) }
    var fitName by remember { mutableStateOf("Chưa chọn FIT") }
    var fitUri by remember { mutableStateOf<Uri?>(null) }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    val videoEpoch = videoInfo?.epochSeconds
    var summary by remember { mutableStateOf<FitSummary?>(null) }
    var loading by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(true) }
    var manualOffset by remember { mutableFloatStateOf(0f) }
    var previewProgress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Chọn video DJI và file FIT để bắt đầu.") }
    val exportPrefs = remember { activity.getSharedPreferences("coros_service_export", android.content.Context.MODE_PRIVATE) }
    var exporting by remember { mutableStateOf(exportPrefs.getBoolean(ExportKeepAliveService.KEY_RUNNING, false)) }
    var exportStatus by remember { mutableStateOf(exportPrefs.getString(ExportKeepAliveService.KEY_STATUS, "") ?: "") }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportTotal by remember { mutableIntStateOf(0) }
    var recentOverlayUris by remember { mutableStateOf<List<String>>(emptyList()) }
    val sessionPrefs = remember { activity.getSharedPreferences("coros_export_session", android.content.Context.MODE_PRIVATE) }
    val outputPrefs = remember { activity.getSharedPreferences("coros_output", android.content.Context.MODE_PRIVATE) }
    val studioOutputTree = remember { activity.intent.getStringExtra("studio_output_tree")?.let(Uri::parse) }
    var outputTreeUri by remember { mutableStateOf(studioOutputTree ?: outputPrefs.getString("tree_uri", null)?.let(Uri::parse)) }
    LaunchedEffect(studioOutputTree) {
        if (studioOutputTree != null) outputPrefs.edit().putString("tree_uri", studioOutputTree.toString()).apply()
    }
    var showPace by remember { mutableStateOf(outputPrefs.getBoolean("show_pace", true)) }
    var showCadence by remember { mutableStateOf(outputPrefs.getBoolean("show_cadence", true)) }
    var showHeartRate by remember { mutableStateOf(outputPrefs.getBoolean("show_hr", true)) }
    var showDistance by remember { mutableStateOf(outputPrefs.getBoolean("show_distance", true)) }
    var showElapsed by remember { mutableStateOf(outputPrefs.getBoolean("show_elapsed", true)) }
    var showAltitude by remember { mutableStateOf(outputPrefs.getBoolean("show_altitude", true)) }
    var showRoute by remember { mutableStateOf(outputPrefs.getBoolean("show_route", true)) }
    var textScale by remember { mutableFloatStateOf(outputPrefs.getFloat("text_scale", 1f)) }
    var textColor by remember { mutableStateOf(outputPrefs.getString("text_color", "white") ?: "white") }
    var textFont by remember { mutableStateOf(outputPrefs.getString("text_font", "sans-serif") ?: "sans-serif") }
    var italicText by remember { mutableStateOf(outputPrefs.getBoolean("italic_text", true)) }
    fun saveOverlayOptions() { outputPrefs.edit().putBoolean("show_pace",showPace).putBoolean("show_cadence",showCadence).putBoolean("show_hr",showHeartRate).putBoolean("show_distance",showDistance).putBoolean("show_elapsed",showElapsed).putBoolean("show_altitude",showAltitude).putBoolean("show_route",showRoute).putFloat("text_scale",textScale).putString("text_color",textColor).putString("text_font",textFont).putBoolean("italic_text",italicText).apply() }

    fun applyExportStatus(message: String) {
        exportStatus = message
        val progressMatch = Regex("Tiến độ (\\d+)/(\\d+)").find(message)
        val currentMatch = Regex("Đang render video (\\d+)/(\\d+)").find(message)
        val recoveryMatch = Regex("đã có (\\d+)/(\\d+) video").find(message)
        val match = progressMatch ?: recoveryMatch ?: currentMatch
        if (match != null) {
            val done = match.groupValues[1].toIntOrNull() ?: 0
            val total = match.groupValues[2].toIntOrNull() ?: exportTotal
            exportTotal = total
            exportProgress = (if (currentMatch != null) done - 1 else done).coerceAtLeast(0).toFloat() / total.coerceAtLeast(1)
        }
        val finished = message.startsWith("Kết thúc:") || message.startsWith("Không thể khôi phục batch:")
        exporting = exportPrefs.getBoolean(ExportKeepAliveService.KEY_RUNNING, !finished)
        if (message.startsWith("Kết thúc:")) {
            exportProgress = 1f
            val raw = exportPrefs.getString(ExportKeepAliveService.KEY_REVIEW_URIS, "[]") ?: "[]"
            recentOverlayUris = runCatching { val a = org.json.JSONArray(raw); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
        }
    }

    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                val message = intent?.getStringExtra(ExportKeepAliveService.EXTRA_STATUS) ?: return
                applyExportStatus(message)
            }
        }
        val filter = IntentFilter(ExportKeepAliveService.ACTION_STATUS)
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(receiver, filter)
        onDispose { runCatching { activity.unregisterReceiver(receiver) } }
    }

    // Restore cached metadata first so the screen can recover immediately without rescanning every MP4.
    LaunchedEffect(Unit) {
        exportPrefs.getString(ExportKeepAliveService.KEY_STATUS, null)?.let { applyExportStatus(it) }
        restoring = true
        status = "Đang khôi phục phiên trước…"
        try {
            val studioFolderOutputNames = mutableSetOf<String>()
            // When Studio launches Overlay with a dated project folder, that folder is the
            // source of truth. Do not restore an older video selection from the previous session.
            val studioFolderVideos = studioOutputTree?.let { folderUri ->
                withContext(Dispatchers.IO) {
                    val parentId = DocumentsContract.getDocumentId(folderUri)
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, parentId)
                    val columns = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    val found = mutableListOf<SelectedVideo>()
                    activity.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1) ?: "Video"
                            val mime = cursor.getString(2) ?: ""
                            if (!mime.startsWith("video/") || name.startsWith(".")) continue
                            val isOverlayOutput = name.startsWith("COROS_", ignoreCase = true) ||
                                Regex("^\\d{6}_\\d{6}(?:_\\d{2})?_ovl(?:_\\d+)?\\.mp4$", RegexOption.IGNORE_CASE).matches(name)
                            if (isOverlayOutput) {
                                studioFolderOutputNames.add(name)
                                continue
                            }
                            val uri = DocumentsContract.buildDocumentUriUsingTree(folderUri, id)
                            runCatching {
                                found += SelectedVideo(uri, name, readVideoInfoFast(activity, uri))
                            }
                        }
                    }
                    found.sortedBy { it.info.epochSeconds ?: Long.MAX_VALUE }
                }
            }
            val orderedRaw = sessionPrefs.getString("video_uris_ordered", null)
            val savedVideos = if (studioFolderVideos != null) {
                studioFolderVideos.map { it.uri.toString() }
            } else if (orderedRaw != null) runCatching {
                val array = org.json.JSONArray(orderedRaw)
                (0 until array.length()).map { array.getString(it) }
            }.getOrDefault(emptyList()) else {
                (sessionPrefs.getStringSet("video_uris", emptySet()) ?: emptySet()).toList()
            }
            val loaded = if (studioFolderVideos != null) {
                studioFolderVideos
            } else if (savedVideos.isNotEmpty()) {
                val cachedRaw = sessionPrefs.getString("video_metadata_cache", null)
                val cached = runCatching {
                    val array = org.json.JSONArray(cachedRaw ?: "[]")
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.getJSONObject(index)
                        val rawUri = item.getString("uri")
                        rawUri to SelectedVideo(
                            Uri.parse(rawUri),
                            item.optString("name", "Video"),
                            VideoInfo(
                                item.optLong("epoch", -1L).takeIf { it > 0L },
                                item.optLong("duration", -1L).takeIf { it > 0L },
                                item.optInt("width", 0).takeIf { it > 0 },
                                item.optInt("height", 0).takeIf { it > 0 },
                                item.optString("fps").takeIf { it.isNotBlank() },
                                item.optInt("rotation", 0)
                            )
                        )
                    }.toMap()
                }.getOrDefault(emptyMap())
                withContext(Dispatchers.IO) {
                    savedVideos.distinct().mapNotNull { raw ->
                        runCatching {
                            val uri = Uri.parse(raw)
                            val previous = cached[raw]
                            // Always refresh container orientation metadata on restore. Old cache
                            // entries predate per-clip rotation tracking and must not default to 0°.
                            val fresh = readVideoInfoFast(activity, uri)
                            val info = fresh.copy(
                                epochSeconds = fresh.epochSeconds ?: previous?.info?.epochSeconds,
                                durationMs = fresh.durationMs ?: previous?.info?.durationMs,
                                fps = previous?.info?.fps
                            )
                            SelectedVideo(uri, previous?.name ?: displayName(activity, uri), info)
                        }.getOrNull()
                    }.sortedBy { it.info.epochSeconds ?: Long.MAX_VALUE }
                }
            } else emptyList<SelectedVideo>()

            videos = loaded
            alreadyRenderedVideoUris = loaded.filter { hasMatchingOverlayOutput(it.name, studioFolderOutputNames, it.info.epochSeconds) }
                .map { it.uri.toString() }.toSet()
            videoInfo = loaded.firstOrNull()?.info
            videoName = when {
                loaded.isEmpty() -> "Chưa chọn video"
                loaded.size == 1 -> loaded.first().name
                else -> "${loaded.first().name} và ${loaded.size - 1} video khác"
            }
            if (studioFolderVideos != null) {
                val ordered = org.json.JSONArray().apply { loaded.forEach { put(it.uri.toString()) } }
                val metadataCache = org.json.JSONArray().apply {
                    loaded.forEach { video ->
                        put(org.json.JSONObject().apply {
                            put("uri", video.uri.toString())
                            put("name", video.name)
                            put("epoch", video.info.epochSeconds ?: -1L)
                            put("duration", video.info.durationMs ?: -1L)
                            put("width", video.info.width ?: 0)
                            put("height", video.info.height ?: 0)
                            put("fps", video.info.fps ?: "")
                            put("rotation", video.info.rotationDegrees)
                        })
                    }
                }
                sessionPrefs.edit().putString("video_uris_ordered", ordered.toString())
                    .putString("video_metadata_cache", metadataCache.toString())
                    .putStringSet("video_uris", loaded.map { it.uri.toString() }.toSet()).apply()
                status = if (loaded.isEmpty()) "Thư mục dự án hiện chưa có video. Hãy chuyển video từ DJI Media rồi mở lại Overlay."
                    else "Đã quét thư mục Studio và nạp mới ${loaded.size} video."
            } else if (loaded.isNotEmpty()) {
                status = "Đã khôi phục nhanh ${loaded.size}/${savedVideos.size} video từ phiên trước."
            }
            if (studioOutputTree != null) {
                val detectedFit = withContext(Dispatchers.IO) { findFitFileInFolder(activity, studioOutputTree) }
                if (detectedFit != null) {
                    fitUri = detectedFit.first
                    fitName = detectedFit.second
                    sessionPrefs.edit().putString("fit_uri", detectedFit.first.toString()).apply()
                    summary = withContext(Dispatchers.IO) {
                        activity.contentResolver.openInputStream(detectedFit.first)?.use { FitParser.parse(it) }
                    }
                    status = if (summary?.points?.isNotEmpty() == true) "Đã tìm thấy FIT trong thư mục video."
                        else "Không đọc được FIT cùng thư mục. Hãy chọn lại file FIT."
                }
            }
            if (fitUri == null && studioOutputTree == null) sessionPrefs.getString("fit_uri", null)?.let { raw ->
                runCatching {
                    val uri = Uri.parse(raw)
                    fitUri = uri
                    fitName = displayName(activity, uri)
                    summary = withContext(Dispatchers.IO) {
                        activity.contentResolver.openInputStream(uri)?.use { FitParser.parse(it) }
                    }
                    if (summary?.points?.isNotEmpty() == true) status = "Đã khôi phục video, file FIT và dữ liệu phiên trước."
                }
            }
            manualOffset = sessionPrefs.getFloat("manual_offset", 0f)
        } finally {
            restoring = false
        }
    }
    LaunchedEffect(manualOffset) {
        sessionPrefs.edit().putFloat("manual_offset", manualOffset).apply()
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            status = "Đã chọn ${uris.size} video. Đang đọc metadata và sắp xếp theo thời gian quay…"
            activity.lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    uris.distinctBy { it.toString() }.map { uri ->
                        runCatching {
                            activity.contentResolver.takePersistableUriPermission(
                                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        SelectedVideo(uri, displayName(activity, uri), readVideoInfo(activity, uri))
                    }.sortedBy { it.info.epochSeconds ?: Long.MAX_VALUE }
                }
                videos = loaded
                val existingOutputNames = withContext(Dispatchers.IO) { listProjectFileNames(activity, outputTreeUri) }
                alreadyRenderedVideoUris = loaded.filter { hasMatchingOverlayOutput(it.name, existingOutputNames, it.info.epochSeconds) }
                    .map { it.uri.toString() }.toSet()
                val ordered = org.json.JSONArray().apply { loaded.forEach { put(it.uri.toString()) } }
                val metadataCache = org.json.JSONArray().apply {
                    loaded.forEach { video ->
                        put(org.json.JSONObject().apply {
                            put("uri", video.uri.toString())
                            put("name", video.name)
                            put("epoch", video.info.epochSeconds ?: -1L)
                            put("duration", video.info.durationMs ?: -1L)
                            put("width", video.info.width ?: 0)
                            put("height", video.info.height ?: 0)
                            put("fps", video.info.fps ?: "")
                            put("rotation", video.info.rotationDegrees)
                        })
                    }
                }
                sessionPrefs.edit().putString("video_uris_ordered", ordered.toString())
                    .putString("video_metadata_cache", metadataCache.toString())
                    .putStringSet("video_uris", loaded.map { it.uri.toString() }.toSet()).apply()
                videoInfo = loaded.firstOrNull()?.info
                videoName = when {
                    loaded.isEmpty() -> "Chưa chọn video"
                    loaded.size == 1 -> loaded.first().name
                    else -> "${loaded.first().name} và ${loaded.size - 1} video khác"
                }
                val recognized = loaded.count { it.info.epochSeconds != null }
                status = "Đã chọn ${loaded.size} video."
            }
        }
    }
    val outputFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            outputTreeUri = uri
            outputPrefs.edit().putString("tree_uri", uri.toString()).apply()
            status = "Đã chọn thư mục dự án. Video overlay sẽ lưu tại đây."
        }
    }
    val fitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            fitUri = uri
            sessionPrefs.edit().putString("fit_uri", uri.toString()).apply()
            fitName = displayName(activity, uri)
            summary = null
            loading = true
            status = "Đang đọc dữ liệu FIT…"
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        activity.contentResolver.openInputStream(uri)?.use { FitParser.parse(it) }
                            ?: FitSummary(emptyList(), null, null, null, null, null, "Không mở được file FIT")
                    }.getOrElse { FitSummary(emptyList(), null, null, null, null, null, "Lỗi đọc FIT: ${it.localizedMessage ?: "không xác định"}") }
                }
                summary = result
                loading = false
                status = result.error ?: "Đã đọc FIT."
            }
        }
    }

    val videosToRender = if (includeAlreadyRendered) videos else videos.filter { it.uri.toString() !in alreadyRenderedVideoUris }
    val alreadyRenderedCount = videos.count { it.uri.toString() in alreadyRenderedVideoUris }

    // Keep the UI's match status identical to the exporter's actual per-clip
    // selection rule: apply the manual offset, then require at least one FIT
    // record inside that video's own time interval.
    val fitMatchedCount = if (summary?.points?.isNotEmpty() == true) {
        videosToRender.count { video ->
            val startAt = video.info.epochSeconds
            if (startAt == null) false else {
                val adjustedStart = startAt + manualOffset.toLong()
                val durationSec = (((video.info.durationMs ?: 30_000L) / 1000L).coerceAtLeast(1L))
                val adjustedEnd = adjustedStart + durationSec
                summary!!.points.any { it.epochSeconds in adjustedStart..adjustedEnd }
            }
        }
    } else 0
    val fitMatchText = when {
        summary?.points.isNullOrEmpty() -> "Chưa có FIT"
        fitMatchedCount == 0 -> "FIT không khớp video – hãy chọn lại"
        fitMatchedCount == videos.size -> "FIT khớp toàn bộ ${videos.size} video"
        else -> "FIT khớp ${fitMatchedCount}/${videos.size} video"
    }
    val specText = videoInfo?.let { info ->
        val resolution = if ((info.width ?: 0) >= 3840 || (info.height ?: 0) >= 3840) "4K"
            else if ((info.width ?: 0) >= 1920 || (info.height ?: 0) >= 1920) "2K"
            else if (info.width != null && info.height != null) "${info.width}×${info.height}" else ""
        listOf(resolution, info.fps?.let { "${it} FPS" } ?: "").filter { it.isNotBlank() }.joinToString(" · ")
    } ?: ""
    val sampleColor = when (textColor) {
        "yellow" -> Color(0xFFFFE36E)
        "cyan" -> Color(0xFF7FE7F2)
        "green" -> Color(0xFF83E6A2)
        else -> Color.White
    }
    val sampleFont = when (textFont) {
        "monospace" -> FontFamily.Monospace
        "sans-serif-condensed" -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
        else -> FontFamily.SansSerif
    }
    val orientationSummary = videos.groupingBy { video ->
        when {
            video.info.rotationDegrees != 0 -> "${video.info.rotationDegrees}°"
            (video.info.height ?: 0) > (video.info.width ?: 0) -> "portrait"
            else -> "landscape"
        }
    }.eachCount().entries.joinToString(" · ") { "${it.value} ${it.key}" }.ifBlank { "chưa đọc được" }

    Column(Modifier.fillMaxSize().statusBarsPadding().background(Color(0xFF0B1F14)).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { activity.finish() }) { Text("← Studio") }
            Spacer(Modifier.weight(1f))
            Text("PHỦ DỮ LIỆU · ACTIVITY OVERLAY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        if (restoring) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${videos.size} video đã chọn", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        if (specText.isNotBlank()) Text(specText, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        if (videos.isNotEmpty()) Text("Orientation từng clip: $orientationSummary",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }) { Text("Chọn video") }
                }
                if (videos.isNotEmpty() && alreadyRenderedCount > 0) {
                    Text("Đã có overlay: $alreadyRenderedCount", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeAlreadyRendered,
                            onCheckedChange = { includeAlreadyRendered = it })
                        Column(Modifier.weight(1f)) {
                            Text("Render lại video đã có overlay", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Thay thế overlay cũ của từng clip bằng bản đã chuẩn hóa orientation",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dữ liệu COROS", fontWeight = FontWeight.Bold)
                        Text(if (fitUri == null) "Chưa có file FIT" else fitName,
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    OutlinedButton(onClick = { fitPicker.launch(arrayOf("*/*")) }, enabled = !loading) {
                        Text(if (loading) "Đang đọc…" else if (fitUri == null) "Chọn FIT" else "Đổi FIT")
                    }
                }
                Text(fitMatchText, fontSize = 12.sp,
                    color = if (fitMatchedCount == 0 && summary?.points?.isNotEmpty() == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold)
            }
        }

        if (videos.isNotEmpty()) {
            OverlayFramePreview(
                activity = activity,
                uri = videos.first().uri,
                info = videos.first().info,
                summary = summary,
                progress = previewProgress,
                onProgress = { previewProgress = it },
                manualOffset = manualOffset,
                showPace = showPace,
                showCadence = showCadence,
                showHeartRate = showHeartRate,
                showDistance = showDistance,
                showElapsed = showElapsed,
                showAltitude = showAltitude,
                showRoute = showRoute,
                textScale = textScale,
                textColor = sampleColor,
                textFont = sampleFont,
                italicText = italicText
            )
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hiển thị trên video", fontWeight = FontWeight.Bold)
                @Composable fun Option(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { onChange(it); saveOverlayOptions() })
                        Text(label, fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Option("Pace", showPace) { showPace = it }
                    Option("Cadence", showCadence) { showCadence = it }
                }
                Row(Modifier.fillMaxWidth()) {
                    Option("Nhịp tim", showHeartRate) { showHeartRate = it }
                    Option("Quãng đường", showDistance) { showDistance = it }
                }
                Row(Modifier.fillMaxWidth()) {
                    Option("Thời gian", showElapsed) { showElapsed = it }
                    Option("Độ cao", showAltitude) { showAltitude = it }
                }
                Row(Modifier.fillMaxWidth()) {
                    Option("Route GPS", showRoute) { showRoute = it }
                    Spacer(Modifier.weight(1f))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                @Composable fun StyleChoice(label: String, checked: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
                    Row(modifier = modifier.heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { if (it) onSelect() },
                            modifier = Modifier.size(34.dp))
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
                Text("Kiểu chữ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Cỡ chữ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth()) {
                    listOf(0.85f, 1f, 1.2f, 1.4f).forEach { scale ->
                        StyleChoice("${(scale * 100).toInt()}%", textScale == scale, Modifier.weight(1f)) {
                            textScale = scale; saveOverlayOptions()
                        }
                    }
                }
                Text("Phông chữ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth()) {
                    listOf("sans-serif" to "Hiện đại", "sans-serif-condensed" to "Gọn", "monospace" to "Mono").forEach { (value, label) ->
                        StyleChoice(label, textFont == value, Modifier.weight(1f)) {
                            textFont = value; saveOverlayOptions()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    StyleChoice("Chữ nghiêng", italicText, Modifier.weight(1f)) {
                        italicText = !italicText; saveOverlayOptions()
                    }
                }
                Text("Màu chữ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth()) {
                    listOf("white" to "Trắng", "yellow" to "Vàng", "cyan" to "Lam", "green" to "Xanh").forEach { (value, label) ->
                        StyleChoice(label, textColor == value, Modifier.weight(1f)) {
                            textColor = value; saveOverlayOptions()
                        }
                    }
                }
            }
        }

        if (outputTreeUri == null) {
            OutlinedButton(onClick = { outputFolderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Chọn thư mục lưu")
            }
        }
        // A force-stopped Android process leaves KEY_RUNNING persisted even though
        // the foreground service is gone. The service already persists the full
        // batch configuration, so starting it without extras restores its journal.
        if (exportPrefs.getBoolean(ExportKeepAliveService.KEY_RUNNING, false)) {
            OutlinedButton(
                onClick = {
                    // Lock immediately so repeated taps cannot issue duplicate resume requests.
                    exporting = true
                    exportStatus = "Đang khôi phục phiên render đã gián đoạn…"
                    val resumeIntent = android.content.Intent(activity, ExportKeepAliveService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) activity.startForegroundService(resumeIntent)
                    else activity.startService(resumeIntent)
                },
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (exporting) "ĐANG RENDER…"
                    else "▶ TIẾP TỤC PHIÊN RENDER ĐANG DỞ")
            }
        }
        if (videos.isNotEmpty() && summary?.points?.isNotEmpty() == true) {
            Button(
                onClick = {
                    val items = videosToRender.mapNotNull { video ->
                        video.info.epochSeconds?.let { start ->
                            VideoExportItem(video.uri, video.name, start, video.info.durationMs ?: 30_000L,
                                video.info.width ?: 1920, video.info.height ?: 1080, video.info.rotationDegrees)
                        }
                    }
                    val fitData = summary
                    if (items.isEmpty() || fitData == null || fitMatchedCount == 0) {
                        exportStatus = "FIT không khớp video. Hãy chọn lại FIT."
                    } else {
                        exporting = true
                        exportProgress = 0f
                        exportTotal = items.size
                        exportStatus = "Đang render ${items.size} video…"
                        val payload = org.json.JSONArray().apply {
                            items.forEach { item ->
                                put(org.json.JSONObject().apply {
                                    put("uri", item.uri.toString()); put("name", item.name)
                                    put("start", item.startEpochSeconds); put("duration", item.durationMs)
                                    put("width", item.width); put("height", item.height); put("rotation", item.rotationDegrees)
                                })
                            }
                        }
                        val serviceIntent = android.content.Intent(activity, ExportKeepAliveService::class.java).apply {
                            putExtra(ExportKeepAliveService.EXTRA_VIDEOS, payload.toString())
                            putExtra(ExportKeepAliveService.EXTRA_FIT_URI, fitUri?.toString())
                            putExtra(ExportKeepAliveService.EXTRA_OFFSET, manualOffset.toLong())
                            putExtra(ExportKeepAliveService.EXTRA_OUTPUT_TREE, outputTreeUri?.toString())
                            putExtra(ExportKeepAliveService.EXTRA_SHOW_PACE, showPace)
                            putExtra(ExportKeepAliveService.EXTRA_SHOW_CADENCE, showCadence)
                            putExtra(ExportKeepAliveService.EXTRA_SHOW_HR, showHeartRate)
                            putExtra(ExportKeepAliveService.EXTRA_SHOW_DISTANCE, showDistance)
                            putExtra(ExportKeepAliveService.EXTRA_SHOW_ELAPSED, showElapsed)
                            putExtra(ExportKeepAliveService.EXTRA_SHOW_ALTITUDE, showAltitude)
                            putExtra(ExportKeepAliveService.EXTRA_SHOW_ROUTE, showRoute)
                            putExtra(ExportKeepAliveService.EXTRA_TEXT_SCALE, textScale)
                            putExtra(ExportKeepAliveService.EXTRA_TEXT_COLOR, textColor)
                            putExtra(ExportKeepAliveService.EXTRA_TEXT_FONT, textFont)
                            putExtra(ExportKeepAliveService.EXTRA_ITALIC_TEXT, italicText)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) activity.startForegroundService(serviceIntent)
                        else activity.startService(serviceIntent)
                    }
                },
                enabled = !exporting && !exportPrefs.getBoolean(ExportKeepAliveService.KEY_RUNNING, false) &&
                    outputTreeUri != null && videosToRender.isNotEmpty() && fitMatchedCount > 0,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(if (exporting) "Đang render…" else if (videosToRender.isEmpty()) "Đã có Overlay cho tất cả video"
                    else "OVERLAY / RENDER  ·  ${videosToRender.size} video")
            }
            if (exportStatus.isNotBlank() || exporting) {
                LinearProgressIndicator(progress = { exportProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(exportStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (status.isNotBlank() && !status.startsWith("Đang khôi phục")) {
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2)
        }
        if (!exporting && recentOverlayUris.isNotEmpty()) {
            OutlinedButton(onClick = {
                val names = recentOverlayUris.indices.map { "Overlay video ${it + 1}" }
                activity.startActivity(android.content.Intent(activity, BatchReviewActivity::class.java).apply {
                    putStringArrayListExtra(BatchReviewActivity.EXTRA_URIS, ArrayList(recentOverlayUris))
                    putStringArrayListExtra(BatchReviewActivity.EXTRA_NAMES, ArrayList(names))
                })
            }, modifier = Modifier.fillMaxWidth()) { Text("▶ Xem lại kết quả (${recentOverlayUris.size} video)") }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun VideoBatchCard(videos: List<SelectedVideo>, fit: FitSummary?, manualOffset: Float) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("ĐỐI CHIẾU TOÀN BỘ VIDEO VỚI FIT", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text("${videos.size} video • đã sắp xếp theo thời gian quay", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (fit == null || fit.points.isEmpty() || fit.startEpochSeconds == null || fit.endEpochSeconds == null) {
                Spacer(Modifier.height(8.dp))
                Text("Chọn file FIT để ứng dụng tự ghép từng video với dữ liệu COROS tương ứng.", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            } else {
                val fitStart = fit.startEpochSeconds
                val fitEnd = fit.endEpochSeconds
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                videos.forEachIndexed { index, video ->
                    Spacer(Modifier.height(12.dp))
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    val start = video.info.epochSeconds
                    val durationSec = ((video.info.durationMs ?: 0L) / 1000L).coerceAtLeast(1L)
                    if (start == null) {
                        Text("${index + 1}. ${video.name}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Không đọc được thời điểm quay — chưa thể tự ghép FIT.", color = Color(0xFFFFCC80), fontSize = 12.sp)
                    } else {
                        val adjustedStart = start + manualOffset.toLong()
                        val adjustedEnd = adjustedStart + durationSec
                        // A clip can begin before or finish after the FIT recording and still
                        // contain a valid overlapping segment. Check interval intersection,
                        // not whether the clip's start timestamp alone is inside the FIT.
                        val overlapStart = maxOf(adjustedStart, fitStart)
                        val overlapEnd = minOf(adjustedEnd, fitEnd)
                        val hasOverlap = overlapStart <= overlapEnd
                        val fullyCovered = hasOverlap && adjustedStart >= fitStart && adjustedEnd <= fitEnd
                        val overlapMiddle = overlapStart + (overlapEnd - overlapStart) / 2L
                        // Never borrow a telemetry record from outside this clip's actual
                        // intersection with the FIT recording.
                        val overlapPoints = if (hasOverlap) fit.points.filter {
                            it.epochSeconds >= overlapStart && it.epochSeconds <= overlapEnd
                        } else emptyList()
                        val firstPoint = overlapPoints.firstOrNull()
                        val middlePoint = overlapPoints.minByOrNull { kotlin.math.abs(it.epochSeconds - overlapMiddle) }
                        val lastPoint = overlapPoints.lastOrNull()
                        val delta = adjustedStart - fitStart
                        Text("${index + 1}. ${video.name}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${formatTime(start)}  •  ${durationSec}s  •  FIT ${formatOffset(delta)}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        Text(
                            when {
                                !hasOverlap -> "Không có phần thời gian trùng với FIT"
                                overlapPoints.isEmpty() -> "Có khoảng thời gian trùng nhưng không có bản ghi FIT trong đoạn này"
                                fullyCovered -> "Đã ghép đủ khoảng thời gian video • ${overlapPoints.size} bản ghi FIT"
                                adjustedStart < fitStart && adjustedEnd <= fitEnd -> "Có dữ liệu FIT trong ${overlapEnd - overlapStart}s cuối video (đầu video trước FIT) • ${overlapPoints.size} bản ghi"
                                adjustedStart >= fitStart && adjustedEnd > fitEnd -> "Có dữ liệu FIT trong ${overlapEnd - overlapStart}s đầu video (cuối video sau FIT) • ${overlapPoints.size} bản ghi"
                                else -> "Chỉ phần giữa video nằm trong khoảng FIT • ${overlapPoints.size} bản ghi"
                            },
                            color = if (hasOverlap) MaterialTheme.colorScheme.primary else Color(0xFFFFCC80),
                            fontSize = 11.sp
                        )
                        if (hasOverlap) {
                            val pace = middlePoint?.speedMetersPerSecond?.takeIf { it > 0.3 }?.let {
                                val sec = (1000.0 / it).toInt()
                                String.format(Locale.US, "%d:%02d/km", sec / 60, sec % 60)
                            } ?: "—"
                            Text(
                                "HR đầu: ${firstPoint?.heartRate?.let { "$it bpm" } ?: if (firstPoint == null) "không có bản ghi" else "không có HR"}  |  Giữa: ${middlePoint?.heartRate?.let { "$it bpm" } ?: if (middlePoint == null) "không có bản ghi" else "không có HR"}, pace $pace  |  Cuối: ${lastPoint?.heartRate?.let { "$it bpm" } ?: if (lastPoint == null) "không có bản ghi" else "không có HR"}",
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp
                            )
                        }
                    }
                }
                }
                Spacer(Modifier.height(8.dp))
                Text("Mỗi video được đối chiếu theo timestamp riêng. Các điểm HR/pace chỉ lấy từ bản ghi FIT nằm trong khoảng giao nhau; không mượn dữ liệu trước hoặc sau đoạn FIT. Thanh hiệu chỉnh áp dụng chung cho cả loạt.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}


@Composable
private fun OverlayFramePreview(
    activity: OverlayActivity,
    uri: Uri,
    info: VideoInfo,
    summary: FitSummary?,
    progress: Float,
    onProgress: (Float) -> Unit,
    manualOffset: Float,
    showPace: Boolean,
    showCadence: Boolean,
    showHeartRate: Boolean,
    showDistance: Boolean,
    showElapsed: Boolean,
    showAltitude: Boolean,
    showRoute: Boolean,
    textScale: Float,
    textColor: Color,
    textFont: FontFamily,
    italicText: Boolean
) {
    val frame by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(activity, uri)
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    val durationSec = ((info.durationMs ?: 30_000L) / 1000L).coerceAtLeast(1L)
    val sampleEpoch = (info.epochSeconds ?: summary?.startEpochSeconds ?: 0L) +
        manualOffset.toLong() + (progress * durationSec).toLong()
    val point = summary?.points?.minByOrNull { kotlin.math.abs(it.epochSeconds - sampleEpoch) }
    val pace = point?.speedMetersPerSecond?.takeIf { it > 0.3 }?.let {
        val seconds = (1000.0 / it).toInt()
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    } ?: "--:--"
    val distance = point?.distanceMeters?.let { String.format(Locale.US, "%.2f KM", it / 1000.0) } ?: "-- KM"
    val elapsedSec = (progress * durationSec).toInt()
    val elapsed = String.format(Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
    val ratio = if ((info.width ?: 0) > 0 && (info.height ?: 0) > 0)
        info.width!!.toFloat() / info.height!!.toFloat() else 16f / 9f
    val fontStyle = if (italicText) androidx.compose.ui.text.font.FontStyle.Italic
        else androidx.compose.ui.text.font.FontStyle.Normal
    val mainSize = (15f * textScale).sp
    val metricSize = (11f * textScale).sp

    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("XEM TRƯỚC TRÊN VIDEO GỐC", color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2.4f))
                    .background(Color.Black, RoundedCornerShape(10.dp))
            ) {
                if (frame != null) {
                    Image(
                        bitmap = frame!!.asImageBitmap(),
                        contentDescription = "Khung hình video gốc",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(Modifier.align(Alignment.TopStart).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (showDistance || showElapsed) {
                            Text(
                                listOfNotNull(
                                    if (showDistance) distance else null,
                                    if (showElapsed) elapsed else null
                                ).joinToString("     "),
                                color = textColor, fontFamily = textFont, fontWeight = FontWeight.Bold,
                                fontStyle = fontStyle, fontSize = mainSize
                            )
                        }
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val metrics = buildList {
                            if (showPace) add("PACE $pace/km")
                            if (showHeartRate) add("HR ${point?.heartRate?.let { it.toString() } ?: "--"}")
                            if (showCadence) add("CAD ${point?.cadence?.let { it.toString() } ?: "--"}")
                            if (showAltitude) add("ELEV ${point?.altitudeMeters?.toInt()?.let { "$it M" } ?: "--"}")
                        }
                        metrics.chunked(3).forEach { row ->
                            Text(row.joinToString("  ·  "), color = textColor, fontFamily = textFont,
                                fontWeight = FontWeight.Bold, fontStyle = fontStyle, fontSize = metricSize)
                        }
                    }
                    if (showRoute) {
                        Text("➤", modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            color = Color(0xFFFF3030), fontSize = (25f * textScale).sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("Đang nạp khung hình video…", modifier = Modifier.align(Alignment.Center),
                        color = Color.White, fontSize = 12.sp)
                }
            }
            Slider(value = progress, onValueChange = onProgress, valueRange = 0f..1f)
            Text("Kéo thanh để xem số liệu tại các thời điểm khác nhau trong video.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TelemetryPreview(s: FitSummary, videoEpoch: Long?, videoDurationMs: Long?, manualOffset: Float, progress: Float, onProgress: (Float) -> Unit) {
    val duration = ((videoDurationMs ?: 0L) / 1000L).takeIf { it > 0L } ?: (s.durationSeconds ?: 1L).coerceAtLeast(1L)
    val sampleEpoch = if (videoEpoch != null) videoEpoch + manualOffset.toLong() + (progress * duration).toLong() else (s.startEpochSeconds ?: 0L) + manualOffset.toLong() + (progress * duration).toLong()
    val point = s.points.minByOrNull { kotlin.math.abs(it.epochSeconds - sampleEpoch) }
    val paceSeconds = point?.speedMetersPerSecond?.takeIf { it > 0.3 }?.let { (1000.0 / it).toInt() }
    val paceText = paceSeconds?.let { String.format(Locale.US, "%d:%02d /km", it / 60, it % 60) } ?: "—"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("04  XEM TRƯỚC TELEMETRY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(formatTime(sampleEpoch), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Slider(value = progress, onValueChange = onProgress, valueRange = 0f..1f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PreviewMetric("PACE", paceText)
                PreviewMetric("HEART RATE", point?.heartRate?.let { "$it bpm" } ?: "—")
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PreviewMetric("DISTANCE", point?.distanceMeters?.let { String.format(Locale.US, "%.2f km", it / 1000.0) } ?: "—")
                PreviewMetric("ALTITUDE", point?.altitudeMeters?.let { "${it.toInt()} m" } ?: "—")
            }
            Spacer(Modifier.height(8.dp))
            Text("Số liệu FIT gần nhất với thời điểm video đã căn chỉnh. Đây là bản xem trước số liệu, chưa phải video render.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PreviewMetric(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun displayName(activity: OverlayActivity, uri: Uri): String {
    activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0) ?: "Tệp đã chọn"
    }
    return uri.lastPathSegment ?: "Tệp đã chọn"
}

@Composable
private fun FitResultCard(s: FitSummary) {
    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("KẾT QUẢ ĐỌC FIT", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (s.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(s.error, color = Color(0xFFFFB4AB), fontSize = 13.sp)
            } else {
                Spacer(Modifier.height(10.dp))
                MetricRow("Số bản ghi", "${s.points.size}")
                MetricRow("Thời lượng", s.durationSeconds?.let { "${it / 60} phút ${it % 60} giây" } ?: "—")
                MetricRow("Quãng đường", s.totalDistanceMeters?.let { String.format(Locale.getDefault(), "%.2f km", it / 1000.0) } ?: "—")
                MetricRow("Nhịp tim TB / max", "${s.averageHeartRate ?: "—"} / ${s.maxHeartRate ?: "—"} bpm")
                MetricRow("Bắt đầu (giờ VN)", s.startEpochSeconds?.let { formatTime(it) } ?: "—")
                MetricRow("Kết thúc (giờ VN)", s.endEpochSeconds?.let { formatTime(it) } ?: "—")
                val gpsCount = s.points.count { it.latitude != null && it.longitude != null }
                MetricRow("Điểm GPS", gpsCount.toString())
            }
        }
    }
}

private fun parseStudioFilenameEpoch(name: String): Long? {
    // Studio names encode the original capture time in Vietnam local time:
    // YYMMDD_HHMMSS, optionally followed by collision/output suffixes.
    val match = Regex("^(\\d{6}_\\d{6})(?:_|\\.)").find(name) ?: return null
    return runCatching {
        java.time.LocalDateTime.parse(
            match.groupValues[1],
            DateTimeFormatter.ofPattern("yyMMdd_HHmmss", Locale.US)
        ).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toEpochSecond()
    }.getOrNull()
}

private fun parseDjiFilenameEpoch(name: String): Long? {
    // DJI originals encode capture time as YYYYMMDD_HHMMSS; this is more
    // reliable than the MP4 creation date when a clip was exported by Mimo.
    val match = Regex("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])_([0-2]\\d[0-5]\\d[0-5]\\d)(?!\\d)").find(name)
        ?: return null
    val stamp = "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}_${match.groupValues[4]}"
    return runCatching {
        java.time.LocalDateTime.parse(
            stamp,
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        ).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toEpochSecond()
    }.getOrNull()
}

private fun normalizeVideoRotation(degrees: Int): Int {
    val normalized = ((degrees % 360) + 360) % 360
    return listOf(0, 90, 180, 270).minByOrNull { candidate ->
        val delta = kotlin.math.abs(normalized - candidate)
        minOf(delta, 360 - delta)
    } ?: 0
}

/**
 * Read the encoded video track directly as well as the framework metadata.
 * MediaMetadataRetriever and MediaExtractor do not expose rotation identically
 * on every OEM/MP4 combination, so keep the per-track values and prefer a
 * non-zero track rotation when the retriever reports its default zero.
 */
private data class SourceVideoTrackInfo(
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int
)

private fun readSourceVideoTrackInfo(activity: OverlayActivity, uri: Uri): SourceVideoTrackInfo? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(activity, uri, null)
        for (trackIndex in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue
            val width = format.takeIf { it.containsKey(MediaFormat.KEY_WIDTH) }
                ?.getInteger(MediaFormat.KEY_WIDTH)?.takeIf { it > 0 }
            val height = format.takeIf { it.containsKey(MediaFormat.KEY_HEIGHT) }
                ?.getInteger(MediaFormat.KEY_HEIGHT)?.takeIf { it > 0 }
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            return SourceVideoTrackInfo(width, height, rotation)
        }
        null
    } catch (error: Throwable) {
        android.util.Log.w("CorosOverlayOrientation", "Không đọc được track orientation: ${error.message}")
        null
    } finally {
        runCatching { extractor.release() }
    }
}

private fun applySourceTrackOrientation(
    activity: OverlayActivity,
    uri: Uri,
    width: Int?,
    height: Int?,
    retrieverRotation: Int
): Triple<Int?, Int?, Int> {
    val track = readSourceVideoTrackInfo(activity, uri)
    val encodedWidth = track?.width ?: width
    val encodedHeight = track?.height ?: height
    val trackRotation = track?.rotationDegrees ?: 0
    // Prefer a non-zero track hint if retriever omitted it. If both APIs provide
    // a non-zero value, MediaExtractor's selected video-track format is canonical.
    val selectedRotation = when {
        trackRotation != 0 -> trackRotation
        retrieverRotation != 0 -> retrieverRotation
        else -> 0
    }
    val rotation = normalizeVideoRotation(selectedRotation)
    val displayWidth = if (rotation % 180 != 0) encodedHeight else encodedWidth
    val displayHeight = if (rotation % 180 != 0) encodedWidth else encodedHeight
    android.util.Log.i(
        "CorosOverlayOrientation",
        "source=${displayName(activity, uri)} encoded=${encodedWidth}x${encodedHeight} " +
            "retrieverRotation=${normalizeVideoRotation(retrieverRotation)} " +
            "trackRotation=${normalizeVideoRotation(trackRotation)} selectedRotation=$rotation " +
            "display=${displayWidth}x${displayHeight}"
    )
    return Triple(displayWidth, displayHeight, rotation)
}

private fun readVideoInfoFast(activity: OverlayActivity, uri: Uri): VideoInfo {
    val retriever = MediaMetadataRetriever()
    var duration: Long? = null
    var width: Int? = null
    var height: Int? = null
    var rawDate: String? = null
    var rotation = 0
    try {
        retriever.setDataSource(activity, uri)
        rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    } catch (_: Exception) {
        // Continue with the MP4 container timestamp below.
    } finally {
        runCatching { retriever.release() }
    }
    // Reconcile MediaMetadataRetriever with the actual selected video track.
    // This runs per source clip, before telemetry layout or rendering is prepared.
    val orientation = applySourceTrackOrientation(activity, uri, width, height, rotation)
    width = orientation.first
    height = orientation.second
    rotation = orientation.third
    val sourceName = displayName(activity, uri)
    val epoch = parseStudioFilenameEpoch(sourceName)
        ?: parseDjiFilenameEpoch(sourceName)
        ?: readMp4CreationTime(activity, uri)
        ?: rawDate?.let { parseVideoDate(it) }
    return VideoInfo(epoch, duration, width, height, null, rotation)
}

private fun readVideoInfo(activity: OverlayActivity, uri: Uri): VideoInfo {
    val retriever = MediaMetadataRetriever()
    var duration: Long? = null
    var width: Int? = null
    var height: Int? = null
    var rawDate: String? = null
    var rotation = 0
    try {
        retriever.setDataSource(activity, uri)
        rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    } catch (_: Exception) {
        // Continue with the MP4 container and track readers below.
    } finally {
        runCatching { retriever.release() }
    }

    // Studio filename is derived from the original DJI capture timestamp and is
    // authoritative. MP4 container/Android dates are fallbacks for other filenames.
    val sourceName = displayName(activity, uri)
    val filenameDate = parseStudioFilenameEpoch(sourceName) ?: parseDjiFilenameEpoch(sourceName)
    val containerDate = readMp4CreationTime(activity, uri)
    val epoch = filenameDate ?: containerDate ?: rawDate?.let { parseVideoDate(it) }
    val fps = readVideoFrameRate(activity, uri)
    // Use the same per-track orientation probe as the fast folder scan so the
    // initial selection and restored selection cannot disagree about portrait/rotation.
    val orientation = applySourceTrackOrientation(activity, uri, width, height, rotation)
    width = orientation.first
    height = orientation.second
    rotation = orientation.third
    return VideoInfo(epoch, duration, width, height, fps, rotation)
}

private fun readMp4CreationTime(activity: OverlayActivity, uri: Uri): Long? = runCatching {
    activity.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
            findMvhdCreationTime(channel, 0L, channel.size())
        }
    }
}.getOrNull()

private fun findMvhdCreationTime(channel: java.nio.channels.FileChannel, start: Long, end: Long): Long? {
    var position = start
    while (position + 8L <= end) {
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        val basic = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        if (!readFullyAt(channel, basic, position)) return null
        basic.flip()
        val size32 = basic.int.toLong() and 0xffffffffL
        val typeBytes = ByteArray(4)
        basic.get(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)
        var headerSize = 8L
        val boxSize = when (size32) {
            0L -> end - position
            1L -> {
                if (!readFullyAt(channel, header, position)) return null
                header.flip()
                header.position(8)
                headerSize = 16L
                header.long
            }
            else -> size32
        }
        if (boxSize < headerSize || position + boxSize > end) return null
        val payloadStart = position + headerSize
        val boxEnd = position + boxSize

        if (type == "mvhd") {
            val versionBuffer = ByteBuffer.allocate(1)
            if (!readFullyAt(channel, versionBuffer, payloadStart)) return null
            versionBuffer.flip()
            val version = versionBuffer.get().toInt() and 0xff
            val timeBuffer = ByteBuffer.allocate(if (version == 1) 8 else 4).order(ByteOrder.BIG_ENDIAN)
            if (!readFullyAt(channel, timeBuffer, payloadStart + 4L)) return null
            timeBuffer.flip()
            val mp4Seconds = if (version == 1) timeBuffer.long else timeBuffer.int.toLong() and 0xffffffffL
            val unixSeconds = mp4Seconds - 2_082_844_800L
            return unixSeconds.takeIf { it > 0L }
        }
        if (type == "moov") {
            findMvhdCreationTime(channel, payloadStart, boxEnd)?.let { return it }
        }
        position = boxEnd
    }
    return null
}

private fun readFullyAt(channel: java.nio.channels.FileChannel, buffer: ByteBuffer, position: Long): Boolean {
    var offset = position
    while (buffer.hasRemaining()) {
        val count = channel.read(buffer, offset)
        if (count <= 0) return false
        offset += count.toLong()
    }
    return true
}

private fun readVideoFrameRate(activity: OverlayActivity, uri: Uri): String? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(activity, uri, null)
        val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return null
        val format = extractor.getTrackFormat(videoTrack)
        val declaredRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
        } else null

        // Count video samples and use their presentation timestamps for a more precise
        // rate (e.g. 60000/1001 = 59.940), rather than Android's rounded integer hint.
        extractor.selectTrack(videoTrack)
        var sampleCount = 0L
        var firstUs = -1L
        var lastUs = -1L
        while (extractor.sampleTrackIndex >= 0) {
            val timeUs = extractor.sampleTime
            if (timeUs >= 0L) {
                if (firstUs < 0L) firstUs = timeUs
                lastUs = timeUs
                sampleCount++
            }
            if (!extractor.advance()) break
        }
        val measured = if (sampleCount > 1L && lastUs > firstUs) {
            (sampleCount - 1L).toDouble() * 1_000_000.0 / (lastUs - firstUs).toDouble()
        } else null
        val rate = measured ?: declaredRate ?: return null
        String.format(Locale.US, "%.3f FPS", rate)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}

private fun parseVideoDate(raw: String): Long? {
    val value = raw.trim()
    runCatching { Instant.parse(value).epochSecond }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(value).toEpochSecond() }.getOrNull()?.let { return it }
    val normalized = value.substringBefore("Z").substringBefore("+").substringBefore(".")
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss", "yyyy:MM:dd HH:mm:ss")
    for (pattern in patterns) {
        val parsed = runCatching {
            java.time.LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern(pattern, Locale.US))
                .atZone(ZoneId.of("UTC")).toEpochSecond()
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

@Composable
private fun VideoInfoCard(info: VideoInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("THÔNG TIN VIDEO ĐÃ ĐỌC", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            MetricRow("Thời điểm quay", info.epochSeconds?.let { formatTime(it) } ?: "Chưa nhận dạng")
            MetricRow("Thời lượng", info.durationMs?.let { String.format(Locale.US, "%d:%02d", it / 60000, (it / 1000) % 60) } ?: "Chưa có")
            MetricRow("Độ phân giải", if (info.width != null && info.height != null) "${info.width} × ${info.height}" else "Chưa có")
            MetricRow("FPS", info.fps ?: "Chưa có")
        }
    }
}

private fun formatOffset(seconds: Long): String {
    val sign = if (seconds < 0) "−" else "+"
    val value = kotlin.math.abs(seconds)
    return "$sign${value / 60}m ${value % 60}s"
}

private fun formatTime(epoch: Long): String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    .withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(Instant.ofEpochSecond(epoch))

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionCard(number: String, title: String, filename: String, description: String, action: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("$number  $title", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(filename, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}
