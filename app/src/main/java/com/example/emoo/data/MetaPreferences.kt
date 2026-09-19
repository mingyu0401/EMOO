package com.example.emoo.data

import android.content.Context
import android.content.SharedPreferences
import com.example.emoo.model.RecentEntry
import com.example.emoo.model.SendMode
import com.example.emoo.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量元数据存储（SharedPreferences + org.json）。
 * “目录即数据”：图片归属完全由磁盘目录决定，这里只保存
 * 最近列表 / 文件夹预览图映射 / 每行列数 / 主题模式四项辅助信息。
 */
class MetaPreferences private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("emoo_meta", Context.MODE_PRIVATE)

    // ---------------- “最近”列表 ----------------

    fun getRecent(): List<RecentEntry> {
        val raw = sp.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                RecentEntry(
                    path = obj.getString("path"),
                    name = obj.optString("name"),
                    folder = obj.optString("folder"),
                    time = obj.optLong("time")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRecent(entries: List<RecentEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("path", e.path)
                put("name", e.name)
                put("folder", e.folder)
                put("time", e.time)
            })
        }
        sp.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    /** 记入最近（去重、按时间倒序、上限 100 条，超出淘汰最旧） */
    fun addToRecent(entries: List<RecentEntry>) {
        if (entries.isEmpty()) return
        val merged = (entries + getRecent())
            .groupBy { it.path }
            .map { (_, group) -> group.maxBy { it.time } }
            .sortedByDescending { it.time }
            .take(MAX_RECENT)
        saveRecent(merged)
    }

    fun removeFromRecent(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val set = paths.toSet()
        saveRecent(getRecent().filter { it.path !in set })
    }

    /** 清空最近记录，返回旧列表以便撤销 */
    fun clearRecent(): List<RecentEntry> {
        val old = getRecent()
        sp.edit().remove(KEY_RECENT).apply()
        return old
    }

    fun restoreRecent(entries: List<RecentEntry>) = saveRecent(entries)

    // ---------------- 文件夹预览图（文件夹名 -> path/uri） ----------------

    /** 返回 folderName -> (previewPath, previewUriString) */
    fun getFolderPreviews(): Map<String, Pair<String, String>> {
        val raw = sp.getString(KEY_PREVIEWS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key ->
                val v = obj.getJSONObject(key)
                v.optString("path") to v.optString("uri")
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setFolderPreview(folder: String, path: String?, uri: String?) {
        val map = getFolderPreviews().toMutableMap()
        if (path == null || uri == null) map.remove(folder) else map[folder] = path to uri
        val obj = JSONObject()
        map.forEach { (f, pair) ->
            obj.put(f, JSONObject().apply {
                put("path", pair.first)
                put("uri", pair.second)
            })
        }
        sp.edit().putString(KEY_PREVIEWS, obj.toString()).apply()
    }

    // ---------------- 设置项 ----------------

    fun getGridColumns(): Int = sp.getInt(KEY_GRID_COLUMNS, 4).coerceIn(2, 6)

    fun setGridColumns(columns: Int) {
        sp.edit().putInt(KEY_GRID_COLUMNS, columns.coerceIn(2, 6)).apply()
    }

    fun getThemeMode(): ThemeMode = ThemeMode.fromName(sp.getString(KEY_THEME_MODE, null))

    fun setThemeMode(mode: ThemeMode) {
        sp.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    // ---------------- 文件夹自定义排序 ----------------

    /** 返回用户自定义的文件夹顺序（仅包含仍存在的文件夹由调用方过滤） */
    fun getFolderOrder(): List<String> {
        val raw = sp.getString(KEY_FOLDER_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setFolderOrder(folders: List<String>) {
        sp.edit().putString(KEY_FOLDER_ORDER, JSONArray(folders).toString()).apply()
    }

    // ---------------- 发送方式（Shizuku / 无障碍） ----------------

    /**
     * 用户显式选择过的发送方式；null 表示尚未选择（首次使用），
     * 供“首次使用选择弹窗”判断。兼容旧 qq_send_mode 键的迁移。
     */
    fun peekSendMode(): SendMode? {
        sp.getString(KEY_SEND_MODE, null)?.let {
            return try {
                SendMode.valueOf(it)
            } catch (_: Exception) {
                null
            }
        }
        // 迁移旧版“QQ 发送方式”键，避免老用户再次弹选择
        val legacy = sp.getString(KEY_QQ_SEND_MODE, null) ?: return null
        val mode = try {
            SendMode.valueOf(legacy)
        } catch (_: Exception) {
            return null
        }
        sp.edit().putString(KEY_SEND_MODE, mode.name).apply()
        return mode
    }

    /** 当前生效的发送方式（未选择过时默认 Shizuku） */
    fun getSendMode(): SendMode = peekSendMode() ?: SendMode.SHIZUKU

    fun setSendMode(mode: SendMode) {
        sp.edit().putString(KEY_SEND_MODE, mode.name).apply()
    }

    companion object {
        const val MAX_RECENT = 100

        private const val KEY_RECENT = "recent_json"
        private const val KEY_PREVIEWS = "folder_previews_json"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FOLDER_ORDER = "folder_order_json"
        private const val KEY_SEND_MODE = "send_mode"
        private const val KEY_QQ_SEND_MODE = "qq_send_mode"

        @Volatile
        private var instance: MetaPreferences? = null

        fun get(context: Context): MetaPreferences =
            instance ?: synchronized(this) {
                instance ?: MetaPreferences(context).also { instance = it }
            }
    }
}
