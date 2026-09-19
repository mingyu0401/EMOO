package com.example.emoo.send

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import com.example.emoo.data.ImageRepository
import com.example.emoo.data.MetaPreferences
import com.example.emoo.model.ImageItem
import com.example.emoo.model.SendMode
import com.example.emoo.service.PasteAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** 一键发送结果 */
enum class SendResult {
    /** 已粘贴/拖放并完成发送 */
    SENT,

    /** 图片已附到聊天输入框，但未能自动点击发送（需手动点一次） */
    ATTACHED,

    /** 无障碍服务未开启 */
    SERVICE_DISABLED,

    /** Shizuku 未运行或未授权 */
    SHIZUKU_UNAVAILABLE,

    /** 小窗中没有检测到 QQ/微信窗口 */
    NO_CHAT_APP,

    /** 微信未把路径识别为图片（已清空输入框，未发送任何内容） */
    NOT_RECOGNIZED,

    /** 视频发送到微信暂不支持（前台聊天窗为微信且所选文件为视频） */
    WECHAT_VIDEO_UNSUPPORTED,

    /** 其他失败 */
    FAILED
}

/**
 * 一键发送管理器（参考搜狗输入法的图片发送体验，非系统分享路径）：
 *
 * 微信：Shizuku 模式下不再区分 GIF/非 GIF，统一把图片临时复制到公共 Download/，
 * 经路径粘贴让微信识别为图片消息，随后自动点击“发送”（拖拽方案暂时停用）；
 * 无障碍模式同样走路径识别。
 *
 * QQ：不支持路径识别，走模拟拖拽——图片格子自身是系统拖拽源（长按发起
 * startDragAndDrop，FileProvider uri + 跨应用读授权），无障碍 dispatchGesture
 * 模拟“长按 → 拖到 QQ 聊天窗 → 悬停 → 抬手”，与真手指拖放完全同路径
 * （用户已验证 ColorOS 小窗间拖图片到 QQ 可直接发送，png/gif 通用）。
 */
object ImageSender {

    private const val TAG = "ImageSender"

    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val QQ_PACKAGE = "com.tencent.mobileqq"

    /** 临时文件名前缀（启动时清理残留用） */
    private const val STAGED_PREFIX = "EMOO_SEND_"

    /** 后台清理作用域：发送结果不被临时文件删除阻塞 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 当前发送方式是否就绪：Shizuku 模式只看 Shizuku 运行与授权，
     * 无障碍模式只看无障碍服务开关。
     */
    fun isReady(context: Context): Boolean = when (MetaPreferences.get(context).getSendMode()) {
        SendMode.SHIZUKU -> ShizukuDragInjector.isAvailable()
        SendMode.ACCESSIBILITY -> PasteAccessibilityService.isEnabled()
    }

    /** 上次未检测到聊天窗口时可访问的窗口包名（诊断用，随 Snackbar 提示） */
    @Volatile
    var lastWindowPackages: String? = null
        private set

