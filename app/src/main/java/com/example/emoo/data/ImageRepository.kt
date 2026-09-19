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
import java.security.MessageDigest
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

    private val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif")
    private val SUPPORTED_VIDEO_EXTENSIONS = ImageItem.VIDEO_EXTENSIONS
    private val SUPPORTED_TEXT_EXTENSIONS = ImageItem.TEXT_EXTENSIONS
    private val SUPPORTED_MIME_TYPES = setOf(
        "image/jpeg", "image/png", "image/gif",
        "video/mp4", "video/webm", "video/x-matroska", "video/quicktime",
        "video/3gpp", "video/x-msvideo", "video/mpeg"
    )

    /** 网格文字卡片显示的正文前缀长度 */
    private const val TEXT_PREVIEW_CHARS = 60
    /** 查看页读取全文的字节上限（防御超大文件） */
    private const val TEXT_READ_MAX_BYTES = 1 shl 20

    /** 文件夹内 sha256↔文件名 映射文件名（点开头隐藏，扩展名不受支持故不会进网格） */
    private const val HASH_FILE_NAME = ".emoo_sha256"

    fun getRootDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "pictures")

    /** 文件名是否为受支持的图片格式（jpg/jpeg/png/gif） */
    fun isSupportedImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_IMAGE_EXTENSIONS

    /** 文件名是否为受支持的视频格式 */
    fun isSupportedVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_VIDEO_EXTENSIONS

    /** 文件名是否为受支持的文字格式（.txt） */
    fun isSupportedText(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_TEXT_EXTENSIONS

    /** 文件名是否为受支持的媒体（图片、视频或文字）——扫描/聚合视图用 */
    fun isSupportedMedia(name: String): Boolean =
        isSupportedImage(name) || isSupportedVideo(name) || isSupportedText(name)

    /** 按扩展名推断 MIME（图片/视频），未知回退 image/jpeg。拖拽、临时文件、外部打开共用 */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "ts", "mpeg", "mpg" -> "video/mpeg"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        else -> "image/jpeg"
    }

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
        size = length(),
        previewText = if (isSupportedText(name)) textPreview() else null
    )

    /** 读取 .txt 正文前缀（压缩空白、限长），供网格卡片显示；失败返回空串 */
    private fun File.textPreview(): String = runCatching {
        inputStream().use { readTextPreview(it, TEXT_PREVIEW_CHARS) }
    }.getOrDefault("")

    /** 从输入流读取并规范化文字预览：UTF-8 解码、连续空白压成单空格、限长 */
    private fun readTextPreview(input: java.io.InputStream, maxChars: Int): String {
        val bytes = input.readNBytes(4096)
        val collapsed = String(bytes, Charsets.UTF_8)
            .replace("\uFFFD", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (collapsed.length > maxChars) collapsed.take(maxChars) else collapsed
    }

    /** 读取文字文件全文（查看页/复制用），上限 [TEXT_READ_MAX_BYTES] 字节 */
    suspend fun readTextFile(path: String): String = withContext(Dispatchers.IO) {
        runCatching {
            File(path).inputStream().use { input ->
                String(input.readNBytes(TEXT_READ_MAX_BYTES), Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

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
            files.filter { isSupportedMedia(it.name) }
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

    /** 真实删除单张图片，返回是否成功；顺带清理映射文件中对应记录 */
    suspend fun deleteImage(context: Context, image: ImageItem): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val file = File(image.path)
                val ok = file.delete()
                if (ok) {
                    file.parentFile?.let { dir ->
                        val map = readHashMap(dir)
                        val key = map.entries.firstOrNull { it.value == file.name }?.key
                        if (key != null) {
                            map.remove(key)
                            writeHashMap(dir, map)
                        }
                    }
                }
                ok
            } catch (_: Exception) {
                false
            }
        }

    /**
     * 重命名图片文件，并同步更新文件夹内 sha256↔文件名 映射文件
     * （旧记录改名；无记录的老导入补算 sha256 后写入）。扩展名强制保留，
     * 避免破坏格式识别与发送侧 MIME 判断。成功返回新 ImageItem。
     */
    suspend fun renameImage(context: Context, image: ImageItem, rawName: String): ImageItem? =
        withContext(Dispatchers.IO) {
            try {
                val old = File(image.path)
                val dir = old.parentFile ?: return@withContext null
                var name = rawName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (name.isEmpty() || name == "." || name == "..") return@withContext null
                val oldExt = old.name.substringAfterLast('.', "")
                if (oldExt.isNotEmpty() && !name.substringAfterLast('.', "").equals(oldExt, true)) {
                    name = "$name.$oldExt"
                }
                if (name != old.name) {
                    val target = File(dir, name)
                    if (target.exists() || !old.renameTo(target)) return@withContext null
                }
                val finalFile = File(dir, name)
                val map = readHashMap(dir)
                val key = map.entries.firstOrNull { it.value == old.name }?.key
                    ?: finalFile.sha256Hex()
                if (key != null) map[key] = name
                writeHashMap(dir, map)
                finalFile.toImageItem()
            } catch (_: Exception) {
                null
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
        if (imported.isNotEmpty()) {
            applyImportPosition(imported, existingFiles, atFront)
            recordHashes(dir, imported)
        }
        imported
    }

    /**
     * 将多段文字各写为一个 .txt 文件到目标文件夹（一段话 = 一个文件），
     * 复用唯一名、导入位置与 sha256 映射记录。文件名取段落首行前缀。
     * 协程取消时抛 CancellationException，已写入的文件保留。
     */
    suspend fun importTexts(
        context: Context,
        paragraphs: List<String>,
        targetFolder: String,
        atFront: Boolean = true,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ): List<ImageItem> = withContext(Dispatchers.IO) {
        val dir = File(getRootDir(context), targetFolder)
        dir.mkdirs()
        val existingFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val existing = mutableSetOf<String>().apply { existingFiles.forEach { add(it.name.lowercase()) } }
        val imported = mutableListOf<ImageItem>()
        paragraphs.forEachIndexed { index, text ->
            coroutineContext.ensureActive()
            val finalName = uniqueName(textFileName(text), existing)
            existing.add(finalName.lowercase())
            try {
                File(dir, finalName).writeText(text)
                imported.add(File(dir, finalName).toImageItem())
            } catch (_: Exception) {
            }
            onProgress(index + 1, paragraphs.size, finalName)
        }
        if (imported.isNotEmpty()) {
            applyImportPosition(imported, existingFiles, atFront)
            recordHashes(dir, imported)
        }
        imported
    }

    /** 由文字段落生成 .txt 文件名：取首行去非法字符、限长，兜底“文字” */
    private fun textFileName(text: String): String {
        val firstLine = text.trim().lines().firstOrNull().orEmpty()
        val base = firstLine
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(20)
            .ifBlank { "文字" }
        return "$base.txt"
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

    /** 流式计算文件内容 SHA-256（十六进制小写），失败返回 null */
    private fun File.sha256Hex(): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 把新导入文件的 sha256↔文件名 合并写入文件夹内映射文件 */
    private fun recordHashes(dir: File, items: List<ImageItem>) {
        val map = readHashMap(dir)
        items.forEach { item ->
            File(item.path).sha256Hex()?.let { map[it] = item.displayName }
        }
        writeHashMap(dir, map)
    }

    /**
     * 启动时补全映射文件：遍历所有文件夹，缺少 `.emoo_sha256` 的静默计算其内全部
     * 图片 sha256 并写入（空文件夹跳过）。返回是否有文件夹被新生成，供调用方决定是否提示。
     */
    suspend fun ensureHashMaps(context: Context): Boolean = withContext(Dispatchers.IO) {
        var generated = false
        try {
            getRootDir(context).listFiles { file -> file.isDirectory }?.forEach { dir ->
                if (File(dir, HASH_FILE_NAME).exists()) return@forEach
                val images = dir.listFiles()?.filter { it.isFile && isSupportedMedia(it.name) }
                    ?: return@forEach
                if (images.isEmpty()) return@forEach
                val map = LinkedHashMap<String, String>()
                images.forEach { f -> f.sha256Hex()?.let { map[it] = f.name } }
                if (map.isNotEmpty()) {
                    writeHashMap(dir, map)
                    generated = true
                }
            }
        } catch (_: Exception) {
        }
        generated
    }

    /** 读取映射文件（每行 `<sha256hex> <文件名>`；sha 定长 64 位，按首个空格切分） */
    private fun readHashMap(dir: File): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val file = File(dir, HASH_FILE_NAME)
        if (!file.exists()) return map
        runCatching {
            file.readLines().forEach { line ->
                val idx = line.indexOf(' ')
                if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1)
            }
        }
        return map
    }

    private fun writeHashMap(dir: File, map: Map<String, String>) {
        runCatching {
            File(dir, HASH_FILE_NAME).writeText(
                map.entries.joinToString("\n") { (sha, name) -> "$sha $name" }
            )
        }
    }

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
            val supported = isSupportedImage(name) || isSupportedVideo(name) ||
                mime in SUPPORTED_MIME_TYPES
            if (supported) ImportCandidate(file.uri, name) else null
        }.sortedBy { it.name.lowercase() }
    }
}
