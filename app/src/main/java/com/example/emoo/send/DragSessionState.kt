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

    /**
     * 微信拖拽用的 MediaStore uri（相册同源载荷）：ImageSender 在注入手势前
     * staging 文件并放入，格子拖拽源 onDragStart 消费。不入 reset——
     * reset 发生在注入函数内部、设置之后，清了会把刚放的 uri 抹掉。
     */
    @Volatile
    private var stagedUri: android.net.Uri? = null

    /** 新一轮模拟拖拽前重置 */
    fun reset() {
        started = false
        autoPending = false
    }

    /** 模拟拖拽注入前放入已 staging 的媒体 uri（微信拖拽通道用） */
    fun setStagedUri(uri: android.net.Uri?) {
        stagedUri = uri
    }

    /** 拖拽源 onDragStart 消费 staging uri（一次性，仅模拟拖拽时调用） */
    fun consumeStagedUri(): android.net.Uri? {
        val u = stagedUri
        stagedUri = null
        return u
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
