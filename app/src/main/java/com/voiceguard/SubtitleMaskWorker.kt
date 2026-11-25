package com.voiceguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.corosdjistudio.StudioSessionLog
import org.json.JSONArray
import org.json.JSONObject
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground, resumable subtitle masking. The session JSON is durable in filesDir;
 * each successful output is checkpointed before the next clip starts.
 */
class SubtitleMaskWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channel = "vg_subtitle_mask"

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createChannel()
        return foreground("Đang chuẩn bị che âm thanh…", 0, 0)
    }

    override suspend fun doWork(): Result {
        val sessionPath = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val sessionFile = File(sessionPath)
        if (!sessionFile.exists()) return Result.failure()
        return try {
            createChannel()
            setForeground(foreground("Đang chuẩn bị che âm thanh…", 0, 0))
            val session = JSONObject(sessionFile.readText())
            val tree = Uri.parse(session.getString("tree"))
            val folderId = session.optString("folderId").takeIf { it.isNotBlank() }
            val musicUri = Uri.parse(session.getString("musicUri"))
            val music = withContext(Dispatchers.IO) {
                MusicSource.load(applicationContext, musicUri)
            } ?: error("Không đọc được âm thanh che")
            val outDir = folderId?.let {
                MaskExporter.ensureDirInFolder(applicationContext, tree, it, MaskExporter.OUTPUT_FOLDER)
            } ?: MaskExporter.ensureDir(applicationContext, tree, MaskExporter.OUTPUT_FOLDER)
            val logFolder = folderId?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) } ?: tree
            val words = session.getJSONArray("words")
            val spans = session.getJSONArray("spans")
            val mapped = linkedMapOf<String, Pair<String, MutableList<VoiceSegment>>>()
            for (i in 0 until words.length()) {
                val w = words.getJSONObject(i)
                val start = w.getDouble("start").toFloat()
                val end = w.getDouble("end").toFloat()
                for (j in 0 until spans.length()) {
                    val s = spans.getJSONObject(j)
                    val from = maxOf(start, s.getDouble("timelineStart").toFloat())
                    val to = minOf(end, s.getDouble("timelineEnd").toFloat())
                    if (to <= from) continue
                    val uri = s.getString("uri")
                    val name = s.getString("name")
                    val localStart = s.getDouble("clipStart").toFloat() + from - s.getDouble("timelineStart").toFloat()
                    val localEnd = s.getDouble("clipStart").toFloat() + to - s.getDouble("timelineStart").toFloat()
                    val entry = mapped.getOrPut(uri) { name to mutableListOf() }
                    entry.second += VoiceSegment(localStart, localEnd)
                }
            }
            if (mapped.isEmpty()) error("Không ánh xạ được từ đã chọn vào video")
            var completed = session.optJSONArray("completed") ?: JSONArray()
            val completedSet = (0 until completed.length()).map { completed.optString(it) }.toMutableSet()
            val total = mapped.size
            var done = completedSet.size
            for ((clipUri, pair) in mapped) {
                if (clipUri in completedSet) continue
                if (isStopped) return Result.retry()
                val (name, ranges) = pair
                val merged = ranges.sortedBy { it.startSec }.fold(mutableListOf<VoiceSegment>()) { acc, next ->
                    val last = acc.lastOrNull()
                    if (last != null && next.startSec <= last.endSec + 0.03f)
                        acc[acc.lastIndex] = last.copy(endSec = maxOf(last.endSec, next.endSec))
                    else acc.add(next)
                    acc
                }
                // If the process stopped after rendering but before checkpointing, safely rerender
                // this one clip; maskOne replaces the same output atomically at completion.
                MaskExporter.maskOne(applicationContext, tree, Uri.parse(clipUri), name,
                    music, merged, outDir, logFolder)
                completedSet += clipUri
                completed = JSONArray().apply { completedSet.forEach { put(it) } }
                session.put("completed", completed)
                sessionFile.writeText(session.toString())
                done++
                setForeground(foreground("Đã che $done/$total video · $name", done, total))
                setProgress(androidx.work.workDataOf("done" to done, "total" to total, "name" to name))
            }
            session.put("finished", true)
            sessionFile.writeText(session.toString())
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("COROS × DJI · Che phụ đề hoàn tất")
                .setContentText("Đã xuất $total video")
                .setAutoCancel(true).build())
            Result.success()
        } catch (t: Throwable) {
            android.util.Log.e("VG_SUBTITLE_MASK", "Worker failed", t)
            Result.failure(androidx.work.workDataOf(
                "error" to (t.localizedMessage ?: t.javaClass.simpleName)
            ))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(channel, "VoiceGuard subtitle masking", NotificationManager.IMPORTANCE_LOW))
    }
    private fun foreground(message: String, done: Int, total: Int): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("COROS × DJI · Đang che âm thanh")
            .setContentText(message).setOngoing(true)
            .setProgress(total, done, total == 0).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }
    companion object {
        const val KEY_SESSION = "subtitle_mask_session"
        private const val NOTIFICATION_ID = 4281
    }
}
