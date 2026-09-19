package com.example.emoo.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.example.emoo.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 纯 Shizuku 一键发送实现：全程只走 shell 通道（dumpsys / uiautomator / input），
 * 完全不触碰无障碍，避免 ColorOS 16 的“无障碍使用提醒”弹窗。
 *
 * QQ：用户点击图片格子的位置（换算成物理屏幕坐标）长按 0.7 秒启动格子自身的
 * 拖拽源，随后 0.5 秒内横向快速滑到屏幕对侧边缘（点左半屏滑到最右、点右半屏
 * 滑到最左）抬手——拖拽离开 EMOO 小窗即落入 QQ 聊天区，QQ 接收 drop 直接发送。
 *
 * 微信：不再区分 GIF/非 GIF，统一走路径粘贴（拖拽方案暂时停用）——把图片临时
 * 复制到公共 Download/（微信读不到私有目录）并把路径写入剪贴板，Toast 提示用户
 * 手动点击微信输入框，侦测到输入框获得焦点后清空、粘贴绝对路径，微信识别为
 * 图片消息，随后点击弹出的绿色“发送”按钮。
 *
 * 坐标换算：ColorOS 灵活小窗会按比例缩放 EMOO 窗口表面，Compose
 * boundsInWindow() 给出的是未缩放的窗口内坐标，而 shell input 注入需要
 * 物理屏幕坐标。物理 = 窗口内坐标 × scale + (tx, ty)，scale/偏移从
 * `dumpsys SurfaceFlinger` 的 EMOO ActivityRecord 层 toDisplayTransform 解析。
 */
object ShizukuSender {

    private const val TAG = "ShizukuSender"

    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val QQ_PACKAGE = "com.tencent.mobileqq"

    private const val DUMP_PATH = "/sdcard/emoo_uidump.xml"

    /** EMOO 窗口表面 → 物理屏幕 的仿射变换（ColorOS 灵活小窗缩放） */
    data class DisplayTransform(val scale: Float, val tx: Float, val ty: Float) {
        fun apply(x: Float, y: Float): Pair<Float, Float> =
            x * scale + tx to y * scale + ty
    }

    private const val OWN_PKG = "com.example.emoo"

    private val EMOO_LAYER_MARKER = Regex("""ActivityRecord\{[^}]*$OWN_PKG/""")
    private val TO_DISPLAY_TRANSFORM =
        Regex("""toDisplayTransform=\{ scale x=([0-9.]+) y=[0-9.]+\s+tx=(-?[0-9.]+) ty=(-?[0-9.]+)""")

    /**
     * 解析 EMOO 窗口在物理屏幕上的缩放与偏移。找不到层或 Shizuku 不可用时
     * 返回恒等变换（全屏/未缩放窗口下窗口内坐标即屏幕坐标）。
     */
    suspend fun emooDisplayTransform(): DisplayTransform = withContext(Dispatchers.IO) {
        val dump = ShizukuShell.execWithOutput("dumpsys", "SurfaceFlinger", timeoutMs = 15_000L)
            ?: return@withContext DisplayTransform(1f, 0f, 0f)
        val lines = dump.lines()
        for (i in lines.indices) {
            val header = lines[i]
            // 同一 Activity 可能有 mirrored 副本层，只认真实窗口层
            if (header.contains("mirrored")) continue
            if (!EMOO_LAYER_MARKER.containsMatchIn(header)) continue
            val window = minOf(i + 9, lines.size)
            for (j in i + 1 until window) {
                TO_DISPLAY_TRANSFORM.find(lines[j])?.let { m ->
                    val tr = DisplayTransform(
                        m.groupValues[1].toFloat(),
                        m.groupValues[2].toFloat(),
                        m.groupValues[3].toFloat()
                    )
                    Log.d(TAG, "EMOO 窗口变换: $tr")
                    return@withContext tr
                }
            }
        }
        Log.d(TAG, "SurfaceFlinger 未找到 EMOO 层变换，按恒等处理")
        DisplayTransform(1f, 0f, 0f)
    }

