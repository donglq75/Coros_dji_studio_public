package com.corosdjistudio

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.voiceguard.Concatenator
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Safety reset for 0.3.16: disable the experimental mover until the user
        // explicitly enables it again. Pending workers will exit without touching files.
        val moverSafetyPrefs = getSharedPreferences(DjiAutoMoveWorker.PREFS, android.content.Context.MODE_PRIVATE)
        if (!moverSafetyPrefs.getBoolean("safety_reset_0316_done", false)) {
            moverSafetyPrefs.edit()
                .putBoolean(DjiAutoMoveWorker.KEY_ENABLED, false)
                .putBoolean("safety_reset_0316_done", true)
                .apply()
        }
        setContent { StudioApp() }
    }
}

@Composable
private fun StudioApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val studioPrefs = remember { context.getSharedPreferences("studio_session", android.content.Context.MODE_PRIVATE) }
    var rootTree by remember { mutableStateOf(studioPrefs.getString("root_tree_uri", null)?.let(Uri::parse)) }
    var dateFolders by remember { mutableStateOf<List<StudioDateFolder>>(emptyList()) }
    var selectedDateUri by remember { mutableStateOf(studioPrefs.getString("selected_date_folder_uri", null)?.let(Uri::parse)) }
    var selectedDateName by remember { mutableStateOf(studioPrefs.getString("selected_date_folder_name", null)) }
    var dateMenuExpanded by remember { mutableStateOf(false) }
    var loadingDates by remember { mutableStateOf(false) }
    var lastCaptureTime by remember { mutableStateOf(studioPrefs.getLong("last_capture_time", 0L)) }
    var movedVideos by remember { mutableStateOf<List<StudioVideo>>(emptyList()) }
    var status by remember { mutableStateOf("Chọn video DJI trên điện thoại và thư mục lưu Studio để bắt đầu.") }
    var orientationScanMessage by remember { mutableStateOf("") }
    var scanningOrientation by remember { mutableStateOf(false) }
    var orientationReport by remember { mutableStateOf<MediaOrientationReport?>(null) }
    var selectedOrientationUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteItems by remember { mutableStateOf<List<MediaOrientationItem>?>(null) }
    var orientationActionBusy by remember { mutableStateOf(false) }
    var orientationActionMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var consolidating by remember { mutableStateOf(false) }
    var consolidationReport by remember { mutableStateOf<StudioConsolidationReport?>(null) }
    var mergeProductCount by remember { mutableIntStateOf(0) }
    val mergeServicePrefs = remember {
        context.getSharedPreferences(StudioMergeService.PREFS, android.content.Context.MODE_PRIVATE)
    }
    var merging by remember { mutableStateOf(mergeServicePrefs.getBoolean(StudioMergeService.KEY_RUNNING, false)) }
    var mergeProgress by remember { mutableFloatStateOf(mergeServicePrefs.getFloat(StudioMergeService.KEY_PROGRESS, 0f)) }
    var mergeMessage by remember { mutableStateOf(mergeServicePrefs.getString(StudioMergeService.KEY_STATUS, "") ?: "") }
    LaunchedEffect(Unit) {
        while (true) {
            merging = mergeServicePrefs.getBoolean(StudioMergeService.KEY_RUNNING, false)
            mergeProgress = mergeServicePrefs.getFloat(StudioMergeService.KEY_PROGRESS, 0f)
            mergeMessage = mergeServicePrefs.getString(StudioMergeService.KEY_STATUS, "") ?: ""
            delay(900)
        }
    }
    val moverPrefs = remember { context.getSharedPreferences(DjiAutoMoveWorker.PREFS, android.content.Context.MODE_PRIVATE) }
    var autoMoveEnabled by remember { mutableStateOf(moverPrefs.getBoolean(DjiAutoMoveWorker.KEY_ENABLED, false)) }
    var moverStatus by remember { mutableStateOf(moverPrefs.getString(DjiAutoMoveWorker.KEY_STATUS, "Chưa chạy quét tự động.") ?: "Chưa chạy quét tự động.") }


    LaunchedEffect(selectedDateUri) {
        val folder = selectedDateUri
        mergeMessage = ""
        if (folder == null) {
            mergeProductCount = 0
        } else {
            val cleanupAndCount = withContext(Dispatchers.IO) {
                val cleaned = runCatching {
                    StudioSessionLog.cleanupExpiredArchive(context, folder, retentionDays = 7)
                }.getOrDefault(0)
                val count = runCatching {
                    StudioSessionLog.listConsolidatedProducts(context, folder).size
                }.getOrDefault(0)
                cleaned to count
            }
            mergeProductCount = cleanupAndCount.second
            if (cleanupAndCount.first > 0) {
                status = "Đã tự dọn ${cleanupAndCount.first} file Studio_Luu_tru của buổi quay quá 7 ngày."
            }
        }
    }

    LaunchedEffect(rootTree) {
        val root = rootTree
        if (root != null) {
            loadingDates = true
            val found = withContext(Dispatchers.IO) {
                runCatching { StudioStorage.listDateFolders(context, root) }.getOrDefault(emptyList())
            }
            dateFolders = found
            val selected = found.firstOrNull { it.uri.toString() == selectedDateUri?.toString() }
                ?: found.firstOrNull { it.name == selectedDateName }
                ?: found.firstOrNull()
            selectedDateUri = selected?.uri
            selectedDateName = selected?.name
            if (selected != null) studioPrefs.edit()
                .putString("selected_date_folder_uri", selected.uri.toString())
                .putString("selected_date_folder_name", selected.name).apply()
            loadingDates = false
        } else {
            dateFolders = emptyList()
            selectedDateUri = null
            selectedDateName = null
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
            selectedUris = uris.distinct()
            status = "Đã chọn ${selectedUris.size} video. Studio sẽ tự xếp theo thời điểm quay."
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            rootTree = uri
            selectedDateUri = null
            selectedDateName = null
            studioPrefs.edit().remove("selected_date_folder_uri").remove("selected_date_folder_name")
                .putString("root_tree_uri", uri.toString()).apply()
            if (autoMoveEnabled) DjiAutoMoveWorker.start(context, uri)
            status = "Đã chọn thư mục gốc. Kết quả sẽ lưu trong dji_studio/yymmdd."
        }
    }

    StudioTheme {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Tổng quan", "DJI Media", "Overlay", "VoiceGuard").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = {
                            when (index) {
                                2 -> {
                                    val output = selectedDateUri
                                    if (output == null) {
                                        Toast.makeText(context, "Hãy vào DJI Media và chọn buổi quay trước.", Toast.LENGTH_SHORT).show()
                                    } else try {
                                        context.startActivity(Intent(context, com.donglq.corosdjioverlay.OverlayActivity::class.java)
                                            .putExtra("studio_output_tree", output.toString()))
                                    } catch (error: Throwable) {
                                        Toast.makeText(context, "Không mở được COROS Overlay: ${error.localizedMessage ?: "lỗi không xác định"}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                3 -> {
                                    val root = rootTree
                                    val output = selectedDateUri
                                    if (root == null || output == null) {
                                        Toast.makeText(context, "Hãy chọn bộ nhớ gốc và buổi quay trong DJI Media trước.", Toast.LENGTH_SHORT).show()
                                    } else try {
                                        context.startActivity(Intent(context, com.voiceguard.VoiceGuardActivity::class.java)
                                            .putExtra("studio_root_tree", root.toString())
                                            .putExtra("studio_folder_document_id", android.provider.DocumentsContract.getDocumentId(output)))
                                    } catch (error: Throwable) {
                                        Toast.makeText(context, "Không mở được VoiceGuard: ${error.localizedMessage ?: "lỗi không xác định"}", Toast.LENGTH_LONG).show()
                                    }
                                }
                                else -> selectedTab = index
                            }
                        },
                        icon = {
                            Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF17452B))) {
                                val icon = when (index) {
                                    0 -> R.drawable.ic_tab_home
                                    1 -> R.drawable.ic_tab_media
                                    2 -> R.drawable.ic_tab_overlay
                                    else -> R.drawable.ic_tab_voiceguard
                                }
                                Image(painterResource(icon), contentDescription = label, modifier = Modifier.fillMaxSize())
                            }
                        },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 14.dp)
                // Keep one vertical scroll owner; the media video list below is a regular Column.
                .verticalScroll(rememberScrollState())) {
                if (selectedTab == 0) {
                    Text("COROS × DJI STUDIO", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("Gom khoảnh khắc,\nkể hành trình", style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
                    Spacer(Modifier.height(20.dp))
                } else {
                    Text("DJI Media", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                }
                when (selectedTab) {
                    0 -> Overview(
                        appVersion = BuildConfig.VERSION_NAME,
                        status = status,
                        canConsolidate = selectedDateUri != null && !consolidating && !merging,
                        canMerge = selectedDateUri != null && mergeProductCount >= 2 && !consolidating && !merging,
                        mergeProductCount = mergeProductCount,
                        merging = merging,
                        mergeProgress = mergeProgress,
                        mergeMessage = mergeMessage,
                        consolidating = consolidating,
                        report = consolidationReport,
                        onDismissReport = { consolidationReport = null },
                        onMerge = {
                            val folder = selectedDateUri
                            val root = rootTree
                            if (folder == null || root == null) {
                                mergeMessage = "Hãy chọn bộ nhớ và buổi quay trước."
                            } else {
                                merging = true
                                mergeProgress = 0f
                                mergeMessage = "Đang khởi chạy tác vụ ghép nền…"
                                scope.launch {
                                    try {
                                        val output = withContext(Dispatchers.IO) {
                                            StudioStorage.productFolderDocument(context, root)
                                        }
                                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                        val serviceIntent = Intent(context, StudioMergeService::class.java)
                                            .putExtra(StudioMergeService.EXTRA_FOLDER_URI, folder.toString())
                                            .putExtra(StudioMergeService.EXTRA_OUTPUT_URI, output.toString())
                                            .putExtra(StudioMergeService.EXTRA_STAMP, stamp)
                                        ContextCompat.startForegroundService(context, serviceIntent)
                                        mergeMessage = "Đã khởi chạy ghép nền. Có thể tắt màn hình; tiến trình sẽ tiếp tục."
                                    } catch (error: Throwable) {
                                        merging = false
                                        mergeMessage = "Không khởi chạy được ghép nền: ${error.localizedMessage ?: error.javaClass.simpleName}"
                                    }
                                }
                            }
                        },
                        onMedia = { selectedTab = 1 },
                        onOverlay = {
                            val output = selectedDateUri
                            if (output == null) Toast.makeText(context, "Chọn buổi quay trong DJI Media trước.", Toast.LENGTH_SHORT).show()
                            else runCatching {
                                context.startActivity(Intent(context, com.donglq.corosdjioverlay.OverlayActivity::class.java)
                                    .putExtra("studio_output_tree", output.toString()))
                            }.onFailure { Toast.makeText(context, "Không mở được Overlay.", Toast.LENGTH_SHORT).show() }
                        },
                        onVoiceGuard = {
                            val root = rootTree
                            val output = selectedDateUri
                            if (root == null || output == null) Toast.makeText(context, "Chọn bộ nhớ và buổi quay trong DJI Media trước.", Toast.LENGTH_SHORT).show()
                            else runCatching {
                                context.startActivity(Intent(context, com.voiceguard.VoiceGuardActivity::class.java)
                                    .putExtra("studio_root_tree", root.toString())
                                    .putExtra("studio_folder_document_id", android.provider.DocumentsContract.getDocumentId(output)))
                            }.onFailure { Toast.makeText(context, "Không mở được VoiceGuard.", Toast.LENGTH_SHORT).show() }
                        },
                        onConsolidate = {
                            val folder = selectedDateUri
                            if (folder == null) status = "Hãy chọn buổi quay trong DJI Media trước."
                            else {
                                consolidating = true
                                scope.launch {
                                    try {
                                        val report = withContext(Dispatchers.IO) { StudioSessionLog.consolidate(context, folder) }
                                        consolidationReport = report
                                        mergeProductCount = withContext(Dispatchers.IO) { StudioSessionLog.listConsolidatedProducts(context, folder).size }
                                        status = "Đã tổng hợp ${report.products} video."
                                        if (report.errors.isNotEmpty()) status += " Cần kiểm tra ${report.errors.size} mục."
                                    } catch (error: Throwable) {
                                        status = "Tổng hợp lỗi: ${error.localizedMessage ?: error.javaClass.simpleName}"
                                    } finally { consolidating = false }
                                }
                            }
                        }
                    )
                    1 -> {
                        Text("DJI Media", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Tự chuyển video từ DJI Mimo vào Studio sau khi tải xong.")
                        Spacer(Modifier.height(10.dp))
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("DJI Mover tự động", fontWeight = FontWeight.Bold)
                                        Text("Tự nhận video mới và quét bù định kỳ.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = autoMoveEnabled, onCheckedChange = { enabled ->
                                        val root = rootTree
                                        if (enabled && root == null) {
                                            Toast.makeText(context, "Hãy chọn thư mục gốc Studio trước.", Toast.LENGTH_LONG).show()
                                        } else if (enabled && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                                            Toast.makeText(context, "Cần cấp quyền truy cập tất cả tệp để theo dõi DCIM/DJI.", Toast.LENGTH_LONG).show()
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                    Uri.parse("package:" + context.packageName)))
                                            }
                                        } else {
                                            try {
                                                if (enabled && root != null) DjiAutoMoveWorker.start(context, root)
                                                else DjiAutoMoveWorker.stop(context)
                                                autoMoveEnabled = enabled
                                                moverStatus = if (enabled) "Đã bật DJI Mover. Đang chờ video tải hoàn tất."
                                                    else "Đã tắt DJI Mover."
                                            } catch (error: Throwable) {
                                                autoMoveEnabled = false
                                                DjiAutoMoveWorker.stop(context)
                                                moverStatus = "Không thể khởi chạy DJI Mover: ${error.localizedMessage ?: "lỗi hệ thống"}"
                                                Toast.makeText(context, moverStatus, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    })
                                }
                                if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                                    OutlinedButton(onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                Uri.parse("package:" + context.packageName)))
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Cấp quyền DCIM/DJI") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        val root = rootTree
                                        if (root != null) {
                                            DjiAutoMoveWorker.scanNow(context, root)
                                            moverStatus = "Đã yêu cầu quét video mới…"
                                        }
                                    }, enabled = rootTree != null, modifier = Modifier.weight(1f)) { Text("Quét ngay") }
                                    OutlinedButton(onClick = {
                                        moverStatus = moverPrefs.getString(DjiAutoMoveWorker.KEY_STATUS, "Chưa có trạng thái.") ?: "Chưa có trạng thái."
                                    }, modifier = Modifier.weight(1f)) { Text("Làm mới") }
                                }
                                Text(moverStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("BUỔI QUAY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { dateMenuExpanded = true }, enabled = dateFolders.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(selectedDateName?.let { "Ngày $it" } ?: if (loadingDates) "Đang quét thư mục ngày…" else "Chưa có thư mục ngày")
                                }
                                DropdownMenu(expanded = dateMenuExpanded, onDismissRequest = { dateMenuExpanded = false }) {
                                    dateFolders.forEach { item ->
                                        DropdownMenuItem(
                                            text = { Text("${item.name}  •  ${item.videoCount} video") },
                                            onClick = {
                                                selectedDateUri = item.uri
                                                selectedDateName = item.name
                                                studioPrefs.edit()
                                                    .putString("selected_date_folder_uri", item.uri.toString())
                                                    .putString("selected_date_folder_name", item.name).apply()
                                                dateMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    loadingDates = true
                                    val found = withContext(Dispatchers.IO) {
                                        rootTree?.let { runCatching { StudioStorage.listDateFolders(context, it) }.getOrDefault(emptyList()) }
                                            ?: emptyList()
                                    }
                                    dateFolders = found
                                    val selected = found.firstOrNull { it.uri == selectedDateUri } ?: found.firstOrNull()
                                    selectedDateUri = selected?.uri
                                    selectedDateName = selected?.name
                                    selected?.let { studioPrefs.edit().putString("selected_date_folder_uri", it.uri.toString())
                                        .putString("selected_date_folder_name", it.name).apply() }
                                    loadingDates = false
                                }
                            }, enabled = rootTree != null && !loadingDates) { Text("↻") }
                        }
                        Text(selectedDateName?.let { "Overlay, VG và Tổng hợp sẽ dùng thư mục ngày $it." }
                            ?: "Chọn bộ nhớ gốc Studio để quét các buổi quay.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("KIỂM TRA HƯỚNG VIDEO", fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge)
                                Text("Quét metadata toàn bộ video trong buổi quay để tìm clip có rotation 90°/180°/270° hoặc khác hướng ngang/dọc với đa số.",
                                    style = MaterialTheme.typography.bodySmall)
                                Button(onClick = {
                                    val folder = selectedDateUri
                                    if (folder == null) {
                                        Toast.makeText(context, "Chọn buổi quay trước.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        scanningOrientation = true
                                        orientationScanMessage = "Đang quét metadata video trong buổi quay…"
                                        scope.launch {
                                            try {
                                                val report = MediaOrientationScanner.scan(context, folder)
                                                orientationReport = report
                                                selectedOrientationUris = emptySet()
                                                orientationActionMessage = ""
                                                orientationScanMessage = "Đã quét ${report.total} video. Xem thống kê và chọn clip cần xử lý bên dưới."
                                                status = orientationScanMessage
                                            } catch (error: Throwable) {
                                                val reason = error.localizedMessage ?: error.javaClass.simpleName
                                                orientationScanMessage = "QUÉT HƯỚNG VIDEO THẤT BẠI: $reason"
                                                status = orientationScanMessage
                                            } finally {
                                                scanningOrientation = false
                                            }
                                        }
                                    }
                                }, enabled = selectedDateUri != null && !scanningOrientation,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (scanningOrientation) "Đang quét metadata…" else "Quét hướng video của buổi quay")
                                }
                                if (orientationScanMessage.isNotBlank() && orientationReport == null) {
                                    Text(orientationScanMessage, style = MaterialTheme.typography.bodySmall)
                                }
                                orientationReport?.let { report ->
                                    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("TỔNG HỢP HƯỚNG VIDEO", fontWeight = FontWeight.Bold)
                                            Text("${report.total} VIDEO • HƯỚNG KHUNG HÌNH CHÍNH: ${when (report.dominantLandscape) { true -> "NGANG"; false -> "DỌC"; null -> "CHƯA XÁC ĐỊNH" }}",
                                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Metadata xoay 180°")
                                                Text("${report.rotation180Count}", fontWeight = FontWeight.Bold)
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Metadata xoay 0°")
                                                Text("${report.rotation0Count}", fontWeight = FontWeight.Bold)
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Xoay 90°/270°")
                                                Text("${report.rotationOtherCount}", fontWeight = FontWeight.Bold)
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Không đọc được rotation")
                                                Text("${report.rotationUnknownCount}", fontWeight = FontWeight.Bold)
                                            }
                                            Text("Ngang: ${report.landscapeCount} • Dọc: ${report.portraitCount} • Không xác định tỷ lệ: ${report.unknownCount}",
                                                style = MaterialTheme.typography.bodySmall)
                                            HorizontalDivider()
                                            Text("CHỌN CLIP CẦN XỬ LÝ", fontWeight = FontWeight.Bold)
                                            Text("Metadata chỉ cho biết góc xoay được ghi trong file, không tự kết luận hình ảnh đang ngược. Hãy xem thử clip trước khi chuyển vào lưu trữ hoặc tạo bản xoay.",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val groups = listOf(
                                                "METADATA 180°" to report.items.filter { it.rotationDegrees == 180 },
                                                "METADATA 0°" to report.items.filter { it.rotationDegrees == 0 },
                                                "GÓC XOAY KHÁC" to report.items.filter { it.rotationDegrees != null && it.rotationDegrees != 0 && it.rotationDegrees != 180 },
                                                "KHÔNG ĐỌC ĐƯỢC" to report.items.filter { it.rotationDegrees == null }
                                            )
                                            val nonEmptyGroups = groups.filter { it.second.isNotEmpty() }
                                            val largestGroup = nonEmptyGroups.maxByOrNull { it.second.size }
                                            @Composable fun renderOrientationItem(item: MediaOrientationItem) {
                                                OrientationItemRow(
                                                    item = item,
                                                    checked = item.uri.toString() in selectedOrientationUris,
                                                    enabled = !orientationActionBusy,
                                                    onCheckedChange = { checked ->
                                                        selectedOrientationUris = if (checked)
                                                            selectedOrientationUris + item.uri.toString()
                                                        else selectedOrientationUris - item.uri.toString()
                                                    }
                                                )
                                            }
                                            // Keep small exception groups visible first; only the largest group scrolls.
                                            nonEmptyGroups.filter { it != largestGroup }.forEach { (label, groupItems) ->
                                                Text("$label (${groupItems.size})", fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary)
                                                groupItems.forEach { item -> renderOrientationItem(item) }
                                            }
                                            largestGroup?.let { (label, groupItems) ->
                                                Text("$label (${groupItems.size})", fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary)
                                                Text("Nhóm đông nhất • cuộn trong khung dưới đây để xem từng video",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Column(
                                                    Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                                        .verticalScroll(rememberScrollState()),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    groupItems.forEach { item -> renderOrientationItem(item) }
                                                }
                                            }
                                            Text("Đã chọn: ${selectedOrientationUris.size} clip",
                                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        pendingDeleteItems = report.items.filter { it.uri.toString() in selectedOrientationUris }
                                                    },
                                                    enabled = selectedOrientationUris.isNotEmpty() && !orientationActionBusy,
                                                    modifier = Modifier.weight(1f)
                                                ) { Text("Chuyển vào lưu trữ") }
                                                Button(
                                                    onClick = {
                                                        val folder = selectedDateUri ?: return@Button
                                                        val selected = report.items.filter { it.uri.toString() in selectedOrientationUris }
                                                        if (selected.isEmpty()) return@Button
                                                        orientationActionBusy = true
                                                        orientationActionMessage = "Đang tạo bản xoay 180°: 0/${selected.size}"
                                                        scope.launch {
                                                            var done = 0
                                                            val errors = mutableListOf<String>()
                                                            selected.forEach { item ->
                                                                try {
                                                                    val outputName = MediaOrientationActions.rotate180InSession(context, folder, item)
                                                                    orientationActionMessage = "Đã xoay và thay thế $outputName; bản gốc đã lưu trong luu_tru"
                                                                } catch (error: Throwable) {
                                                                    errors += "${item.name}: ${error.localizedMessage ?: error.javaClass.simpleName}"
                                                                }
                                                                done++
                                                                orientationActionMessage = "Đã xử lý $done/${selected.size} clip"
                                                            }
                                                            selectedOrientationUris = emptySet()
                                                            orientationReport = MediaOrientationScanner.scan(context, folder)
                                                            orientationActionMessage = "Đã xoay và thay thế ${done - errors.size} video trong thư mục ngày; bản gốc được lưu trong luu_tru." +
                                                                if (errors.isNotEmpty()) " Lỗi: ${errors.take(3).joinToString("; ")}" else ""
                                                            orientationActionBusy = false
                                                        }
                                                    },
                                                    enabled = selectedOrientationUris.isNotEmpty() && !orientationActionBusy,
                                                    modifier = Modifier.weight(1f)
                                                ) { Text("Tạo bản xoay 180°") }
                                            }
                                            if (orientationActionBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                            if (orientationActionMessage.isNotBlank()) {
                                                Text(orientationActionMessage, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text("Video đã xoay nằm ngay trong thư mục ngày với tên gốc để OVL và VoiceGuard sử dụng. Bản gốc được giữ trong luu_tru.",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                pendingDeleteItems?.let { deleting ->
                                    AlertDialog(
                                        onDismissRequest = { if (!orientationActionBusy) pendingDeleteItems = null },
                                        title = { Text("Chuyển video vào luu_tru?") },
                                        text = { Text("Sẽ chuyển ${deleting.size} video đã chọn từ thư mục ngày vào luu_tru. File được giữ nguyên để có thể xem lại hoặc khôi phục; không xóa video nguồn trong DJI Mimo.") },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    pendingDeleteItems = null
                                                    orientationActionBusy = true
                                                    orientationActionMessage = "Đang chuyển video vào luu_tru…"
                                                    scope.launch {
                                                        val (deleted, failed) = MediaOrientationActions.archiveSelected(context, selectedDateUri ?: return@launch, deleting)
                                                        selectedDateUri?.let { folder ->
                                                            orientationReport = MediaOrientationScanner.scan(context, folder)
                                                        }
                                                        selectedOrientationUris = emptySet()
                                                        orientationActionMessage = "Đã chuyển ${deleted.size} video vào luu_tru." +
                                                            if (failed.isNotEmpty()) " Không chuyển được: ${failed.take(3).joinToString("; ")}" else ""
                                                        orientationActionBusy = false
                                                    }
                                                },
                                                enabled = !orientationActionBusy,
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) { Text("Chuyển vào luu_tru") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { pendingDeleteItems = null }, enabled = !orientationActionBusy) { Text("Hủy") }
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) { Text("Chọn video (${selectedUris.size})") }
                        OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (rootTree == null) "Chọn thư mục Studio" else "Đổi thư mục Studio")
                        }
                        Button(onClick = {
                            val root = rootTree ?: return@Button
                            val sources = selectedUris
                            if (sources.isEmpty()) return@Button
                            busy = true
                            progress = 0f
                            progressText = "Đang chuẩn bị…"
                            scope.launch {
                                val report = StudioStorage.moveSelected(context, sources, root) { done, total, name ->
                                    progress = if (total == 0) 0f else done.toFloat() / total
                                    progressText = if (name.isBlank()) "Đã xử lý $done/$total"
                                        else "Đang chuyển $name (${done + 1}/$total)"
                                }
                                movedVideos = (movedVideos + report.moved).distinctBy { it.uri }.sortedBy { it.captureTimeMillis }
                                report.moved.firstOrNull()?.let { first ->
                                    lastCaptureTime = first.captureTimeMillis
                                    studioPrefs.edit().putLong("last_capture_time", first.captureTimeMillis).apply()
                                }
                                selectedUris = emptyList()
                                busy = false
                                status = "Đã chuyển ${report.moved.size} video vào dji_studio theo ngày." +
                                    (if (report.failed.isNotEmpty()) "\nKhông chuyển được: ${report.failed.joinToString("; ")}" else "") +
                                    (if (report.sourceNotDeleted.isNotEmpty()) "\nĐã chép nhưng nguồn vẫn còn: ${report.sourceNotDeleted.joinToString(", ")}" else "")
                            }
                        }, enabled = !busy && selectedUris.isNotEmpty() && rootTree != null,
                            modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Đang chuyển…" else "Chuyển vào Studio") }
                        if (busy) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            Text(progressText, style = MaterialTheme.typography.bodySmall)
                        }
                        StatusCard(status)
                        Spacer(Modifier.height(8.dp))
                        Text("CÔNG CỤ PHIÊN QUAY", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = {
                            val output = selectedDateUri
                            if (output == null) Toast.makeText(context, "Hãy chọn buổi quay trong DJI Media trước.", Toast.LENGTH_SHORT).show()
                            else context.startActivity(Intent(context, com.donglq.corosdjioverlay.OverlayActivity::class.java)
                                .putExtra("studio_output_tree", output.toString()))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Cấu hình COROS Overlay") }
                        OutlinedButton(onClick = {
                            val root = rootTree
                            val output = selectedDateUri
                            if (root == null || output == null) Toast.makeText(context, "Hãy chọn bộ nhớ gốc và buổi quay trong DJI Media trước.", Toast.LENGTH_SHORT).show()
                            else context.startActivity(Intent(context, com.voiceguard.VoiceGuardActivity::class.java)
                                .putExtra("studio_root_tree", root.toString())
                                .putExtra("studio_folder_document_id", android.provider.DocumentsContract.getDocumentId(output)))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Mở VoiceGuard") }
                        Spacer(Modifier.height(10.dp))
                        Text("Video đã đưa vào Studio: ${movedVideos.size}", fontWeight = FontWeight.Bold)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            movedVideos.forEach { item ->
                                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text("${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(item.captureTimeMillis))} • ${item.sizeBytes / (1024 * 1024)} MB",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    2 -> ModulePage("Overlay", "Đọc file FIT, căn thời gian video và render route GPS, mũi tên, pace, cadence, nhịp tim, quãng đường và độ cao.") {
                        val output = selectedDateUri
                        if (output == null) status = "Hãy vào DJI Media và chọn buổi quay trước."
                        else try {
                            context.startActivity(Intent(context, com.donglq.corosdjioverlay.OverlayActivity::class.java)
                                .putExtra("studio_output_tree", output.toString()))
                        } catch (error: Throwable) {
                            status = "Không mở được COROS Overlay: ${error.localizedMessage ?: "lỗi không xác định"}"
                            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> ModulePage("VoiceGuard", "Quét voice, nghe liên tục hoặc nhận dạng phụ đề, rồi chọn đoạn cần che.") {
                        val root = rootTree
                        val outputDocument = selectedDateUri
                        if (root == null || outputDocument == null) {
                            status = "Vào DJI Media, chọn bộ nhớ gốc và buổi quay để tiếp tục."
                            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                        } else try {
                            context.startActivity(Intent(context, com.voiceguard.VoiceGuardActivity::class.java)
                                .putExtra("studio_root_tree", root.toString())
                                .putExtra("studio_folder_document_id", android.provider.DocumentsContract.getDocumentId(outputDocument)))
                        } catch (error: Throwable) {
                            status = "Không mở được VoiceGuard: ${error.localizedMessage ?: "lỗi không xác định"}"
                            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrientationItemRow(
    item: MediaOrientationItem,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
            Text(item.note, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Overview(
    appVersion: String,
    status: String,
    canConsolidate: Boolean,
    canMerge: Boolean,
    mergeProductCount: Int,
    merging: Boolean,
    mergeProgress: Float,
    mergeMessage: String,
    consolidating: Boolean,
    report: StudioConsolidationReport?,
    onDismissReport: () -> Unit,
    onMerge: () -> Unit,
    onMedia: () -> Unit,
    onOverlay: () -> Unit,
    onVoiceGuard: () -> Unit,
    onConsolidate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeAction("▣", "DJI Media", onMedia, Modifier.weight(1f))
            HomeAction("⌁", "Overlay", onOverlay, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeAction("◉", "VoiceGuard", onVoiceGuard, Modifier.weight(1f))
            HomeAction("▤", if (consolidating) "Đang gom…" else "Tổng hợp", onConsolidate, Modifier.weight(1f), enabled = canConsolidate)
        }
        HomeAction("⧉", if (merging) "Đang ghép…" else "Ghép video ($mergeProductCount)", onMerge,
            Modifier.fillMaxWidth(), enabled = canMerge)
        if (merging) LinearProgressIndicator(progress = { mergeProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        if (mergeMessage.isNotBlank()) {
            Text(mergeMessage, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        if (status.isNotBlank() && (status.contains("lỗi", true) || status.contains("xong", true) || status.contains("đã tổng hợp", true))) {
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        if (report != null) {
            AlertDialog(
                onDismissRequest = onDismissReport,
                title = { Text("Báo cáo tổng hợp ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Video gốc đã nạp: ${report.originals}", fontWeight = FontWeight.SemiBold)
                        Text("Phủ dữ liệu COROS: ${report.overlayCount}/${report.originals} video • ${formatReportDuration(report.overlayDurationMs)}")
                        Text("Che âm thanh VG: ${report.maskedCount}/${report.originals} video • ${formatReportDuration(report.maskedDurationMs)}")
                        Text("File OVL đã tạo: ${report.overlayCount}")
                        Text("Video gốc đã chuyển vào Studio_Luu_tru: ${report.archivedOriginals}")
                        if (report.archivedCleaned > 0) Text("Đã dọn ${report.archivedCleaned} file lưu trữ quá 7 ngày.")
                        Text("Tổng thời gian các công đoạn: ${formatReportDuration(report.importDurationMs + report.overlayDurationMs + report.maskedDurationMs)}",
                            fontWeight = FontWeight.SemiBold)
                        if (report.errors.isNotEmpty()) {
                            Divider()
                            Text("Có ${report.errors.size} mục cần kiểm tra:", color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold)
                            report.errors.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            if (report.errors.size > 5) Text("… và ${report.errors.size - 5} mục khác", style = MaterialTheme.typography.bodySmall)
                        }
                        if (report.importDurationMs == 0L) {
                            Text("Thời gian nạp video chưa được ghi nhận cho các file đã có từ phiên bản cũ.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismissReport) { Text("Đóng") } }
            )
        }
        Spacer(Modifier.height(2.dp))
        Text("v$appVersion", modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private fun formatReportDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "chưa ghi nhận"
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "$minutes phút $seconds giây" else "$seconds giây"
}

@Composable
private fun HomeAction(
    symbol: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(10.dp)
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(symbol, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun ModulePage(title: String, description: String, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        StatusCard(description)
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Mở $title") }
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}

// Orientation report summary is intentionally grouped into one visible result card.
