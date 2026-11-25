package com.voiceguard

import android.app.Activity
import com.corosdjistudio.BatchReviewActivity
import com.corosdjistudio.StudioTheme
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

class VoiceGuardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val tree = intent.getStringExtra("studio_root_tree")
            val folder = intent.getStringExtra("studio_folder_document_id")
            LaunchedEffect(tree, folder) { if (!tree.isNullOrBlank()) vm.setTree(tree, folder) }
            StudioTheme { Surface(Modifier.fillMaxSize()) { StudioVoiceReview(vm, studioFolderLocked = !folder.isNullOrBlank()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioVoiceReview(vm: MainViewModel, studioFolderLocked: Boolean) {
    val context = LocalContext.current
    val hostActivity = context as? Activity
    LaunchedEffect(studioFolderLocked) { if (studioFolderLocked) vm.maxClips = 0 }
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF } }
    val timeline = vm.voiceTimeline
    var playing by remember { mutableStateOf(false) }
    var playhead by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var markStart by remember { mutableStateOf<Float?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.setTree(it.toString())
        }
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.setMusic(it)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) duration = player.duration.coerceAtLeast(0L) / 1000f
                if (state == Player.STATE_ENDED) playing = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    LaunchedEffect(timeline) {
        player.stop()
        playhead = 0f
        duration = timeline?.durationSec ?: 0f
        playing = false
        markStart = null
        if (timeline != null && timeline.file.exists()) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(timeline.file)))
            player.prepare()
            player.playbackParameters = PlaybackParameters(speed)
        }
    }

    LaunchedEffect(player, timeline) {
        while (true) {
            if (timeline != null) {
                playhead = player.currentPosition.coerceAtLeast(0L) / 1000f
                if (player.duration > 0L) duration = player.duration / 1000f
                playing = player.isPlaying
            }
            delay(100)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VoiceGuard", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { hostActivity?.finish() }) { Text("← Studio") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${vm.clips.count { it.voiceSec > 0f }} video có voice", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (!studioFolderLocked) {
                            TextButton(onClick = { folderPicker.launch(null) }) { Text("Thư mục") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Voice ≥", fontSize = 12.sp)
                        listOf(1f, 2f, 3f).forEach { seconds ->
                            FilterChip(
                                selected = vm.minimumVoiceSeconds == seconds,
                                onClick = { vm.updateMinimumVoiceSeconds(seconds) },
                                label = { Text("${seconds.toInt()}s") }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = vm::scan, enabled = !vm.scanning && !vm.masking && !vm.timelineBuilding && vm.treeUri.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(if (vm.scanning) "Đang quét…" else "Quét", maxLines = 1, softWrap = false)
                        }
                    }
                    if (vm.scanning) {
                        LinearProgressIndicator(
                            progress = { if (vm.scanTotal > 0) vm.scanDone.toFloat() / vm.scanTotal else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${vm.scanDone}/${vm.scanTotal}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Nghe liên tục", fontWeight = FontWeight.Bold)
                            Text(
                                timeline?.let { "${it.voiceClipCount} video · ${timeText(it.voiceSec)} âm thanh" }
                                    ?: "Gom voice, bỏ đoạn im lặng",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = vm::buildVoiceTimeline,
                            enabled = !vm.scanning && !vm.masking && !vm.timelineBuilding &&
                                vm.clips.any { it.voiceSec > 0f && it.status != ClipStatus.MASKED && it.status != ClipStatus.ARCHIVED }
                        ) { Text(if (vm.timelineBuilding) "Đang nối…" else "Tạo chuỗi") }
                    }
                    Button(
                        onClick = {
                            val current = vm.voiceTimeline ?: return@Button
                            val spans = org.json.JSONArray()
                            current.spans.forEach { span ->
                                spans.put(org.json.JSONObject().put("uri", span.clipUri).put("name", span.clipName)
                                    .put("clipStart", span.clipStartSec).put("timelineStart", span.timelineStartSec)
                                    .put("timelineEnd", span.timelineEndSec))
                            }
                            context.startActivity(Intent(context, VgTestActivity::class.java)
                                .putExtra("studio_root_tree", vm.treeUri)
                                .putExtra("studio_folder_document_id", vm.folderDocumentId)
                                .putExtra("shared_timeline_path", current.file.absolutePath)
                                .putExtra("shared_timeline_spans", spans.toString())
                                .putExtra("shared_timeline_duration", current.durationSec)
                                .putExtra("shared_timeline_voice_sec", current.voiceSec))
                        },
                        enabled = !vm.scanning && !vm.masking && !vm.timelineBuilding && vm.voiceTimeline != null,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 15.dp)
                    ) { Text("Mở VG Phụ đề", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Bỏ im lặng >", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(5f, 10f, 15f).forEach { seconds ->
                            FilterChip(
                                selected = vm.silenceThresholdSeconds == seconds,
                                onClick = { vm.updateSilenceThreshold(seconds) },
                                enabled = !vm.timelineBuilding,
                                label = { Text("${seconds.toInt()}s") }
                            )
                        }
                    }
                    if (vm.timelineBuilding) {
                        LinearProgressIndicator(
                            progress = { if (vm.timelineTotal > 0) vm.timelineDone.toFloat() / vm.timelineTotal else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${vm.timelineDone}/${vm.timelineTotal}  ·  ${vm.currentName}", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (timeline == null) {
                        Text("Quét video trước, rồi tạo chuỗi để nghe liên tục.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        StudioWaveform(
                            timeline.waveform, duration.coerceAtLeast(timeline.durationSec),
                            vm.timelineMarks, playhead, Modifier.fillMaxWidth().height(76.dp)
                        ) { sec ->
                            player.seekTo((sec * 1000f).toLong())
                            playhead = sec
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(timeText(playhead), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text(timeText(timeline.durationSec), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { player.seekTo((player.currentPosition - 5000L).coerceAtLeast(0L)) },
                                modifier = Modifier.weight(1f)
                            ) { Text("−5s") }
                            Button(
                                onClick = { if (player.isPlaying) player.pause() else player.play() },
                                modifier = Modifier.weight(1.4f)
                            ) { Text(if (playing) "Ⅱ Dừng" else "▶ Nghe") }
                            OutlinedButton(
                                onClick = { player.seekTo((player.currentPosition + 5000L).coerceAtMost((timeline.durationSec * 1000).toLong())) },
                                modifier = Modifier.weight(1f)
                            ) { Text("+5s") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { player.seekTo((player.currentPosition - 500L).coerceAtLeast(0L)) },
                                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)
                            ) { Text("−0,5s") }
                            Spacer(Modifier.weight(1.4f))
                            OutlinedButton(
                                onClick = { player.seekTo((player.currentPosition + 500L).coerceAtMost((timeline.durationSec * 1000).toLong())) },
                                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)
                            ) { Text("+0,5s") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tốc độ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf(1f, 1.5f, 2f).forEach { v ->
                                FilterChip(selected = speed == v,
                                    onClick = { speed = v; player.playbackParameters = PlaybackParameters(v) },
                                    label = { Text("${v}×") })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { if (!playing) markStart = playhead },
                                enabled = !playing,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (markStart == null) "Mốc đầu" else "Đầu: ${timeText(markStart!!)}")
                            }
                            Button(
                                onClick = {
                                    val start = markStart
                                    if (start != null && playhead > start + 0.08f) {
                                        vm.markTimelineRange(start, playhead)
                                        markStart = null
                                    }
                                },
                                enabled = !playing && markStart != null && playhead > (markStart ?: playhead) + 0.08f,
                                modifier = Modifier.weight(1f)
                            ) { Text("Mốc cuối") }
                        }
                        if (markStart != null && playhead <= markStart!! + 0.08f) {
                            Text("Mốc cuối phải sau mốc đầu.",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        } else if (markStart != null) {
                            Text("Đã chọn ${timeText(markStart!!)}–${timeText(playhead)} · chạm Mốc đầu để sửa",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "${vm.timelineMarks.size} đoạn đã chọn · tự ghép về video gốc khi xuất",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { musicPicker.launch(arrayOf("audio/*")) },
                    enabled = !vm.masking,
                    modifier = Modifier.weight(1.25f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (vm.musicName.isBlank()) "Âm thanh che" else "Đổi âm thanh",
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Button(
                    onClick = vm::maskSensitive,
                    enabled = !vm.scanning && !vm.masking && !vm.timelineBuilding &&
                        vm.clips.any { it.status == ClipStatus.SENSITIVE } && vm.musicUri != null,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Text(if (vm.masking) "Đang xuất…" else "Xuất video", maxLines = 1, softWrap = false)
                }
            }
            if (vm.masking) {
                LinearProgressIndicator(
                    progress = { if (vm.scanTotal > 0) vm.scanDone.toFloat() / vm.scanTotal else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${vm.scanDone}/${vm.scanTotal}  ·  ${vm.currentName}", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!vm.masking && vm.recentMaskedUris.isNotEmpty()) {
                OutlinedButton(onClick = {
                    val intent = Intent(context, BatchReviewActivity::class.java).apply {
                        putStringArrayListExtra(BatchReviewActivity.EXTRA_URIS, ArrayList(vm.recentMaskedUris.map { it.second }))
                        putStringArrayListExtra(BatchReviewActivity.EXTRA_NAMES, ArrayList(vm.recentMaskedUris.map { it.first }))
                    }
                    context.startActivity(intent)
                }, modifier = Modifier.fillMaxWidth()) { Text("Xem video đã xuất (${vm.recentMaskedUris.size})") }
            }
            if (vm.message.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(vm.message, Modifier.padding(12.dp), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, count: Int, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text("$label: $count", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StudioWaveform(data: FloatArray, duration: Float, segments: List<VoiceSegment>, position: Float, modifier: Modifier = Modifier, onSeek: (Float) -> Unit) {
    Canvas(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(duration) {
                if (duration > 0f) detectTapGestures { p -> onSeek((p.x / size.width.coerceAtLeast(1) * duration).coerceIn(0f, duration)) }
            }
            .pointerInput(duration) {
                if (duration > 0f) detectDragGestures(
                    onDragStart = { p -> onSeek((p.x / size.width.coerceAtLeast(1) * duration).coerceIn(0f, duration)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onSeek((change.position.x / size.width.coerceAtLeast(1) * duration).coerceIn(0f, duration))
                    }
                )
            }
    ) {
        if (duration <= 0f || data.isEmpty()) return@Canvas
        val step = size.width / data.size
        data.forEachIndexed { i, amp ->
            val h = (amp.coerceIn(0f, 1f) * size.height * 0.9f).coerceAtLeast(1.5f)
            val x = i * step + step / 2f
            drawLine(Color(0xFF16845B), Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f), strokeWidth = max(1f, step * 0.68f), cap = StrokeCap.Round)
        }
        // Vùng đã chọn được tô sau waveform để luôn nổi bật, không bị các cột sóng che mất.
        segments.forEach { seg ->
            val l = (seg.startSec / duration * size.width).coerceIn(0f, size.width)
            val r = (seg.endSec / duration * size.width).coerceIn(l, size.width)
            if (r > l) {
                drawRect(Color(0x99FF8A00), Offset(l, 0f), Size(r - l, size.height))
                drawLine(Color(0xFFFF6D00), Offset(l, 0f), Offset(l, size.height), strokeWidth = 2.dp.toPx())
                drawLine(Color(0xFFFF6D00), Offset(r, 0f), Offset(r, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        val x = (position / duration * size.width).coerceIn(0f, size.width)
        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        drawLine(Color.Black, Offset(x + 2f, 0f), Offset(x + 2f, size.height), strokeWidth = 1.dp.toPx())
    }
}

private fun timeText(sec: Float): String {
    val s = sec.coerceAtLeast(0f)
    return "%02d:%04.1f".format((s / 60).toInt(), s % 60)
}
