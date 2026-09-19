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
    /** 导入时间（文件系统 lastModified，导入位置通过改写它实现） */
    val addedTime: Long,
    /** 文件系统创建时间；取不到（旧设备/不支持）为 null */
    val creationTime: Long? = null,
    val size: Long,
    /** 文字文件（.txt）正文前缀，供网格卡片显示；图片/视频为 null */
    val previewText: String? = null
) {
    val isGif: Boolean get() = displayName.endsWith(".gif", ignoreCase = true)
    val isVideo: Boolean
        get() = displayName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
    val isText: Boolean
        get() = displayName.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    companion object {
        /** 受支持的视频扩展名（导入不压缩，网格显示封面，查看页仅外部打开） */
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "wmv", "flv", "ts"
        )
        /** 文字文件扩展名（一段话=一个文件，网格显示正文前缀） */
        val TEXT_EXTENSIONS = setOf("txt")
    }
}

/** 文件夹内表情包的排序方式 */
enum class StickerSortMode {
    /** 导入顺序（.emoo_seq 序列文件），新导入在前 */
    DEFAULT,
    /** 文件系统创建时间 */
    CREATION,
    /** 发送使用次数（含手动点选发送），多者在前 */
    USAGE,
    /** 每次进入/刷新时随机 */
    RANDOM,
    /** 文件名升序 */
    NAME;

    companion object {
        fun fromName(name: String?): StickerSortMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 某文件夹的排序设置：方式 + 是否倒序 */
data class FolderSort(val mode: StickerSortMode, val reverse: Boolean)

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
