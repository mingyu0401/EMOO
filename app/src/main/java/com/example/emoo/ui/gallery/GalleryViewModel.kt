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

/**
 * 图片主页面 ViewModel：持有文件夹列表、当前选中文件夹（null = “最近”）
 * 与该视图下的图片列表。所有文件操作与文件夹级元数据（排序/计数/预览/顺序，
 * 目录内 .emoo_* 文件）均委托 Repository，MetaPreferences 只承载全局设置。
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
        val folderSort: FolderSort? = null,
        /** 是否在网格角标显示使用次数 */
        val showUsageCount: Boolean = false,
        /** path -> 发送使用次数（角标与 USAGE 排序共用） */
        val usageCounts: Map<String, Int> = emptyMap()
    )

    private val meta = MetaPreferences.get(application)
    private val context get() = getApplication<Application>()

    /** 搜索态下的全库图片缓存：输入关键字时只在内存过滤，不重复扫盘 */
    private var searchBase: List<ImageItem> = emptyList()

    /** 进入搜索的后台扫盘任务与其令牌：连点搜索时丢弃过期扫描结果，防止与刷新互相覆盖 */
    private var searchScanJob: kotlinx.coroutines.Job? = null
    private var searchToken = 0

    /** 刷新令牌：异步刷新只写回自己那一代的结果，防连点切文件夹时旧结果覆盖新结果 */
    private var refreshToken = 0

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
        val token = ++refreshToken
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // 旧版 sp 里的文件夹级元数据一次性迁移为目录内 .emoo_* 文件（无旧数据时立即返回）
            meta.takeLegacyFolderMeta()?.let { legacy ->
                ImageRepository.migrateLegacyMeta(context, legacy)
            }
            val folders = orderedFolders(ImageRepository.listFolders(context))
            val selected = _state.value.selectedFolder?.takeIf { it in folders }
            val sort = selected?.let { ImageRepository.getFolderSort(context, it) }
            val images = if (_state.value.searchActive) {
                val base = ImageRepository.listImages(context, null)
                searchBase = base
                // 未输入关键字时保持空白，只展示过滤结果
                if (_state.value.searchQuery.isBlank()) emptyList()
                else applyQuery(base, _state.value.searchQuery)
            } else if (selected == null) {
                resolveRecentImages()
            } else {
                ImageRepository.listImages(
                    context, selected,
                    sortMode = sort?.mode ?: StickerSortMode.DEFAULT,
                    reverse = sort?.reverse ?: false
                )
            }
            val previews = ImageRepository.loadFolderPreviews(context)
            val usage = ImageRepository.loadUsageCounts(context)
            // 期间又发起了新的刷新/切换时，丢弃本次过期结果
            if (token != refreshToken) return@launch
            _state.update {
                it.copy(
                    folders = folders,
                    selectedFolder = selected,
                    images = images,
                    previewMap = previews,
                    gridColumns = meta.getGridColumns(),
                    folderSort = sort,
                    showUsageCount = meta.getShowUsageCount(),
                    usageCounts = usage,
                    loading = false
                )
            }
        }
    }

    /** 保存文件夹的排序设置（弹窗「确认」：按所选方式排序，写入文件夹内 .emoo_sort） */
    fun setFolderSort(folder: String, sort: FolderSort) {
        viewModelScope.launch {
            ImageRepository.setFolderSort(context, folder, sort)
            if (_state.value.selectedFolder == folder) refresh()
        }
    }

    /**
     * 弹窗「覆盖自定义」：把当前列表按所选方式+倒序排一遍，
     * 结果整表写入该文件夹的自定义顺序文件，并把排序切回「自定义」。
     */
    fun overwriteCustomSort(folder: String, sort: FolderSort, onResult: (Boolean) -> Unit) {
        val items = _state.value.images
        viewModelScope.launch {
            val ok = ImageRepository.overwriteCustomOrder(
                context, folder, items, sort.mode, sort.reverse
            )
            if (ok) setFolderSort(folder, FolderSort(StickerSortMode.DEFAULT, false))
            onResult(ok)
        }
    }

    /**
     * 长按图片 -> 移到最前/移至最后：以当前展示顺序为基准调整该图片位置后
     * 写入自定义顺序文件，并把该文件夹排序切回「自定义」。仅限单文件夹浏览态。
     */
    fun moveImageToEdge(image: ImageItem, front: Boolean, onResult: (Boolean) -> Unit) {
        val st = _state.value
        val folder = image.folderName
        if (st.searchActive || st.selectedFolder != folder) {
            onResult(false)
            return
        }
        val names = st.images.map { it.displayName }.toMutableList()
        if (!names.remove(image.displayName)) {
            onResult(false)
            return
        }
        if (front) names.add(0, image.displayName) else names.add(image.displayName)
        viewModelScope.launch {
            val ok = ImageRepository.saveCustomOrder(context, folder, names)
            if (ok) setFolderSort(folder, FolderSort(StickerSortMode.DEFAULT, false))
            onResult(ok)
        }
    }

    /** 进入搜索态：同步切换到空白搜索界面（状态即时翻转，连点不会与异步刷新互相覆盖），
     * 再后台扫全库供关键字过滤；扫描完成时若已输入关键字则补一次过滤。
     * 同时清空文件夹选中，保证侧栏只有「搜索」一项高亮 */
    fun enterSearch() {
        val token = ++searchToken
        ++refreshToken // 作废进行中的刷新结果，避免其稍后覆盖搜索态列表
        _state.update {
            it.copy(
                searchActive = true,
                searchQuery = "",
                selectedFolder = null,
                images = emptyList(),
                folderSort = null,
                loading = false
            )
        }
        searchBase = emptyList()
        searchScanJob?.cancel()
        searchScanJob = viewModelScope.launch {
            val base = ImageRepository.listImages(context, null)
            if (token != searchToken || !_state.value.searchActive) return@launch
            searchBase = base
            _state.update {
                if (it.searchActive && it.searchQuery.isNotBlank())
                    it.copy(images = applyQuery(base, it.searchQuery))
                else it
            }
        }
    }

    /** 退出搜索态：作废进行中的搜索扫描，同步翻转标记后恢复之前的文件夹视图 */
    fun exitSearch() {
        ++searchToken
        searchScanJob?.cancel()
        _state.update { it.copy(searchActive = false, searchQuery = "") }
        refresh()
    }

    /** 更新搜索关键字（内存过滤，不扫盘）；清空关键字时回到空白初始态 */
    fun setSearchQuery(query: String) {
        _state.update {
            it.copy(
                searchQuery = query,
                images = if (query.isBlank()) emptyList() else applyQuery(searchBase, query)
            )
        }
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

    /**
     * 侧栏点击切换视图（高亮唯一）：点击任何条目都会退出搜索态；
     * 进入「最近」时清空文件夹选中。点击当前已选条目不做任何事
     * （否则整表刷新会让网格重置）；真正切换时立即清空旧列表，
     * 避免上一文件夹的图片在加载期间残留。
     */
    fun selectFolder(folder: String?) {
        val prev = _state.value
        if (!prev.searchActive && prev.selectedFolder == folder) return
        _state.update {
            it.copy(
                selectedFolder = folder,
                searchActive = false,
                searchQuery = "",
                images = emptyList()
            )
        }
        refresh()
    }

    /** 应用用户自定义排序：已排序的在前（保持保存顺序），新文件夹按字母序追加在后 */
    private suspend fun orderedFolders(alphabetical: List<String>): List<String> {
        val order = ImageRepository.getFolderOrder(context)
        if (order.isEmpty()) return alphabetical
        return order.filter { it in alphabetical } + alphabetical.filter { it !in order }
    }

    /** 上移/下移文件夹并持久化自定义顺序到根目录顺序文件（“最近”为特殊入口，不在 folders 内、不可移动） */
    fun moveFolder(folder: String, up: Boolean) {
        val current = _state.value.folders.toMutableList()
        val index = current.indexOf(folder)
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in current.indices) return
        current.removeAt(index)
        current.add(target, folder)
        _state.update { it.copy(folders = current) }
        viewModelScope.launch { ImageRepository.setFolderOrder(context, current) }
    }

    /** 发送成功后记入“最近”并累计使用次数（写入所在文件夹的 .emoo_usage，供 USAGE 排序） */
    fun recordSentImage(image: ImageItem) {
        meta.addToRecent(
            listOf(RecentEntry(image.path, image.displayName, image.folderName, System.currentTimeMillis()))
        )
        viewModelScope.launch {
            ImageRepository.incrementUsage(image.path)
            if (_state.value.selectedFolder == null) refresh()
            else _state.update { it.copy(usageCounts = ImageRepository.loadUsageCounts(context)) }
        }
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

    /** 网格角标显示使用次数的开关 */
    fun setShowUsageCount(show: Boolean) {
        meta.setShowUsageCount(show)
        _state.update { it.copy(showUsageCount = show) }
        if (show) {
            viewModelScope.launch {
                val counts = ImageRepository.loadUsageCounts(context)
                _state.update { it.copy(usageCounts = counts) }
            }
        }
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

    /** 长按图片 -> 设为所在文件夹的预览图（记文件名到文件夹内 .emoo_preview） */
    fun setFolderPreview(image: ImageItem) {
        _state.update { it.copy(previewMap = it.previewMap + (image.folderName to image.uriString)) }
        viewModelScope.launch {
            ImageRepository.setFolderPreview(context, image.folderName, image.displayName)
        }
    }

    /**
     * 长按图片 -> 重命名。文件改名与 sha256 映射、使用次数、预览图等
     * 文件夹内元数据文件均由 Repository 同步更新，这里只修正“最近”记录。
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
                refresh()
            }
            onResult(renamed != null)
        }
    }

    /** 查看页编辑文字：覆写 .txt 正文并同步 sha 映射，成功后刷新（网格预览随之更新） */
    fun editTextFile(image: ImageItem, text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = ImageRepository.saveTextFile(context, image, text)
            if (ok) refresh()
            onResult(ok)
        }
    }

    /** 真实删除单张图片，并从“最近”移除；文件夹内计数/预览等记录由 Repository 顺带清理 */
    fun deleteImage(image: ImageItem, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = ImageRepository.deleteImage(context, image)
            meta.removeFromRecent(listOf(image.path))
            refresh()
            onResult(ok)
        }
    }

    /** 真实删除文件夹及其全部图片（文件夹内元数据文件随目录删除，
     * 根目录顺序文件由 Repository 顺带清理），并清理相关“最近”记录 */
    fun deleteFolder(folder: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val recentPaths = meta.getRecent().filter { it.folder == folder }.map { it.path }
            val deleted = ImageRepository.deleteFolder(context, folder)
            meta.removeFromRecent(recentPaths)
            refresh()
            onResult(deleted)
        }
    }
}
