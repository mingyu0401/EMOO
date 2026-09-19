package com.example.emoo.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.emoo.data.MetaPreferences
import com.example.emoo.model.RecentEntry
import com.example.emoo.model.SendMode
import com.example.emoo.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置项 ViewModel。在 MainActivity 级别创建（Activity 作用域），
 * 使主题模式在全局即时生效，同时供设置页与图片页共享。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val meta = MetaPreferences.get(application)

    private val _themeMode = MutableStateFlow(meta.getThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _gridColumns = MutableStateFlow(meta.getGridColumns())
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    /** 一键发送实现通道（Shizuku 推荐 / 无障碍），QQ 与微信共用 */
    private val _sendMode = MutableStateFlow(meta.getSendMode())
    val sendMode: StateFlow<SendMode> = _sendMode.asStateFlow()

    /** “最近”记录变更版本号（清理/撤销后自增，供图片页监听刷新） */
    private val _recentVersion = MutableStateFlow(0)
    val recentVersion: StateFlow<Int> = _recentVersion.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        meta.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun setGridColumns(columns: Int) {
        val value = columns.coerceIn(2, 6)
        meta.setGridColumns(value)
        _gridColumns.value = value
    }

    fun setSendMode(mode: SendMode) {
        meta.setSendMode(mode)
        _sendMode.value = mode
    }

    /** 清空“最近”记录，返回旧列表以便撤销 */
    fun clearRecent(): List<RecentEntry> {
        val old = meta.clearRecent()
        _recentVersion.value++
        return old
    }

    /** 撤销清理 */
    fun restoreRecent(entries: List<RecentEntry>) {
        meta.restoreRecent(entries)
        _recentVersion.value++
    }
}
