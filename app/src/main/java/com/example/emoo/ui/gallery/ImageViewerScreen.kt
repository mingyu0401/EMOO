package com.example.emoo.ui.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.emoo.data.MetaPreferences
import com.example.emoo.model.ImageItem
import com.example.emoo.model.SendMode
import com.example.emoo.send.ImageSender
import java.io.File

/**
 * 大图浏览页：左右滑动切换同列表图片（HorizontalPager），
 * 双指缩放/平移（transformable，单指滑动仍交给 Pager 翻页），
 * 顶栏显示文件名与所在文件夹；整页长按弹出与网格相同的文件信息弹窗。
 */
@OptIn(ExperimentalFoundationApi::class)
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

    // 长按预览图弹出的文件信息弹窗（与网格长按同一组件）
    var detailImage by remember { mutableStateOf<ImageItem?>(null) }

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
            // 整页长按：与网格一致的“更多信息”文件信息弹窗（单击不响应，滑动仍翻页）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { detailImage = image }
                    )
            ) {
                if (image.isText) {
                    TextReaderPage(image, viewModel)
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
                        // 长按查看文件信息的提示（位于网格/预览图里外均可用）
                        Text(
                            text = "里外均可长按查看更多信息",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                        )
                        // 全屏浏览：右下角圆形“分享”按钮拉起系统分享；
                        // Shizuku/无障碍模式下顺带提示小窗发送更方便
                        FloatingActionButton(
                            onClick = {
                                if (MetaPreferences.get(context)
                                        .getSendMode() != SendMode.NORMAL
                                ) {
                                    Toast.makeText(context, "小窗更方便哦", Toast.LENGTH_SHORT).show()
                                }
                                ImageSender.shareViaSystem(context, image)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "分享")
                        }
                    }
                }
            }
        }
    }

    // 长按预览图：与网格相同的文件信息弹窗（含重命名/删除；结果用 Toast 提示）
    ImageDetailSheet(
        image = detailImage,
        viewModel = viewModel,
        usageCount = detailImage?.let { state.usageCounts[it.path] },
        canReorder = detailImage?.let {
            state.selectedFolder == it.folderName && !state.searchActive
        } ?: false,
        onDismiss = { detailImage = null },
        onMessage = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    )
}

/** 文字查看页：底色/文字跟随深浅色主题，可滚动显示全文，右下角"复制全文/编辑" */
@Composable
private fun TextReaderPage(image: ImageItem, viewModel: GalleryViewModel) {
    val context = LocalContext.current
    var content by remember(image.path) { mutableStateOf<String?>(null) }
    var reloadKey by remember(image.path) { mutableIntStateOf(0) }
    var showEditDialog by remember(image.path) { mutableStateOf(false) }
    LaunchedEffect(image.path, reloadKey) {
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
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    val full = content.orEmpty()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("emoo_text", full))
                }
            ) {
                Text("复制全文")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { showEditDialog = true }) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Text("编辑", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }

    // 编辑正文：多行输入，保存后覆写 .txt 并同步 sha 映射与网格预览
    if (showEditDialog) {
        var draft by remember(image.path) { mutableStateOf(content.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑文字") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f),
                    shape = RoundedCornerShape(10.dp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    showEditDialog = false
                    viewModel.editTextFile(image, draft) { ok ->
                        if (ok) {
                            reloadKey++
                            android.widget.Toast
                                .makeText(context, "已保存修改", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            android.widget.Toast
                                .makeText(context, "保存失败", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("取消") }
            }
        )
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
