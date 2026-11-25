package com.voiceguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class WordMaskRange(val startSec: Float, val endSec: Float, val word: String)

private data class AsrWord(val start: Float, val end: Float, val word: String)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WordMaskEditor(
    jsonText: String,
    initiallySelected: List<WordMaskRange> = emptyList(),
    onSelectionChanged: (List<WordMaskRange>) -> Unit
) {
    val allWords = remember(jsonText) { extractAsrWords(jsonText) }
    val words = allWords
    val selected = remember(jsonText, initiallySelected) {
        mutableStateListOf<Int>().apply {
            words.forEachIndexed { index, word ->
                if (initiallySelected.any {
                    it.word == word.word &&
                        kotlin.math.abs(it.startSec - word.start) < 0.02f &&
                        kotlin.math.abs(it.endSec - word.end) < 0.02f
                }) add(index)
            }
        }
    }

    fun publishSelection() {
        onSelectionChanged(selected.sorted().map { index ->
            val word = words[index]
            WordMaskRange(word.start, word.end, word.word)
        })
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("5. Chọn từ / đoạn cần che", fontWeight = FontWeight.Bold)
            if (allWords.isEmpty()) {
                Text(
                    "JSON chưa có timestamp từng từ hợp lệ. Hãy giữ JSON để kiểm tra; không thể xuất che theo từ khi thiếu mốc thời gian.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Đã nhận dạng " + words.size + " từ · đã chọn " + selected.size + " từ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = {
                        selected.clear()
                        selected.addAll(words.indices.toList())
                        publishSelection()
                    }) { Text("Chọn tất cả") }
                    Button(onClick = {
                        selected.clear()
                        publishSelection()
                    }) { Text("Bỏ chọn") }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    words.forEachIndexed { index, word ->
                        val isSelected = index in selected
                        Text(
                            text = word.word,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) Color(0xFFFFD54F)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .then(
                                    if (isSelected) Modifier.border(
                                        width = 1.dp,
                                        color = Color(0xFFFFA000),
                                        shape = RoundedCornerShape(8.dp)
                                    ) else Modifier
                                )
                                .clickable {
                                    if (isSelected) selected.remove(index) else selected.add(index)
                                    publishSelection()
                                }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            color = if (isSelected) Color(0xFF3E2723)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Text(
                    "Chạm vào từng từ để bật/tắt chọn che. Từ được tô nền là từ sẽ bị thay âm thanh.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun extractAsrWords(jsonText: String): List<AsrWord> {
    return try {
        val root = JSONObject(jsonText)
        val arrays = mutableListOf<JSONArray>()
        collectWordArrays(root, 0, arrays)
        arrays.flatMap { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val word = item.optString("word", item.optString("text", "")).trim()
                    val start = item.optDouble("start", Double.NaN)
                    val end = item.optDouble("end", Double.NaN)
                    if (word.isNotBlank() && start.isFinite() && end.isFinite() && start >= 0 && end > start) {
                        add(AsrWord(start.toFloat(), end.toFloat(), word))
                    }
                }
            }
        }.distinctBy { Triple(it.start, it.end, it.word) }.sortedBy { it.start }
    } catch (_: Throwable) { emptyList() }
}

private fun collectWordArrays(value: JSONObject, depth: Int, result: MutableList<JSONArray>) {
    if (depth > 10) return
    val direct = value.optJSONArray("words")
    if (direct != null && direct.length() > 0) result.add(direct)
    val keys = value.keys()
    while (keys.hasNext()) {
        when (val child = value.opt(keys.next())) {
            is JSONObject -> collectWordArrays(child, depth + 1, result)
            is JSONArray -> for (i in 0 until child.length()) {
                val item = child.optJSONObject(i) ?: continue
                collectWordArrays(item, depth + 1, result)
            }
        }
    }
}
