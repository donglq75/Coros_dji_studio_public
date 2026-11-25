package com.corosdjistudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.voiceguard.Concatenator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * User-initiated, long-running product merge.
 *
 * A foreground dataSync service plus a partial wake lock keeps CPU work alive
 * while the display is off. Inputs are persisted so START_STICKY can restore
 * the operation after Android recreates the service; Concatenator checkpoints
 * resume completed mux/normalization groups.
 */
class StudioMergeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mergeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ghép video Studio", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(notification("Đang khởi tạo tác vụ ghép video…"))
        acquireWakeLock()

        if (intent?.hasExtra(EXTRA_FOLDER_URI) == true) {
            prefs.edit()
                .putString(KEY_FOLDER_URI, intent.getStringExtra(EXTRA_FOLDER_URI))
                .putString(KEY_OUTPUT_URI, intent.getStringExtra(EXTRA_OUTPUT_URI))
                .putString(KEY_STAMP, intent.getStringExtra(EXTRA_STAMP))
                .putBoolean(KEY_RUNNING, true)
                .putFloat(KEY_PROGRESS, 0f)
                .putString(KEY_STATUS, "Đang chuẩn bị ghép video…")
                .commit()
        }

        if (mergeJob?.isActive != true) startOrRestore()
        return START_STICKY
    }

    private fun startForegroundCompat(value: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, value)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CorosDjiStudio:ProductMerge").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun startOrRestore() {
        val folderRaw = prefs.getString(KEY_FOLDER_URI, null)
        val outputRaw = prefs.getString(KEY_OUTPUT_URI, null)
        val stamp = prefs.getString(KEY_STAMP, null)
        if (!prefs.getBoolean(KEY_RUNNING, false) || folderRaw.isNullOrBlank() ||
            outputRaw.isNullOrBlank() || stamp.isNullOrBlank()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        mergeJob = serviceScope.launch {
            try {
                val folder = Uri.parse(folderRaw)
                val output = Uri.parse(outputRaw)
                val files = withContext(Dispatchers.IO) {
                    StudioSessionLog.listConsolidatedProducts(this@StudioMergeService, folder)
                }
                if (files.size < 2) error("Cần ít nhất 2 video đã tổng hợp để ghép.")

                val result = Concatenator.concatResumable(
                    this@StudioMergeService,
                    files,
                    output,
                    stamp,
                    outputPrefix = "DJI_Studio_Product",
                    chunkSize = 8
                ) { done, total, name ->
                    val progress = if (total > 0) done.toFloat() / total else 0f
                    val message = when {
                        name.isBlank() -> "Đang hoàn tất file sản phẩm…"
                        name.startsWith("Đang ") -> name
                        else -> "Đang ghép ${done + 1}/${total}: $name"
                    }
                    publishProgress(progress, message)
                }

                val finalMessage = buildString {
                    append("Đã ghép ${result.clips} video thành ${result.parts} file trong dji_studio/product.")
                    if (result.skipped.isNotEmpty()) append(" Bỏ qua ${result.skipped.size} file lỗi/không đọc được.")
                }
                prefs.edit()
                    .putBoolean(KEY_RUNNING, false)
                    .putFloat(KEY_PROGRESS, 1f)
                    .putString(KEY_STATUS, finalMessage)
                    .remove(KEY_FOLDER_URI)
                    .remove(KEY_OUTPUT_URI)
                    .remove(KEY_STAMP)
                    .apply()
                updateNotification(finalMessage, ongoing = false)
                sendStatus(finalMessage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = "Ghép video lỗi: ${error.localizedMessage ?: error.javaClass.simpleName}"
                prefs.edit().putBoolean(KEY_RUNNING, false).putString(KEY_STATUS, message).apply()
                updateNotification(message, ongoing = false)
                sendStatus(message)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun publishProgress(progress: Float, message: String) {
        prefs.edit()
            .putFloat(KEY_PROGRESS, progress.coerceIn(0f, 1f))
            .putString(KEY_STATUS, message)
            .apply()
        updateNotification(message, ongoing = true)
        sendStatus(message)
    }

    private fun notification(message: String, ongoing: Boolean = true): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("COROS × DJI Studio")
            .setContentText(message.take(120))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, ongoing))
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val WORK_NAME = "studio_product_merge"
        const val PREFS = "studio_product_merge_service"
        const val KEY_RUNNING = "running"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val ACTION_STATUS = "com.corosdjistudio.STUDIO_MERGE_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val EXTRA_OUTPUT_URI = "output_uri"
        const val EXTRA_STAMP = "stamp"
        private const val KEY_FOLDER_URI = "folder_uri_saved"
        private const val KEY_OUTPUT_URI = "output_uri_saved"
        private const val KEY_STAMP = "stamp_saved"
        private const val CHANNEL_ID = "studio_product_merge"
        private const val NOTIFICATION_ID = 7312
    }
}
