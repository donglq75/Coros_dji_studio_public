package com.voiceguard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.corosdjistudio.StudioTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class VgTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val timeline = try {
            val file = intent.getStringExtra("shared_timeline_path")?.let(::File)
            val array = JSONArray(intent.getStringExtra("shared_timeline_spans") ?: "[]")
            val spans = buildList {
                for (i in 0 until array.length()) {
                    val s = array.optJSONObject(i) ?: continue
                    val uri = s.optString("uri")
                    val name = s.optString("name")
                    if (uri.isNotBlank() && name.isNotBlank()) add(
                        VoiceTimelineSpan(uri, name, s.optDouble("clipStart").toFloat(),
                            s.optDouble("timelineStart").toFloat(), s.optDouble("timelineEnd").toFloat())
                    )
                }
            }
            if (file != null && file.exists() && spans.isNotEmpty()) VoiceTimeline(
                file, spans, intent.getFloatExtra("shared_timeline_duration", 0f),
                spans.map { it.clipUri }.distinct().size,
                intent.getFloatExtra("shared_timeline_voice_sec", 0f), FloatArray(240)
            ) else null
        } catch (_: Throwable) { null }
        setContent {
            StudioTheme {
                Surface(Modifier.fillMaxSize()) {
                    VgTestScreen(timeline, intent.getStringExtra("studio_root_tree") ?: "",
                        intent.getStringExtra("studio_folder_document_id"))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VgTestScreen(timeline: VoiceTimeline?, rootTree: String, folderId: String?) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var asrJson by remember { mutableStateOf("") }
    var selectedMaskWords by remember { mutableStateOf<List<WordMaskRange>>(emptyList()) }
    LaunchedEffect(asrJson) {
        if (asrJson.isNotBlank()) selectedMaskWords = loadSavedMaskWords(context, asrJson)
    }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(timeline?.file?.absolutePath) {
        player.stop()
        playing = false
        if (timeline != null && timeline.file.exists()) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(timeline.file)))
            player.prepare()
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("VG Phụ đề", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                OutlinedButton(onClick = { (context as? android.app.Activity)?.finish() }) { Text("← VG") }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Âm thanh đã gom", fontWeight = FontWeight.Bold)
                    if (timeline == null) {
                        Text("Chưa có âm thanh. Quay lại VoiceGuard và chọn Tạo chuỗi.",
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("${timeline.voiceClipCount} video · voice ${"%.0f".format(java.util.Locale.US, timeline.voiceSec)}s · tổng ${"%.0f".format(java.util.Locale.US, timeline.durationSec)}s")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (player.isPlaying) player.pause() else player.play() },
                                modifier = Modifier.weight(1f)) { Text(if (playing) "Ⅱ Dừng" else "▶ Nghe") }
                            OutlinedButton(onClick = { player.seekTo(0); player.play() },
                                modifier = Modifier.weight(1f)) { Text("Từ đầu") }
                        }
                    }
                }
            }
            AsrPanel(sharedWavFile = timeline?.file, onResultJson = { asrJson = it })
            if (asrJson.isNotBlank()) {
                WordMaskEditor(asrJson, selectedMaskWords) { updated ->
                    selectedMaskWords = updated
                    saveMaskWords(context, asrJson, updated)
                }
                WordMaskExportPanel(timeline, rootTree, folderId, selectedMaskWords)
            }
            Spacer(Modifier.height(8.dp))
            Text("Chọn các từ cần che trong phụ đề, rồi xuất video.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun loadSavedMaskWords(context: android.content.Context, sourceJson: String): List<WordMaskRange> {
    return try {
        val prefs = context.getSharedPreferences("vg_subtitle_mask_selection", android.content.Context.MODE_PRIVATE)
        if (prefs.getString("source_hash", null) != sourceJson.hashCode().toString()) return emptyList()
        val array = JSONArray(prefs.getString("selected_words", "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optDouble("start", Double.NaN)
                val end = item.optDouble("end", Double.NaN)
                val word = item.optString("word", "")
                if (start.isFinite() && end.isFinite() && end > start && word.isNotBlank())
                    add(WordMaskRange(start.toFloat(), end.toFloat(), word))
            }
        }
    } catch (_: Throwable) { emptyList() }
}

private fun saveMaskWords(context: android.content.Context, sourceJson: String, words: List<WordMaskRange>) {
    val array = JSONArray()
    words.forEach { array.put(JSONObject().put("start", it.startSec).put("end", it.endSec).put("word", it.word)) }
    context.getSharedPreferences("vg_subtitle_mask_selection", android.content.Context.MODE_PRIVATE)
        .edit().putString("source_hash", sourceJson.hashCode().toString())
        .putString("selected_words", array.toString()).apply()
}
