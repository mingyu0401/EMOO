package com.example.emoo.ui.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoveDown
import androidx.compose.material.icons.filled.MoveUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.emoo.model.ImageItem

/**
 * 图片文件信息底部弹窗（网格格子与全屏预览页长按共用，默认完全展开），
 * 以及其衍生出的重命名/删除确认对话框。
 *
 * 重命名只编辑文件名主体：原后缀固定在输入框右侧展示、不可修改；
 * 输入框内有内容时右侧显示清空按钮。
 * 操作结果通过 [onMessage] 回调交给宿主提示（画廊页 Snackbar / 预览页 Toast）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImageDetailSheet(
    image: ImageItem?,
    viewModel: GalleryViewModel,
    usageCount: Int?,
    canReorder: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // 重命名/删除目标独立持有：弹窗打开后即使 [image] 置空（底部弹窗关闭）对话框仍可继续
    var renameTarget by remember { mutableStateOf<ImageItem?>(null) }
    var deleteTarget by remember { mutableStateOf<ImageItem?>(null) }

    if (image != null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            // 文件名条目（置顶）：长按文件名可复制到剪贴板
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
                                onDismiss()
                                onMessage("已复制文件名")
                            }
                        )
                    )
                },
                supportingContent = { Text("长按文件名可复制") },
                leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) }
            )
            ListItem(
                headlineContent = { Text("信息") },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                supportingContent = {
                    Column {
                        infoRow("创建时间", formatTime(image.creationTime))
                        infoRow("导入时间", formatTime(image.addedTime))
                        infoRow("使用次数", "${usageCount ?: 0} 次")
                        infoRow("文件大小", formatFileSize(image.size))
                    }
                }
            )
            // 移到最前 / 移至最后：调整所在文件夹的自定义顺序（仅单文件夹浏览态）
            if (canReorder) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            val target = image
                            onDismiss()
                            viewModel.moveImageToEdge(target, front = true) { ok ->
                                onMessage(if (ok) "已移到最前（自定义顺序）" else "移动失败")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.MoveUp, contentDescription = null)
                        Text("移到最前", modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(
                        onClick = {
                            val target = image
                            onDismiss()
                            viewModel.moveImageToEdge(target, front = false) { ok ->
                                onMessage(if (ok) "已移至最后（自定义顺序）" else "移动失败")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.MoveDown, contentDescription = null)
                        Text("移至最后", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (!image.isText) {
                ListItem(
                    headlineContent = { Text("设为「${image.folderName}」的预览图") },
                    leadingContent = { Icon(Icons.Filled.Image, contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            viewModel.setFolderPreview(image)
                            onDismiss()
                            onMessage("已设为文件夹预览图")
                        }
                    )
                )
            }
            ListItem(
                headlineContent = { Text("重命名") },
                leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                modifier = Modifier.combinedClickable(
                    onClick = {
                        renameTarget = image
                        onDismiss()
                    }
                )
            )
            ListItem(
                headlineContent = { Text("删除图片") },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                modifier = Modifier.combinedClickable(
                    onClick = {
                        deleteTarget = image
                        onDismiss()
                    }
                )
            )
            // 底部留白，保证弹窗内容不完全贴底
            Box(Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
    }

    // 重命名图片：仅编辑文件名主体，后缀固定展示不可改；输入框右侧可一键清空
    renameTarget?.let { target ->
        val suffix = target.displayName
            .substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && target.displayName.contains('.') }
            ?.let { ".$it" }
            ?: ""
        val baseName = target.displayName.removeSuffix(suffix)
        var newName by remember(target.path, target.displayName) {
            mutableStateOf(baseName)
        }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名图片") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("文件名") },
                        trailingIcon = {
                            if (newName.isNotEmpty()) {
                                IconButton(onClick = { newName = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清空")
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    if (suffix.isNotEmpty()) {
                        Text(
                            text = suffix,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val name = newName.trim() + suffix
                        renameTarget = null
                        viewModel.renameImage(target, name) { ok ->
                            onMessage(if (ok) "已重命名" else "重命名失败（重名或无效）")
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
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除图片") },
            text = { Text("将删除 1 张图片且不可恢复：\n${target.displayName}") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        viewModel.deleteImage(target) { ok ->
                            onMessage(if (ok) "已删除图片" else "删除失败")
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun infoRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTime(time: Long?): String {
    if (time == null) return "未知"
    return java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm", java.util.Locale.CHINA
    ).format(java.util.Date(time))
}

private fun formatFileSize(size: Long): String = when {
    size >= 1 shl 20 -> String.format(java.util.Locale.CHINA, "%.1f MB", size / 1048576.0)
    size >= 1024 -> String.format(java.util.Locale.CHINA, "%.1f KB", size / 1024.0)
    else -> "$size B"
}
