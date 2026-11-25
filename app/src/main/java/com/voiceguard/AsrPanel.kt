package com.voiceguard

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.vietasr.Pipeline
import io.vietasr.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val VIETASR_MODEL_DIR = "viet-asr-int8-46adacf"
private const val VIETASR_BASE_URL = "https://huggingface.co/dangvansam/viet-asr/resolve/46adacf22d379e3d6b860d5d08e9fb02cbc122ea/int8/"
private data class ModelFile(val name: String, val size: Long, val sha256: String)
private val VIETASR_FILES = listOf(
    ModelFile("encoder.int8.onnx", 72828389L, "571b7801b887b1ec65630ef3adcf6c3fed610ccdaf4b8c4f8af9d2bbe558331d"),
    ModelFile("decoder.int8.onnx", 4478997L, "7158b5a057fc56a03df5a95ebc5307d5a58bd67c3180b80c4068056104a9d41c"),
    ModelFile("joiner.int8.onnx", 4228494L, "857ed7495ac0d7b109fe881a45ed56e0b54de279adc35c9364bfc721358f599a"),
    ModelFile("tokens.txt", 106912L, "bcf511255ff5a63c6572885accac84265203fba0be304b7243cb912c3975d1e4")
)

@Composable
fun AsrPanel(sharedWavFile: File? = null, onResultJson: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wavUri by remember { mutableStateOf<Uri?>(null) }
    var wavName by remember { mutableStateOf("Chưa có âm thanh") }
    var modelReady by remember { mutableStateOf(modelIsReady(context)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val restoredResult = remember { loadSavedAsrResult(context) }
    var status by remember { mutableStateOf(
        when {
            restoredResult != null -> "Đã khôi phục bản kết quả gần nhất (có thể chưa gồm câu cuối)."
            modelReady -> "VietASR đã sẵn sàng trên máy."
            else -> "Cần tải VietASR lần đầu (khoảng 82 MB)."
        }
    ) }
    var transcript by remember { mutableStateOf(restoredResult?.first ?: "") }
    var resultJson by remember { mutableStateOf(restoredResult?.second ?: "") }
    var elapsed by remember { mutableStateOf("") }
    var errorDetails by remember { mutableStateOf("") }

    LaunchedEffect(sharedWavFile?.absolutePath) {
        val shared = sharedWavFile
        if (shared != null && shared.exists()) {
            wavUri = Uri.fromFile(shared)
            wavName = shared.name
            transcript = ""
            resultJson = ""
            transcript = ""
            resultJson = ""
            progress = 0f
            status = checkpointStatus(context, shared) ?: "Đã nạp âm thanh từ Studio."
        }
    }

    LaunchedEffect(resultJson) { if (resultJson.isNotBlank()) onResultJson(resultJson) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            wavUri = it
            wavName = it.lastPathSegment?.substringAfterLast('/') ?: "WAV đã chọn"
            transcript = ""
            resultJson = ""
            transcript = ""
            resultJson = ""
            progress = 0f
            status = "Đã chọn WAV. Có thể bắt đầu nhận dạng."
        }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && resultJson.isNotBlank()) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(resultJson.toByteArray(Charsets.UTF_8)) }
                }
                status = "Đã lưu JSON kết quả."
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Nhận dạng tiếng Việt", fontWeight = FontWeight.Bold)
            Text("VietASR · chạy offline trên điện thoại. Mô hình chỉ cần tải một lần.",
                style = MaterialTheme.typography.bodySmall)
            Text(wavName, style = MaterialTheme.typography.bodySmall)
            if (sharedWavFile == null) {
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Chọn file WAV") }
            }

            if (!modelReady) {
                Button(onClick = {
                    busy = true
                    progress = 0f
                    status = "Đang tải VietASR…"
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                downloadAndInstallModel(context) { p, message ->
                                    progress = p
                                    status = message
                                }
                            }
                            modelReady = modelIsReady(context)
                            status = if (modelReady) "Đã tải và xác thực đủ 4 tệp VietASR. Có thể nhận dạng offline." else "Chưa tìm thấy đủ tệp VietASR."
                        } catch (e: Throwable) {
                            status = "Lỗi tải mô hình: ${e.localizedMessage ?: e.javaClass.simpleName}"
                        } finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Đang tải mô hình…" else "Tải mô hình")
                }
            }

            Button(onClick = {
                val selected = wavUri ?: return@Button
                busy = true
                progress = 0f
                errorDetails = ""
                status = "Đang kiểm tra checkpoint nhận dạng…"
                scope.launch {
                    try {
                        val started = System.currentTimeMillis()
                        val output = runResumableVietAsr(context, selected) { fraction, message ->
                            progress = fraction
                            status = message
                        }
                        transcript = output.optString("text", "").trim()
                        resultJson = output.toString(2)
                        withContext(Dispatchers.IO) {
                            File(context.filesDir, "vietasr_last_result.json").writeText(resultJson)
                        }
                        elapsed = "%.1f giây".format(Locale.US, (System.currentTimeMillis() - started) / 1000.0)
                        status = if (transcript.isBlank()) "Đã chạy xong nhưng không phát hiện lời nói. Thời gian: $elapsed"
                            else "Nhận dạng hoàn tất · $elapsed"
                        progress = 1f
                    } catch (e: Throwable) {
                        val trace = java.io.StringWriter().also { writer -> e.printStackTrace(java.io.PrintWriter(writer)) }.toString()
                        errorDetails = "WAV: $wavName\n\n${e.javaClass.name}: ${e.localizedMessage ?: "(không có message)"}\n\n$trace"
                        Log.e("VG_VietASR", errorDetails, e)
                        status = "Nhận dạng bị gián đoạn. Checkpoint đã giữ lại; chạm Tiếp tục để chạy phần còn thiếu.\n${e.localizedMessage ?: e.javaClass.simpleName}"
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy && modelReady && wavUri != null, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Đang nhận dạng…" else "Nhận dạng / Tiếp tục")
            }
            OutlinedButton(
                onClick = {
                    clearAsrCheckpoint(context)
                    status = "Đã xóa checkpoint. Lần nhận dạng tiếp theo sẽ bắt đầu từ đầu."
                    progress = 0f
                },
                enabled = !busy && hasAsrCheckpoint(context),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Xóa tiến độ và chạy lại từ đầu") }

            if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (errorDetails.isNotBlank()) {
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("VietASR error details", errorDetails))
                    status = "Đã sao chép chi tiết lỗi. Bạn gửi nội dung đó cho mình nhé."
                }, modifier = Modifier.fillMaxWidth()) { Text("Sao chép lỗi") }
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    Text(errorDetails, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (transcript.isNotBlank()) {
                Text("KẾT QUẢ · $elapsed", fontWeight = FontWeight.Bold)
                Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(transcript, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = { saveJson.launch("vietasr_result.json") },
                    modifier = Modifier.fillMaxWidth()) { Text("Xuất phụ đề + JSON") }
                VietAsrSubtitleEditor(resultJson)
            }
        }
    }
}


