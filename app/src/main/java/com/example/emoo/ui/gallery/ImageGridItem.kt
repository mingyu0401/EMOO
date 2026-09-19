package com.example.emoo.ui.gallery

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.example.emoo.data.ImageRepository
import com.example.emoo.model.ImageItem
import com.example.emoo.send.DragSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 等宽正方形缩略图。GIF 依赖 Coil AnimatedImageDecoder 实时循环播放；
 * LazyGrid 的复用机制保证滑出可视区的项被回收、动画随之暂停。
 * 损坏/无法解码的图片显示占位图标，不崩溃。
 *
 * [dragSource] 为 true 时（小窗模式）：长按并拖动会发起系统级拖放
 * （startDragAndDrop，FileProvider uri + 跨应用读授权），可拖到 QQ/微信
 * 聊天窗直接发送——既供无障碍模拟拖拽使用，也允许用户真手指长按拖动。
 * [onClick] 回调携带该格子在屏幕上的中心坐标（模拟拖拽的起点）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGridItem(
    image: ImageItem,
    onClick: (screenCenter: Offset) -> Unit,
    onLongPress: () -> Unit,
    dragSource: Boolean = false,
    usageCount: Int? = null
) {
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf<Rect?>(null) }

    // 拖拽虚像用的缩略图：后台预解码（约 512px，GIF 取首帧），拖起时直接绘制
    var dragThumb by remember(image.path, dragSource) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(image.path, dragSource) {
        if (dragSource) {
            dragThumb = withContext(Dispatchers.IO) { decodeDragThumbnail(image.path) }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .combinedClickable(
                onClick = { bounds?.center?.let(onClick) },
                onLongClick = onLongPress
            )
            .then(
                if (dragSource) {
                    Modifier.pointerInput(image.path) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                // 发起跨应用拖放：DRAG_FLAG_GLOBAL 允许拖出本应用窗口，
                                // DRAG_FLAG_GLOBAL_URI_READ 授予接收方读取 FileProvider uri 的权限。
                                // GIF 已在导入时压缩，直接使用磁盘文件发起拖放
                                DragSessionState.markStarted()
                                // 模拟拖拽（Shizuku 注入）用透明虚影，避免缩略图横穿屏幕；
                                // 真手指拖动仍保留缩略图虚影
                                val autoDrag = DragSessionState.consumeAutoDrag()
                                val file = File(image.path)
                                val uri = FileProvider.getUriForFile(
                                    context, ImageRepository.FILE_PROVIDER_AUTHORITY, file
                                )
                                // 显式声明 MIME（对齐系统相册的拖拽载荷）：
                                // ClipData.newUri 依赖 resolver 反查类型，微信对类型缺失/
                                // 不明确的载荷会拒收，导致拖入后不发送。图片/视频统一按扩展名推断
                                val mime = ImageRepository.mimeOf(file.name)
                                val clip = ClipData(file.name, arrayOf(mime), ClipData.Item(uri))
                                view.startDragAndDrop(
                                    clip,
                                    if (autoDrag) {
                                        TransparentDragShadowBuilder()
                                    } else {
                                        dragThumb?.let { thumb ->
                                            // 虚像直接画图片本身（约 96dp 方形圆角），不再是整格快照
                                            val sizePx = with(density) { 96.dp.toPx() }.toInt()
                                            BitmapDragShadowBuilder(thumb, sizePx)
                                        } ?: View.DragShadowBuilder(view)
                                    },
                                    null,
                                    View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                                )
                            },
                            onDrag = { _, _ -> },
                            onDragEnd = { },
                            onDragCancel = { }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (image.isText) {
            // 文字文件：纸面卡片显示正文前缀 + 省略号（非图片）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                Text(
                    text = (image.previewText?.takeIf { it.isNotBlank() } ?: "（空）") + "…",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            AsyncImage(
                model = image.uriString,
                contentDescription = image.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
                error = rememberVectorPainter(Icons.Filled.BrokenImage)
            )
        }
        if (image.isGif || image.isVideo || image.isText) {
            Text(
                text = when {
                    image.isText -> "TEXT"
                    image.isVideo -> "VIDEO"
                    else -> "GIF"
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        // 使用次数角标（设置页开关控制），与类型角标分列两侧
        if (usageCount != null && usageCount > 0) {
            Text(
                text = "×$usageCount",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

/** 网格首位固定位置的“添加图片”卡片（+ 图标） */
@Composable
fun AddImageCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "+",
            fontSize = 36.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Light
        )
    }
}

/** 解码拖拽虚像缩略图：采样到约 512px，GIF 自动取首帧 */
private fun decodeDragThumbnail(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) {
        sample *= 2
    }
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/**
 * 拖拽虚像：直接绘制图片本身（正方形居中裁剪 + 圆角），触摸点位于虚像中心。
 * 替代 View.DragShadowBuilder(view) 的整格快照。
 */
private class BitmapDragShadowBuilder(
    private val bitmap: Bitmap,
    private val sizePx: Int
) : View.DragShadowBuilder() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(sizePx, sizePx)
        outShadowTouchPoint.set(sizePx / 2, sizePx / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        // 与格子显示一致：正方形居中裁剪（ContentScale.Crop）后铺满虚像区
        val side = minOf(bitmap.width, bitmap.height)
        val src = android.graphics.Rect(
            (bitmap.width - side) / 2, (bitmap.height - side) / 2,
            (bitmap.width + side) / 2, (bitmap.height + side) / 2
        )
        val corner = sizePx * 0.12f
        val dst = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        canvas.clipPath(android.graphics.Path().apply {
            addRoundRect(dst, corner, corner, android.graphics.Path.Direction.CW)
        })
        canvas.drawBitmap(bitmap, src, dst, paint)
    }
}

/**
 * 模拟拖拽的透明虚影：只提供 1×1 尺寸且不绘制任何内容，
 * 系统拖放会话照常进行，但屏幕上看不到跟随的缩略图。
 */
private class TransparentDragShadowBuilder : View.DragShadowBuilder() {

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(1, 1)
        outShadowTouchPoint.set(0, 0)
    }

    override fun onDrawShadow(canvas: Canvas) = Unit
}
