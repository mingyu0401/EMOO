package com.example.emoo.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.emoo.data.ImageRepository
import com.example.emoo.model.SendMode
import com.example.emoo.model.ThemeColor
import com.example.emoo.model.ThemeMode
import com.example.emoo.send.ShizukuDragInjector
import com.example.emoo.service.PasteAccessibilityService
import com.example.emoo.ui.ModeTutorialDialog
import com.example.emoo.ui.theme.themeColorSwatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * 设置页：深色模式三档与主题色（各自收入二级菜单，即时生效；右上角圆圈按钮进主题色页）、
 * 每行图片个数（2~6，即时重排版）、
 * 清理“最近”记录（确认 + Snackbar 撤销）、图片根目录（点击复制）、
 * 关于、版本号彩蛋（连点 10 次触发 5 秒倒计时“boom”弹窗后强制退出）。
 */
@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel) {
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val themeColor by settingsViewModel.themeColor.collectAsStateWithLifecycle()
    val gridColumns by settingsViewModel.gridColumns.collectAsStateWithLifecycle()
    val sendMode by settingsViewModel.sendMode.collectAsStateWithLifecycle()
    val showUsageCount by settingsViewModel.showUsageCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showClearRecentConfirm by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    // 二级菜单：null = 主列表；深色模式 / 主题色各自成页
    var subPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    BackHandler(enabled = subPage != null) { subPage = null }
    // 当前是否处于深色配色（主题色圆圈取对应档代表色）
    val darkTheme = themeMode == ThemeMode.DARK ||
        (themeMode == ThemeMode.FOLLOW_SYSTEM && isSystemInDarkTheme())
    // 真实版本号随构建自动更新（0.93 起不再硬编码在设置页）
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.02"
    }

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
        when (subPage) {
            null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // 标题行：左侧“设置”，右侧主题色圆圈按钮（进入主题色二级菜单）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(themeColorSwatch(themeColor, darkTheme))
                            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { subPage = SettingsSubPage.THEME_COLOR }
                    )
                }

                // 深色模式：收进二级菜单，小字提示当前档位
                ListItem(
                    headlineContent = { Text("深色模式") },
                    supportingContent = { Text("当前：${themeModeLabel(themeMode)}") },
                    leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                    trailingContent = {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable { subPage = SettingsSubPage.APPEARANCE }
                )

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

            // 显示使用次数角标：网格格子右下角 ×N
            ListItem(
                headlineContent = { Text("显示使用次数") },
                supportingContent = { Text("在格子右下角显示角标 ×N（N 为发送使用次数）") },
                leadingContent = { Icon(Icons.Filled.Tag, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = showUsageCount,
                        onCheckedChange = { settingsViewModel.setShowUsageCount(it) }
                    )
                }
            )

            // 发送方式：影响 QQ 与微信两条发送链路（Shizuku / 无障碍 / 普通）
            ListItem(
                headlineContent = { Text("发送方式") },
                supportingContent = {
                    Text(
                        "推荐 Shizuku：QQ 拖拽与微信发送全部经 shell 指令注入实现，" +
                            "不触碰无障碍，不会触发系统频繁弹窗（需安装并启动 Shizuku 且授权本应用）。" +
                            "无障碍方式无需额外应用，但 ColorOS 16 可能频繁弹窗提醒。" +
                            "普通模式不需要任何权限：小窗短按图片复制其地址（微信粘贴发送），长按图片直接拖拽（QQ）。"
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
                FilterChip(
                    selected = sendMode == SendMode.NORMAL,
                    onClick = { settingsViewModel.setSendMode(SendMode.NORMAL) },
                    label = { Text("普通") }
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
                    val shizukuHint = when {
                        !ShizukuDragInjector.binderAlive() ->
                            "当前 Shizuku 模式下无法发送。请在 Shizuku 应用中启动服务，或切换为无障碍方式"
                        shizukuReady -> "QQ 拖拽与微信发送将全程经 Shizuku shell 通道实现"
                        else -> "点击右侧按钮，在弹出的 Shizuku 窗口中允许本应用"
                    }
                    Text("$shizukuHint\n版本推荐 github.com/thedjchi/Shizuku 最新版")
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

            // 再次查看欢迎/教程弹窗（与首次启动时一致，三种发送方式说明）
            ListItem(
                headlineContent = { Text("点我再次查看教程") },
                supportingContent = { Text("重新查看小窗使用与三种发送方式说明，可切换发送方式") },
                leadingContent = { Icon(Icons.Filled.School, contentDescription = null) },
                modifier = Modifier.clickable { showTutorial = true }
            )

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
                supportingContent = { Text(versionName) },
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

            // 二级菜单：深色模式三档（即时生效）
            SettingsSubPage.APPEARANCE -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                SubPageHeader(title = "深色模式", onBack = { subPage = null })
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { settingsViewModel.setThemeMode(mode) },
                            label = { Text(themeModeLabel(mode)) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Text(
                    "切换即时生效，无需重启",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 二级菜单：主题色（改变交互选项的颜色）
            SettingsSubPage.THEME_COLOR -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                SubPageHeader(title = "主题色", onBack = { subPage = null })
                Text(
                    "改变按钮/开关/选中项等交互选项的颜色，切换即时生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                ThemeColor.entries.forEach { color ->
                    ListItem(
                        headlineContent = { Text(color.label) },
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(themeColorSwatch(color, darkTheme))
                                    .border(
                                        1.5.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        CircleShape
                                    )
                            )
                        },
                        trailingContent = {
                            if (themeColor == color) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "当前",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            settingsViewModel.setThemeColor(color)
                        }
                    )
                }
            }
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
                        "· 小窗中点击图片可一键发送到 QQ/微信（Shizuku / 无障碍 / 普通 三种方式）\n" +
                        "· 全屏浏览时右下角分享按钮可系统分享\n" +
                        "· 图片导入为复制，不改动原文件\n\n" +
                        "当前版本：$versionName"
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("确定") }
            }
        )
    }

    // 教程弹窗：与首次启动的欢迎界面一致，选择即切换发送方式
    if (showTutorial) {
        ModeTutorialDialog(
            onDismissRequest = { showTutorial = false },
            onModePicked = { settingsViewModel.setSendMode(it) }
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

/** 二级菜单页头：返回箭头 + 标题 */
@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** 深色模式档位的小字文案 */
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
    ThemeMode.FOLLOW_SYSTEM -> "跟随系统"
}

/** 设置页的二级菜单页面 */
private enum class SettingsSubPage { APPEARANCE, THEME_COLOR }
