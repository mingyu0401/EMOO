package com.example.emoo.ui.gallery

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.emoo.MultiWindowState
import com.example.emoo.data.ImageRepository
import com.example.emoo.data.MetaPreferences
import com.example.emoo.model.FolderSort
import com.example.emoo.model.ImageItem
import com.example.emoo.model.SendMode
import com.example.emoo.model.StickerSortMode
import com.example.emoo.send.ImageSender
import com.example.emoo.send.SendResult
import com.example.emoo.send.ShizukuDragInjector
import kotlinx.coroutines.launch

/**
 * 图片主页面：横向 5:1 划分——左侧 5/6 为可滑动图片网格（首项固定"+"卡片），
 * 右侧 1/6 为文件夹侧栏。支持长按图片（设为预览图/删除）、长按文件夹（上下排序/删除）、
 * 新建文件夹、ON_RESUME 自动重扫描同步外部改动。
 *
 * 小窗/分屏模式（isInMultiWindowMode）下点击图片不走大图浏览，
 * 而是经无障碍服务一键发送到前台聊天应用：微信走“路径识别+自动点发送”，
 * QQ 走“模拟拖拽到聊天窗直接发送”（图片格子同时作为真手指长按拖拽的源）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onNavigateToImport: () -> Unit,
    onOpenImage: (Int) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // 回到前台时重扫描，同步外部文件管理器的增删
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 交互状态
    var longPressImage by remember { mutableStateOf<ImageItem?>(null) }
    var deleteImageTarget by remember { mutableStateOf<ImageItem?>(null) }
    var renameTarget by remember { mutableStateOf<ImageItem?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<String?>(null) }
    var deleteFolderCount by remember { mutableIntStateOf(0) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showAccessibilityGuide by remember { mutableStateOf(false) }
    var showShizukuGuide by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var sortFolderTarget by remember { mutableStateOf<String?>(null) }
    var showClearRecentConfirm by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // 首次使用（未选择过发送方式）时弹选择：Shizuku（推荐）/ 无障碍
    var showModeChooser by remember {
        mutableStateOf(MetaPreferences.get(context).peekSendMode() == null)
    }

    // 删除文件夹前统计其中图片数量，用于二次确认文案
    LaunchedEffect(deleteFolderTarget) {
        deleteFolderCount = deleteFolderTarget?.let { viewModel.countImages(it) } ?: 0
    }

    // 切换文件夹/进出搜索时网格回到顶部，避免沿用上一列表的滚动位置
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.selectedFolder, state.searchActive) { gridState.scrollToItem(0) }

    /** 小窗模式下点击图片：一键发送到前台聊天应用（[center] 为被点格子的屏幕中心，QQ 拖拽起点） */
    fun sendImage(image: ImageItem, center: androidx.compose.ui.geometry.Offset?) {
        if (sending) return
        sending = true
        scope.launch {
            val result = ImageSender.sendToForegroundChat(context, image, center)
            sending = false
            if (result == SendResult.SENT || result == SendResult.ATTACHED) {
                viewModel.recordSentImage(image)
            }
            val message = when (result) {
                SendResult.SENT -> "已发送到聊天窗口"
                SendResult.ATTACHED -> "图片已附到聊天框，请手动点击发送"
                SendResult.SERVICE_DISABLED -> "发送失败：无障碍服务未开启"
                SendResult.SHIZUKU_UNAVAILABLE ->
                    "发送失败：Shizuku 未运行或未授权本应用（可在设置中切换发送方式）"
                SendResult.NO_CHAT_APP -> {
                    val seen = ImageSender.lastWindowPackages
                    if (seen.isNullOrEmpty()) {
                        "未检测到 QQ/微信聊天窗口（请确认聊天应用在小窗/分屏中打开）"
                    } else {
                        "未检测到 QQ/微信聊天窗口（可见：$seen）"
                    }
                }
                SendResult.NOT_RECOGNIZED -> "微信未能识别图片路径，已取消发送"
                SendResult.FAILED -> "发送失败，可重试或手动发送"
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    /** 小窗点击文字：读取 .txt 全文复制到剪贴板（暂不做发送） */
    fun copyTextToClipboard(image: ImageItem) {
        scope.launch {
            val full = ImageRepository.readTextFile(image.path)
            clipboard.setPrimaryClip(ClipData.newPlainText("emoo_text", full))
            snackbarHostState.showSnackbar(if (full.isNotBlank()) "已复制文字" else "文字内容为空")
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 左侧 5/6：图片网格区（inMultiWindow 为可观察状态，进出小窗实时重组）
            Column(modifier = Modifier.weight(5f)) {
                // 搜索态：顶部文件名搜索栏（范围为全库，内存过滤）
                if (state.searchActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::setSearchQuery,
                            singleLine = true,
                            placeholder = { Text("搜索文件名") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清空")
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.exitSearch() }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出搜索")
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    val inMultiWindow = MultiWindowState.isInMultiWindow ||
                        (context as? Activity)?.isInMultiWindowMode == true
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(state.gridColumns),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 固定在最前面的“添加图片”卡片
                        item(key = "__add_card__") {
                            AddImageCard(onClick = onNavigateToImport)
                        }
                        itemsIndexed(state.images, key = { _, item -> item.path }) { index, image ->
                            ImageGridItem(
                                image = image,
                                onClick = { center ->
                                    if (inMultiWindow) {
                                        // 文字：暂不做发送，小窗点击仅复制全文
                                        if (image.isText) {
                                            copyTextToClipboard(image)
                                        } else if (ImageSender.isReady(context)) {
                                            // 小窗/分屏：一键发送到前台聊天应用
                                            sendImage(image, center)
                                        } else if (MetaPreferences.get(context)
                                                .getSendMode() == SendMode.SHIZUKU
                                        ) {
                                            showShizukuGuide = true
                                        } else {
                                            showAccessibilityGuide = true
                                        }
                                    } else if (state.images.isNotEmpty()) {
                                        onOpenImage(index)
                                    }
                                },
                                // 小窗模式下长按拖拽是拖拽源（供模拟拖拽与真手指拖动），
                                // 长按菜单弹窗会干扰拖拽会话，故小窗内禁用菜单
                                onLongPress = {
                                    if (!inMultiWindow) longPressImage = image
                                },
                                // 文字不参与拖拽发送
                                dragSource = inMultiWindow && !image.isText
                            )
                        }
                    }

                    // 空状态引导
                    if (!state.loading && state.images.isEmpty()) {
                        val hint = when {
                            state.searchActive && state.searchQuery.isNotBlank() ->
                                "没有文件名包含「${state.searchQuery}」的图片"
                            state.folders.isEmpty() -> "还没有文件夹\n点击右下角 + 创建你的第一个文件夹"
                            state.selectedFolder == null -> "暂无最近使用记录\n点击下方“导入”或网格首位的 + 添加图片"
                            else -> "此文件夹暂无图片\n点击网格首位的 + 导入图片"
                        }
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp)
                        )
                    }
                    if (state.loading && state.images.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }

            // 右侧 1/6：文件夹侧栏（浮动圆角面板，与底栏之间柔和过渡）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                FolderSidebar(
                    folders = state.folders,
                    selectedFolder = state.selectedFolder,
                    previewMap = state.previewMap,
                    searchActive = state.searchActive,
                    onSelectFolder = { viewModel.selectFolder(it) },
                    onToggleSearch = {
                        if (state.searchActive) viewModel.exitSearch() else viewModel.enterSearch()
                    },
                    onCreateFolder = { showCreateFolderDialog = true },
                    onLongPressFolder = { folder ->
                        sortFolderTarget = folder
                        showReorderDialog = true
                    },
                    onLongPressRecent = { showClearRecentConfirm = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // 长按图片：操作菜单（底部弹窗）
    longPressImage?.let { image ->
        ModalBottomSheet(onDismissRequest = { longPressImage = null }) {
            if (!image.isText) {
                ListItem(
                    headlineContent = { Text("设为「${image.folderName}」的预览图") },
                    leadingContent = { Icon(Icons.Filled.Image, contentDescription = null) },
                    modifier = Modifier.clickable {
                        viewModel.setFolderPreview(image)
                        longPressImage = null
                        scope.launch { snackbarHostState.showSnackbar("已设为文件夹预览图") }
                    }
                )
            }
            // 文件名条目：长按文件名可复制到剪贴板
            ListItem(
                headlineContent = {
                    Text(
                        text = image.displayName,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("emoo", image.displayName)
                                )
                                longPressImage = null
                                scope.launch { snackbarHostState.showSnackbar("已复制文件名") }
                            }
                        )
                    )
                },
                supportingContent = { Text("长按文件名可复制") },
                leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) }
            )
            ListItem(
                headlineContent = { Text("重命名") },
                supportingContent = { Text(image.displayName) },
                leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                modifier = Modifier.clickable {
                    renameTarget = image
                    longPressImage = null
                }
            )
            ListItem(
                headlineContent = { Text("删除图片") },
                supportingContent = { Text(image.displayName) },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                modifier = Modifier.clickable {
                    deleteImageTarget = image
                    longPressImage = null
                }
            )
            // 底部留白，保证弹窗内容不完全贴底
            Box(Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
    }

    // 重命名图片：编辑文件名，同步更新 sha256 映射文件与“最近”记录
    renameTarget?.let { image ->
        var newName by remember(image.path) { mutableStateOf(image.displayName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名图片") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("文件名") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val target = image
                        val name = newName.trim()
                        renameTarget = null
                        viewModel.renameImage(target, name) { ok ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) "已重命名" else "重命名失败（重名或无效）"
                                )
                            }
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    // 删除单张图片：二次确认（真实文件删除，不可恢复）
    deleteImageTarget?.let { image ->
        AlertDialog(
            onDismissRequest = { deleteImageTarget = null },
            title = { Text("删除图片") },
            text = { Text("将删除 1 张图片且不可恢复：\n${image.displayName}") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = image
                        deleteImageTarget = null
                        viewModel.deleteImage(target) { ok ->
                            scope.launch {
                                snackbarHostState.showSnackbar(if (ok) "已删除图片" else "删除失败")
                            }
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteImageTarget = null }) { Text("取消") }
            }
        )
    }

    // 长按“最近”：清空确认弹窗（仅清记录，图片文件不受影响，可撤销）
    if (showClearRecentConfirm) {
        AlertDialog(
            onDismissRequest = { showClearRecentConfirm = false },
            title = { Text("清空「最近」记录") },
            text = { Text("将清空最近使用的图片记录（最多 100 条），图片文件本身不会被删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearRecentConfirm = false
                        val oldEntries = viewModel.clearRecent()
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "已清空 ${oldEntries.size} 条最近记录",
                                actionLabel = "撤销",
                                withDismissAction = true
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.restoreRecent(oldEntries)
                                snackbarHostState.showSnackbar("已撤销清空")
                            }
                        }
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearRecentConfirm = false }) { Text("取消") }
            }
        )
    }

    // 长按文件夹：整理对话框（上下排序 + 删除 + 表情包排序设置）。“最近”为固定入口不参与排序
    if (showReorderDialog) {
        val sortFolder = sortFolderTarget
        val sortable = sortFolder != null && sortFolder == state.selectedFolder && !state.searchActive
        val currentSort = state.folderSort
        var sortMode by remember(sortFolder, currentSort) {
            mutableStateOf(currentSort?.mode ?: StickerSortMode.DEFAULT)
        }
        var sortReverse by remember(sortFolder, currentSort) {
            mutableStateOf(currentSort?.reverse ?: false)
        }
        AlertDialog(
            onDismissRequest = { showReorderDialog = false },
            title = { Text("整理文件夹") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (state.folders.isEmpty()) {
                            Text(
                                "还没有文件夹",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.folders.forEachIndexed { index, folder ->
                            ListItem(
                                headlineContent = { Text(folder) },
                                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            enabled = index > 0,
                                            onClick = { viewModel.moveFolder(folder, up = true) }
                                        ) {
                                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                                        }
                                        IconButton(
                                            enabled = index < state.folders.lastIndex,
                                            onClick = { viewModel.moveFolder(folder, up = false) }
                                        ) {
                                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                                        }
                                        IconButton(onClick = {
                                            deleteFolderTarget = folder
                                            showReorderDialog = false
                                        }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "删除文件夹",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // 表情包排序设置（仅对当前打开的文件夹）
                    if (sortable && sortFolder != null) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Text(
                            "「$sortFolder」表情包排序",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            sortModeDescription(sortMode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            StickerSortMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = sortMode == mode,
                                    onClick = { sortMode = mode },
                                    label = { Text(sortModeLabel(mode)) }
                                )
                            }
                        }
                        ListItem(
                            headlineContent = { Text("倒序") },
                            supportingContent = {
                                if (sortMode == StickerSortMode.RANDOM) Text("随机排序无倒序意义")
                            },
                            trailingContent = {
                                Switch(
                                    checked = sortReverse,
                                    onCheckedChange = { sortReverse = it },
                                    enabled = sortMode != StickerSortMode.RANDOM
                                )
                            }
                        )
                    }
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val appliedFolder = sortFolder?.takeIf { sortable }
                    if (appliedFolder != null) {
                        TextButton(onClick = {
                            showReorderDialog = false
                            viewModel.setFolderSort(
                                appliedFolder, FolderSort(sortMode, sortReverse), persist = false
                            )
                            scope.launch {
                                snackbarHostState.showSnackbar("已应用临时排序（下次进入文件夹恢复）")
                            }
                        }) { Text("临时排序") }
                        TextButton(onClick = {
                            showReorderDialog = false
                            viewModel.setFolderSort(
                                appliedFolder, FolderSort(sortMode, sortReverse), persist = true
                            )
                            scope.launch {
                                snackbarHostState.showSnackbar("已覆盖「$appliedFolder」默认排序")
                            }
                        }) { Text("覆盖默认排序") }
                    } else {
                        TextButton(onClick = { showReorderDialog = false }) { Text("完成") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showReorderDialog = false }) { Text("取消") }
            }
        )
    }

    // 删除文件夹：二次确认（目录及内部图片一并真实删除）
    deleteFolderTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("删除文件夹") },
            text = {
                Text(
                    if (deleteFolderCount > 0) {
                        "将删除文件夹「$folder」及其中 $deleteFolderCount 张图片，且不可恢复。"
                    } else {
                        "将删除空文件夹「$folder」，且不可恢复。"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteFolderTarget = null
                        viewModel.deleteFolder(folder) { deleted ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (deleted > 0) "已删除文件夹「$folder」（$deleted 张图片）"
                                    else "已删除空文件夹「$folder」"
                                )
                            }
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFolderTarget = null }) { Text("取消") }
            }
        )
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                Column {
                    Text("将在 Android/data/com.example.emoo/files/pictures/ 下真实创建该目录。")
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        singleLine = true,
                        label = { Text("文件夹名") },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = folderName.isNotBlank(),
                    onClick = {
                        val name = folderName.trim()
                        showCreateFolderDialog = false
                        viewModel.createFolder(name) { ok ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) "已创建文件夹「$name」" else "创建失败：文件夹名无效"
                                )
                            }
                        }
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("取消") }
            }
        )
    }

    // 一键发送服务未开启引导
    if (showAccessibilityGuide) {
        AlertDialog(
            onDismissRequest = { showAccessibilityGuide = false },
            title = { Text("开启一键发送") },
            text = {
                Text(
                    "小窗内点击图片直接发送到 QQ/微信聊天框，需要开启“EMOO 一键发送”无障碍服务：\n\n" +
                        "设置 → 无障碍 → 已下载的应用 → EMOO 一键发送\n\n" +
                        "注意：更新/重装 App 后需关闭再重新开启一次服务\n\n" +
                        "提示：无障碍方式可能触发系统频繁弹窗，推荐在设置中切换为 Shizuku 模式"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityGuide = false
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityGuide = false }) { Text("取消") }
            }
        )
    }

    // Shizuku 模式未就绪引导：请求授权 / 指引启动 Shizuku
    if (showShizukuGuide) {
        val shizukuAlive = ShizukuDragInjector.binderAlive()
        AlertDialog(
            onDismissRequest = { showShizukuGuide = false },
            title = { Text("授权 Shizuku") },
            text = {
                Text(
                    if (shizukuAlive) {
                        "检测到 Shizuku 正在运行，但尚未授权本应用。点击“去授权”，在弹出的 Shizuku 窗口中允许即可。"
                    } else {
                        "当前使用 Shizuku 模式发送，但 Shizuku 服务未运行：\n\n" +
                            "请先安装并打开 Shizuku 应用，按其指引启动服务（无线调试或 Root），\n\n" +
                            "也可以在 设置 中切换为无障碍方式。"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showShizukuGuide = false
                        if (shizukuAlive) {
                            ShizukuDragInjector.requestPermission(context)
                        } else {
                            runCatching {
                                context.startActivity(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
                            }
                        }
                    }
                ) { Text(if (shizukuAlive) "去授权" else "打开 Shizuku") }
            },
            dismissButton = {
                TextButton(onClick = { showShizukuGuide = false }) { Text("取消") }
            }
        )
    }

    // 首次使用：选择发送方式（推荐 Shizuku），只申请所选定方式的权限
    if (showModeChooser) {
        AlertDialog(
            onDismissRequest = { showModeChooser = false },
            title = { Text("选择发送方式") },
            text = {
                Text(
                    "小窗中点击图片可一键发送到 QQ/微信，请选择实现方式：\n\n" +
                        "· Shizuku（推荐）：shell 级注入，不触发系统的无障碍频繁弹窗，需要安装并启动 Shizuku 后授权\n\n" +
                        "· 无障碍：无需额外应用，但 ColorOS 可能频繁弹窗提醒无障碍使用\n\n" +
                        "选择后仅申请对应权限，之后可在 设置 中切换。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        MetaPreferences.get(context).setSendMode(SendMode.SHIZUKU)
                        showModeChooser = false
                        ShizukuDragInjector.requestPermission(context)
                    }
                ) { Text("Shizuku（推荐）") }
            },
            dismissButton = {
                Button(
                    onClick = {
                        MetaPreferences.get(context).setSendMode(SendMode.ACCESSIBILITY)
                        showModeChooser = false
                        showAccessibilityGuide = true
                    }
                ) { Text("无障碍") }
            }
        )
    }
}

private fun sortModeLabel(mode: StickerSortMode): String = when (mode) {
    StickerSortMode.DEFAULT -> "默认"
    StickerSortMode.CREATION -> "创建时间"
    StickerSortMode.USAGE -> "使用次数"
    StickerSortMode.RANDOM -> "每次随机"
    StickerSortMode.NAME -> "名称"
}

private fun sortModeDescription(mode: StickerSortMode): String = when (mode) {
    StickerSortMode.DEFAULT -> "按导入先后排列，新导入的在前"
    StickerSortMode.CREATION -> "按文件系统创建时间排列"
    StickerSortMode.USAGE -> "按发送使用次数排列，常用在前"
    StickerSortMode.RANDOM -> "每次进入或刷新时随机打乱"
    StickerSortMode.NAME -> "按文件名升序排列"
}
