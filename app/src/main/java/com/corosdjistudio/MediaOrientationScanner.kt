package com.corosdjistudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaOrientationItem(
    val name: String,
    val rotationDegrees: Int?,
    val width: Int?,
    val height: Int?,
    val effectiveLandscape: Boolean?,
    val note: String
)

data class MediaOrientationReport(
    val total: Int,
    val landscapeCount: Int,
    val portraitCount: Int,
    val unknownCount: Int,
    val dominantLandscape: Boolean?,
    val outliers: List<MediaOrientationItem>,
    val items: List<MediaOrientationItem>
) {
    val hasMismatch: Boolean get() = outliers.isNotEmpty()
    fun summary(): String {
        val dominant = when (dominantLandscape) {
            true -> "ngang"
            false -> "dọc"
            null -> "chưa xác định"
        }
        return "Đã quét $total video • hướng chính: $dominant • ngang: $landscapeCount • dọc: $portraitCount • thiếu metadata: $unknownCount"
    }
}

/**
 * Metadata-only session audit. It detects MP4 rotation tags and effective
 * portrait/landscape geometry; it cannot identify footage whose pixels are
 * upside down while the container reports rotation=0.
 */
object MediaOrientationScanner {
    suspend fun scan(context: Context, sessionFolder: Uri): MediaOrientationReport =
        withContext(Dispatchers.IO) {
            val folderId = DocumentsContract.getDocumentId(sessionFolder)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(sessionFolder, folderId)
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            val videos = mutableListOf<Pair<String, Uri>>()
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    if (mime.startsWith("video/")) {
                        videos += name to DocumentsContract.buildDocumentUriUsingTree(sessionFolder, id)
                    }
                }
            }

            val items = videos.map { (name, uri) ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull()?.let { ((it % 360) + 360) % 360 }
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val quarterTurn = rotation == 90 || rotation == 270
                    val effectiveWidth = if (quarterTurn) height else width
                    val effectiveHeight = if (quarterTurn) width else height
                    val landscape = if (effectiveWidth != null && effectiveHeight != null && effectiveWidth != effectiveHeight)
                        effectiveWidth > effectiveHeight else null
                    val note = when {
                        rotation == 180 -> "Metadata xoay 180°"
                        rotation == 90 || rotation == 270 -> "Metadata xoay $rotation°"
                        rotation == null -> "Không đọc được rotation"
                        landscape == null -> "Không xác định được tỷ lệ khung hình"
                        else -> "Rotation $rotation°"
                    }
                    MediaOrientationItem(name, rotation, width, height, landscape, note)
                } catch (t: Throwable) {
                    MediaOrientationItem(name, null, null, null, null,
                        "Không đọc được metadata: ${t.localizedMessage ?: t.javaClass.simpleName}")
                } finally {
                    runCatching { retriever.release() }
                }
            }

            val known = items.filter { it.effectiveLandscape != null }
            val landscapeCount = known.count { it.effectiveLandscape == true }
            val portraitCount = known.count { it.effectiveLandscape == false }
            val dominant = when {
                known.isEmpty() -> null
                landscapeCount >= portraitCount -> true
                else -> false
            }
            val outliers = items.filter { item ->
                item.rotationDegrees == 180 ||
                    (dominant != null && item.effectiveLandscape != null && item.effectiveLandscape != dominant) ||
                    (item.rotationDegrees == 90 || item.rotationDegrees == 270)
            }
            MediaOrientationReport(
                total = items.size,
                landscapeCount = landscapeCount,
                portraitCount = portraitCount,
                unknownCount = items.count { it.effectiveLandscape == null },
                dominantLandscape = dominant,
                outliers = outliers,
                items = items
            )
        }
}
