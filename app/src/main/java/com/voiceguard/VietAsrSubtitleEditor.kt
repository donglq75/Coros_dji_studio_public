package com.voiceguard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

private data class EditableSubtitleSegment(
    val startSec: Double,
    val endSec: Double,
    val text: String
)

@Composable
fun VietAsrSubtitleEditor(jsonText: String) {
    val context = LocalContext.current
    var editorExpanded by remember(jsonText) { androidx.compose.runtime.mutableStateOf(false) }
    val segments = remember(jsonText) {
        mutableStateListOf<EditableSubtitleSegment>().apply {
            addAll(extractSubtitleSegments(jsonText))
        }
    }
    val createSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) {
            val srt = buildString {
                segments.forEachIndexed { index, segment ->
                    append(index + 1).append('\n')
                    append(formatSrtTime(segment.startSec)).append(" --> ")
                    append(formatSrtTime(segment.endSec)).append('\n')
                    append(segment.text.trim()).append("\n\n")
                }
            }
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(srt.toByteArray(Charsets.UTF_8))
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("4. Biên tập phụ đề VietASR", fontWeight = FontWeight.Bold)
            if (segments.isEmpty()) {
                Text(
                    "JSON hiện chưa có mảng segments chứa text/start/end. Vẫn có thể lưu JSON gốc để kiểm tra cấu trúc.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "${segments.size} câu · mốc thời gian được giữ nguyên khi sửa chữ.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = { editorExpanded = !editorExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (editorExpanded) "Thu gọn biên tập phụ đề" else "Mở biên tập ${segments.size} câu")
                }
                if (editorExpanded) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(segments) { index, segment ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        "#${index + 1}  ${formatSrtTime(segment.startSec)} → ${formatSrtTime(segment.endSec)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    OutlinedTextField(
                                        value = segment.text,
                                        onValueChange = { updated ->
                                            segments[index] = segment.copy(text = updated)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 1,
                                        maxLines = 4,
                                        label = { Text("Nội dung phụ đề") }
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { createSrt.launch("vietasr_subtitles.srt") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Xuất phụ đề SRT đã biên tập")
                    }
                }
            }
        }
    }
}

private fun extractSubtitleSegments(jsonText: String): List<EditableSubtitleSegment> {
    return try {
        val root = JSONObject(jsonText)
        findSegments(root, 0)
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun findSegments(value: JSONObject, depth: Int): List<EditableSubtitleSegment> {
    if (depth > 10) return emptyList()
    val collected = mutableListOf<EditableSubtitleSegment>()
    val direct = value.optJSONArray("segments")
    if (direct != null) collected.addAll(parseSegmentArray(direct))
    val keys = value.keys()
    while (keys.hasNext()) {
        when (val child = value.opt(keys.next())) {
            is JSONObject -> collected.addAll(findSegments(child, depth + 1))
            is JSONArray -> for (i in 0 until child.length()) {
                val item = child.optJSONObject(i) ?: continue
                collected.addAll(findSegments(item, depth + 1))
            }
        }
    }
    return collected.distinctBy { Triple(it.startSec, it.endSec, it.text) }.sortedBy { it.startSec }
}

private fun parseSegmentArray(array: JSONArray): List<EditableSubtitleSegment> {
    val result = mutableListOf<EditableSubtitleSegment>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val text = item.optString("text", "").trim()
        if (text.isBlank() || !item.has("start") || !item.has("end")) continue
        val start = item.optDouble("start", Double.NaN)
        val end = item.optDouble("end", Double.NaN)
        if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) continue
        result += EditableSubtitleSegment(start, end, text)
    }
    return result
}

private fun formatSrtTime(seconds: Double): String {
    val millis = (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    val hours = millis / 3_600_000L
    val minutes = (millis % 3_600_000L) / 60_000L
    val secs = (millis % 60_000L) / 1_000L
    val ms = millis % 1_000L
    return "%02d:%02d:%02d,%03d".format(hours, minutes, secs, ms)
}
