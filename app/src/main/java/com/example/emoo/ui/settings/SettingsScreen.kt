package com.example.emoo.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.emoo.data.ImageRepository
import com.example.emoo.model.SendMode
import com.example.emoo.model.ThemeMode
import com.example.emoo.send.ShizukuDragInjector
import com.example.emoo.service.PasteAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * 设置页：深色模式三档（即时生效）、每行图片个数（2~6，即时重排版）、
 * 清理“最近”记录（确认 + Snackbar 撤销）、图片根目录（点击复制）、
 * 关于、版本号彩蛋（连点 10 次触发 5 秒倒计时“boom”弹窗后强制退出）。
 */
@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel) {
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val gridColumns by settingsViewModel.gridColumns.collectAsStateWithLifecycle()
    val sendMode by settingsViewModel.sendMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showClearRecentConfirm by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // 版本号彩蛋状态
    var versionClicks by remember { mutableIntStateOf(0) }
    var showBoomDialog by remember { mutableStateOf(false) }
    var boomCountdown by remember { mutableIntStateOf(5) }

    // boom 倒计时：5 秒后关闭弹窗并强制退出应用回到桌面
    LaunchedEffect(showBoomDialog) {
        if (showBoomDialog) {
            for (t in 5 downTo 1) {
                boomCountdown = t
                delay(1000)
            }
            showBoomDialog = false
            (context as? Activity)?.finishAffinity()
            exitProcess(0)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // 深色模式：三档即时切换
            ListItem(
                headlineContent = { Text("深色模式") },
                supportingContent = { Text("切换即时生效，无需重启") },
                leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { settingsViewModel.setThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                    ThemeMode.FOLLOW_SYSTEM -> "跟随系统"
                                }
                            )
                        },
                        modifier = Modifier
                            .padding(end = 8.dp)
                    )
                }
            }

            // 每行图片个数：2~6，修改后即时生效并重新排版
            ListItem(
                headlineContent = { Text("每行图片个数") },
                supportingContent = { Text("当前 $gridColumns 张/行，修改后即时重新排版") },
                leadingContent = { Icon(Icons.Filled.GridOn, contentDescription = null) }
            )
            Slider(
                value = gridColumns.toFloat(),
                onValueChange = { settingsViewModel.setGridColumns(it.toInt()) },
                valueRange = 2f..6f,
                steps = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            // 发送方式：影响 QQ 与微信两条发送链路
            ListItem(
                headlineContent = { Text("发送方式") },
                supportingContent = {
                    Text(
                        "推荐 Shizuku：QQ 拖拽与微信路径识别全部经 shell 指令注入实现，" +
                            "不触碰无障碍，不会触发系统频繁弹窗（需安装并启动 Shizuku 且授权本应用）。" +
                            "无障碍方式无需额外应用，但 ColorOS 16 可能频繁弹窗提醒。"
                    )
                },
                leadingContent = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = sendMode == SendMode.SHIZUKU,
                    onClick = {
                        settingsViewModel.setSendMode(SendMode.SHIZUKU)
                        // 切到 Shizuku 时若尚未授权，立刻拉起授权
                        if (ShizukuDragInjector.binderAlive() &&
                            !ShizukuDragInjector.hasPermission()
                        ) {
                            ShizukuDragInjector.requestPermission(context)
                        }
                    },
                    label = { Text("Shizuku（推荐）") }
                )
                FilterChip(
                    selected = sendMode == SendMode.ACCESSIBILITY,
                    onClick = {
                        settingsViewModel.setSendMode(SendMode.ACCESSIBILITY)
                        // 切到无障碍时若服务未开启，直接带用户去开启
                        if (!PasteAccessibilityService.isEnabled()) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    label = { Text("无障碍") }
                )
            }
            // Shizuku 连接/授权状态（从授权弹窗返回时刷新）
            var shizukuReady by remember { mutableStateOf(ShizukuDragInjector.isAvailable()) }
            val settingsLifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(settingsLifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        shizukuReady = ShizukuDragInjector.isAvailable()
                    }
                }
                settingsLifecycleOwner.lifecycle.addObserver(observer)
                onDispose { settingsLifecycleOwner.lifecycle.removeObserver(observer) }
            }
            ListItem(
                headlineContent = {
                    when {
                        !ShizukuDragInjector.binderAlive() -> Text("Shizuku 未运行")
                        shizukuReady -> Text("Shizuku 已连接并授权")
                        else -> Text("Shizuku 正在运行，等待授权")
                    }
                },
                supportingContent = {
                    when {
                        !ShizukuDragInjector.binderAlive() -> Text(
                            "当前 Shizuku 模式下无法发送。请在 Shizuku 应用中启动服务，或切换为无障碍方式"
                        )
                        shizukuReady -> Text("QQ 拖拽与微信发送将全程经 Shizuku shell 通道实现")
                        else -> Text("点击右侧按钮，在弹出的 Shizuku 窗口中允许本应用")
                    }
                },
                leadingContent = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                trailingContent = {
                    if (ShizukuDragInjector.binderAlive() && !shizukuReady) {
                        Button(onClick = { ShizukuDragInjector.requestPermission(context) }) {
                            Text("授权")
                        }
                    }
                }
            )

            // 清理“最近”记录：可撤销
            ListItem(
                headlineContent = { Text("清理「最近」记录") },
                supportingContent = { Text("清空最近使用的图片记录（图片本身不受影响，可撤销）") },
                leadingContent = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                trailingContent = {
                    Button(onClick = { showClearRecentConfirm = true }) { Text("清理") }
                }
            )

            // 图片根目录大横条：点击复制路径
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("EMOO 图片根目录", ImageRepository.ROOT_DIR_DISPLAY)
                        )
                        Toast.makeText(context, "已复制图片根目录", Toast.LENGTH_SHORT).show()
                    }
            ) {
                ListItem(
                    headlineContent = { Text("图片根目录（点击复制）") },
                    supportingContent = {
                        Text(
                            "${ImageRepository.ROOT_DIR_DISPLAY} 使用mt管理器可以很轻易找到这里哦",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.FolderShared,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            // 关于
            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = { Text("EMOO · 目录即分类的图片管理工具") },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                modifier = Modifier.clickable { showAbout = true }
            )

            // 版本号：连点彩蛋（第 4~10 次点击提示剩余次数，第 10 次触发 boom）
            ListItem(
                headlineContent = { Text("版本") },
                supportingContent = { Text("1.0.0") },
                modifier = Modifier.clickable {
                    versionClicks++
                    if (versionClicks in 4..10) {
                        Toast.makeText(
                            context,
                            "还剩 ${10 - versionClicks} 次进入开发者模式",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (versionClicks >= 10) {
                        versionClicks = 0
                        showBoomDialog = true
                    }
                }
            )
        }
    }

    // 清理“最近”确认弹窗
    if (showClearRecentConfirm) {
        AlertDialog(
            onDismissRequest = { showClearRecentConfirm = false },
            title = { Text("清理「最近」记录") },
            text = { Text("将清空最近使用的图片记录（最多 100 条），图片文件本身不会被删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearRecentConfirm = false
                        val oldEntries = settingsViewModel.clearRecent()
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "已清理 ${oldEntries.size} 条最近记录",
                                actionLabel = "撤销",
                                withDismissAction = true
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                settingsViewModel.restoreRecent(oldEntries)
                                snackbarHostState.showSnackbar("已撤销清理")
                            }
                        }
                    }
                ) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showClearRecentConfirm = false }) { Text("取消") }
            }
        )
    }

    // 关于对话框
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 EMOO") },
            text = {
                Text(
                    "EMOO 是一款以真实文件系统目录结构为基础的图片分类浏览工具。\n\n" +
                        "· 分类 = 图片根目录下的真实子目录\n" +
                        "· 支持 jpg / jpeg / png / gif（GIF 实时循环播放）\n" +
                        "· 小窗中点击图片可一键发送到 QQ/微信\n" +
                        "· 图片导入为复制，不改动原文件"
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("确定") }
            }
        )
    }

    // boom 弹窗：确认/取消均不可关闭，5 秒倒计时后强制退出
    if (showBoomDialog) {
        AlertDialog(
            onDismissRequest = { /* 不可通过点击外部关闭 */ },
            title = { Text("开发者模式") },
            text = { Text("你还真信啊?${boomCountdown}秒后手机会boom!") },
            confirmButton = {
                Button(onClick = { /* 不可关闭 */ }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { /* 不可关闭 */ }) { Text("取消") }
            }
        )
    }
}
