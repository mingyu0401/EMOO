package com.example.emoo.ui.imports

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.emoo.data.ImageRepository
import com.example.emoo.data.MetaPreferences
import com.example.emoo.model.ImportCandidate
import com.example.emoo.model.ImportProgress
import com.example.emoo.model.RecentEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导入流程步骤：选择来源 -> 勾选图片 -> 选择目标 -> 复制（进度） */
sealed interface ImportStep {
    data object PickSource : ImportStep
    data class PickImages(val candidates: List<ImportCandidate>) : ImportStep
    data class PickTarget(val selected: List<ImportCandidate>) : ImportStep
    /** 文字导入：段落列表 -> 选择目标文件夹 -> 各写为一个 .txt */
    data class PickTextTarget(val texts: List<String>) : ImportStep
}

/**
 * 导入 ViewModel：来源选择（Photo Picker 多选 / SAF 目录树）、
 * 勾选管理、目标文件夹选择（含新建）、流式复制（可取消、显示进度），
 * 完成后将新图片记入“最近”。
 */
class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ImageRepository
    private val meta = MetaPreferences.get(application)
    private val context get() = getApplication<Application>()

    private val _step = MutableStateFlow<ImportStep>(ImportStep.PickSource)
    val step: StateFlow<ImportStep> = _step.asStateFlow()

    /** 已勾选的来源 uri 集合 */
    private val _selection = MutableStateFlow<Set<Uri>>(emptySet())
    val selection: StateFlow<Set<Uri>> = _selection.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    /** 导入位置：true = 排到文件夹最前（默认），false = 排到最后 */
    private val _atFront = MutableStateFlow(true)
    val atFront: StateFlow<Boolean> = _atFront.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val message: SharedFlow<String> = _message.asSharedFlow()

    private var copyJob: Job? = null

    // ---------------- 来源：按图片（Photo Picker） ----------------

    fun onImagesPicked(uris: List<Uri>) {
        viewModelScope.launch {
            _loading.value = true
            val candidates = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = repo.displayNameOf(context, uri) ?: return@mapNotNull null
                    if (repo.isSupportedMedia(name)) ImportCandidate(uri, name) else null
                }
            }
            _loading.value = false
            if (candidates.isEmpty()) {
                _message.tryEmit("未选择受支持的图片或视频")
            } else {
                _step.value = ImportStep.PickImages(candidates)
                _selection.value = candidates.map { it.uri }.toSet()
            }
        }
    }

    // ---------------- 来源：按文件夹（SAF 目录树） ----------------

    fun onFolderPicked(treeUri: Uri) {
        viewModelScope.launch {
            _loading.value = true
            val candidates = withContext(Dispatchers.IO) {
                repo.listDocumentImages(context, treeUri)
            }
            _loading.value = false
            if (candidates.isEmpty()) {
                _message.tryEmit("该文件夹内没有受支持的图片或视频")
            } else {
                _step.value = ImportStep.PickImages(candidates)
                _selection.value = candidates.map { it.uri }.toSet()
            }
        }
    }

    // ---------------- 勾选管理 ----------------

    fun toggleSelect(uri: Uri) {
        _selection.value = if (uri in _selection.value) {
            _selection.value - uri
        } else {
            _selection.value + uri
        }
    }

    fun selectAll(candidates: List<ImportCandidate>, all: Boolean) {
        _selection.value = if (all) candidates.map { it.uri }.toSet() else emptySet()
    }

    fun goBack() {
        when (val current = _step.value) {
            is ImportStep.PickImages -> _step.value = ImportStep.PickSource
            is ImportStep.PickTarget -> _step.value = ImportStep.PickImages(current.selected)
            is ImportStep.PickTextTarget -> _step.value = ImportStep.PickSource
            ImportStep.PickSource -> Unit
        }
    }

    // ---------------- 目标选择 ----------------

    fun confirmSelection() {
        val current = _step.value as? ImportStep.PickImages ?: return
        val chosen = current.candidates.filter { it.uri in _selection.value }
        if (chosen.isEmpty()) {
            _message.tryEmit("请至少选择一张图片")
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _folders.value = repo.listFolders(context)
            _loading.value = false
            _step.value = ImportStep.PickTarget(chosen)
        }
    }

    fun reloadFolders() {
        viewModelScope.launch { _folders.value = repo.listFolders(context) }
    }

    /** 在导入目标页新建文件夹，成功时回调返回新文件夹名 */
    fun createFolder(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val safe = repo.sanitizeFolderName(name)
            if (safe == null) {
                _message.tryEmit("文件夹名无效")
                onResult(null)
                return@launch
            }
            if (repo.createFolder(context, safe)) {
                _folders.value = repo.listFolders(context)
                _message.tryEmit("已创建文件夹「$safe」")
                onResult(safe)
            } else {
                _message.tryEmit("创建失败")
                onResult(null)
            }
        }
    }

    // ---------------- 执行复制 ----------------

    fun setAtFront(atFront: Boolean) {
        _atFront.value = atFront
    }

    fun startImport(targetFolder: String) {
        val current = _step.value as? ImportStep.PickTarget ?: return
        val atFront = _atFront.value
        copyJob = viewModelScope.launch {
            var copied = 0
            _progress.value = ImportProgress(0, current.selected.size, "")
            try {
                val imported = repo.copyImages(
                    context = context,
                    candidates = current.selected,
                    targetFolder = targetFolder,
                    atFront = atFront,
                    onProgress = { cur, total, name ->
                        _progress.value = ImportProgress(cur, total, name)
                    },
                    onCopied = { copied++ }
                )
                // 记入“最近”
                meta.addToRecent(imported.map {
                    RecentEntry(it.path, it.displayName, it.folderName, System.currentTimeMillis())
                })
                _message.tryEmit(
                    "已导入 ${imported.size} 张图片到「$targetFolder」" +
                        if (atFront) "（排在最前）" else "（排在最后）"
                )
            } catch (e: CancellationException) {
                _message.tryEmit("导入已取消（已复制 $copied 张）")
                throw e
            } catch (e: Exception) {
                _message.tryEmit("导入出错：${e.message ?: "未知错误"}")
            } finally {
                _progress.value = null
                reset()
            }
        }
    }

    fun cancelImport() {
        copyJob?.cancel()
    }

    // ---------------- 文字导入 ----------------

    /** 用户在文字对话框提交原文：按空行切分为段落，进入目标文件夹选择步骤 */
    fun onTextSubmitted(raw: String) {
        val paragraphs = raw.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) {
            _message.tryEmit("请输入要导入的文字内容")
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _folders.value = repo.listFolders(context)
            _loading.value = false
            _step.value = ImportStep.PickTextTarget(paragraphs)
        }
    }

    /** 将待导入的每段文字各写为一个 .txt 文件到目标文件夹 */
    fun startTextImport(targetFolder: String) {
        val current = _step.value as? ImportStep.PickTextTarget ?: return
        val atFront = _atFront.value
        copyJob = viewModelScope.launch {
            _progress.value = ImportProgress(0, current.texts.size, "")
            try {
                val imported = repo.importTexts(
                    context = context,
                    paragraphs = current.texts,
                    targetFolder = targetFolder,
                    atFront = atFront,
                    onProgress = { cur, total, name ->
                        _progress.value = ImportProgress(cur, total, name)
                    }
                )
                meta.addToRecent(imported.map {
                    RecentEntry(it.path, it.displayName, it.folderName, System.currentTimeMillis())
                })
                _message.tryEmit("已导入 ${imported.size} 段文字到「$targetFolder」")
            } catch (e: CancellationException) {
                _message.tryEmit("文字导入已取消")
                throw e
            } catch (e: Exception) {
                _message.tryEmit("导入出错：${e.message ?: "未知错误"}")
            } finally {
                _progress.value = null
                reset()
            }
        }
    }

    fun reset() {
        _step.value = ImportStep.PickSource
        _selection.value = emptySet()
        _progress.value = null
    }
}
