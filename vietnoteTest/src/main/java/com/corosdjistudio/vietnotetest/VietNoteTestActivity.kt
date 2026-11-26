package com.corosdjistudio.vietnotetest

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import kotlin.math.floor

class VietNoteTestActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickButton: Button
    private lateinit var modelButton: Button
    private lateinit var runButton: Button
    private var selectedUris: List<Uri> = emptyList()
    private val scope = CoroutineScope(Dispatchers.Main)

    private val modelDir get() = File(filesDir, "vietnote-offline-int8")
    private val modelFiles = listOf(
        "encoder.int8.onnx",
        "decoder.int8.onnx",
        "joiner.int8.onnx",
        "tokens.txt"
    )
    private val baseUrl = "https://huggingface.co/Kaisye/VietNote-Zipformer-78M-Streaming/resolve/main/offline/int8/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "VietNote ASR – Offline INT8"
            textSize = 21f
        }
        status = TextView(this).apply {
            text = "Bản Offline xử lý toàn bộ đoạn ghi âm. Không can thiệp VietASR trong Studio."
            textSize = 14f
        }
        modelButton = Button(this).apply { text = "1. Tải mô hình Offline (~88 MB)" }
        pickButton = Button(this).apply { text = "2. Chọn video DJI (tối đa 20)"; isEnabled = false }
        runButton = Button(this).apply { text = "3. Nhận dạng Offline"; isEnabled = false }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        output = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(title)
        root.addView(status)
        root.addView(modelButton)
        root.addView(progress)
        root.addView(pickButton)
        root.addView(runButton)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        modelButton.setOnClickListener { downloadModel() }
        pickButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        }
        runButton.setOnClickListener { runRecognition() }
        refreshState()
    }

    @Deprecated("Legacy result API for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        } ?: data.data?.let { uris += it }
        selectedUris = uris.distinct().take(20)
        selectedUris.forEach {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        status.text = "Đã chọn ${selectedUris.size} video"
        refreshState()
    }

    private fun refreshState() {
        val ready = modelFiles.all { File(modelDir, it).length() > 0L }
        pickButton.isEnabled = ready
        runButton.isEnabled = ready && selectedUris.isNotEmpty()
        modelButton.text = if (ready) "Mô hình Offline đã sẵn sàng ✓" else "1. Tải mô hình Offline (~88 MB)"
    }

    private fun downloadModel() {
        modelButton.isEnabled = false
        status.text = "Đang tải VietNote Offline INT8. Cần Internet ở lần đầu."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    modelDir.mkdirs()
                    modelFiles.forEachIndexed { index, name ->
                        val target = File(modelDir, name)
                        if (target.exists() && target.length() > 0L) return@forEachIndexed
                        val url = if (name == "tokens.txt") {
                            "https://huggingface.co/Kaisye/VietNote-Zipformer-78M-Streaming/resolve/main/tokens.txt"
                        } else baseUrl + name
                        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 30000
                            readTimeout = 120000
                            instanceFollowRedirects = true
                        }
                        connection.connect()
                        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}: $name")
                        connection.inputStream.use { input ->
                            FileOutputStream(target).use { out ->
                                val buffer = ByteArray(1024 * 256)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        connection.disconnect()
                        withContext(Dispatchers.Main) {
                            progress.progress = ((index + 1) * 100 / modelFiles.size)
                            status.text = "Đã tải ${index + 1}/${modelFiles.size}: $name"
                        }
                    }
                }
                status.text = "Đã tải xong mô hình Offline. Chọn video để nhận dạng."
            } catch (e: Exception) {
                status.text = "Lỗi tải mô hình: ${e.message}"
            } finally {
                modelButton.isEnabled = true
                refreshState()
            }
        }
    }

    private fun runRecognition() {
        runButton.isEnabled = false
        pickButton.isEnabled = false
        output.text = ""
        status.text = "Đang khởi tạo VietNote Offline..."
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val config = OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                                decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                                joiner = File(modelDir, "joiner.int8.onnx").absolutePath
                            ),
                            tokens = File(modelDir, "tokens.txt").absolutePath,
                            numThreads = 2,
                            provider = "cpu",
                            modelType = "transducer"
                        ),
                        decodingMethod = "greedy_search"
                    )
                    val recognizer = OfflineRecognizer(config = config)
                    val result = StringBuilder()
                    try {
                        selectedUris.forEachIndexed { index, uri ->
                            val name = queryName(uri)
                            withContext(Dispatchers.Main) {
                                status.text = "Đang nhận dạng Offline ${index + 1}/${selectedUris.size}: $name"
                            }
                            val samples = decodeTo16kMono(uri)
                            result.append("VIDEO: ").append(name).append('\n')
                            if (samples.isEmpty()) {
                                result.append("TEXT: [KHÔNG GIẢI MÃ ĐƯỢC ÂM THANH]\n\n")
                                return@forEachIndexed
                            }
                            val stream = recognizer.createStream()
                            try {
                                stream.acceptWaveform(samples, 16000)
                                recognizer.decode(stream)
                                val decoded = recognizer.getResult(stream)
                                result.append("TEXT: ").append(decoded.text).append('\n')
                                result.append("TOKEN_TIMESTAMPS (seconds):\n")
                                decoded.tokens.forEachIndexed { tokenIndex, token ->
                                    val time = decoded.timestamps.getOrNull(tokenIndex) ?: 0f
                                    result.append(String.format(java.util.Locale.US, "%.3f", time))
                                        .append("\\t").append(token).append('\n')
                                }
                                result.append('\n')
                            } finally {
                                stream.release()
                            }
                        }
                    } finally {
                        recognizer.release()
                    }
                    result.toString()
                }
                output.text = report
                status.text = "Hoàn tất ${selectedUris.size} video. Kết quả Offline có timestamp token (nếu model cung cấp)."
                saveReport(report)
            } catch (e: Exception) {
                status.text = "Nhận dạng Offline lỗi: ${e.message}"
            } finally {
                refreshState()
            }
        }
    }

    private fun queryName(uri: Uri): String {
        val cursor = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) return it.getString(0) }
        return uri.lastPathSegment ?: "video"
    }

    private fun decodeTo16kMono(uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(this, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Video không có track âm thanh")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Không xác định codec âm thanh")
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val mono = ArrayList<Float>(16000 * 20)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sourceRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frameCount = pcm.remaining() / channels.coerceAtLeast(1)
                            for (frame in 0 until frameCount) {
                                var sum = 0f
                                for (channel in 0 until channels) sum += pcm.get(frame * channels + channel) / 32768f
                                mono.add(sum / channels)
                            }
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            decoder.release()
            extractor.release()
        }
        if (mono.isEmpty()) return FloatArray(0)
        if (sourceRate == 16000) return mono.toFloatArray()
        val outSize = (mono.size.toDouble() * 16000.0 / sourceRate).toInt()
        return FloatArray(outSize) { i ->
            val sourcePosition = i.toDouble() * sourceRate / 16000.0
            val left = floor(sourcePosition).toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val fraction = (sourcePosition - left).toFloat()
            mono[left] * (1f - fraction) + mono[right] * fraction
        }
    }

    private fun saveReport(report: String) {
        val file = File(getExternalFilesDir(null), "vietnote-offline-comparison.txt")
        file.writeText(report)
        status.append("\nĐã lưu báo cáo: Android/data/com.corosdjistudio.vietnotetest/files/vietnote-offline-comparison.txt")
    }

    companion object {
        private const val REQ_PICK = 401
    }
}
