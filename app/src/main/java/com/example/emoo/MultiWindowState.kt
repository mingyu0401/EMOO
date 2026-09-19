package com.example.emoo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 小窗/分屏状态的全局可观察快照。
 *
 * Activity.isInMultiWindowMode 是普通属性，变化不触发 Compose 重组，
 * 导致进入小窗后 UI 一直读到旧值（需切 Tab 才刷新）。
 * 由 MainActivity.onMultiWindowModeChanged 回调写入，组合中直接读取即可实时响应。
 */
object MultiWindowState {
    var isInMultiWindow by mutableStateOf(false)
}
