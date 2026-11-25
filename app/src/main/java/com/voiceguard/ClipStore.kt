package com.voiceguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lưu thư mục đã chọn và trạng thái kiểm duyệt; tương thích dữ liệu các bản Studio trước. */
class ClipStore(context: Context) {
    private val prefs = context.getSharedPreferences("voiceguard", Context.MODE_PRIVATE)

    var treeUri: String?
        get() = prefs.getString("tree", null)
        set(value) { prefs.edit().putString("tree", value).apply() }

    var musicUri: String?
        get() = prefs.getString("music_uri", null)
        set(value) { prefs.edit().putString("music_uri", value).apply() }

    var musicName: String?
        get() = prefs.getString("music_name", null)
        set(value) { prefs.edit().putString("music_name", value).apply() }

    var folderDocumentId: String?
        get() = prefs.getString("review_folder_id", null)
        set(value) { prefs.edit().putString("review_folder_id", value).apply() }

    var minimumVoiceSeconds: Float
        get() = prefs.getFloat("minimum_voice_seconds", 2f)
        set(value) { prefs.edit().putFloat("minimum_voice_seconds", value).apply() }

    fun load(): Map<String, Clip> {
        val out = LinkedHashMap<String, Clip>()
        try {
            val arr = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val segments = mutableListOf<VoiceSegment>()
                val saved = o.optJSONArray("segments") ?: JSONArray()
                for (j in 0 until saved.length()) {
                    val s = saved.getJSONObject(j)
                    segments += VoiceSegment(s.optDouble("start", 0.0).toFloat(), s.optDouble("end", 0.0).toFloat())
                }
                val clip = Clip(
                    uri = o.getString("uri"),
                    name = o.getString("name"),
                    status = ClipStatus.valueOf(o.getString("status")),
                    voiceSec = o.getDouble("voiceSec").toFloat(),
                    segments = segments
                )
                out[clip.uri] = clip
            }
        } catch (_: Exception) { }
        return out
    }

    fun save(items: Collection<Clip>) {
        val arr = JSONArray()
        for (c in items) {
            val segments = JSONArray()
            c.segments.forEach { segments.put(JSONObject().put("start", it.startSec.toDouble()).put("end", it.endSec.toDouble())) }
            arr.put(JSONObject()
                .put("uri", c.uri)
                .put("name", c.name)
                .put("status", c.status.name)
                .put("voiceSec", c.voiceSec.toDouble())
                .put("segments", segments))
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
