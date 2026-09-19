package com.example.emoo.ui.gallery

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.emoo.data.ImageRepository
import com.example.emoo.data.MetaPreferences
import com.example.emoo.model.FolderSort
import com.example.emoo.model.ImageItem
import com.example.emoo.model.RecentEntry
import com.example.emoo.model.StickerSortMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 图片主页面 ViewModel：持有文件夹列表、当前选中文件夹（null = “最近”）
 * 与该视图下的图片列表。所有文件操作均委托 Repository，元数据走 MetaPreferences。
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    data class GalleryState(
        val folders: List<String> = emptyList(),
        /** null 表示“最近”聚合视图 */
        val selectedFolder: String? = null,
        val images: List<ImageItem> = emptyList(),
        val gridColumns: Int = 4,
        /** 文件夹名 -> 预览图 uriString（供侧栏加载） */
        val previewMap: Map<String, String> = emptyMap(),
        val loading: Boolean = true,
        /** 搜索态：images 为全库按文件名过滤后的结果 */
        val searchActive: Boolean = false,
        val searchQuery: String = "",
        /** 当前文件夹生效的排序（临时覆盖优先于持久默认；null=默认序） */
        val folderSort: FolderSort? = null
    )

    private val meta = MetaPreferences.get(application)
    private val context get() = getApplication<Application>()

    /** 本次进程内的临时排序覆盖（folder -> 排序），不写入持久设置 */
    private val tempSorts = mutableMapOf<String, FolderSort>()

    /** 搜索态下的全库图片缓存：输入关键字时只在内存过滤，不重复扫盘 */
    private var searchBase: List<ImageItem> = emptyList()

    private val _state = MutableStateFlow(GalleryState(gridColumns = meta.getGridColumns()))
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    init {
        refresh()
        ensureHashMaps()
    }

    /** 启动时后台静默补全缺失的 sha256 映射文件；确有生成时提示用户 */
    private fun ensureHashMaps() {
        viewModelScope.launch {
            val generated = ImageRepository.ensureHashMaps(context)
            if (generated) {
                Toast.makeText(context, "已悄悄完成文件优化", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 重新扫描目录（进入前台、增删后调用），保证与文件系统一致 */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val folders = orderedFolders(ImageRepository.listFolders(context))
            val selected = _state.value.selectedFolder?.takeIf { it in folders }
            val sort = selected?.let { resolveSort(it) }
            val images = if (_state.value.searchActive) {
                val base = ImageRepository.listImages(context, null)
                searchBase = base
                applyQuery(base, _state.value.searchQuery)
            } else if (selected == null) {
                resolveRecentImages()
            } else {
                ImageRepository.listImages(
                    context, selected,
                    sortMode = sort?.mode ?: StickerSortMode.DEFAULT,
                    reverse = sort?.reverse ?: false,
                    usageCounts = meta.getUsageCounts()
                )
            }
            val previews = meta.getFolderPreviews()
                .filterKeys { it in folders }
                .mapValues { it.value.second }
            _state.update {
                it.copy(
                    folders = folders,
                    selectedFolder = selected,
                    images = images,
                    previewMap = previews,
                    gridColumns = meta.getGridColumns(),
                    folderSort = sort,
                    loading = false
                )
            }
        }
    }

    /** 解析文件夹生效排序：临时覆盖 > 持久默认（未设置过即默认序） */
    private fun resolveSort(folder: String): FolderSort? =
        tempSorts[folder] ?: meta.getFolderSorts()[folder]

    /**
     * 应用文件夹内表情包排序。[persist] 为 true 时覆盖持久默认，
     * 否则仅作为本次进程的临时排序。
     */
    fun setFolderSort(folder: String, sort: FolderSort, persist: Boolean) {
        if (persist) meta.setFolderSort(folder, sort) else tempSorts[folder] = sort
        if (_state.value.selectedFolder == folder) refresh()
    }

    /** 清除该文件夹的临时排序，恢复持久默认 */
    fun clearTempSort(folder: String) {
        if (tempSorts.remove(folder) != null && _state.value.selectedFolder == folder) refresh()
    }

    /** 进入搜索态：全库图片为范围，按文件名过滤 */
    fun enterSearch() {
        viewModelScope.launch {
            val base = ImageRepository.listImages(context, null)
            searchBase = base
            _state.update {
                it.copy(searchActive = true, searchQuery = "", images = base, loading = false)
            }
        }
    }

    /** 退出搜索态，恢复之前的文件夹视图 */
    fun exitSearch() {
        _state.update { it.copy(searchActive = false, searchQuery = "") }
        refresh()
    }

    /** 更新搜索关键字（内存过滤，不扫盘） */
    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query, images = applyQuery(searchBase, query)) }
    }

    private fun applyQuery(base: List<ImageItem>, query: String): List<ImageItem> =
        if (query.isBlank()) base
        else base.filter { it.displayName.contains(query, ignoreCase = true) }

    /** 将“最近”记录解析为真实存在的图片（失效记录顺带清理），按时间倒序，上限 100 */
    private suspend fun resolveRecentImages(): List<ImageItem> {
        val recent = meta.getRecent()
        if (recent.isEmpty()) return emptyList()
        val all = ImageRepository.listImages(context, null)
        val byPath = all.associateBy { it.path }
        val valid = recent.mapNotNull { entry -> byPath[entry.path]?.let { entry.time to it } }
        if (valid.size != recent.size) {
            meta.saveRecent(valid.map { (time, item) ->
                RecentEntry(item.path, item.displayName, item.folderName, time)
            })
        }
        return valid.sortedByDescending { it.first }
            .take(MetaPreferences.MAX_RECENT)
            .map { it.second }
    }

    fun selectFolder(folder: String?) {
        _state.update { it.copy(selectedFolder = folder) }
        refresh()
    }

    /** 应用用户自定义排序：已排序的在前（保持保存顺序），新文件夹按字母序追加在后 */
    private fun orderedFolders(alphabetical: List<String>): List<String> {
        val order = meta.getFolderOrder()
        if (order.isEmpty()) return alphabetical
        return order.filter { it in alphabetical } + alphabetical.filter { it !in order }
    }

    /** 上移/下移文件夹并持久化自定义顺序（“最近”为特殊入口，不在 folders 内、不可移动） */
    fun moveFolder(folder: String, up: Boolean) {
        val current = _state.value.folders.toMutableList()
        val index = current.indexOf(folder)
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in current.indices) return
        current.removeAt(index)
        current.add(target, folder)
        meta.setFolderOrder(current)
        _state.update { it.copy(folders = current) }
    }

    /** 发送成功后记入“最近”并累计使用次数（供 USAGE 排序） */
    fun recordSentImage(image: ImageItem) {
        meta.incrementUsage(image.path)
        meta.addToRecent(
            listOf(RecentEntry(image.path, image.displayName, image.folderName, System.currentTimeMillis()))
        )
        if (_state.value.selectedFolder == null) refresh()
    }

    /** 统计某文件夹内图片数量（删除确认弹窗文案用） */
    suspend fun countImages(folder: String): Int =
        ImageRepository.listImages(context, folder).size

    /** 清空“最近”记录（图片文件不受影响），返回旧列表以便撤销 */
    fun clearRecent(): List<RecentEntry> {
        val old = meta.clearRecent()
        if (_state.value.selectedFolder == null) refresh()
        return old
    }

    /** 撤销清空“最近” */
    fun restoreRecent(entries: List<RecentEntry>) {
        meta.restoreRecent(entries)
        if (_state.value.selectedFolder == null) refresh()
    }

    fun setGridColumns(columns: Int) {
        meta.setGridColumns(columns)
        _state.update { it.copy(gridColumns = columns.coerceIn(2, 6)) }
    }

    /** 新建真实文件夹，成功后选中它 */
    fun createFolder(name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val safe = ImageRepository.sanitizeFolderName(name)
            val ok = safe != null && ImageRepository.createFolder(context, safe)
            if (ok && safe != null) {
                _state.update { it.copy(selectedFolder = safe) }
            }
            refresh()
            onResult(ok)
        }
    }

    /** 长按图片 -> 设为所在文件夹的预览图（持久化到 MetaPreferences） */
    fun setFolderPreview(image: ImageItem) {
        meta.setFolderPreview(image.folderName, image.path, image.uriString)
        _state.update { it.copy(previewMap = it.previewMap + (image.folderName to image.uriString)) }
    }

    /**
     * 长按图片 -> 重命名。文件改名与 sha256 映射文件由 Repository 同步更新，
     * 这里顺带修正“最近”记录与文件夹预览图中指向旧路径的条目。
     */
    fun renameImage(image: ImageItem, newName: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val renamed = ImageRepository.renameImage(context, image, newName)
            if (renamed != null) {
                val recent = meta.getRecent()
                if (recent.any { it.path == image.path }) {
                    meta.saveRecent(recent.map {
                        if (it.path == image.path) {
                            it.copy(path = renamed.path, name = renamed.displayName)
                        } else {
                            it
                        }
                    })
                }
                if (meta.getFolderPreviews()[image.folderName]?.first == image.path) {
                    meta.setFolderPreview(image.folderName, renamed.path, renamed.uriString)
                }
                meta.transferUsage(image.path, renamed.path)
                refresh()
            }
            onResult(renamed != null)
        }
    }

    /** 真实删除单张图片，并从“最近”移除；若它是文件夹预览图则回退默认图标 */
    fun deleteImage(image: ImageItem, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = ImageRepository.deleteImage(context, image)
            meta.removeFromRecent(listOf(image.path))
            meta.removeUsage(listOf(image.path))
            if (meta.getFolderPreviews()[image.folderName]?.first == image.path) {
                meta.setFolderPreview(image.folderName, null, null)
            }
            refresh()
            onResult(ok)
        }
    }

    /** 真实删除文件夹及其全部图片，并清理预览图、排序记录与相关“最近”记录 */
    fun deleteFolder(folder: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val recentPaths = meta.getRecent().filter { it.folder == folder }.map { it.path }
            // 删除前采集文件路径，用于清理各自的使用计数
            val paths = File(ImageRepository.getRootDir(context), folder)
                .listFiles()?.map { it.absolutePath } ?: emptyList()
            val deleted = ImageRepository.deleteFolder(context, folder)
            meta.setFolderPreview(folder, null, null)
            meta.setFolderOrder(meta.getFolderOrder() - folder)
            meta.cleanupFolderMeta(folder, paths + recentPaths)
            tempSorts.remove(folder)
            meta.removeFromRecent(recentPaths)
            refresh()
            onResult(deleted)
        }
    }
}