    /**
     * 将图片发送到小窗/分屏中的 QQ/微信聊天窗口。必须在主线程协程中调用。
     * [sourceCenter] 为被点击图片格子在屏幕上的中心坐标（QQ 拖拽的起点）。
     *
     * Shizuku 模式：全程 shell 通道（dumpsys 检测窗口 + input 注入），
     * 不触碰无障碍。无障碍模式：仅在微信流程与判定聊天应用时做窗口扫描，
     * QQ 拖拽不再读取 QQ 节点（drop 点由 EMOO 小窗几何位置推算），
     * 把 ColorOS 的“无障碍使用提醒”压到最低。
     */
    suspend fun sendToForegroundChat(
        context: Context,
        image: ImageItem,
        sourceCenter: androidx.compose.ui.geometry.Offset?
    ): SendResult = withContext(Dispatchers.Main) {
        val mode = MetaPreferences.get(context).getSendMode()
        if (mode == SendMode.SHIZUKU) {
            if (!ShizukuDragInjector.isAvailable()) return@withContext SendResult.SHIZUKU_UNAVAILABLE
            return@withContext withContext(Dispatchers.IO) {
                if (ShizukuSender.windowBounds(WECHAT_PACKAGE) != null) {
                    // 视频经微信路径识别发送不可行，暂不支持（QQ 拖拽仍可用）
                    if (image.isVideo) return@withContext SendResult.WECHAT_VIDEO_UNSUPPORTED
                    // 微信：不再区分 GIF/非 GIF，统一走“复制路径→点输入框→清空→粘贴→点发送”。
                    // 拖拽方案暂时停用（保留代码备查）：
                    // if (image.isGif) ShizukuSender.sendWechat(context, image)
                    // else ShizukuSender.sendQqDrag(context, sourceCenter)
                    ShizukuSender.sendWechat(context, image)
                } else if (ShizukuSender.windowBounds(QQ_PACKAGE) != null) {
                    ShizukuSender.sendQqDrag(context, sourceCenter)
                } else {
                    lastWindowPackages = "Shizuku dumpsys 未发现 QQ/微信窗口"
                    SendResult.NO_CHAT_APP
                }
            }
        }

        val service = PasteAccessibilityService.instance
            ?: return@withContext SendResult.SERVICE_DISABLED
        // 窗口列表可能偶发尚未同步（小窗切换瞬间），最多补扫一次；
        // 单次扫描同时判定微信与 QQ，避免每个包各扫一遍
        val targets = listOf(WECHAT_PACKAGE, QQ_PACKAGE)
        var scan = service.scanWindows(targets)
        if (scan.targets.isEmpty()) {
            delay(150)
            scan = service.scanWindows(targets)
        }
        when {
            scan.targets.containsKey(WECHAT_PACKAGE) ->
                if (image.isVideo) SendResult.WECHAT_VIDEO_UNSUPPORTED
                else sendViaWechat(service, context, image, scan.targets[WECHAT_PACKAGE])
            scan.targets.containsKey(QQ_PACKAGE) ->
                sendViaQQDrag(service, context, sourceCenter)
            else -> {
                lastWindowPackages = scan.allPackages.joinToString(", ")
                SendResult.NO_CHAT_APP
            }
        }
    }

    // ================================ 微信：路径识别 ================================

    private suspend fun sendViaWechat(
        service: PasteAccessibilityService,
        context: Context,
        image: ImageItem,
        wechatWindow: AccessibilityWindowInfo?
    ): SendResult {
        // 图片在私有目录，微信读不到——先复制到公共 Download 临时文件
        val staged = stageToDownloads(context, image) ?: return SendResult.FAILED
        val (uri, path) = staged
        val fileName = path.substringAfterLast('/')
        // 图片已附到输入框但未发出时不能删临时文件（微信发送时才读文件），
        // 残留文件由下次启动的 cleanupStaleStaged 清理
        var keepStagedFile = false
        return try {
            val root = service.rootOf(wechatWindow, WECHAT_PACKAGE) ?: return SendResult.FAILED
            val input = service.findEditableNode(root) ?: return SendResult.FAILED
            if (!service.setText(input, path)) return SendResult.FAILED

            // 轮询等待识别：微信识别成功后会把输入框中的路径文本替换为空（图片态）。
            // 每次只刷新缓存窗口的根节点，不做全窗口扫描
            var recognized = false
            for (i in 0 until 13) {
                delay(150)
                val freshRoot = service.rootOf(wechatWindow, WECHAT_PACKAGE) ?: continue
                val freshInput = service.findEditableNode(freshRoot) ?: run {
                    recognized = true
                    break
                }
                val text = service.nodeText(freshInput).orEmpty()
                if (!text.contains(fileName)) {
                    recognized = true
                    break
                }
            }

            if (recognized) {
                // 图片已附到输入框，轮询等发送按钮出现立即点击（通常 100~200ms 内就绪）
                var clicked = false
                for (i in 0 until 13) {
                    clicked = service.rootOf(wechatWindow, WECHAT_PACKAGE)
                        ?.let { service.clickNodeByText(it, "发送") } == true
                    if (clicked) break
                    delay(150)
                }
                if (clicked) {
                    SendResult.SENT
                } else {
                    keepStagedFile = true
                    SendResult.ATTACHED
                }
            } else {
                // 未识别：清空输入框，避免把路径当文本发出去
                service.rootOf(wechatWindow, WECHAT_PACKAGE)?.let { r ->
                    service.findEditableNode(r)?.let { service.setText(it, "") }
                }
                SendResult.NOT_RECOGNIZED
            }
        } finally {
            if (keepStagedFile) {
                // ATTACHED 时微信发送前仍需读文件，保留临时文件
            } else {
                // 微信点击“发送”后需数秒才真正读完文件，延迟转后台删除，
                // 不阻塞发送结果返回
                cleanupScope.launch {
                    delay(3000)
                    runCatching { context.contentResolver.delete(uri, null, null) }
                        .onFailure { Log.w(TAG, "清理临时文件失败", it) }
                }
            }
        }
    }

