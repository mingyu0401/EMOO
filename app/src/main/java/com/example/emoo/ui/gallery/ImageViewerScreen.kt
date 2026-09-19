package com.example.emoo.ui.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.emoo.data.ImageRepository
import com.example.emoo.model.ImageItem
import java.io.File

/**
 * 大图浏览页：左右滑动切换同列表图片（HorizontalPager），
 * 双指缩放/平移（transformable，单指滑动仍交给 Pager 翻页），
 * 顶栏显示文件名与所在文件夹。
 */
@Composable
fun ImageViewerScreen(
    viewModel: GalleryViewModel,
    initialIndex: Int,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val images = state.images
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    if (images.isEmpty()) {
        // 数据已变化（如刚被删除），自动返回
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("图片不存在", color = Color.White)
        }
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex)
    ) { images.size }

    // 双指缩放与平移
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 6f)
        if (newScale == 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX += panChange.x
            offsetY += panChange.y
        }
        scale = newScale
    }
    // 切页时复位缩放
    LaunchedEffect(pagerState.currentPage, pagerState.targetPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    val current = images[pagerState.currentPage.coerceIn(0, images.lastIndex)]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 顶栏：返回 + 文件名/所在文件夹 + 页码
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "文件夹：${current.folderName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${pagerState.currentPage + 1}/${images.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            val image = images[page]
            if (image.isText) {
                TextReaderPage(image)
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = image.uriString,
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                            .transformable(transformableState)
                    )
                    // 视频：仅显示封面（首帧），不在应用内播放，提供外部应用打开入口
                    if (image.isVideo) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "视频不在应用内播放",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Button(onClick = { openVideoExternal(context, image) }) {
                                Text("用其他应用打开")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 文字查看页：底色/文字跟随深浅色主题，可滚动显示全文，右下角提供"复制全文" */
@Composable
private fun TextReaderPage(image: ImageItem) {
    val context = LocalContext.current
    var content by remember(image.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(image.path) {
        content = ImageRepository.readTextFile(image.path)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = content?.takeIf { it.isNotBlank() } ?: if (content == null) "加载中…" else "（空）",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        }
        Button(
            onClick = {
                val full = content.orEmpty()
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("emoo_text", full))
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Text("复制全文")
        }
    }
}

/** 用外部应用打开视频：FileProvider uri + ACTION_VIEW + 读权限，不在应用内播放 */
private fun openVideoExternal(context: Context, image: ImageItem) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, ImageRepository.FILE_PROVIDER_AUTHORITY, File(image.path)
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ImageRepository.mimeOf(image.displayName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "用其他应用打开"))
    }
}
