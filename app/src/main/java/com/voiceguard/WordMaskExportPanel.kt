package com.voiceguard

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

@Composable
fun WordMaskExportPanel(timeline: VoiceTimeline?, rootTreeUri: String, folderDocumentId: String?, selectedWords: List<WordMaskRange>) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("vg_subtitle_mask", android.content.Context.MODE_PRIVATE) }
    val scopeKey = remember(rootTreeUri, folderDocumentId) { rootTreeUri + "|" + (folderDocumentId ?: "root") }
    var musicUri by remember(scopeKey) { mutableStateOf(prefs.getString("music:$scopeKey", null)?.let(Uri::parse)) }
    var musicName by remember(scopeKey) { mutableStateOf(prefs.getString("music_name:$scopeKey", null) ?: "Chưa chọn âm thanh che") }
    var status by remember(scopeKey) { mutableStateOf("") }
    var workerRunning by remember(scopeKey) { mutableStateOf(false) }
    var queueNeedsRetry by remember(scopeKey) { mutableStateOf(false) }
    var submitting by remember(scopeKey) { mutableStateOf(false) }
    var recentlySubmitted by remember(scopeKey) { mutableStateOf(false) }
    var workLabel by remember(scopeKey) { mutableStateOf("") }
    var workProgress by remember(scopeKey) { mutableStateOf("") }
    var workError by remember(scopeKey) { mutableStateOf("") }

    // v4 starts with a clean unique-work identity. Old pending requests are retired,
    // but an actually RUNNING worker is never cancelled and keeps its checkpoints.
    val legacyWorkNames = remember(scopeKey) {
        listOf(
            "vg-subtitle-mask-${scopeKey.hashCode()}",
            "vg-subtitle-mask-v2-${scopeKey.hashCode()}",
            "vg-subtitle-mask-v3-${scopeKey.hashCode()}"
        )
    }
    val uniqueWorkName = remember(scopeKey) { "vg-subtitle-mask-v4-${scopeKey.hashCode()}" }

    LaunchedEffect(legacyWorkNames) {
        withContext(Dispatchers.IO) {
            val wm = WorkManager.getInstance(context)
            legacyWorkNames.forEach { oldName ->
                runCatching {
                    val oldInfo = wm.getWorkInfosForUniqueWork(oldName).get().firstOrNull()
                    if (oldInfo?.state == WorkInfo.State.ENQUEUED || oldInfo?.state == WorkInfo.State.BLOCKED) {
                        wm.cancelUniqueWork(oldName).result.get()
                    }
                }
            }
        }
    }

    // ENQUEUED is not reported as running: the user can explicitly retry that request.
    LaunchedEffect(uniqueWorkName) {
        val wm = WorkManager.getInstance(context)
        while (true) {
            val info = runCatching {
                withContext(Dispatchers.IO) {
                    wm.getWorkInfosForUniqueWork(uniqueWorkName).get().firstOrNull()
                }
            }.getOrNull()
            if (info != null) {
                workerRunning = info.state == WorkInfo.State.RUNNING
                queueNeedsRetry = info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED
                val done = info.progress.getInt("done", -1)
                val total = info.progress.getInt("total", -1)
                workProgress = if (done >= 0 && total > 0) "$done/$total video" else ""
                workError = info.outputData.getString("error").orEmpty()
                workLabel = when (info.state) {
                    WorkInfo.State.ENQUEUED -> "Tác vụ đang nằm chờ, chưa bắt đầu."
                    WorkInfo.State.RUNNING -> if (workProgress.isNotBlank()) "Đang xử lý · $workProgress" else "Worker đang chạy…"
                    WorkInfo.State.BLOCKED -> "Tác vụ đang bị chặn, có thể thử khởi chạy lại."
                    WorkInfo.State.SUCCEEDED -> "Đã hoàn tất · $workProgress"
                    WorkInfo.State.FAILED -> "Tác vụ thất bại"
                    WorkInfo.State.CANCELLED -> "Tác vụ đã bị hủy"
                }
            } else {
                workerRunning = false
                queueNeedsRetry = false
                workLabel = ""
                workProgress = ""
                workError = ""
            }
            delay(1000)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            musicUri = it
            musicName = it.lastPathSegment?.substringAfterLast('/') ?: "Âm thanh che"
            prefs.edit().putString("music:$scopeKey", it.toString()).putString("music_name:$scopeKey", musicName).apply()
        }
    }
    val missing = buildList {
        if (selectedWords.isEmpty()) add("chưa có từ được đánh dấu")
        if (musicUri == null) add("chưa chọn âm thanh che")
        if (timeline == null) add("chưa có timeline WAV từ bước 2")
        if (rootTreeUri.isBlank()) add("chưa có quyền/thư mục video gốc")
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Xuất video", fontWeight = FontWeight.Bold)
            Text("Tiến trình chạy nền; có thể tắt màn hình. Mỗi video hoàn tất sẽ được lưu checkpoint để lần sau tiếp tục.", style = MaterialTheme.typography.bodySmall)
            Text(musicName, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Chọn âm thanh che") }
            Text("${selectedWords.size} từ được chọn", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = missing.isEmpty() && !workerRunning && !submitting && !(queueNeedsRetry && recentlySubmitted),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val tl = timeline ?: return@Button
                    val music = musicUri ?: return@Button
                    submitting = true
                    status = if (queueNeedsRetry) "Đang phục hồi yêu cầu đang chờ…" else "Đang gửi yêu cầu xuất…"
                    coroutineScope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val wm = WorkManager.getInstance(context)
                                val existing = wm.getWorkInfosForUniqueWork(uniqueWorkName).get().firstOrNull()
                                if (existing?.state == WorkInfo.State.RUNNING) return@withContext "RUNNING"

                                // Only retire a request confirmed not to have started. Never replace RUNNING.
                                if (existing?.state == WorkInfo.State.ENQUEUED || existing?.state == WorkInfo.State.BLOCKED) {
                                    wm.cancelUniqueWork(uniqueWorkName).result.get()
                                }

                                val spans = JSONArray().apply {
                                    tl.spans.forEach { s ->
                                        put(JSONObject()
                                            .put("uri", s.clipUri).put("name", s.clipName)
                                            .put("clipStart", s.clipStartSec)
                                            .put("timelineStart", s.timelineStartSec)
                                            .put("timelineEnd", s.timelineEndSec))
                                    }
                                }
                                val words = JSONArray().apply {
                                    selectedWords.forEach { w ->
                                        put(JSONObject().put("start", w.startSec).put("end", w.endSec).put("word", w.word))
                                    }
                                }
                                val session = JSONObject()
                                    .put("tree", rootTreeUri)
                                    .put("folderId", folderDocumentId ?: "")
                                    .put("musicUri", music.toString())
                                    .put("spans", spans)
                                    .put("words", words)
                                val file = File(context.filesDir, "subtitle_mask_${scopeKey.hashCode()}.json")
                                val fingerprint = (rootTreeUri + folderDocumentId + music + words.toString() + spans.toString()).hashCode().toString()
                                val old = runCatching { JSONObject(file.readText()) }.getOrNull()
                                if (old?.optString("fingerprint") == fingerprint && !old.optBoolean("finished")) {
                                    session.put("completed", old.optJSONArray("completed") ?: JSONArray())
                                } else {
                                    session.put("completed", JSONArray())
                                }
                                session.put("fingerprint", fingerprint)
                                file.writeText(session.toString())

                                val request = OneTimeWorkRequestBuilder<SubtitleMaskWorker>()
                                    .setInputData(workDataOf(SubtitleMaskWorker.KEY_SESSION to file.absolutePath))
                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                                    .build()
                                wm.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.REPLACE, request).result.get()
                                "QUEUED"
                            }

                            when (result) {
                                "RUNNING" -> {
                                    status = "Tác vụ đã chạy; mình không tạo tác vụ trùng."
                                    Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                }
                                else -> {
                                    status = "Đã gửi lại yêu cầu. Đang chờ worker khởi chạy…"
                                    recentlySubmitted = true
                                    coroutineScope.launch {
                                        delay(45000)
                                        recentlySubmitted = false
                                    }
                                    Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Throwable) {
                            status = "Không thể khởi chạy: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                        } finally {
                            submitting = false
                        }
                    }
                }
            ) {
                Text(
                    when {
                        workerRunning -> "Đang chạy…"
                        submitting -> "Đang gửi…"
                        queueNeedsRetry -> "Thử khởi chạy lại"
                        else -> "Xuất video (chạy nền)"
                    }
                )
            }
            if (workLabel.isNotBlank()) {
                Text(workLabel, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                if (workProgress.isNotBlank()) Text("Tiến độ: $workProgress", style = MaterialTheme.typography.bodySmall)
            }
            if (workError.isNotBlank()) Text("Chi tiết lỗi: $workError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (missing.isNotEmpty()) Text("Cần hoàn tất: ${missing.joinToString("; ")}.", color = MaterialTheme.colorScheme.error)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
