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
 * 微信：非 GIF 复用上面同一套拖拽算法；GIF 拖入会退化成文件/静图，改走路径识别——
 * 把图片临时复制到公共 Download/（微信读不到私有目录），tap 输入框（语音态先切键盘）
 * 后粘贴绝对路径，微信识别为图片消息，轮询 uiautomator 找到“发送”按钮后 tap 发送。
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

    /** 微信 GIF 发送：等待用户手动点击输入框的最长时间 */
    private const val FOCUS_TIMEOUT_MS = 20_000L

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

    // ============================ 微信：路径识别 ============================

    /**
     * 纯 Shizuku 微信 GIF 发送（非 GIF 走 [sendQqDrag] 拖拽）。流程：
     *
     * 1. staging 到公共 Download（微信读不到私有目录）
     * 2. 弹 Toast 提示用户手动点一次输入框（顺带覆盖语音输入态），轮询等待
     *    微信 EditText 获得焦点后再继续
     * 3. 清空输入框已有文字，注入 Ctrl+V 粘贴绝对路径（相当于 ACTION_SET_TEXT）
     * 4. 轮询识别：微信识别成功会把输入框中的路径文本替换为空（图片态），
     *    即“EditText 消失或文本不再含文件名”视为识别成功
     * 5. 识别成功 → 快速轮询“发送”按钮出现立即 tap → SENT；按钮找不到 →
     *    ATTACHED 且保留临时文件（微信点发送时才读文件，残留由下次启动清理）
     * 6. 未识别 → 清空输入框避免路径被当文本发出 → NOT_RECOGNIZED，
     *    临时文件延迟删除
     */
    suspend fun sendWechat(context: Context, image: ImageItem): SendResult =
        withContext(Dispatchers.IO) {
            val staged = ImageSender.stageToDownloads(context, image)
                ?: return@withContext SendResult.FAILED
            val (uri, path) = staged
            val fileName = path.substringAfterLast('/')
            // ATTACHED 时微信发送前仍需读文件，临时文件不能删
            var keepStagedFile = false
            try {
                // 让用户手动点一次输入框：既确保微信输入框真正获得焦点，也顺带
                // 覆盖语音输入态（用户自行切到键盘并聚焦），比 shell 盲点更可靠。
                // 检测到输入框获得焦点后再继续自动清空 + 粘贴。
                showToast(context, "请手动点击一次输入框")
                if (!awaitWechatInputFocus(FOCUS_TIMEOUT_MS)) {
                    Log.w(TAG, "等待用户点击输入框超时，仍尝试继续")
                }

                // 清空输入框已有文字，避免与路径拼接
                runCatching {
                    ShizukuShell.exec("input", "keycombination", "113", "29") // CTRL+A
                    repeat(3) { ShizukuShell.exec("input", "keyevent", "67") } // DEL
                }

                // 无障碍模式用 ACTION_SET_TEXT 直接提交文本；shell 的 `input text`
                // 走按键事件会被中文输入法拦截转成拼音候选词（框里出现乱码）。
                // 等价做法：趁焦点还在 EMOO 时先把路径写入剪贴板，tap 输入框后
                // 注入 Ctrl+V 粘贴，同样绕过输入法。
                runCatching {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("emoo", path))
                }.onFailure { Log.w(TAG, "写入剪贴板失败", it) }
                val pasted = ShizukuShell.exec("input", "keycombination", "113", "47") || // Ctrl+V
                    ShizukuShell.exec("input", "keyevent", "279") // KEYCODE_PASTE 兜底
                if (!pasted) return@withContext SendResult.FAILED

                // 轮询识别：识别成功后输入框节点消失（变图片态）或文本不再含文件名。
                // sawPath 区分“粘贴没生效”（从未见过路径文本）与“微信不识别路径”
                var sawPath = false
                var recognized = false
                for (i in 0 until 6) {
                    delay(150)
                    val fresh = uiDump() ?: continue
                    val input = fresh.let { findWechatInput(it) }
                    if (input != null && input.text.contains(fileName)) {
                        sawPath = true
                        continue
                    }
                    recognized = true
                    break
                }

                if (recognized) {
                    // 图片已附到输入框，快速轮询等发送按钮出现立即点击
                    var clicked = false
                    for (i in 0 until 8) {
                        val fresh = uiDump() ?: continue
                        val sendNode = parseNodes(fresh).firstOrNull {
                            it.pkg == WECHAT_PACKAGE && it.text.trim() == "发送"
                        }
                        if (sendNode != null) {
                            clicked = tap(sendNode.bounds.centerX(), sendNode.bounds.centerY())
                            if (clicked) break
                        }
                        delay(60)
                    }
                    if (clicked) {
                        SendResult.SENT
                    } else {
                        keepStagedFile = true
                        SendResult.ATTACHED
                    }
                } else {
                    if (!sawPath) {
                        // 输入框里从未出现过路径文本：粘贴没有生效
                        Log.w(TAG, "粘贴未生效，输入框无路径文本")
                        return@withContext SendResult.FAILED
                    }
                    // 微信未识别路径：清空输入框，避免把路径当文本发出去
                    runCatching {
                        ShizukuShell.exec("input", "keycombination", "113", "29") // CTRL+A
                        repeat(12) { ShizukuShell.exec("input", "keyevent", "67") } // DEL×12
                    }
                    SendResult.NOT_RECOGNIZED
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

    // ============================ uiautomator 节点 ============================

    /** shell 执行 uiautomator dump 并读出 XML 内容，失败重试一次 */
    private suspend fun uiDump(): String? {
        // 单进程完成 dump + 读回：用一层 sh 把 `uiautomator dump` 与 `cat` 串起来，
        // 省掉原先 chmod / cat 两次独立的 Shizuku 进程往返（每条命令都要 binder+fork）
        val cmd = "uiautomator dump $DUMP_PATH >/dev/null 2>&1 && cat $DUMP_PATH"
        for (attempt in 0 until 2) {
            val xml = ShizukuShell.execWithOutput("sh", "-c", cmd, timeoutMs = 8000)
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

    /** 微信文字输入框：包内最大的 EditText 节点 */
    private fun findWechatInput(xml: String): UiNode? =
        parseNodes(xml)
            .filter { it.pkg == WECHAT_PACKAGE && it.cls.contains("EditText") }
            .maxByOrNull { it.bounds.width() * it.bounds.height() }

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
            delay(400)
        }
        return false
    }

    private fun tap(x: Int, y: Int): Boolean =
        ShizukuShell.exec("input", "tap", x.toString(), y.toString())
}
