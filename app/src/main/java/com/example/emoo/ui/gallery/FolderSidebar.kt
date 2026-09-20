package com.example.emoo.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * 右侧 1/6 文件夹侧栏：
 * 顶部固定「最近」聚合入口与「搜索」入口（第二项），中间为真实文件夹列表（可滚动），
 * 底部固定「+」新建文件夹入口。选中项高亮且同一时刻只有一项高亮
 * （进入搜索态时文件夹/最近均取消选中）；「搜索」点击只进入不退出
 * （连点停留在搜索页，退出走搜索栏旁的关闭按钮）；长按真实文件夹弹出整理对话框
 * （上下排序 + 删除），长按顶部「最近」弹出清空确认（「最近」固定在顶部不可移动）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderSidebar(
    folders: List<String>,
    selectedFolder: String?,
    previewMap: Map<String, String>,
    searchActive: Boolean,
    onSelectFolder: (String?) -> Unit,
    onCreateFolder: () -> Unit,
    onEnterSearch: () -> Unit,
    onLongPressFolder: (String) -> Unit,
    onLongPressRecent: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        // 顶部固定：最近
        SidebarEntry(
            label = "最近",
            previewUri = null,
            iconVector = Icons.Filled.History,
            selected = selectedFolder == null && !searchActive,
            onClick = { onSelectFolder(null) },
            onLongPress = onLongPressRecent
        )
        // 顶部固定第二项：搜索（只进入，不随连点退出）
        SidebarEntry(
            label = "搜索",
            previewUri = null,
            iconVector = Icons.Filled.Search,
            selected = searchActive,
            onClick = onEnterSearch
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // 中间动态区：真实文件夹
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(folders, key = { it }) { folder ->
                SidebarEntry(
                    label = folder,
                    previewUri = previewMap[folder],
                    iconVector = Icons.Filled.Folder,
                    selected = selectedFolder == folder,
                    onClick = { onSelectFolder(folder) },
                    onLongPress = { onLongPressFolder(folder) }
                )
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        // 底部固定：新建文件夹
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onCreateFolder)
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "新建文件夹",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = "新建",
                fontSize = 10.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarEntry(
    label: String,
    previewUri: String?,
    iconVector: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                enabled = true
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (previewUri != null) {
                // 预览图失效（源文件被删/外部改动）时回退默认文件夹图标
                AsyncImage(
                    model = previewUri,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = rememberVectorPainter(iconVector),
                    fallback = rememberVectorPainter(iconVector)
                )
            } else {
                Icon(
                    imageVector = iconVector,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Text(
            text = label,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = contentColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )
    }
}
