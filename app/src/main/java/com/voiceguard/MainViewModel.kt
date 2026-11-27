package com.voiceguard

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.donglq.corosdjioverlay.overlayOutputBaseName
import com.corosdjistudio.StudioSessionLog

enum class Screen { HOME, REVIEW }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ClipStore(app)
    private val map = LinkedHashMap<String, Clip>()
    private var scanJob: Job? = null

    var clips by mutableStateOf(listOf<Clip>())
        private set
    var minimumVoiceSeconds by mutableStateOf(store.minimumVoiceSeconds)
        private set
    var silenceThresholdSeconds by mutableStateOf(10f)
        private set
    var includeAlreadyMasked by mutableStateOf(false)
        private set

    fun updateMinimumVoiceSeconds(value: Float) {
        minimumVoiceSeconds = listOf(1f, 2f, 3f).minByOrNull { kotlin.math.abs(it - value) } ?: 2f
        store.minimumVoiceSeconds = minimumVoiceSeconds
    }

    fun updateSilenceThreshold(value: Float) {
        silenceThresholdSeconds = listOf(5f, 10f, 15f).minByOrNull { kotlin.math.abs(it - value) } ?: 10f
    }

    fun updateIncludeAlreadyMasked(value: Boolean) {
        includeAlreadyMasked = value
    }
    // Đã cố định kiểu String an toàn tuyệt đối, mặc định là chuỗi rỗng nếu store chưa có
    var treeUri by mutableStateOf(store.treeUri ?: "")
        private set
    var folderDocumentId by mutableStateOf<String?>(store.folderDocumentId)
        private set
    var musicUri by mutableStateOf(store.musicUri)
        private set
    var musicName by mutableStateOf(store.musicName ?: "")
        private set
    var scanning by mutableStateOf(false)
        private set
    var masking by mutableStateOf(false)
        private set
    var timelineBuilding by mutableStateOf(false)
        private set
    var timelineDone by mutableStateOf(0)
        private set
    var timelineTotal by mutableStateOf(0)
        private set
    var voiceTimeline by mutableStateOf<VoiceTimeline?>(null)
        private set
    var timelineMarks by mutableStateOf(listOf<VoiceSegment>())
        private set
    var stopRequested by mutableStateOf(false)
        private set
    var scanDone by mutableStateOf(0)
        private set
    var scanTotal by mutableStateOf(0)
        private set
    var currentName by mutableStateOf("")
        private set
    var avgSec by mutableStateOf(0f)
        private set
    var message by mutableStateOf("")
        private set
    var recentMaskedUris by mutableStateOf(listOf<Pair<String, String>>())
        private set
    var allFilesAccess by mutableStateOf(checkAllFilesAccess())
        private set
    
    var selectedVideos by mutableStateOf(listOf<VideoFile>())
        private set

    var screen by mutableStateOf(Screen.HOME)
    var skipped by mutableStateOf(setOf<String>())
        private set

    var filterDays by mutableStateOf(0)
    var filterName by mutableStateOf("")
    var maxClips by mutableStateOf(30)

    val current: Clip?
        get() = clips.firstOrNull { it.status == ClipStatus.HAS_VOICE && it.uri !in skipped }

    init {
        synchronized(map) {
            map.putAll(store.load())
            map.values.removeAll { it.name.startsWith(".") }
        }
        persist()
        refresh()
        if (treeUri.isNotEmpty()) {
            rebind(treeUri)
        }
    }

    private fun checkAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun refreshPermission() {
        allFilesAccess = checkAllFilesAccess()
    }

    private fun refresh() {
        // Keep the persisted/rebound review queue chronological too, not just the latest scan batch.
        clips = synchronized(map) { map.values.toList() }
            .sortedBy { voiceChronologicalKey(it.name, 0L) }
    }

    private fun persist() {
        store.save(synchronized(map) { map.values.toList() })
    }

    fun setTree(uri: String, folderId: String? = null) {
        val previousFolderId = store.folderDocumentId
        // Mỗi thư mục ngày là một phiên duyệt độc lập. Không mang hàng chờ của
        // buổi quay cũ (ví dụ file Mimo/COROS đã chuyển đi) sang thư mục mới.
        if (folderId != null && previousFolderId != null && previousFolderId != folderId) {
            synchronized(map) { map.clear() }
            skipped = emptySet()
            persist()
            refresh()
        }
        store.folderDocumentId = folderId
        store.treeUri = uri
        treeUri = uri
        folderDocumentId = folderId
        rebind(uri, folderId)
    }

    private fun listTargetFiles(ctx: android.content.Context, tree: Uri, folderId: String?): List<VideoFile> {
        if (folderId == null) return VideoLister.list(ctx, tree, includeMasked = true) +
            VideoLister.listSubfolder(ctx, tree, MaskExporter.OUTPUT_FOLDER) +
            VideoLister.listSubfolder(ctx, tree, MaskExporter.ARCHIVE_FOLDER)
        return VideoLister.listInFolder(ctx, tree, folderId, includeMasked = true) +
            VideoLister.listSubfolderInFolder(ctx, tree, folderId, MaskExporter.OUTPUT_FOLDER) +
            VideoLister.listSubfolderInFolder(ctx, tree, folderId, MaskExporter.ARCHIVE_FOLDER)
    }

    private fun rebind(tree: String, folderId: String? = folderDocumentId) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val t = Uri.parse(tree)
                val all = listTargetFiles(ctx, t, folderId)
                // Chỉ giữ những clip thực sự còn nằm trong thư mục ngày đang mở
                // (hoặc thư mục con masked/archive của chính ngày đó). Mục cũ không
                // tìm thấy sẽ bị loại khỏi hàng chờ, tránh phát URI đã mất và báo Invalid.
                val byName = HashMap<String, VideoFile>()
                for (f in all) byName.putIfAbsent(f.name, f)

                var rebound = 0
                var removed = 0
                synchronized(map) {
                    val updated = LinkedHashMap<String, Clip>()
                    for (c in map.values) {
                        val f = byName[c.name]
                        if (f == null) {
                            removed++
                            continue
                        }
                        val nc = if (f.uri.toString() != c.uri) {
                            rebound++
                            c.copy(uri = f.uri.toString())
                        } else c
                        updated[nc.uri] = nc
                    }
                    map.clear()
                    map.putAll(updated)
                }
                if (rebound > 0 || removed > 0) {
                    persist()
                    refresh()
                    message = when {
                        removed > 0 && rebound > 0 -> "Đã nối lại $rebound clip và loại $removed mục cũ không còn trong thư mục ngày."
                        removed > 0 -> "Đã loại $removed mục cũ không còn trong thư mục ngày."
                        else -> "Đã nối lại $rebound clip với thư mục hiện tại."
                    }
                }
                // Tiếp tục checkpoint sau khi đã nối lại URI SAF của thư mục.
                resumeVoiceTimelineIfAvailable()
            } catch (e: Throwable) {
            }
        }
    }

    fun setMusic(uri: Uri) {
        var name = "nhạc"
        try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
        } catch (e: Exception) {
        }
        store.musicUri = uri.toString()
        store.musicName = name
        musicUri = uri.toString()
        musicName = name
    }

    fun mark(uri: String, status: ClipStatus) {
        synchronized(map) {
            val c = map[uri] ?: return
            map[uri] = c.copy(status = status)
        }
        persist()
        refresh()
    }

    fun updateSegments(uri: String, segments: List<VoiceSegment>) {
        val cleaned = segments.map { VoiceSegment(it.startSec.coerceAtLeast(0f), it.endSec.coerceAtLeast(0f)) }
            .filter { it.endSec > it.startSec }
            .sortedBy { it.startSec }
        synchronized(map) {
            val clip = map[uri] ?: return
            map[uri] = clip.copy(segments = cleaned)
        }
        persist()
        refresh()
    }

    private fun resumeVoiceTimelineIfAvailable() {
        if (scanning || masking || timelineBuilding || voiceTimeline != null) return
        val candidates = clips.filter {
            it.voiceSec > 0f && it.status != ClipStatus.NO_VOICE &&
                it.status != ClipStatus.MASKED && it.status != ClipStatus.ARCHIVED
        }
        // Checkpoint mang theo ngưỡng cắt im lặng; dò các mức hỗ trợ để khôi phục đúng WAV.
        val savedThreshold = listOf(5f, 10f, 15f).firstOrNull { threshold ->
            VoiceTimelineBuilder.hasCheckpoint(
                getApplication<Application>(), candidates, minimumVoiceSeconds, threshold
            )
        }
        if (savedThreshold != null) {
            silenceThresholdSeconds = savedThreshold
            message = "Đang khôi phục timeline WAV (ngưỡng im lặng ${savedThreshold.toInt()} giây)…"
            buildVoiceTimeline(savedThreshold)
        }
    }

    /** Tạo bản nghe liên tục, giữ gần như toàn bộ âm thanh và chỉ cắt khoảng im lặng dài. */
    fun buildVoiceTimeline(silenceThresholdSec: Float = silenceThresholdSeconds) {
        if (scanning || masking || timelineBuilding) return
        val candidates = clips.filter {
            it.voiceSec > 0f && it.status != ClipStatus.NO_VOICE &&
                it.status != ClipStatus.MASKED && it.status != ClipStatus.ARCHIVED
        }
        if (candidates.isEmpty()) {
            message = "Chưa có video voice. Hãy quét video trước."
            return
        }
        silenceThresholdSeconds = silenceThresholdSec
        // Dừng phát timeline cũ trước khi thay nội dung WAV trên cùng file.
        voiceTimeline = null
        timelineMarks = emptyList()
        timelineBuilding = true
        timelineDone = 0
        timelineTotal = candidates.size
        message = ""
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val built = VoiceTimelineBuilder.build(
                    getApplication(), candidates, minimumVoiceSeconds, silenceThresholdSec
                ) { done, total, name ->
                    ensureActive()
                    timelineDone = done
                    timelineTotal = total
                    currentName = name
                }
                voiceTimeline = built
                val byUri = clips.associateBy { it.uri }
                timelineMarks = built.spans.flatMap { span ->
                    val existing = byUri[span.clipUri]?.segments.orEmpty()
                    existing.mapNotNull { segment ->
                        val a = maxOf(segment.startSec, span.clipStartSec)
                        val b = minOf(segment.endSec, span.clipStartSec + (span.timelineEndSec - span.timelineStartSec))
                        if (b <= a) null else VoiceSegment(
                            span.timelineStartSec + (a - span.clipStartSec),
                            span.timelineStartSec + (b - span.clipStartSec)
                        )
                    }
                }.sortedBy { it.startSec }
                message = "Đã giữ âm thanh của ${built.voiceClipCount} video · ${"%.1f".format(Locale.US, built.voiceSec)} giây sau khi bỏ khoảng im lặng dài."
            } catch (e: Throwable) {
                message = "Không tạo được timeline: ${e.message}"
                voiceTimeline = null
            } finally {
                timelineBuilding = false
                currentName = ""
            }
        }
    }

    /** Dịch ngược một khoảng trên timeline thành các khoảng timestamp riêng của từng video. */
    fun markTimelineRange(startSec: Float, endSec: Float) {
        val timeline = voiceTimeline ?: return
        if (endSec <= startSec) return
        val additions = mutableMapOf<String, MutableList<VoiceSegment>>()
        timeline.spans.forEach { span ->
            val a = maxOf(startSec, span.timelineStartSec)
            val b = minOf(endSec, span.timelineEndSec)
            if (b > a) {
                val localStart = span.clipStartSec + (a - span.timelineStartSec)
                val localEnd = span.clipStartSec + (b - span.timelineStartSec)
                additions.getOrPut(span.clipUri) { mutableListOf() } += VoiceSegment(localStart, localEnd)
            }
        }
        if (additions.isEmpty()) return
        synchronized(map) {
            additions.forEach { (uri, newSegments) ->
                val clip = map[uri] ?: return@forEach
                val all = (clip.segments + newSegments).sortedBy { it.startSec }
                val merged = mutableListOf<VoiceSegment>()
                all.forEach { segment ->
                    val last = merged.lastOrNull()
                    if (last != null && segment.startSec <= last.endSec + 0.03f) {
                        merged[merged.lastIndex] = VoiceSegment(last.startSec, maxOf(last.endSec, segment.endSec))
                    } else merged += segment
                }
                map[uri] = clip.copy(status = ClipStatus.SENSITIVE, segments = merged)
            }
        }
        val allMarks = (timelineMarks + VoiceSegment(startSec, endSec)).sortedBy { it.startSec }
        val mergedMarks = mutableListOf<VoiceSegment>()
        allMarks.forEach { segment ->
            val last = mergedMarks.lastOrNull()
            if (last != null && segment.startSec <= last.endSec + 0.03f) {
                mergedMarks[mergedMarks.lastIndex] = VoiceSegment(last.startSec, maxOf(last.endSec, segment.endSec))
            } else mergedMarks += segment
        }
        timelineMarks = mergedMarks
        persist()
        refresh()
    }

    fun skip(uri: String) {
        skipped = skipped + uri
    }

    fun resetSkipped() {
        skipped = emptySet()
    }

    fun stopScan() {
        if (!scanning && !masking && !timelineBuilding) return
        stopRequested = true
        scanJob?.cancel()
    }

    fun scan() {
        if (treeUri.isEmpty()) return
        val tree = treeUri
        if (scanning || masking) return
        scanning = true
        stopRequested = false
        message = ""
        scanDone = 0
        scanTotal = 0
        avgSec = 0f
        currentName = ""

        val days = filterDays
        val nameFilter = filterName.trim()
        val limit = maxClips

        voiceTimeline = null
        timelineMarks = emptyList()
        VoiceTimelineBuilder.clearCheckpoint(getApplication<Application>())
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            val ctx = getApplication<Application>()
            var detector: VoiceDetector? = null
            try {
                val cutoff = if (days > 0) System.currentTimeMillis() - days * 86_400_000L else 0L
                val scanTree = Uri.parse(tree)
                val scanFolderId = folderDocumentId ?: DocumentsContract.getTreeDocumentId(scanTree)
                val allFiles = (VideoLister.listInFolder(ctx, scanTree, scanFolderId, includeMasked = true) +
                    VideoLister.listSubfolderInFolder(ctx, scanTree, scanFolderId, MaskExporter.OUTPUT_FOLDER))
                    .distinctBy { it.uri.toString() }
                // Mỗi timestamp chỉ đưa MỘT nguồn vào VG: có *_ovl.mp4 thì dùng overlay,
                // chỉ dùng video gốc khi chưa có overlay. Hỗ trợ cả tên COROS_ của phiên bản cũ.
                val overlayVideos = allFiles.filter {
                    !isMaskedName(it.name) &&
                        (it.name.startsWith("COROS_", ignoreCase = true) ||
                            Regex("^\\d{6}_\\d{6}(?:_\\d{2})?_ovl(?:_\\d+)?\\.mp4$", RegexOption.IGNORE_CASE).matches(it.name))
                }
                val originals = allFiles.filter {
                    !isMaskedName(it.name) && !it.name.startsWith(".") &&
                        !it.name.startsWith("Studio_Luu_tru", ignoreCase = true) &&
                        !it.name.startsWith("COROS_", ignoreCase = true) &&
                        !Regex("^\\d{6}_\\d{6}(?:_\\d{2})?_ovl(?:_\\d+)?\\.mp4$", RegexOption.IGNORE_CASE).matches(it.name)
                }
                // Overlay luôn là nguồn ưu tiên, kể cả khi video gốc đã được chuyển
                // sang nơi khác. Chỉ bổ sung video gốc khi không có overlay cùng timestamp.
                val originalsWithoutOverlay = originals.filter { original ->
                    val base = overlayOutputBaseName(original.name)
                    val pattern = Regex("^" + Regex.escape(base) + "(?:_\\d+)?\\.mp4$", RegexOption.IGNORE_CASE)
                    overlayVideos.none { pattern.matches(it.name) }
                }
                val selectedInputs = (overlayVideos + originalsWithoutOverlay)
                    .distinctBy { it.uri.toString() }
                val existingMasked = allFiles.filter { isMaskedName(it.name) }.map { stemOf(it.name) }.toSet()
                // Nếu phiên trước ghi trạng thái MASKED nhưng file đầu ra đã bị xóa/di chuyển,
                // bỏ trạng thái cũ để clip quay lại hàng đợi rà soát mặc định.
                if (!includeAlreadyMasked) {
                    val staleMaskedNames = synchronized(map) {
                        map.values.filter { clip ->
                            clip.status == ClipStatus.MASKED &&
                                matchedSourceNameExists(selectedInputs, clip.name) &&
                                stemOf(clip.name) !in existingMasked
                        }.map { it.name }.toSet()
                    }
                    if (staleMaskedNames.isNotEmpty()) {
                        synchronized(map) {
                            map.entries.removeAll { it.value.name in staleMaskedNames }
                        }
                        persist()
                        refresh()
                    }
                }
                val matched = selectedInputs
                    .filter { it.modified == 0L || it.modified >= cutoff }
                    .filter { nameFilter.isEmpty() || it.name.contains(nameFilter, ignoreCase = true) }
                    .filter { includeAlreadyMasked || stemOf(it.name) !in existingMasked }
                    // Timestamp YYMMDD_HHMMSS tăng dần: nghe theo đúng trình tự câu chuyện.
                    .sortedBy { voiceChronologicalKey(it.name, it.modified) }
                if (includeAlreadyMasked) {
                    synchronized(map) { map.entries.removeAll { entry -> matched.any { it.name == entry.value.name } } }
                    persist()
                    refresh()
                }
                val knownNames = synchronized(map) { map.values.map { it.name }.toSet() }
                val fresh = matched.filter {
                    synchronized(map) { it.uri.toString() !in map } && (includeAlreadyMasked || it.name !in knownNames)
                }
                val todo = if (limit > 0) fresh.take(limit) else fresh
                scanTotal = todo.size

                if (todo.isEmpty()) {
                    message = when {
                        selectedInputs.isEmpty() -> "Không thấy video Overlay hoặc video gốc trong thư mục ngày."
                        matched.isEmpty() -> "Có ${selectedInputs.size} video nguồn nhưng không còn video nào khớp bộ lọc hoặc cần quét."
                        else -> "Không có video mới nào cần quét."
                    }
                    return@launch
                }

                val det = VoiceDetector(ctx)
                detector = det
                var withVoice = 0
                val t0 = System.nanoTime()
                recentMaskedUris = emptyList()

                todo.forEachIndexed { i, f ->
                    ensureActive()
                    scanDone = i
                    currentName = f.name
                    val audio = try {
                        AudioDecoder.decodeTo16kMono(ctx, f.uri)
                    } catch (e: Throwable) {
                        null
                    }
                    val segments = if (audio == null) emptyList() else det.speechSegments(audio, minimumVoiceSeconds)
                    val sec = segments.sumOf { it.durationSec.toDouble() }.toFloat()
                    val status = if (segments.isNotEmpty()) ClipStatus.HAS_VOICE else ClipStatus.NO_VOICE
                    if (status == ClipStatus.HAS_VOICE) withVoice++
                    // Speech detection is only a cue for review. It is not an instruction
                    // to mask: only ranges explicitly marked by the user are exported.
                    synchronized(map) {
                        map[f.uri.toString()] = Clip(f.uri.toString(), f.name, status, sec, emptyList())
                    }
                    persist()
                    refresh()
                    avgSec = (System.nanoTime() - t0) / 1e9f / (i + 1)
                }
                scanDone = todo.size
                message = "Xong: quét ${todo.size} clip, $withVoice clip có voice."
            } catch (e: CancellationException) {
                message = "Đã dừng."
                throw e
            } catch (e: Throwable) {
                message = "Lỗi khi quét: ${e.message}"
            } finally {
                detector?.close()
                scanning = false
                stopRequested = false
            }
        }
    }

    fun maskSensitive() {
        if (treeUri.isEmpty()) return
        val tree = treeUri
        val music = musicUri
        if (music == null) {
            message = "Hãy chọn file nhạc dùng để che voice trước."
            return
        }
        if (scanning || masking) return
        masking = true
        stopRequested = false
        message = ""
        scanDone = 0
        scanTotal = 0
        avgSec = 0f
        currentName = ""

        scanJob = viewModelScope.launch(Dispatchers.Default) {
            val ctx = getApplication<Application>()
            var done = 0
            var failed = 0
            var lastError = ""
            var archiveWarnings = 0
            var lastArchiveWarning = ""
            var detector: VoiceDetector? = null
            try {
                val todo = clips.filter { it.status == ClipStatus.SENSITIVE }
                scanTotal = todo.size
                if (todo.isEmpty()) {
                    message = "Không có clip nhạy cảm nào cần xử lý."
                    return@launch
                }
                val source = MusicSource.load(ctx, Uri.parse(music))
                if (source == null) {
                    message = "Không đọc được file nhạc."
                    return@launch
                }
                val rootTree = Uri.parse(tree)
                val outDir = folderDocumentId?.let { DocumentsContract.buildDocumentUriUsingTree(rootTree, it) } ?: rootTree
                detector = VoiceDetector(ctx)
                val t0 = System.nanoTime()

                todo.forEachIndexed { i, clip ->
                    ensureActive()
                    scanDone = i
                    currentName = clip.name
                    val maskStartedAtNanos = System.nanoTime()
                    try {
                        val segments = if (clip.segments.isNotEmpty()) clip.segments else {
                            val audio = AudioDecoder.decodeTo16kMono(ctx, Uri.parse(clip.uri))
                            if (audio == null) emptyList() else detector!!.speechSegments(audio, minimumVoiceSeconds)
                        }
                        if (segments.isEmpty()) throw IOException("Không còn đoạn voice đạt ngưỡng để che")
                        val outputUri = MaskExporter.maskOne(ctx, rootTree, Uri.parse(clip.uri), clip.name, source, segments, outDir)
                        val outputName = ctx.contentResolver.query(outputUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: (clip.name.substringBeforeLast(".") + "_masked.mp4")
                        val maskDurationMs = ((System.nanoTime() - maskStartedAtNanos) / 1_000_000L).coerceAtLeast(1L)
                        runCatching { StudioSessionLog.recordMasked(ctx, outDir, clip.name, outputName, maskDurationMs) }
                        val archiveResult = runCatching { StudioSessionLog.archiveSourceVideo(ctx, outDir, Uri.parse(clip.uri), clip.name) }
                        if (archiveResult.isFailure) {
                            archiveWarnings++
                            lastArchiveWarning = clip.name + ": " + (archiveResult.exceptionOrNull()?.localizedMessage ?: "lỗi SAF")
                        }
                        recentMaskedUris = recentMaskedUris + (outputName to outputUri.toString())
                        mark(clip.uri, ClipStatus.MASKED)
                        done++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed++
                        lastError = "${clip.name}: ${e.message}"
                    }
                    avgSec = (System.nanoTime() - t0) / 1e9f / (i + 1)
                }
                scanDone = todo.size
                message = "Xong: đã che voice theo từng đoạn cho $done clip" + if (failed > 0) ", lỗi $failed clip: $lastError" else "."
                if (archiveWarnings > 0) message += " Chưa chuyển vào luu_tru " + archiveWarnings + " nguồn: " + lastArchiveWarning
            } catch (e: CancellationException) {
                message = "Đã dừng."
                throw e
            } catch (e: Throwable) {
                message = "Lỗi: ${e.message}"
            } finally {
                detector?.close()
                masking = false
                stopRequested = false
            }
        }
    }

    fun archiveOriginals() {
        if (treeUri.isEmpty()) return
        val tree = treeUri
        if (scanning || masking) return
        masking = true
        stopRequested = false
        message = ""
        scanDone = 0
        scanTotal = 0
        avgSec = 0f
        currentName = ""

        scanJob = viewModelScope.launch(Dispatchers.Default) {
            val ctx = getApplication<Application>()
            var done = 0
            var failed = 0
            var lastError = ""
            try {
                val todo = clips.filter { it.status == ClipStatus.MASKED }
                scanTotal = todo.size
                if (todo.isEmpty()) {
                    message = "Không có clip nào."
                    return@launch
                }
                val treeUriParsed = Uri.parse(tree)
                val dir = folderDocumentId?.let { MaskExporter.ensureDirInFolder(ctx, treeUriParsed, it, MaskExporter.ARCHIVE_FOLDER) }
                    ?: MaskExporter.ensureDir(ctx, treeUriParsed, MaskExporter.ARCHIVE_FOLDER)
                MaskExporter.ensureNoMedia(ctx, treeUriParsed, dir)

                todo.forEachIndexed { i, clip ->
                    ensureActive()
                    scanDone = i
                    currentName = clip.name
                    try {
                        MaskExporter.archiveOriginal(ctx, treeUriParsed, Uri.parse(clip.uri), dir)
                        mark(clip.uri, ClipStatus.ARCHIVED)
                        done++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed++
                        lastError = "${clip.name}: ${e.message}"
                    }
                }
                scanDone = todo.size
                message = "Xong: đã cất bản gốc của $done clip."
            } catch (e: CancellationException) {
                message = "Đã dừng."
                throw e
            } catch (e: Throwable) {
                message = "Lỗi: ${e.message}"
            } finally {
                masking = false
                stopRequested = false
            }
        }
    }

    fun reorderTimes() {
        if (treeUri.isEmpty()) return
        val tree = treeUri
        if (scanning || masking) return
        refreshPermission()
        if (!allFilesAccess) {
            message = "Cần cấp quyền."
            return
        }
        masking = true
        stopRequested = false
        message = ""
        scanDone = 0
        scanTotal = 0
        avgSec = 0f
        currentName = ""

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            try {
                val path = MaskExporter.treeToPath(Uri.parse(tree))
                    ?: throw IOException("Không xác định được đường dẫn")
                val folder = File(path)
                if (!folder.isDirectory) throw IOException("Không truy cập được thư mục")

                val r = TimeFixer.run(ctx, folder) { done, total, name ->
                    scanDone = done
                    scanTotal = total
                    currentName = name
                }
                scanDone = r.maskedTotal
                message = "Xong: đặt lại giờ cho ${r.fixed} file."
            } catch (e: Throwable) {
                message = "Lỗi: ${e.message}"
            } finally {
                masking = false
                stopRequested = false
            }
        }
    }

    fun setMergeSelection(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        val list = ArrayList<VideoFile>()
        for (u in uris.distinct()) {
            var name = "clip"
            var size = 0L
            try {
                ctx.contentResolver.query(
                    u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        size = if (!c.isNull(1)) c.getLong(1) else 0L
                    }
                }
            } catch (e: Exception) {
            }
            list.add(VideoFile(u, name, 0L, size))
        }
        selectedVideos = list.sortedWith(compareBy({ stemOf(it.name) }, { it.name }))
        message = ""
    }

    fun setMergeSelectionByVideos(videos: List<VideoFile>) {
        selectedVideos = videos.distinctBy { it.uri }.sortedWith(compareBy({ stemOf(it.name) }, { it.name }))
        message = ""
    }

    fun clearMergeSelection() {
        selectedVideos = emptyList()
    }

    fun mergeSelected() {
        if (treeUri.isEmpty()) {
            message = "Hãy chọn thư mục chứa clip ở trên trước."
            return
        }
        val tree = treeUri
        if (scanning || masking) return
        val sel = selectedVideos
        if (sel.isEmpty()) return
        masking = true
        stopRequested = false
        message = ""
        scanDone = 0
        scanTotal = 0
        avgSec = 0f
        currentName = ""
        val snapshot = clips

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            try {
                val statusByStem = snapshot.groupBy { stemOf(it.name) }.mapValues { e -> e.value.map { it.status } }
                val files = ArrayList<VideoFile>()
                val excluded = ArrayList<String>()
                var unreviewedVoice = 0
                for (f in sel) {
                    val st = statusByStem[stemOf(f.name)]
                    val needsMask = st != null && st.any {
                        it == ClipStatus.SENSITIVE || it == ClipStatus.MASKED || it == ClipStatus.ARCHIVED
                    }
                    if (!isMaskedName(f.name) && needsMask) {
                        excluded.add(f.name)
                        continue
                    }
                    if (!needsMask && st != null && st.any { it == ClipStatus.HAS_VOICE }) unreviewedVoice++
                    files.add(f)
                }

                if (files.isEmpty()) {
                    message = "Không còn file nào để ghép."
                    return@launch
                }

                val outDir = MaskExporter.ensureDir(ctx, Uri.parse(tree), "VoiceGuard_merged")
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

                val r = Concatenator.concat(ctx, files, outDir, 0, stamp) { done, total, name ->
                    scanDone = done
                    scanTotal = total
                    currentName = name
                }
                selectedVideos = emptyList()
                message = "Xong: ghép ${r.clips} file thành ${r.parts} file."
            } catch (e: CancellationException) {
                message = "Đã dừng."
                throw e
            } catch (e: Throwable) {
                message = "Lỗi khi ghép: ${e.message}"
            } finally {
                masking = false
                stopRequested = false
            }
        }
    }
}

private fun matchedSourceNameExists(inputs: List<VideoFile>, name: String) = inputs.any { it.name == name }

private fun isMaskedName(n: String) = n.substringBeforeLast('.').endsWith("_masked")

private fun voiceChronologicalKey(name: String, modified: Long): String {
    val match = Regex("^(\\d{6})_(\\d{6})(?:_\\d{2})?").find(name)
    return if (match != null) "0${match.groupValues[1]}${match.groupValues[2]}" else "1${modified.toString().padStart(16, '0')}"
}

private fun stemOf(n: String) = n.substringBeforeLast('.').removeSuffix("_masked")