private fun loadSavedAsrResult(context: Context): Pair<String, String>? {
    return try {
        val file = File(context.filesDir, "vietasr_last_result.json")
        if (!file.exists() || file.length() == 0L) return null
        val json = file.readText(Charsets.UTF_8)
        val root = JSONObject(json)
        val text = root.optString("text", "")
        if (text.isBlank()) null else text to json
    } catch (_: Throwable) {
        null
    }
}

private fun modelDir(context: Context) = File(context.filesDir, VIETASR_MODEL_DIR)

private fun modelIsReady(context: Context): Boolean {
    val d = modelDir(context)
    return VIETASR_FILES.all { spec ->
        val f = File(d, spec.name)
        f.exists() && f.length() == spec.size
    }
}

private fun downloadAndInstallModel(context: Context, onProgress: (Float, String) -> Unit) {
    val destination = modelDir(context)
    destination.mkdirs()
    val totalBytes = VIETASR_FILES.sumOf { it.size }
    var completedBytes = VIETASR_FILES.sumOf { spec ->
        val target = File(destination, spec.name)
        if (target.exists() && target.length() == spec.size && sha256(target) == spec.sha256) spec.size else 0L
    }
    for ((index, spec) in VIETASR_FILES.withIndex()) {
        val target = File(destination, spec.name)
        if (target.exists() && target.length() == spec.size && sha256(target) == spec.sha256) {
            onProgress(completedBytes.toFloat() / totalBytes, "Đã xác thực ${index + 1}/4: ${spec.name}")
            continue
        }
        val partial = File(context.filesDir, "${spec.name}.part")
        var existing = if (partial.exists()) partial.length() else 0L
        var connection = (URL(VIETASR_BASE_URL + spec.name).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "COROS-DJI-Studio-VG-ASR")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            connection.connect()
            var code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK && existing > 0L) existing = 0L
            if (code == 416 && existing > 0L) {
                connection.disconnect()
                partial.delete()
                existing = 0L
                connection = (URL(VIETASR_BASE_URL + spec.name).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "COROS-DJI-Studio-VG-ASR")
                }
                connection.connect()
                code = connection.responseCode
            }
            if (code !in 200..299) error("HTTP $code khi tải ${spec.name}")
            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!append) existing = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(partial, append)).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var count = existing
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        count += n
                        onProgress(((completedBytes + count).toFloat() / totalBytes).coerceIn(0f, 0.99f),
                            "Đang tải ${index + 1}/4: ${spec.name} · ${count / (1024 * 1024)} MB")
                    }
                }
            }
        } finally { connection.disconnect() }
        check(partial.length() == spec.size) { "Tệp ${spec.name} tải chưa đủ (${partial.length()}/${spec.size} byte)." }
        check(sha256(partial) == spec.sha256) { "SHA-256 không khớp với ${spec.name}." }
        target.delete()
        check(partial.renameTo(target)) { "Không thể lưu ${spec.name}" }
        completedBytes += spec.size
    }
    check(modelIsReady(context)) { "VietASR chưa đủ hoặc có tệp không hợp lệ." }
    onProgress(1f, "Đã tải và xác thực VietASR.")
}

private fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    val out = File(context.cacheDir, "vg_asr_input.wav")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(out).use { output -> input.copyTo(output) }
    } ?: error("Không đọc được file WAV")
    return out
}

private data class PcmWavInfo(val dataStart: Long, val sampleCount: Long)

private fun inspectPcm16kMonoWav(file: File): PcmWavInfo {
    RandomAccessFile(file, "r").use { raf ->
        val riff = ByteArray(4); raf.readFully(riff)
        require(String(riff, Charsets.US_ASCII) == "RIFF") { "File không phải WAV RIFF." }
        raf.skipBytes(4)
        val wave = ByteArray(4); raf.readFully(wave)
        require(String(wave, Charsets.US_ASCII) == "WAVE") { "File WAV không hợp lệ." }
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var format = 0
        var dataStart = -1L
        var dataSize = 0L
        while (raf.filePointer + 8 <= raf.length()) {
            val id = ByteArray(4); raf.readFully(id)
            val size = Integer.toUnsignedLong(readIntLE(raf))
            val chunkEnd = raf.filePointer + size
            when (String(id, Charsets.US_ASCII)) {
                "fmt " -> {
                    format = readUShortLE(raf)
                    channels = readUShortLE(raf)
                    sampleRate = readIntLE(raf)
                    raf.skipBytes(6)
                    bits = readUShortLE(raf)
                }
                "data" -> { dataStart = raf.filePointer; dataSize = size }
            }
            if (dataStart >= 0 && format != 0) break
            raf.seek(chunkEnd + (size and 1L))
        }
        require(format == 1 && channels == 1 && sampleRate == 16000 && bits == 16) {
            "WAV cần PCM 16-bit, mono, 16 kHz. File hiện tại: format=$format, channels=$channels, rate=$sampleRate, bits=$bits"
        }
        require(dataStart >= 0 && dataSize > 0) { "Không tìm thấy dữ liệu âm thanh trong WAV." }
        require(dataSize % 2L == 0L) { "Kích thước dữ liệu PCM không hợp lệ." }
        return PcmWavInfo(dataStart, dataSize / 2L)
    }
}

private fun readIntLE(raf: RandomAccessFile): Int {
    val b0 = raf.read(); val b1 = raf.read(); val b2 = raf.read(); val b3 = raf.read()
    return (b0 and 255) or ((b1 and 255) shl 8) or ((b2 and 255) shl 16) or ((b3 and 255) shl 24)
}
private fun readUShortLE(raf: RandomAccessFile): Int = (raf.read() and 255) or ((raf.read() and 255) shl 8)
private fun readShortLE(raf: RandomAccessFile): Short = readUShortLE(raf).toShort()
