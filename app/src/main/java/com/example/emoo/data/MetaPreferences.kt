package com.example.emoo.data

import android.content.Context
import android.content.SharedPreferences
import com.example.emoo.model.FolderSort
import com.example.emoo.model.RecentEntry
import com.example.emoo.model.SendMode
import com.example.emoo.model.StickerSortMode
import com.example.emoo.model.ThemeColor
import com.example.emoo.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量元数据存储（SharedPreferences + org.json）。
 * “目录即数据”：图片归属与文件夹级元数据（排序设置、使用次数、预览图、
 * 文件夹顺序）全部由磁盘目录内的 .emoo_* 文件承载（见 ImageRepository），
 * 这里只保存 最近列表 / 每行列数 / 主题模式 / 角标开关 / 发送方式 等全局设置，
 * 以及旧版本文件夹级数据的一次性迁移出口 [takeLegacyFolderMeta]。
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

    // ---------------- 设置项 ----------------

    fun getGridColumns(): Int = sp.getInt(KEY_GRID_COLUMNS, 4).coerceIn(2, 6)

    fun setGridColumns(columns: Int) {
        sp.edit().putInt(KEY_GRID_COLUMNS, columns.coerceIn(2, 6)).apply()
    }

    fun getThemeMode(): ThemeMode = ThemeMode.fromName(sp.getString(KEY_THEME_MODE, null))

    fun setThemeMode(mode: ThemeMode) {
        sp.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /** 主题色（交互强调色），默认紫色 */
    fun getThemeColor(): ThemeColor = ThemeColor.fromName(sp.getString(KEY_THEME_COLOR, null))

    fun setThemeColor(color: ThemeColor) {
        sp.edit().putString(KEY_THEME_COLOR, color.name).apply()
    }

    /** 网格角标是否显示使用次数 */
    fun getShowUsageCount(): Boolean = sp.getBoolean(KEY_SHOW_USAGE, false)

    fun setShowUsageCount(show: Boolean) {
        sp.edit().putBoolean(KEY_SHOW_USAGE, show).apply()
    }

    // ---------------- 旧版文件夹级元数据（仅迁移用） ----------------

    /**
     * 取出旧版本存在 sp 里的文件夹级元数据（预览图/顺序/排序/使用次数）
     * 并清除对应键。四项均为空时返回 null，表示无需迁移。
     * 迁移落盘由 [ImageRepository.migrateLegacyMeta] 完成。
     */
    fun takeLegacyFolderMeta(): ImageRepository.LegacyFolderMeta? {
        val hasAny = LEGACY_KEYS.any { sp.contains(it) }
        if (!hasAny) return null

        // 预览图：folder -> path
        val previews = try {
            val obj = JSONObject(sp.getString(KEY_PREVIEWS, null) ?: "{}")
            obj.keys().asSequence().associateWith { obj.getJSONObject(it).optString("path") }
                .filterValues { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyMap()
        }

        // 文件夹顺序
        val order = try {
            val arr = JSONArray(sp.getString(KEY_FOLDER_ORDER, null) ?: "[]")
            List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }

        // 每文件夹排序设置
        val sorts = try {
            val obj = JSONObject(sp.getString(KEY_FOLDER_SORTS, null) ?: "{}")
            obj.keys().asSequence().mapNotNull { key ->
                val v = obj.optJSONObject(key) ?: return@mapNotNull null
                key to FolderSort(
                    mode = StickerSortMode.fromName(v.optString("mode")),
                    reverse = v.optBoolean("reverse")
                )
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }

        // 使用次数：path -> count
        val usage = try {
            val obj = JSONObject(sp.getString(KEY_USAGE_COUNTS, null) ?: "{}")
            obj.keys().asSequence().mapNotNull { key ->
                val v = obj.optInt(key, -1)
                if (v > 0) key to v else null
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }

        sp.edit().apply {
            LEGACY_KEYS.forEach { remove(it) }
        }.apply()

        val legacy = ImageRepository.LegacyFolderMeta(sorts, previews, usage, order)
        return if (legacy.isEmpty()) null else legacy
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
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_SHOW_USAGE = "show_usage_count"
        private const val KEY_SEND_MODE = "send_mode"
        private const val KEY_QQ_SEND_MODE = "qq_send_mode"

        // 旧版文件夹级元数据键（已迁移为目录内 .emoo_* 文件，仅迁移时读取并清除）
        private const val KEY_PREVIEWS = "folder_previews_json"
        private const val KEY_FOLDER_ORDER = "folder_order_json"
        private const val KEY_FOLDER_SORTS = "folder_sorts_json"
        private const val KEY_USAGE_COUNTS = "usage_counts_json"
        private val LEGACY_KEYS = listOf(KEY_PREVIEWS, KEY_FOLDER_ORDER, KEY_FOLDER_SORTS, KEY_USAGE_COUNTS)

        @Volatile
        private var instance: MetaPreferences? = null

        fun get(context: Context): MetaPreferences =
            instance ?: synchronized(this) {
                instance ?: MetaPreferences(context).also { instance = it }
            }
    }
}
