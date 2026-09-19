package com.example.emoo.model

import android.net.Uri

/** 主题模式：浅色 / 深色 / 跟随系统 */
enum class ThemeMode {
    LIGHT, DARK, FOLLOW_SYSTEM;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: FOLLOW_SYSTEM
    }
}

/**
 * 一键发送（QQ 拖拽 + 微信路径识别）的注入通道。
 * SHIZUKU：纯 Shizuku shell 级实现（窗口检测 + 触摸/文本注入），不触碰无障碍；
 * ACCESSIBILITY：无障碍服务实现。
 */
enum class SendMode {
    SHIZUKU, ACCESSIBILITY;

    companion object {
        fun fromName(name: String?): SendMode =
            entries.firstOrNull { it.name == name } ?: SHIZUKU
    }
}

/**
 * 一张图片。磁盘上的物理位置即逻辑归属：
 * [path] 为唯一标识（同时用于“最近”记录与预览图持久化），
 * [uriString] 用于 Coil 加载（API 29+ 为 MediaStore content uri，26-28 为 file uri）。
 */
data class ImageItem(
    val id: Long,
    val uriString: String,
    val path: String,
    val displayName: String,
    val folderName: String,
    val addedTime: Long,
    val size: Long
) {
    val isGif: Boolean get() = displayName.endsWith(".gif", ignoreCase = true)
}

/** “最近”聚合视图中的单条记录 */
data class RecentEntry(
    val path: String,
    val name: String,
    val folder: String,
    val time: Long
)

/** 导入流程中待复制的来源图片 */
data class ImportCandidate(
    val uri: Uri,
    val name: String
)

/** 导入进度（current 从 1 开始计） */
data class ImportProgress(
    val current: Int,
    val total: Int,
    val fileName: String
)