    /** 整块屏幕的尺寸 */
    fun screenBounds(context: Context): Rect? = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(wm.maximumWindowMetrics.bounds)
        } else {
            Rect(0, 0, wm.defaultDisplay.width, wm.defaultDisplay.height)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 从 `dumpsys window windows` 解析 [pkg] 可见主窗口在屏幕上的矩形。
     * 不同版本的输出格式有差异，依次尝试 mFrame=[l,t][r,b]、frame=[...]、
     * Rect(l, t - r, b) 三种写法，取覆盖面积最大的一块。
     *
     * 可见性过滤：后台残留窗口（如退到后台的微信）mHasSurface=false /
     * mViewVisibility≠0，若不过滤会把 QQ 发送误路由到微信流程。
     * 段内出现可见性标记时要求全部为可见值；无标记的段（格式差异）放行。
     */
    fun windowBounds(pkg: String): Rect? {
        val dump = ShizukuShell.execWithOutput("dumpsys", "window", "windows")
            ?: ShizukuShell.execWithOutput("dumpsys", "window")
            ?: return null
        // 按窗口分段：WindowState 段以 “Window #” 开头，段内含包名
        val sections = dump.split(Regex("\n(?=\\s*Window #)"))
        var best: Rect? = null
        for (section in sections) {
            if (!section.contains(pkg)) continue
            if (section.contains("mHasSurface=") && !section.contains("mHasSurface=true")) continue
            if (section.contains("isReadyForDisplay()=") && !section.contains("isReadyForDisplay()=true")) continue
            if (section.contains("mViewVisibility=") && !section.contains("mViewVisibility=0x0")) continue
            val rect = FRAME_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.find(section)?.destructured?.let { (l, t, r, b) ->
                    Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
                }
            } ?: continue
            if (rect.width() <= 0 || rect.height() <= 0) continue
            if (best == null || rect.width() * rect.height() > best.width() * best.height()) {
                best = rect
            }
        }
        if (best == null) {
            Log.d(TAG, "dumpsys 未找到 $pkg 可见窗口，输出头部: ${dump.take(300)}")
        }
        return best
    }

    private val FRAME_PATTERNS = listOf(
        Regex("""mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""),
        Regex("""frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""),
        Regex("""Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")
    )

    // ============================ QQ：模拟拖拽 ============================

    /** 一次 QQ 拖拽的起点/终点（均为物理屏幕坐标） */
    data class QqDragTargets(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float
    )

    /**
     * 用户指定算法：以点击图片格子的位置（坐标1，换算到物理屏幕）为拖拽起点，
     * 若起点在屏幕左半部分则 0.5 秒内横向滑到屏幕最右边缘，右半部分滑到最左
     * 边缘，y 保持不变。供 Shizuku 与无障碍两条注入通道共用。
     */
    suspend fun computeQqDragTargets(
        context: Context,
        sourceCenter: androidx.compose.ui.geometry.Offset
    ): QqDragTargets? {
        val screen = screenBounds(context) ?: return null
        val tr = emooDisplayTransform()
        val (fromX, fromY) = tr.apply(sourceCenter.x, sourceCenter.y)
        // 终点让出屏幕宽度 4%，避免落在边缘手势区被系统拦截
        val margin = screen.width() * 0.04f
        val toX = if (fromX < screen.exactCenterX()) {
            screen.right - margin
        } else {
            screen.left + margin
        }
        return QqDragTargets(fromX, fromY, toX, fromY)
    }

    /**
     * 纯 Shizuku QQ 拖拽：[sourceCenter] 为图片格子在窗口内的中心（坐标1）。
     * 长按 0.7 秒启动格子拖拽源，0.5 秒内横向滑到屏幕对侧边缘抬手。
     */
    suspend fun sendQqDrag(
        context: Context,
        sourceCenter: androidx.compose.ui.geometry.Offset?
    ): SendResult = withContext(Dispatchers.IO) {
        if (sourceCenter == null) return@withContext SendResult.FAILED
        val targets = computeQqDragTargets(context, sourceCenter)
            ?: return@withContext SendResult.FAILED
        Log.d(
            TAG,
            "QQ 拖拽目标: from=(${targets.fromX},${targets.fromY}) to=(${targets.toX},${targets.toY}) " +
                "source(window)=(${sourceCenter.x},${sourceCenter.y})"
        )

        DragSessionState.reset()
        val injected = ShizukuDragInjector.dragAndDrop(
            targets.fromX, targets.fromY, targets.toX, targets.toY, 500L
        ) || ShizukuDragInjector.dragAndDropManual(
            targets.fromX, targets.fromY, targets.toX, targets.toY
        )
        if (!injected) return@withContext SendResult.FAILED

        // 等 QQ 处理 drop；拖拽源从未启动（长按未触发 drag）则报失败
        delay(900)
        if (DragSessionState.wasStarted()) SendResult.SENT else SendResult.FAILED
    }

    // ============================ 微信：路径粘贴 ============================

    /** 等待用户点击微信输入框（EditText 获得焦点）的超时 */
    private const val FOCUS_TIMEOUT_MS = 20_000L

    /**
     * 纯 Shizuku 微信发送（不再区分 GIF/非 GIF，拖拽方案暂时停用）。流程：
     *
     * 1. staging 到公共 Download（微信读不到私有目录），把绝对路径写入剪贴板
     * 2. Toast 提示用户手动点击微信输入框，轮询 uiautomator 侦测输入框获得焦点
     * 3. 侦测到焦点后 0.5 秒，清空输入框已有文字（Ctrl+A 全选后删除）
     * 4. 注入 KEYCODE_PASTE 粘贴路径（绕过中文输入法，避免路径被转成候选词）
     * 5. 0.6 秒后轮询微信弹出的绿色“发送”按钮并点击
     *
     * 点到“发送” → SENT；找不到按钮 → ATTACHED 且保留临时文件（微信点发送时才读文件）。
     */
    suspend fun sendWechat(context: Context, image: ImageItem): SendResult =
        withContext(Dispatchers.IO) {
            val staged = ImageSender.stageToDownloads(context, image)
                ?: return@withContext SendResult.FAILED
            val (uri, path) = staged
            // ATTACHED 时微信发送前仍需读文件，临时文件不能删
            var keepStagedFile = false
            try {
                // 1. 趁焦点还在 EMOO，把图片绝对路径写入剪贴板
                runCatching {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("emoo", path))
                }.onFailure { Log.w(TAG, "写入剪贴板失败", it) }

                // 2. 提示用户手动点击微信输入框，侦测到焦点后再继续。
                // 用户点击既保证输入框真正获得焦点，也顺带覆盖语音输入态。
                showToast(context, "请点击微信输入框")
                if (!awaitWechatInputFocus(FOCUS_TIMEOUT_MS)) {
                    Log.w(TAG, "等待用户点击输入框超时")
                    return@withContext SendResult.FAILED
                }

                // 3. 侦测到点击后，清空输入框已有文字

                ShizukuShell.exec("input", "keycombination", "113", "29") // CTRL+A
                ShizukuShell.exec("input", "keyevent", "67")  // DEL

                // 4. 粘贴路径：注入 KEYCODE_PASTE（shell 的 `input text` 会被中文输入法转候选词，故走剪贴板粘贴）
                val pasted = ShizukuShell.exec("input", "keyevent", "279") // KEYCODE_PASTE
                if (!pasted) return@withContext SendResult.FAILED

                // 5.点击微信弹出的绿色“发送”按钮
                var clicked = false
                for (i in 0 until 6) {
                    val fresh = uiDump()
                    if (fresh != null) {
                        val sendNode = parseNodes(fresh).firstOrNull {
                            it.pkg == WECHAT_PACKAGE && it.text.trim() == "发送"
                        }
                        if (sendNode != null) {
                            clicked = tap(sendNode.bounds.centerX(), sendNode.bounds.centerY())
                            if (clicked) break
                        }
                    }
                    delay(120)
                }
                if (clicked) {
                    SendResult.SENT
                } else {
                    keepStagedFile = true
                    SendResult.ATTACHED
                }
            } finally {
                runCatching { ShizukuShell.exec("rm", "-f", DUMP_PATH) }
                if (keepStagedFile) {
                    // ATTACHED 时微信发送前仍需读文件，保留临时文件
                } else {
                    // 微信点击“发送”后需数秒才真正读完文件，延迟转后台删除
                    ImageSender.scheduleStagedCleanup(context, uri)
                }
            }
        }

    /** 主线程弹系统 Toast（发送时 EMOO 处于小窗/后台，Toast 才能覆盖到微信之上） */
    private fun showToast(context: Context, message: String) {
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 轮询等待微信文字输入框获得焦点（即用户已按提示手动点击）。
     * 在 [timeoutMs] 内检测到 focused 的 EditText 返回 true，超时返回 false。
     */
    private suspend fun awaitWechatInputFocus(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val xml = uiDump()
            if (xml != null && parseNodes(xml).any {
                    it.pkg == WECHAT_PACKAGE && it.cls.contains("EditText") && it.focused
                }
            ) {
                return true
            }
            delay(100)
        }
        return false
    }

    // ============================ uiautomator 节点 ============================

    /** shell 执行 uiautomator dump 并读出 XML 内容，失败重试一次 */
    private suspend fun uiDump(): String? {
        // 走常驻 shell：dump + cat 在同一 sh 进程内串联完成，免去每条命令的 fork 开销
        val cmd = "uiautomator dump $DUMP_PATH >/dev/null 2>&1 && cat $DUMP_PATH"
        for (attempt in 0 until 2) {
            val xml = ShizukuShell.runShell(cmd, timeoutMs = 8000)
            if (xml != null && xml.contains("<node")) return xml
            delay(200)
        }
        return null
    }

    data class UiNode(
        val pkg: String,
        val cls: String,
        val text: String,
        val desc: String,
        val focused: Boolean,
        val bounds: Rect
    )

    /** 解析 uiautomator dump 的 XML（属性均为规范化输出，正则提取足够） */
    fun parseNodes(xml: String): List<UiNode> {
        val nodes = ArrayList<UiNode>()
        val tagRegex = Regex("""<node [^>]*>""")
        for (match in tagRegex.findAll(xml)) {
            val tag = match.value
            fun attr(name: String): String =
                Regex("""$name="([^"]*)"""").find(tag)?.groupValues?.get(1) ?: ""
            val bounds = Regex("""bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""")
                .find(tag)?.destructured?.let { (l, t, r, b) ->
                    Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
                } ?: continue
            nodes.add(
                UiNode(
                    pkg = attr("package"),
                    cls = attr("class"),
                    text = attr("text").unescapeXml(),
                    desc = attr("content-desc").unescapeXml(),
                    focused = attr("focused") == "true",
                    bounds = bounds
                )
            )
        }
        return nodes
    }

    private fun String.unescapeXml(): String =
        replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    private fun tap(x: Int, y: Int): Boolean =
        ShizukuShell.exec("input", "tap", x.toString(), y.toString())
}
