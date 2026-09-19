package com.example.emoo.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.emoo.model.ImageItem
import com.example.emoo.model.ImportCandidate
import com.example.emoo.send.GifCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 仓库层：封装全部文件系统操作。
 *
 * 存储方案（方案 A）：应用私有目录 Android/data/com.example.emoo/files/pictures/<文件夹名>/。
 * - 媒体扫描器跳过 Android/data，系统相册/QQ/微信的图片选择器均不会收录；
 * - 私有目录读写无需任何权限，File API 全功能可用（建删目录、重命名、直接删除）；
 * - 代价：Android 11+ 第三方文件管理器默认不可见，卸载/清除数据时图片一并删除。
 *
 * “目录即数据”：一张图片只存在于一个文件夹内，物理位置即逻辑归属。
 */
object ImageRepository {

    /** 展示用根目录路径（设置页复制用） */
    const val ROOT_DIR_DISPLAY = "/storage/emulated/0/Android/data/com.example.emoo/files/pictures/"

    /** FileProvider authority（剪贴板分享图片给聊天应用） */
    const val FILE_PROVIDER_AUTHORITY = "com.example.emoo.fileprovider"

    private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif")
    private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif")

    fun getRootDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "pictures")

    /** 文件名是否为受支持的图片格式（jpg/jpeg/png/gif） */
    fun isSupportedImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

    /** 过滤非法字符并校验文件夹名，非法返回 null */
    fun sanitizeFolderName(raw: String): String? {
        val name = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.startsWith('.')) return null
        return name.take(64)
    }

    private fun File.toImageItem(): ImageItem = ImageItem(
        id = -1L,
        uriString = Uri.fromFile(this).toString(),
        path = absolutePath,
        displayName = name,
        folderName = parentFile?.name ?: "",
        addedTime = lastModified(),
        size = length()
    )

    // ================================ 目录扫描 ================================

    /** 列出所有分类文件夹（pictures 下的直接子目录，按名称排序） */
    suspend fun listFolders(context: Context): List<String> = withContext(Dispatchers.IO) {
        try {
            getRootDir(context).listFiles { file -> file.isDirectory }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 列出图片。[folder] 为 null 时聚合全部文件夹，否则只列该文件夹。按修改时间倒序。 */
    suspend fun listImages(context: Context, folder: String?): List<ImageItem> =
        withContext(Dispatchers.IO) {
            val root = getRootDir(context)
            val files = try {
                if (folder == null) {
                    // 图片都存放在各子文件夹内，聚合视图需遍历子目录而非只扫描根目录的文件
                    root.listFiles { file -> file.isDirectory }?.flatMap { sub ->
                        sub.listFiles()?.filter { it.isFile } ?: emptyList()
                    } ?: emptyList()
                } else {
                    File(root, folder).listFiles()?.filter { it.isFile } ?: emptyList()
                }
            } catch (_: Exception) {
                return@withContext emptyList()
            }
            files.filter { isSupportedImage(it.name) }
                .sortedByDescending { it.lastModified() }
                .map { it.toImageItem() }
        }

    // ================================ 文件夹操作 ================================

    /** 创建真实文件夹（私有目录直接 mkdirs，空目录可被 File API 正常列出） */
    suspend fun createFolder(context: Context, name: String): Boolean =
        withContext(Dispatchers.IO) {
            val safe = sanitizeFolderName(name) ?: return@withContext false
            try {
                File(getRootDir(context), safe).mkdirs()
            } catch (_: Exception) {
                false
            }
        }

    /** 删除文件夹及其内部全部图片，返回删除的文件数 */
    suspend fun deleteFolder(context: Context, folder: String): Int =
        withContext(Dispatchers.IO) {
            val dir = File(getRootDir(context), folder)
            var deleted = 0
            try {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.delete()) deleted++
                }
                dir.delete()
            } catch (_: Exception) {
            }
            deleted
        }

    /** 真实删除单张图片，返回是否成功 */
    suspend fun deleteImage(context: Context, image: ImageItem): Boolean =
        withContext(Dispatchers.IO) {
            try {
                File(image.path).delete()
            } catch (_: Exception) {
                false
            }
        }

    // ================================ 导入 ================================

    /**
     * 将候选图片流式复制到目标文件夹（保留原文件名，重名自动追加序号如 photo(1).jpg）。
     * [atFront] 为 true 时排在文件夹最前（比所有现有图片新），否则排最后。
     * 文件夹内顺序由 lastModified 倒序决定，故通过改写复制文件的 lastModified 实现位置。
     * 协程取消时抛 CancellationException，已复制的文件保留。
     */
    suspend fun copyImages(
        context: Context,
        candidates: List<ImportCandidate>,
        targetFolder: String,
        atFront: Boolean = true,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit,
        onCopied: (ImageItem) -> Unit = {}
    ): List<ImageItem> = withContext(Dispatchers.IO) {
        val dir = File(getRootDir(context), targetFolder)
        dir.mkdirs()
        val existingFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val existing = mutableSetOf<String>().apply { existingFiles.forEach { add(it.name.lowercase()) } }
        val imported = mutableListOf<ImageItem>()
        candidates.forEachIndexed { index, candidate ->
            coroutineContext.ensureActive()
            val finalName = uniqueName(candidate.name, existing)
            existing.add(finalName.lowercase())
            copyOne(context, candidate.uri, finalName, dir)?.let { item ->
                imported.add(item)
                onCopied(item)
            }
            onProgress(index + 1, candidates.size, finalName)
        }
        if (imported.isNotEmpty()) applyImportPosition(imported, existingFiles, atFront)
        imported
    }

    /** 改写新导入文件的 lastModified，使其排在现有文件之前或之后 */
    private fun applyImportPosition(
        imported: List<ImageItem>,
        existingFiles: List<File>,
        atFront: Boolean
    ) {
        val baseTime = if (atFront) {
            (existingFiles.maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis() - imported.size * 1000)
        } else {
            (existingFiles.minOfOrNull { it.lastModified() } ?: System.currentTimeMillis()).coerceAtLeast(imported.size + 1L)
        }
        imported.forEachIndexed { i, item ->
            val time = if (atFront) {
                baseTime + (imported.size - i)
            } else {
                (baseTime - imported.size + i).coerceAtLeast(1L)
            }
            try {
                File(item.path).setLastModified(time)
            } catch (_: Exception) {
            }
        }
    }

    private fun uniqueName(original: String, existing: MutableSet<String>): String {
        if (original.lowercase() !in existing) return original
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base($index)$ext"
            if (candidate.lowercase() !in existing) return candidate
            index++
        }
    }

    private suspend fun copyOne(context: Context, source: Uri, displayName: String, dir: File): ImageItem? {
        return try {
            val target = File(dir, displayName)
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            // GIF 在导入时就地压缩（超阈值才处理），发送时无需再压缩
            compressGifInPlace(context, target)
            target.toImageItem()
        } catch (_: Exception) {
            // 单个来源失败不中断整体导入
            null
        }
    }

    /** 超过阈值的 GIF 就地压缩覆盖原文件；非 GIF、未超限或压缩失败均保持原样 */
    private suspend fun compressGifInPlace(context: Context, target: File) {
        if (!target.name.endsWith(".gif", ignoreCase = true)) return
        if (target.length() <= GifCompressor.COMPRESS_THRESHOLD_BYTES) return
        val outDir = File(context.cacheDir, "import_gif")
        val compressed = GifCompressor.compressIfNeeded(target, outDir) ?: return
        try {
            compressed.copyTo(target, overwrite = true)
        } catch (_: Exception) {
        } finally {
            compressed.delete()
        }
    }

    // ================================ 工具 ================================

    /** 查询 content uri 的显示名（用于 Photo Picker 结果） */
    fun displayNameOf(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    /** 列出 SAF 目录树中受支持的图片（用于“按文件夹导入”） */
    fun listDocumentImages(context: Context, treeUri: Uri): List<ImportCandidate> {
        val root = try {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return root.listFiles().mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val name = file.name ?: return@mapNotNull null
            val mime = file.type
            val supported = isSupportedImage(name) || mime in SUPPORTED_MIME_TYPES
            if (supported) ImportCandidate(file.uri, name) else null
        }.sortedBy { it.name.lowercase() }
    }
}
