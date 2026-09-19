package com.example.emoo.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.emoo.model.ImportCandidate
import com.example.emoo.model.ImportProgress

/**
 * 导入页：三步流程（来源选择 -> 图片勾选 -> 目标文件夹选择），
 * 复制过程显示进度并支持取消；完成后记入“最近”并提示。
 */
@Composable
fun ImportScreen() {
    val viewModel: ImportViewModel = viewModel()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val atFront by viewModel.atFront.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    // 按图片导入：系统 Photo Picker 多选
    val pickImagesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onImagesPicked(uris)
    }

    // 按文件夹导入：SAF 目录树选择
    val pickFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }

    Scaffold(
        snackbarHost = {
            // 上移，避免遮挡底部的“下一步 / 开始导入”按钮
            SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = 80.dp))
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val current = step) {
                ImportStep.PickSource -> SourceStep(
                    onPickImages = {
                        pickImagesLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickFolder = { pickFolderLauncher.launch(null) }
                )

                is ImportStep.PickImages -> ImagesStep(
                    candidates = current.candidates,
                    selection = selection,
                    onToggle = viewModel::toggleSelect,
                    onSelectAll = { all -> viewModel.selectAll(current.candidates, all) },
                    onNext = viewModel::confirmSelection,
                    onBack = viewModel::goBack
                )

                is ImportStep.PickTarget -> TargetStep(
                    folders = folders,
                    selectedCount = current.selected.size,
                    atFront = atFront,
                    onSetAtFront = viewModel::setAtFront,
                    onSelect = { /* 由内部单选状态管理 */ },
                    onBack = viewModel::goBack,
                    onReload = viewModel::reloadFolders,
                    onCreateFolder = { name, onResult -> viewModel.createFolder(name, onResult) },
                    onStart = { folder -> viewModel.startImport(folder) }
                )
            }

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // 复制进度（可取消）
            progress?.let { p ->
                ImportProgressDialog(progress = p, onCancel = viewModel::cancelImport)
            }
        }
    }
}

// ---------------- 第一步：选择来源 ----------------

@Composable
private fun SourceStep(
    onPickImages: () -> Unit,
    onPickFolder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "导入图片",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
        )
        Text(
            text = "图片将以复制方式导入所选文件夹，保留原文件名",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = "过大的 GIF（5MB 以上）会被压缩以减少发送失败的可能性",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        SourceCard(
            title = "按图片导入",
            description = "通过系统图片选择器多选若干张图片（jpg/png/gif）",
            icon = Icons.Filled.Image,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
            onClick = onPickImages
        )
        SourceCard(
            title = "按文件夹导入",
            description = "选择一个外部文件夹，列出其中所有图片供勾选",
            icon = Icons.Filled.FolderOpen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            onClick = onPickFolder
        )
    }
}

@Composable
private fun SourceCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------- 第二步：勾选图片 ----------------

@Composable
private fun ImagesStep(
    candidates: List<ImportCandidate>,
    selection: Set<android.net.Uri>,
    onToggle: (android.net.Uri) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("选择图片", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已选 ${selection.size}/${candidates.size} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onSelectAll(selection.size < candidates.size) }) {
                Text(if (selection.size < candidates.size) "全选" else "清除")
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(candidates, key = { it.uri }) { candidate ->
                val checked = candidate.uri in selection
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onToggle(candidate.uri) }
                ) {
                    AsyncImage(
                        model = candidate.uri,
                        contentDescription = candidate.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (checked) {
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选择",
                                tint = Color.White,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onNext,
            enabled = selection.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("下一步（已选 ${selection.size} 张）")
        }
    }
}

// ---------------- 第三步：选择目标文件夹 ----------------

@Composable
private fun TargetStep(
    folders: List<String>,
    selectedCount: Int,
    atFront: Boolean,
    onSetAtFront: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onCreateFolder: (String, (String?) -> Unit) -> Unit,
    onStart: (String) -> Unit
) {
    var targetFolder by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("选择目标文件夹", style = MaterialTheme.typography.titleMedium)
                Text(
                    "将复制 $selectedCount 张图片到所选文件夹",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onReload) {
                Icon(Icons.Filled.Folder, contentDescription = "刷新")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            if (folders.isEmpty()) {
                Text(
                    text = "还没有文件夹\n点击下方“新建文件夹”创建一个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                )
            }
            folders.forEach { folder ->
                ListItem(
                    headlineContent = { Text(folder) },
                    leadingContent = {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                    },
                    trailingContent = {
                        RadioButton(
                            selected = targetFolder == folder,
                            onClick = {
                                targetFolder = folder
                                onSelect(folder)
                            }
                        )
                    },
                    modifier = Modifier.clickable {
                        targetFolder = folder
                        onSelect(folder)
                    }
                )
            }
            ListItem(
                headlineContent = { Text("新建文件夹") },
                leadingContent = {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable { showCreateDialog = true }
            )
        }

        // 导入位置选择：最前 / 最后
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("放到：", style = MaterialTheme.typography.bodyMedium)
            FilterChip(
                selected = atFront,
                onClick = { onSetAtFront(true) },
                label = { Text("文件夹最前") }
            )
            FilterChip(
                selected = !atFront,
                onClick = { onSetAtFront(false) },
                label = { Text("文件夹最后") }
            )
        }

        Button(
            onClick = { targetFolder?.let(onStart) },
            enabled = targetFolder != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("开始导入${targetFolder?.let { "到「$it」" } ?: ""}")
        }
    }

    if (showCreateDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true,
                    label = { Text("文件夹名") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = folderName.isNotBlank(),
                    onClick = {
                        val name = folderName.trim()
                        showCreateDialog = false
                        onCreateFolder(name) { created ->
                            if (created != null) targetFolder = created
                        }
                    }
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
            }
        )
    }
}

// ---------------- 复制进度对话框 ----------------

@Composable
private fun ImportProgressDialog(progress: ImportProgress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 进行中不允许点外部关闭，只能取消 */ },
        title = { Text("正在导入…") },
        text = {
            Column {
                Text(
                    text = "(${progress.current}/${progress.total}) ${progress.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = { if (progress.total == 0) 0f else progress.current.toFloat() / progress.total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onCancel) { Text("取消导入") }
        }
    )
}
