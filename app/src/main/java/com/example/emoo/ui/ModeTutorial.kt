package com.example.emoo.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.emoo.model.SendMode
import com.example.emoo.send.ShizukuDragInjector
import com.example.emoo.service.PasteAccessibilityService

/**
 * 欢迎/教程弹窗：三种发送方式均推荐以“小窗/分屏”方式使用 EMOO。
 * 图片页首次启动（未选择过发送方式）与设置页“点我再次查看教程”共用；
 * 选定方式经 [onModePicked] 持久化，并按方式引导开启对应权限。
 */
@Composable
fun ModeTutorialDialog(
    onDismissRequest: () -> Unit,
    onModePicked: (SendMode) -> Unit
) {
    val context = LocalContext.current

    fun pick(mode: SendMode) {
        onModePicked(mode)
        when (mode) {
            SendMode.SHIZUKU ->
                // 已运行但未授权时立刻拉起授权弹窗；未运行则先在教程文案里提示安装
                if (ShizukuDragInjector.binderAlive() && !ShizukuDragInjector.hasPermission()) {
                    ShizukuDragInjector.requestPermission(context)
                }
            SendMode.ACCESSIBILITY ->
                if (!PasteAccessibilityService.isEnabled()) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            SendMode.NORMAL -> Unit
        }
        onDismissRequest()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("欢迎使用 EMOO") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "三种发送方式都推荐把 EMOO 以「小窗/分屏」挂在 QQ/微信聊天窗旁使用，" +
                        "小窗里操作图片即可发送：\n\n" +
                        "· Shizuku（推荐）：全程 shell 注入，点图即发，不触发系统无障碍弹窗；" +
                        "需安装并启动 Shizuku 且授权本应用\n\n" +
                        "· 无障碍：无需额外应用，点图即发；ColorOS 可能频繁弹窗提醒无障碍使用\n\n" +
                        "· 普通模式：不装 Shizuku、不开无障碍——小窗短按图片即复制其文件地址，" +
                        "到微信输入框粘贴即发；长按图片可直接拖拽到 QQ 窗口发送\n\n" +
                        "选择后即可开始，之后可在「设置」中随时切换。"
                )
                Button(
                    onClick = { pick(SendMode.SHIZUKU) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Shizuku（推荐）") }
                Button(
                    onClick = { pick(SendMode.ACCESSIBILITY) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("无障碍") }
                Button(
                    onClick = { pick(SendMode.NORMAL) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("普通模式") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text("稍后再说") }
        }
    )
}
