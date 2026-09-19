package com.example.emoo.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.emoo.data.ImageRepository
import com.example.emoo.data.MetaPreferences
import com.example.emoo.model.ImageItem
import com.example.emoo.model.RecentEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        val loading: Boolean = true
    )

    private val meta = MetaPreferences.get(application)
    private val context get() = getApplication<Application>()

    private val _state = MutableStateFlow(GalleryState(gridColumns = meta.getGridColumns()))
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新扫描目录（进入前台、增删后调用），保证与文件系统一致 */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val folders = orderedFolders(ImageRepository.listFolders(context))
            val selected = _state.value.selectedFolder?.takeIf { it in folders }
            val images = if (selected == null) {
                resolveRecentImages()
            } else {
                ImageRepository.listImages(context, selected)
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
                    loading = false
                )
            }
        }
    }

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

    /** 发送成功后记入“最近”：同路径旧记录被新时间覆盖并置顶，不产生重复 */
    fun recordSentImage(image: ImageItem) {
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

    /** 真实删除单张图片，并从“最近”移除；若它是文件夹预览图则回退默认图标 */
    fun deleteImage(image: ImageItem, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = ImageRepository.deleteImage(context, image)
            meta.removeFromRecent(listOf(image.path))
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
            val deleted = ImageRepository.deleteFolder(context, folder)
            meta.setFolderPreview(folder, null, null)
            meta.setFolderOrder(meta.getFolderOrder() - folder)
            val recentPaths = meta.getRecent().filter { it.folder == folder }.map { it.path }
            meta.removeFromRecent(recentPaths)
            refresh()
            onResult(deleted)
        }
    }
}
