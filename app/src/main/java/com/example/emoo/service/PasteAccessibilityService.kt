package com.example.emoo.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 一键发送无障碍服务：提供跨应用窗口的节点查找与操作能力，
 * 供 ImageSender 驱动微信“路径识别 + 自动点击发送”与 QQ“模拟拖拽发送”。
 *
 * 小窗/分屏场景下焦点在本应用，故不使用 rootInActiveWindow，
 * 而是遍历 [windows] 中其他应用的窗口。用户需在系统设置中手动开启本服务。
 */
class PasteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ============================ 手势模拟（拖拽） ============================

    /** 各阶段时长：长按出拖拽 → 快速移动到目标 → 短悬停后抬手。
     *  移动段要快：ColorOS 会把慢速跨窗拖动识别成窗口管理手势（拖动整个小窗） */
    private object DragTiming {
        const val HOLD_MS = 700L
        const val MOVE_MS = 500L
        const val HOVER_MS = 150L
    }

    /**
     * 模拟人手跨窗口拖放：在 (fromX, fromY) 长按启动拖拽源，沿直线拖到
     * (toX, toY) 悬停后抬手（drop）。注入的是真实触摸流，ColorOS 小窗间的
     * 跨窗口拖放机制与真手指完全同路径。[onFinished] 在全部手势完成后回调，
     * 参数表示手势序列是否完整执行（未被取消）。
     */
    fun performDragGesture(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        onFinished: (Boolean) -> Unit
    ) {
        // 第一段：按下并长按（触发拖拽源的 detectDragGesturesAfterLongPress）
        val pressPath = Path().apply { moveTo(fromX, fromY) }
        val press = GestureDescription.StrokeDescription(pressPath, 0, DragTiming.HOLD_MS)
        dispatchGesture(
            GestureDescription.Builder().addStroke(press).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    dispatchMoveStroke(press, fromX, fromY, toX, toY, onFinished)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onFinished(false)
                }
            },
            null
        )
    }

    /** 第二段：直线拖到目标位置 */
    private fun dispatchMoveStroke(
        previous: GestureDescription.StrokeDescription,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        onFinished: (Boolean) -> Unit
    ) {
        val midX = fromX + (toX - fromX) / 2f
        val midY = fromY + (toY - fromY) / 2f
        val movePath = Path().apply {
            moveTo(fromX, fromY)
            lineTo(midX, midY)
            lineTo(toX, toY)
        }
        val move = previous.continueStroke(movePath, 0, DragTiming.MOVE_MS, true)
        dispatchGesture(
            GestureDescription.Builder().addStroke(move).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    dispatchHoverStroke(move, toX, toY, onFinished)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onFinished(false)
                }
            },
            null
        )
    }

    /** 第三段：目标上悬停后抬手（willContinue=false 结束触摸即触发 drop） */
    private fun dispatchHoverStroke(
        previous: GestureDescription.StrokeDescription,
        toX: Float,
        toY: Float,
        onFinished: (Boolean) -> Unit
    ) {
        val hoverPath = Path().apply { moveTo(toX, toY) }
        val hover = previous.continueStroke(hoverPath, 0, DragTiming.HOVER_MS, false)
        dispatchGesture(
            GestureDescription.Builder().addStroke(hover).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onFinished(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onFinished(false)
                }
            },
            null
        )
    }

    // ============================ 窗口与节点查找 ============================

    /**
     * 全部可交互窗口。API 30+ 的 [windowsOnAllDisplays] 已含默认显示器，直接用它；
     * 低版本或取值异常时回退 [windows]。ColorOS/部分 ROM 的小窗、自由窗口可能挂在
     * 非默认显示器上，仅靠默认显示器遍历不到，必须扫全显示器。
     */
    private fun allInteractiveWindows(): List<AccessibilityWindowInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val byDisplay = windowsOnAllDisplays
                val result = ArrayList<AccessibilityWindowInfo>()
                for (i in 0 until byDisplay.size()) {
                    byDisplay.valueAt(i)?.let { result.addAll(it) }
                }
                return result
            } catch (_: Exception) {
            }
        }
        return windows
    }

    /** 一次全窗口扫描的结果，见 [scanWindows]。 */
    class WindowScan internal constructor(
        /** 命中的目标包名 -> 所在窗口；value 为 null 表示仅活动窗口回退命中 */
        val targets: Map<String, AccessibilityWindowInfo?>,
        /** 当前可访问的全部窗口包名（诊断用） */
        val allPackages: List<String>
    )

    /**
     * 单次全窗口扫描，同时判定所有目标包，替代“每个包各扫一遍”的多次调用。
     * ColorOS 15 等系统会对窗口遍历弹“正在使用无障碍权限”提醒，
     * 调用方应复用本次结果 + [rootOf] 刷新根节点，把敏感访问压到最低。
     */
    fun scanWindows(targetPackages: List<String>): WindowScan {
        val found = HashMap<String, AccessibilityWindowInfo>()
        val packages = LinkedHashSet<String>()
        for (window in allInteractiveWindows()) {
            val pkg = window.root?.packageName?.toString() ?: continue
            packages.add(pkg)
            if (pkg in targetPackages) found.putIfAbsent(pkg, window)
        }
        // 部分系统对小窗窗口遍历受限，但活动窗口在点击图片前通常仍是聊天应用
        val targets = HashMap<String, AccessibilityWindowInfo?>(found)
        rootInActiveWindow?.packageName?.toString()?.let { activePkg ->
            if (activePkg in targetPackages) targets.putIfAbsent(activePkg, null)
            packages.add(activePkg)
        }
        return WindowScan(targets, packages.toList())
    }

    /** 从 [scanWindows] 缓存的窗口刷新根节点；窗口消失时回退活动窗口，避免再全扫 */
    fun rootOf(
        window: AccessibilityWindowInfo?,
        targetPackage: String
    ): AccessibilityNodeInfo? =
        window?.root?.takeIf { it.packageName?.toString() == targetPackage }
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == targetPackage }

    /** 查找窗口内聊天输入框：优先取拥有输入焦点的可编辑节点，找不到则递归找第一个 */
    fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
            ?.let { return it }
        return findEditableRecursive(root)
    }

    private fun findEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findEditableRecursive(child)?.let { return it }
            }
        }
        return null
    }

    fun nodeText(node: AccessibilityNodeInfo): String? = node.text?.toString()

    // ============================ 节点操作 ============================

    /** 直接设置输入框文本（触发应用的文本变化监听，微信据此识别本地图片路径） */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
        )

    /**
     * 点击 text/contentDescription 匹配候选词的节点。
     * 优先精确匹配（避免误点消息正文中的相同字样），无命中再包含匹配。
     * 目标节点自身不可点击时向上冒泡找可点击祖先（点击区域常在父容器上）。
     */
    fun clickNodeByText(root: AccessibilityNodeInfo, vararg candidates: String): Boolean {
        val node = findNodeByLabel(root, candidates) ?: return false
        return clickWithBubble(node)
    }

    private fun findNodeByLabel(
        root: AccessibilityNodeInfo,
        candidates: Array<out String>
    ): AccessibilityNodeInfo? {
        val exact = mutableListOf<AccessibilityNodeInfo>()
        val loose = mutableListOf<AccessibilityNodeInfo>()
        collectLabeledNodes(root, candidates, exact, loose)
        return exact.firstOrNull() ?: loose.firstOrNull()
    }

    private fun collectLabeledNodes(
        node: AccessibilityNodeInfo,
        candidates: Array<out String>,
        exact: MutableList<AccessibilityNodeInfo>,
        loose: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            when {
                text == candidate || desc == candidate -> exact.add(node)
                text?.contains(candidate) == true || desc?.contains(candidate) == true -> loose.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectLabeledNodes(it, candidates, exact, loose) }
        }
    }

    private fun clickWithBubble(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    companion object {
        @Volatile
        var instance: PasteAccessibilityService? = null
            private set

        /** 无障碍服务是否已开启 */
        fun isEnabled(): Boolean = instance != null
    }
}