    // ================================ QQ：模拟拖拽 ================================

    /**
     * 模拟人手把图片从 EMOO 格子拖到 QQ 聊天窗：拖拽会话由图片格子的
     * drag source 发起（ClipData 带 FileProvider uri），QQ 接收 drop 即直接发送。
     *
     * drop 点不通过无障碍读取 QQ 节点（把敏感访问压到最低）：与 Shizuku 模式
     * 共用同一算法——点击位置长按后横向快速滑到屏幕对侧边缘。
     */
    private suspend fun sendViaQQDrag(
        service: PasteAccessibilityService,
        context: Context,
        sourceCenter: androidx.compose.ui.geometry.Offset?
    ): SendResult = withContext(Dispatchers.IO) {
        if (sourceCenter == null) return@withContext SendResult.FAILED
        val targets = ShizukuSender.computeQqDragTargets(context, sourceCenter)
            ?: return@withContext SendResult.FAILED
        Log.d(
            TAG,
            "QQ 拖拽(无障碍): from=(${targets.fromX},${targets.fromY}) to=(${targets.toX},${targets.toY})"
        )

        DragSessionState.reset()
        // 与 Shizuku 通道一致：模拟拖拽用透明虚影，避免缩略图横穿屏幕
        DragSessionState.markAutoPending()
        val gestured = suspendCancellableCoroutine { continuation ->
            service.performDragGesture(
                targets.fromX, targets.fromY, targets.toX, targets.toY
            ) { completed ->
                if (continuation.isActive) continuation.resume(completed)
            }
        }
        if (!gestured) {
            DragSessionState.consumeAutoDrag()
            return@withContext SendResult.FAILED
        }

        // 等待 QQ 处理 drop；若拖拽源从未启动（长按未触发 drag），报失败
        delay(900)
        return@withContext if (DragSessionState.wasStarted()) {
            SendResult.SENT
        } else {
            DragSessionState.consumeAutoDrag()
            SendResult.FAILED
        }
    }

    // ================================ 临时文件 ================================

    /**
     * 把私有目录中的图片复制到公共 Download/ 下的临时文件（微信可读），
     * 返回 (content uri, 真实磁盘路径)。ShizukuSender 的微信流程也复用此方法。
     */
    internal suspend fun stageToDownloads(
        context: Context,
        image: ImageItem
    ): Pair<Uri, String>? = withContext(Dispatchers.IO) {
        try {
            val source = File(image.path)
            if (!source.exists()) return@withContext null
            val ext = image.displayName.substringAfterLast('.', "jpg").lowercase()
            val mime = ImageRepository.mimeOf(image.displayName)
            val values = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "${STAGED_PREFIX}${System.currentTimeMillis()}.$ext"
                )
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Files.getContentUri("external"), values
            ) ?: return@withContext null
            val copied = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (!copied) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }
            // 查真实落盘路径（微信路径识别需要绝对路径）
            val path = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull()
            if (path == null) {
                runCatching { resolver.delete(uri, null, null) }
                return@withContext null
            }
            uri to path
        } catch (_: Exception) {
            null
        }
    }

    /** 后台延迟清理 staging 临时文件（微信点击发送后仍需读文件数秒） */
    internal fun scheduleStagedCleanup(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        cleanupScope.launch {
            delay(3000)
            runCatching { appContext.contentResolver.delete(uri, null, null) }
                .onFailure { Log.w(TAG, "清理临时文件失败", it) }
        }
    }

    /** 启动时清理 Download 下残留的发送临时文件（上次发送中断遗留） */
    suspend fun cleanupStaleStaged(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            resolver.delete(
                MediaStore.Files.getContentUri("external"),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("$STAGED_PREFIX%", "Download/")
            )
        }
    }
}
