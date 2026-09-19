package com.example.emoo.send

/**
 * 拖拽会话状态：由图片格子的拖拽源（detectDragGesturesAfterLongPress 回调）
 * 标记，供 ImageSender 在模拟拖拽后判断本次系统拖放会话是否真的启动过。
 */
object DragSessionState {
    @Volatile
    private var started = false

    /** 新一轮模拟拖拽前重置 */
    fun reset() {
        started = false
    }

    /** 拖拽源 onDragStart（startDragAndDrop 已发起）时标记 */
    fun markStarted() {
        started = true
    }

    /** 上一轮模拟中拖拽会话是否启动过 */
    fun wasStarted(): Boolean = started
}
