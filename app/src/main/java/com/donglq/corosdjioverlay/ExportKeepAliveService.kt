package com.donglq.corosdjioverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ExportKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var exporter: VideoOverlayBatchExporter? = null
    private val prefs by lazy { getSharedPreferences("coros_service_export", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "COROS DJI export", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Đang chuẩn bị render video…"))
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CorosDjiOverlay:BatchExport").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (intent?.action == ACTION_STOP_AFTER_CURRENT) {
            exporter?.requestStopAfterCurrent()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_AFTER_CURRENT) {
            exporter?.requestCancelAfterCurrent()
            return START_NOT_STICKY
        }

        if (intent?.hasExtra(EXTRA_VIDEOS) == true) {
            prefs.edit()
                .putString(KEY_VIDEOS, intent.getStringExtra(EXTRA_VIDEOS))
                // A user-started batch is a fresh run; process-death recovery keeps this ID.
                .putString(KEY_SESSION_ID, UUID.randomUUID().toString())
                .putString(KEY_FIT_URI, intent.getStringExtra(EXTRA_FIT_URI))
                .putLong(KEY_OFFSET, intent.getLongExtra(EXTRA_OFFSET, 0L))
                .putString(KEY_OUTPUT_TREE, intent.getStringExtra(EXTRA_OUTPUT_TREE))
                .putBoolean(KEY_SHOW_PACE, intent.getBooleanExtra(EXTRA_SHOW_PACE, true))
                .putBoolean(KEY_SHOW_CADENCE, intent.getBooleanExtra(EXTRA_SHOW_CADENCE, true))
                .putBoolean(KEY_SHOW_HR, intent.getBooleanExtra(EXTRA_SHOW_HR, true))
                .putBoolean(KEY_SHOW_DISTANCE, intent.getBooleanExtra(EXTRA_SHOW_DISTANCE, true))
                .putBoolean(KEY_SHOW_ELAPSED, intent.getBooleanExtra(EXTRA_SHOW_ELAPSED, true))
                .putBoolean(KEY_SHOW_ALTITUDE, intent.getBooleanExtra(EXTRA_SHOW_ALTITUDE, true))
                .putBoolean(KEY_SHOW_ROUTE, intent.getBooleanExtra(EXTRA_SHOW_ROUTE, true))
                .putFloat(KEY_TEXT_SCALE, intent.getFloatExtra(EXTRA_TEXT_SCALE, 1f))
                .putString(KEY_TEXT_COLOR, intent.getStringExtra(EXTRA_TEXT_COLOR) ?: "white")
                .putString(KEY_TEXT_FONT, intent.getStringExtra(EXTRA_TEXT_FONT) ?: "sans-serif")
                .putBoolean(KEY_ITALIC_TEXT, intent.getBooleanExtra(EXTRA_ITALIC_TEXT, true))
                .putBoolean(KEY_RUNNING, true)
                .putBoolean(KEY_PENDING, true)
                .putString(KEY_REVIEW_URIS, "[]")
                .putString(KEY_STATUS, "Đang chuẩn bị render video…")
                .apply()
        }
        if (exporter == null) {
            if (intent?.hasExtra(EXTRA_VIDEOS) != true) {
                prefs.edit().putBoolean(KEY_RUNNING, true).putBoolean(KEY_PENDING, true)
                    .putString(KEY_STATUS, "Đang tiếp tục phiên render…").apply()
            }
            startOrRestore()
        }
        return START_STICKY
    }

    private fun startOrRestore() {
        try {
            val rawVideos = prefs.getString(KEY_VIDEOS, null) ?: error("Không tìm thấy danh sách video để khôi phục.")
            val fitRaw = prefs.getString(KEY_FIT_URI, null) ?: error("Không tìm thấy file FIT để khôi phục.")
            val videosJson = JSONArray(rawVideos)
            val videos = (0 until videosJson.length()).map { i ->
                val item = videosJson.getJSONObject(i)
                VideoExportItem(
                    Uri.parse(item.getString("uri")),
                    item.getString("name"),
                    item.getLong("start"),
                    item.getLong("duration"),
                    item.getInt("width"),
                    item.getInt("height")
                )
            }
            val fitUri = Uri.parse(fitRaw)
            val fit = contentResolver.openInputStream(fitUri)?.use { FitParser.parse(it) }
                ?: error("Không mở được file FIT.")
            if (fit.points.isEmpty()) error(fit.error ?: "File FIT không có dữ liệu.")
            val offset = prefs.getLong(KEY_OFFSET, 0L)
            exporter = VideoOverlayBatchExporter(
                this, videos, fit, offset,
                prefs.getString(KEY_SESSION_ID, "legacy") ?: "legacy",
                prefs.getString(KEY_OUTPUT_TREE, null)?.let(Uri::parse),
                prefs.getBoolean(KEY_SHOW_PACE, true), prefs.getBoolean(KEY_SHOW_CADENCE, true), prefs.getBoolean(KEY_SHOW_HR, true),
                prefs.getBoolean(KEY_SHOW_DISTANCE, true), prefs.getBoolean(KEY_SHOW_ELAPSED, true),
                prefs.getBoolean(KEY_SHOW_ALTITUDE, true), prefs.getBoolean(KEY_SHOW_ROUTE, true),
                prefs.getFloat(KEY_TEXT_SCALE, 1f),
                prefs.getString(KEY_TEXT_COLOR, "white") ?: "white",
                prefs.getString(KEY_TEXT_FONT, "sans-serif") ?: "sans-serif",
                prefs.getBoolean(KEY_ITALIC_TEXT, true),
                { message ->
                    sendStatus(message)
                    updateNotification(message)
                },
                { message ->
                    sendStatus(message)
                    if (!message.startsWith("Đã dừng:")) {
                        // Normal completion or explicit Hủy: discard only the pending queue.
                        // Published OVL files and the completion journal remain untouched.
                        prefs.edit().remove(KEY_VIDEOS).remove(KEY_FIT_URI).remove(KEY_OFFSET)
                            .putBoolean(KEY_PENDING, false).apply()
                    } else {
                        // Dừng keeps the batch payload so the remaining clips can be resumed.
                        prefs.edit().putBoolean(KEY_PENDING, true).apply()
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            )
            exporter?.start()
        } catch (error: Throwable) {
            sendStatus("Không thể khôi phục batch: ${error.localizedMessage ?: "lỗi không xác định"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("COROS × DJI")
            .setContentText(message.take(120))
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun sendStatus(message: String) {
        val finished = message.startsWith("Kết thúc:") ||
            message.startsWith("Không thể khôi phục batch:") ||
            message.startsWith("Đã dừng:") || message.startsWith("Đã hủy:")
        if (message.contains("Đã lưu video")) {
            val rawUri = Regex("(content://[^\\s]+)").find(message)?.groupValues?.getOrNull(1)
            val uri = rawUri?.substringBefore("•")?.trimEnd()
            if (!uri.isNullOrBlank()) {
                val list = runCatching { JSONArray(prefs.getString(KEY_REVIEW_URIS, "[]")) }.getOrElse { JSONArray() }
                if ((0 until list.length()).none { list.optString(it) == uri }) list.put(uri)
                prefs.edit().putString(KEY_REVIEW_URIS, list.toString()).apply()
            }
        }
        prefs.edit()
            .putString(KEY_STATUS, message)
            .putBoolean(KEY_RUNNING, !finished)
            .putBoolean(KEY_PENDING, when {
                message.startsWith("Đã hủy:") || message.startsWith("Kết thúc:") ||
                    message.startsWith("Không thể khôi phục batch:") -> false
                message.startsWith("Đã dừng:") -> true
                else -> prefs.getBoolean(KEY_PENDING, false)
            })
            .apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    override fun onDestroy() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS = "com.donglq.corosdjioverlay.EXPORT_STATUS"
        const val ACTION_STOP_AFTER_CURRENT = "com.donglq.corosdjioverlay.STOP_AFTER_CURRENT"
        const val ACTION_CANCEL_AFTER_CURRENT = "com.donglq.corosdjioverlay.CANCEL_AFTER_CURRENT"
        const val EXTRA_STATUS = "status"
        const val EXTRA_VIDEOS = "videos"
        const val EXTRA_FIT_URI = "fit_uri"
        const val EXTRA_OFFSET = "offset"
        const val EXTRA_OUTPUT_TREE = "output_tree"
        const val EXTRA_SHOW_PACE = "show_pace"
        const val EXTRA_SHOW_CADENCE = "show_cadence"
        const val EXTRA_SHOW_HR = "show_hr"
        const val EXTRA_SHOW_DISTANCE = "show_distance"
        const val EXTRA_SHOW_ELAPSED = "show_elapsed"
        const val EXTRA_SHOW_ALTITUDE = "show_altitude"
        const val EXTRA_SHOW_ROUTE = "show_route"
        const val EXTRA_TEXT_SCALE = "text_scale"
        const val EXTRA_TEXT_COLOR = "text_color"
        const val EXTRA_TEXT_FONT = "text_font"
        const val EXTRA_ITALIC_TEXT = "italic_text"
        private const val KEY_OUTPUT_TREE = "output_tree"
        private const val KEY_SHOW_PACE = "show_pace"
        private const val KEY_SHOW_CADENCE = "show_cadence"
        private const val KEY_SHOW_HR = "show_hr"
        private const val KEY_SHOW_DISTANCE = "show_distance"
        private const val KEY_SHOW_ELAPSED = "show_elapsed"
        private const val KEY_SHOW_ALTITUDE = "show_altitude"
        private const val KEY_SHOW_ROUTE = "show_route"
        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_TEXT_FONT = "text_font"
        private const val KEY_ITALIC_TEXT = "italic_text"
        private const val KEY_VIDEOS = "videos"
        private const val KEY_FIT_URI = "fit_uri"
        private const val KEY_OFFSET = "offset"
        private const val KEY_SESSION_ID = "session_id"
        const val KEY_RUNNING = "export_running"
        const val KEY_PENDING = "export_pending"
        const val KEY_STATUS = "export_status"
        const val KEY_REVIEW_URIS = "review_uris"
        private const val CHANNEL_ID = "coros_dji_export"
        private const val NOTIFICATION_ID = 4217
    }
}
