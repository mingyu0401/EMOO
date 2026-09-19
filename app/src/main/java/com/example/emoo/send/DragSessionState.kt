package com.example.emoo.send

/**
 * 拖拽会话状态：由图片格子的拖拽源（detectDragGesturesAfterLongPress 回调）
 * 标记，供 ImageSender 在模拟拖拽后判断本次系统拖放会话是否真的启动过。
 */
object DragSessionState {
    @Volatile
    private var started = false

    @Volatile
    private var autoPending = false

    /** 新一轮模拟拖拽前重置 */
    fun reset() {
        started = false
        autoPending = false
    }

    /** 模拟拖拽注入前置标记：本轮拖拽源由注入事件触发，虚影用透明图 */
    fun markAutoPending() {
        autoPending = true
    }

    /** 拖拽源 onDragStart 消费标记：返回 true 表示本轮是模拟拖拽 */
    fun consumeAutoDrag(): Boolean {
        val v = autoPending
        autoPending = false
        return v
    }

    /** 拖拽源 onDragStart（startDragAndDrop 已发起）时标记 */
    fun markStarted() {
        started = true
    }

    /** 上一轮模拟中拖拽会话是否启动过 */
    fun wasStarted(): Boolean = started
}
