package com.buxingjie.localai.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buxingjie.localai.BuildConfig
import com.buxingjie.localai.data.AiProvider
import com.buxingjie.localai.data.ChatSession
import com.buxingjie.localai.data.ChatStore
import com.buxingjie.localai.data.DownloadRecord
import com.buxingjie.localai.data.LocalModel
import com.buxingjie.localai.data.ModelRepository
import com.buxingjie.localai.data.Prefs
import com.buxingjie.localai.data.RemoteModel
import com.buxingjie.localai.data.StoredMessage
import com.buxingjie.localai.data.TerminalLine
import com.buxingjie.localai.data.TerminalStore
import com.buxingjie.localai.doc.DocumentParser
import com.buxingjie.localai.doc.DocEntry
import com.buxingjie.localai.doc.ParsedDoc
import com.buxingjie.localai.doc.TextTools
import com.buxingjie.localai.llm.GenerateResult
import com.buxingjie.localai.llm.LlamaEngine
import com.buxingjie.localai.llm.Prompts
import com.buxingjie.localai.net.ChatMsg
import com.buxingjie.localai.net.RemoteChatClient
import com.buxingjie.localai.net.RemoteRequest
import com.buxingjie.localai.net.UpdateChecker
import com.buxingjie.localai.net.UpdateDownloader
import com.buxingjie.localai.net.UpdateInfo
import com.buxingjie.localai.service.QuickStopService
import com.buxingjie.localai.tools.PhoneControl
import com.buxingjie.localai.tools.ShellRunner
import com.buxingjie.localai.tools.ToolCall
import com.buxingjie.localai.tools.ToolProtocol
import com.buxingjie.localai.tools.WebFetcher
import com.buxingjie.localai.tools.WorkspaceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val streaming: Boolean = false,
)

data class DownloadState(
    val id: String,
    val received: Long,
    val total: Long,
    val active: Boolean = true,
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val generating: Boolean = false,
    val modelLoaded: Boolean = false,
    val modelLoading: Boolean = false,
    val modelName: String = "",
    val modelInfo: String = "",
    val installedModels: List<LocalModel> = emptyList(),
    val selectedModelPath: String? = null,
    val docs: List<ParsedDoc> = emptyList(),
    val workMode: Boolean = false,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val contextSize: Int = 8192,
    val threads: Int = 4,
    val status: String? = null,
    val error: String? = null,
    val downloads: Map<String, DownloadState> = emptyMap(),
    val docBusy: Boolean = false,
    val engineId: String = "local",
    val engineLabel: String = "本地模型",
    val engineReady: Boolean = false,
    val remoteProviderId: String = "",
    val remoteBaseUrl: String = "",
    val remoteModel: String = "",
    val remoteApiKey: String = "",
    val webToolsEnabled: Boolean = true,
    val phoneToolsEnabled: Boolean = true,
    val accessibilityEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val toolRunning: Boolean = false,
    val downloadHistory: List<DownloadRecord> = emptyList(),
    val workingDir: String = "",
    val terminalLines: List<TerminalLine> = emptyList(),
    val terminalBusy: Boolean = false,
    val workspacePath: String = "",
    val workspaceFiles: List<WorkspaceFile> = emptyList(),
    val workspaceLoading: Boolean = false,
    val previewPath: String = "",
    val previewTitle: String? = null,
    val previewText: String = "",
    val previewEntries: List<DocEntry> = emptyList(),
    val previewLoading: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val updateDialogVisible: Boolean = false,
    val updateChecking: Boolean = false,
    val updateDownloading: Boolean = false,
    val updateProgress: Float = 0f,
    val updateError: String? = null,
    val updateApkPath: String? = null,
    val primaryColor: Long = 0xFF2DD4BF,
    val accentColor: Long = 0xFF7DD3FC,
    val glassOpacity: Float = 0.16f,
    val wallpaperPath: String? = null,
    val wallpaperBlur: Int = 0,
)

private data class EngineSnapshot(
    val id: String,
    val label: String,
    val ready: Boolean,
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = Prefs(application)
    private val repository = ModelRepository(application)
    private val chatStore = ChatStore(application)
    private val parser = DocumentParser(application)
    private val engine = LlamaEngine()
    private val client = RemoteChatClient()
    private val terminalStore = TerminalStore(application)

    private val defaultWorkspace: String =
        File(application.getExternalFilesDir(null) ?: application.filesDir, "workspace")
            .apply { mkdirs() }
            .absolutePath
    private val workDir: String = prefs.workingDir
        ?.takeIf { it.isNotBlank() && File(it).isDirectory }
        ?: defaultWorkspace

    private val _state = MutableStateFlow(
        ChatUiState(
            workMode = prefs.workMode,
            temperature = prefs.temperature,
            maxTokens = prefs.maxTokens,
            contextSize = prefs.contextSize,
            threads = prefs.threads,
            installedModels = repository.installed(),
            selectedModelPath = prefs.modelPath,
            webToolsEnabled = prefs.webToolsEnabled,
            phoneToolsEnabled = prefs.phoneToolsEnabled,
            engineId = prefs.engine,
            downloadHistory = prefs.downloadHistory,
            workingDir = workDir,
            workspacePath = workDir,
            terminalLines = terminalStore.load(prefs.engine),
            primaryColor = prefs.primaryColor,
            accentColor = prefs.accentColor,
            glassOpacity = prefs.glassOpacity,
            wallpaperPath = prefs.wallpaperPath,
            wallpaperBlur = prefs.wallpaperBlur,
        )
    )
    val state = _state.asStateFlow()

    private var session: ChatSession = prefs.lastSessionId?.let { chatStore.load(it) } ?: chatStore.newSession()
    private var needPrime = true
    private var sendJob: Job? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PhoneControl.ACTION_EMERGENCY_STOP) {
                emergencyStop()
            }
        }
    }

    init {
        val stored = session.messages
        if (stored.isNotEmpty()) {
            _state.update {
                it.copy(messages = stored.map { message ->
                    UiMessage(UUID.randomUUID().toString(), message.role, message.content)
                })
            }
        }
        refreshEngineState()
        refreshSystemState()
        prefs.workingDir = workDir
        refreshWorkspace(workDir)

        val app = getApplication<Application>()
        val filter = IntentFilter(PhoneControl.ACTION_EMERGENCY_STOP)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(stopReceiver, filter)
        }

        val path = prefs.modelPath
        if (path != null && File(path).exists()) {
            loadModel(LocalModel(File(path), File(path).name.removeSuffix(".gguf"), File(path).length()))
        } else {
            _state.update { it.copy(selectedModelPath = null) }
        }

        checkUpdate()
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(stopReceiver) }
        client.cancel()
        super.onCleared()
    }

    // ---------------- update ----------------

    fun checkUpdate(manual: Boolean = false) {
        if (_state.value.updateChecking) return
        _state.update { it.copy(updateChecking = true, updateError = null) }
        viewModelScope.launch {
            val current = BuildConfig.VERSION_NAME
            val info = UpdateChecker.latest()
            if (info == null) {
                _state.update {
                    it.copy(
                        updateChecking = false,
                        status = if (manual) "检查更新失败，请检查网络后重试" else it.status,
                    )
                }
                return@launch
            }
            val newer = UpdateChecker.isNewer(info.version, current)
            _state.update {
                it.copy(
                    updateChecking = false,
                    updateInfo = if (newer) info else it.updateInfo,
                    updateDialogVisible = newer,
                    status = when {
                        newer -> "发现新版本 v${info.version}"
                        manual -> "当前已是最新版本 v$current"
                        else -> it.status
                    },
                )
            }
        }
    }

    fun showUpdateDialog() {
        if (_state.value.updateInfo != null) {
            _state.update { it.copy(updateDialogVisible = true, updateError = null) }
        }
    }

    fun dismissUpdate() {
        _state.update { it.copy(updateDialogVisible = false) }
    }

    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: return
        if (_state.value.updateDownloading) return
        val app = getApplication<Application>()
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "LocalAI-${info.version}.apk")
        if (target.exists() && info.apkSize > 0 && target.length() == info.apkSize) {
            _state.update {
                it.copy(
                    updateApkPath = target.absolutePath,
                    updateProgress = 1f,
                    updateError = null,
                    updateDialogVisible = true,
                )
            }
            return
        }
        _state.update {
            it.copy(
                updateDownloading = true,
                updateProgress = 0f,
                updateError = null,
                updateDialogVisible = true,
            )
        }
        viewModelScope.launch {
            val temp = File(dir, target.name + ".part")
            try {
                if (temp.exists()) temp.delete()
                UpdateDownloader.download(info.apkUrl, temp) { received, total ->
                    val expected = if (total > 0) total else info.apkSize
                    if (expected > 0) {
                        _state.update {
                            it.copy(updateProgress = (received.toFloat() / expected).coerceIn(0f, 1f))
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) throw IOException("保存安装包失败")
                _state.update {
                    it.copy(
                        updateDownloading = false,
                        updateProgress = 1f,
                        updateApkPath = target.absolutePath,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { temp.delete() }
                _state.update {
                    it.copy(
                        updateDownloading = false,
                        updateError = "下载失败：${e.message ?: "网络错误"}",
                    )
                }
            }
        }
    }

    fun installUpdate() {
        val path = _state.value.updateApkPath ?: return
        val file = File(path)
        if (!file.exists() || file.length() <= 0) {
            _state.update { it.copy(updateError = "安装包不存在，请重新下载") }
            return
        }
        val app = getApplication<Application>()
        runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(intent)
        }.onFailure { e ->
            _state.update {
                it.copy(updateError = "无法打开安装界面：${e.message ?: "请允许安装未知应用"}")
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun stop() {
        engine.cancel()
        client.cancel()
        _state.update { it.copy(status = "正在停止…") }
    }

    private fun emergencyStop() {
        engine.cancel()
        client.cancel()
        _state.update { it.copy(status = "已紧急停止手机操控") }
    }

    fun newChat() {
        persist()
        session = chatStore.newSession()
        prefs.lastSessionId = session.id
        needPrime = true
        _state.update {
            it.copy(messages = emptyList(), error = null, status = null, generating = false)
        }
    }

    fun toggleWorkMode(enabled: Boolean) {
        prefs.workMode = enabled
        needPrime = true
        _state.update { it.copy(workMode = enabled, status = if (enabled) "已切换到办公助手" else "已切换到通用助手") }
    }

    // ---------------- engine ----------------

    private fun engineSnapshot(): EngineSnapshot {
        val provider = AiProvider.fromId(prefs.engine)
        return if (provider == null) {
            EngineSnapshot(
                id = "local",
                label = "本地模型",
                ready = _state.value.modelLoaded,
                providerId = "",
                baseUrl = "",
                model = "",
                apiKey = "",
            )
        } else {
            val config = prefs.config(provider)
            EngineSnapshot(
                id = provider.id,
                label = "${provider.label} · ${config.model}",
                ready = config.ready,
                providerId = provider.id,
                baseUrl = config.baseUrl,
                model = config.model,
                apiKey = config.apiKey,
            )
        }
    }

    private fun refreshEngineState() {
        val snapshot = engineSnapshot()
        _state.update {
            it.copy(
                engineId = snapshot.id,
                engineLabel = snapshot.label,
                engineReady = snapshot.ready,
                remoteProviderId = snapshot.providerId,
                remoteBaseUrl = snapshot.baseUrl,
                remoteModel = snapshot.model,
                remoteApiKey = snapshot.apiKey,
            )
        }
    }

    private fun currentProvider(): AiProvider? = AiProvider.fromId(prefs.engine)

    fun selectEngine(id: String) {
        val valid = if (id == "local") true else AiProvider.fromId(id) != null
        if (!valid) return
        if (prefs.engine == id) return
        prefs.engine = id
        needPrime = true
        refreshEngineState()
        _state.update { it.copy(terminalLines = terminalStore.load(id)) }
        val label = _state.value.engineLabel
        val hint = if (_state.value.engineReady) "已切换到 $label" else "已切换到 $label，请先完成配置"
        _state.update { it.copy(status = hint) }
    }

    fun setRemoteApiKey(value: String) {
        val provider = currentProvider() ?: return
        prefs.setApiKey(provider, value)
        refreshEngineState()
    }

    fun setRemoteModel(value: String) {
        val provider = currentProvider() ?: return
        prefs.setModelName(provider, value)
        refreshEngineState()
    }

    fun setRemoteBaseUrl(value: String) {
        val provider = currentProvider() ?: return
        prefs.setBaseUrl(provider, value)
        refreshEngineState()
    }

    fun resetRemoteConfig() {
        val provider = currentProvider() ?: return
        prefs.setApiKey(provider, "")
        prefs.setBaseUrl(provider, provider.defaultBaseUrl)
        prefs.setModelName(provider, provider.defaultModel)
        refreshEngineState()
        _state.update { it.copy(status = "已恢复 ${provider.label} 默认配置") }
    }

    fun setWebToolsEnabled(enabled: Boolean) {
        prefs.webToolsEnabled = enabled
        _state.update { it.copy(webToolsEnabled = enabled) }
    }

    fun setPhoneToolsEnabled(enabled: Boolean) {
        prefs.phoneToolsEnabled = enabled
        refreshSystemState()
        _state.update { it.copy(phoneToolsEnabled = enabled) }
    }

    fun refreshSystemState() {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                accessibilityEnabled = PhoneControl.serviceReady(),
                overlayEnabled = PhoneControl.overlayAllowed(app),
            )
        }
    }

    // ---------------- models ----------------

    fun refreshModels() {
        _state.update { it.copy(installedModels = repository.installed()) }
        refreshEngineState()
    }

    fun selectModel(model: LocalModel) {
        if (model.path == prefs.modelPath && _state.value.modelLoaded) return
        prefs.modelPath = model.path
        needPrime = true
        _state.update { it.copy(selectedModelPath = model.path) }
        loadModel(model)
    }

    fun loadModel(model: LocalModel) {
        if (_state.value.modelLoading) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    modelLoading = true,
                    modelLoaded = false,
                    modelName = model.displayName,
                    status = "正在加载模型，请稍候…",
                    error = null,
                )
            }
            val code = engine.loadModel(model.path, prefs.contextSize, prefs.threads)
            needPrime = true
            if (code == 0) {
                prefs.modelPath = model.path
                _state.update {
                    it.copy(
                        modelLoading = false,
                        modelLoaded = true,
                        modelInfo = engine.modelInfo,
                        selectedModelPath = model.path,
                        status = null,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        modelLoading = false,
                        modelLoaded = false,
                        error = "模型加载失败（错误码 $code）。请确认内存充足，或换更小的模型。",
                        status = null,
                    )
                }
            }
            refreshEngineState()
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.unload()
            needPrime = true
            _state.update { it.copy(modelLoaded = false, modelInfo = "", status = "模型已卸载") }
            refreshEngineState()
        }
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            if (_state.value.selectedModelPath == model.path) {
                engine.unload()
                prefs.modelPath = null
                _state.update { it.copy(modelLoaded = false, modelInfo = "", selectedModelPath = null) }
                refreshEngineState()
            }
            repository.delete(model)
            removeDownloadRecord(model.path)
            refreshModels()
            _state.update { it.copy(status = "已删除 ${model.displayName}") }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(status = "正在导入模型…", error = null) }
            runCatching {
                repository.importFromUri(uri) { copied, total ->
                    _state.update { current ->
                        current.copy(
                            downloads = current.downloads + ("import" to DownloadState("import", copied, total)),
                        )
                    }
                }
            }.onSuccess { model ->
                _state.update { it.copy(downloads = it.downloads - "import") }
                refreshModels()
                addDownloadRecord("import-${System.currentTimeMillis()}", model.file.name, model)
                selectModel(model)
                _state.update { it.copy(status = "模型导入成功") }
            }.onFailure { error ->
                _state.update {
                    it.copy(downloads = it.downloads - "import", error = error.message ?: "导入失败")
                }
            }
        }
    }

    fun downloadModel(remote: RemoteModel) {
        if (_state.value.downloads[remote.id]?.active == true) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    downloads = it.downloads + (remote.id to DownloadState(remote.id, 0, remote.sizeBytes)),
                    error = null,
                )
            }
            runCatching {
                repository.download(remote) { copied, total ->
                    _state.update { current ->
                        current.copy(
                            downloads = current.downloads + (remote.id to DownloadState(remote.id, copied, total)),
                        )
                    }
                }
            }.onSuccess { model ->
                refreshModels()
                addDownloadRecord(remote.id, remote.name, model)
                _state.update {
                    it.copy(
                        downloads = it.downloads - remote.id,
                        status = "${remote.name} 下载完成，正在加载…",
                    )
                }
                loadModel(model)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        downloads = it.downloads - remote.id,
                        error = "下载失败：${error.message}",
                    )
                }
            }
        }
    }

    // ---------------- downloads ----------------

    fun loadDownloaded(record: DownloadRecord) {
        val file = File(record.path)
        if (!file.exists()) {
            _state.update { it.copy(error = "模型文件已不存在：${record.path}") }
            removeDownloadRecord(record.path)
            return
        }
        prefs.engine = "local"
        refreshEngineState()
        _state.update { it.copy(terminalLines = terminalStore.load("local")) }
        loadModel(LocalModel(file, record.name, file.length()))
    }

    fun deleteDownloaded(record: DownloadRecord) {
        val file = File(record.path)
        if (file.exists()) {
            deleteModel(LocalModel(file, record.name, file.length()))
        } else {
            removeDownloadRecord(record.path)
            _state.update { it.copy(status = "已移除下载记录") }
        }
    }

    private fun addDownloadRecord(id: String, name: String, model: LocalModel) {
        val record = DownloadRecord(
            id = id,
            name = name,
            path = model.path,
            sizeBytes = model.sizeBytes,
            time = System.currentTimeMillis(),
        )
        val history = (prefs.downloadHistory.filterNot { it.path == model.path } + record).takeLast(50)
        prefs.downloadHistory = history
        _state.update { it.copy(downloadHistory = history) }
    }

    private fun removeDownloadRecord(path: String) {
        val history = prefs.downloadHistory.filterNot { it.path == path }
        prefs.downloadHistory = history
        _state.update { it.copy(downloadHistory = history) }
    }

    // ---------------- terminal & workspace ----------------

    fun runTerminal(command: String) {
        val value = command.trim()
        if (value.isEmpty() || _state.value.terminalBusy) return
        appendTerminal("$ $value", "input")
        viewModelScope.launch {
            _state.update { it.copy(terminalBusy = true) }
            val result = withContext(Dispatchers.IO) {
                ShellRunner.run(value, _state.value.workingDir)
            }
            appendTerminal(result.output, if (result.ok) "output" else "error")
            _state.update { it.copy(terminalBusy = false) }
            refreshWorkspace(_state.value.workspacePath)
        }
    }

    fun clearTerminal() {
        terminalStore.clear(prefs.engine)
        _state.update { it.copy(terminalLines = emptyList()) }
    }

    private fun appendTerminal(text: String, kind: String) {
        if (text.isBlank()) return
        val engineId = prefs.engine
        val lines = (_state.value.terminalLines + TerminalLine(text, kind, System.currentTimeMillis()))
            .takeLast(300)
        _state.update { it.copy(terminalLines = lines) }
        terminalStore.save(engineId, lines)
    }

    fun setWorkingDir(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "工作目录不能为空") }
            return
        }
        val dir = File(trimmed)
        if (!ShellRunner.ensureDir(trimmed)) {
            _state.update { it.copy(error = "无法创建目录：$trimmed（请检查权限或换个路径）") }
            return
        }
        prefs.workingDir = dir.absolutePath
        _state.update {
            it.copy(
                workingDir = dir.absolutePath,
                workspacePath = dir.absolutePath,
                status = "工作目录已设置为 ${dir.absolutePath}",
            )
        }
        appendTerminal("工作目录已切换到 ${dir.absolutePath}", "info")
        refreshWorkspace(dir.absolutePath)
    }

    fun refreshWorkspace(path: String? = null) {
        val root = _state.value.workingDir
        val requested = path?.takeIf { it.isNotBlank() } ?: _state.value.workspacePath.ifBlank { root }
        val target = if (root.isBlank() || !requested.startsWith(root)) root else requested
        viewModelScope.launch {
            _state.update { it.copy(workspaceLoading = true, workspacePath = target) }
            val files = withContext(Dispatchers.IO) { ShellRunner.listDir(target) }
            _state.update { it.copy(workspaceLoading = false, workspaceFiles = files) }
        }
    }

    fun openWorkspaceFile(path: String) {
        val file = File(path)
        if (!file.isFile) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    previewPath = file.absolutePath,
                    previewTitle = file.name,
                    previewText = "正在解析…",
                    previewEntries = emptyList(),
                    previewLoading = true,
                )
            }
            runCatching {
                val name = file.name.lowercase()
                when {
                    name.endsWith(".zip") -> {
                        val entries = parser.listArchive(file)
                        val firstText = entries.firstOrNull()?.let { entry ->
                            runCatching {
                                parser.readArchiveEntry(file, entry.path).take(2000)
                            }.getOrDefault("")
                        }.orEmpty()
                        entries to firstText
                    }
                    name.endsWith(".rar") || name.endsWith(".7z") -> {
                        emptyList<DocEntry>() to "暂不支持直接读取 rar / 7z，请先解压成 zip 后再查看。"
                    }
                    else -> {
                        val doc = parser.parseFile(file)
                        emptyList<DocEntry>() to doc.text.take(3000)
                    }
                }
            }.onSuccess { (entries, text) ->
                _state.update {
                    it.copy(
                        previewEntries = entries,
                        previewText = text.ifBlank { "压缩包内没有可预览的文本，请点上方文件查看" },
                        previewLoading = false,
                    )
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        previewText = "解析失败：${failure.message ?: "未知错误"}",
                        previewLoading = false,
                    )
                }
            }
        }
    }

    fun readArchiveEntry(entryName: String) {
        val path = _state.value.previewPath
        if (path.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(previewLoading = true) }
            runCatching {
                parser.readArchiveEntry(File(path), entryName).take(4000)
            }.onSuccess { text ->
                _state.update {
                    it.copy(previewText = "【$entryName】\n\n$text", previewLoading = false)
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(previewText = "读取失败：${failure.message ?: "未知错误"}", previewLoading = false)
                }
            }
        }
    }

    fun closePreview() {
        _state.update {
            it.copy(
                previewPath = "",
                previewTitle = null,
                previewText = "",
                previewEntries = emptyList(),
                previewLoading = false,
            )
        }
    }

    // ---------------- files ----------------

    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(docBusy = true, status = "正在解析文件…", error = null) }
            val parsed = ArrayList<ParsedDoc>()
            for (uri in uris) {
                runCatching { parser.parse(uri) }
                    .onSuccess { parsed.add(it) }
                    .onFailure { failure ->
                        _state.update { it.copy(error = "解析失败：${failure.message}") }
                    }
            }
            _state.update { current ->
                current.copy(
                    docs = current.docs + parsed,
                    docBusy = false,
                    status = if (parsed.isNotEmpty()) "已导入 ${parsed.size} 个文件" else null,
                )
            }
        }
    }

    fun removeDoc(doc: ParsedDoc) {
        _state.update { it.copy(docs = it.docs.filterNot { item -> item.id == doc.id }) }
    }

    fun summarizeDoc(doc: ParsedDoc) {
        sendPrompt(
            display = "总结《${doc.name}》",
            modelText = Prompts.summarize(doc.name, TextTools.takeEvenly(doc.text, 4200)),
        )
    }

    fun keyPointsOf(doc: ParsedDoc) {
        sendPrompt(
            display = "提取《${doc.name}》的要点",
            modelText = Prompts.keyPoints(doc.name, TextTools.takeEvenly(doc.text, 4200)),
        )
    }

    fun translateDoc(doc: ParsedDoc, toChinese: Boolean) {
        sendPrompt(
            display = if (toChinese) "把《${doc.name}》翻译成中文" else "把《${doc.name}》翻译成英文",
            modelText = Prompts.translate(doc.name, TextTools.takeEvenly(doc.text, 4200), toChinese),
        )
    }

    // ---------------- chat ----------------

    fun send(text: String) {
        if (text.isBlank()) return
        val current = _state.value
        if (current.generating) return
        if (!current.engineReady) {
            _state.update {
                it.copy(error = if (current.engineId == "local") "还没有加载本地模型，请先到“模型”页加载" else "请先在设置中填写在线模型的 API Key")
            }
            return
        }
        val history = current.messages.map { StoredMessage(it.role, it.content) }
        val url = if (current.webToolsEnabled) WebFetcher.firstUrl(text) else null
        if (url == null) {
            val modelText = buildModelText(text, current.docs)
            startGeneration(text, modelText, history)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(generating = true, status = "正在读取网页：$url", error = null) }
            val page = runCatching { withContext(Dispatchers.IO) { WebFetcher.fetch(url) } }.getOrElse { null }
            _state.update {
                it.copy(
                    generating = false,
                    status = null,
                    error = if (page == null) "网页读取失败，将仅根据你的文字回答" else null,
                )
            }
            val context = buildModelText(text, current.docs)
            val modelText = if (page.isNullOrBlank()) context else {
                "$context\n\n【用户消息里的网页内容：$url】\n${page.take(4000)}"
            }
            startGeneration(text, modelText, history)
        }
    }

    private fun sendPrompt(display: String, modelText: String) {
        val current = _state.value
        if (!current.engineReady) {
            _state.update {
                it.copy(error = if (current.engineId == "local") "还没有加载本地模型，请先到“模型”页加载" else "请先在设置中填写在线模型的 API Key")
            }
            return
        }
        if (current.generating) return
        val history = current.messages.map { StoredMessage(it.role, it.content) }
        startGeneration(display, modelText, history)
    }

    private fun buildModelText(userText: String, docs: List<ParsedDoc>): String {
        if (docs.isEmpty()) return userText
        val context = TextTools.retrieve(userText, docs.joinToString("\n\n") { it.text }, 3000)
        if (context.isBlank()) return userText
        return Prompts.docAnswer(userText, context)
    }

    private fun systemPrompt(): String {
        val base = Prompts.system(_state.value.workMode)
        val web = _state.value.webToolsEnabled
        val phone = _state.value.phoneToolsEnabled
        return base + "\n\n" + ToolProtocol.PROMPT +
            (if (web) "\n\n（联网查看网页已开启）" else "\n\n（联网查看网页已关闭）") +
            (if (phone) "\n（手机操控已开启）" else "\n（手机操控已关闭）") +
            "\n（终端工作目录：${_state.value.workingDir}）"
    }

    private fun startGeneration(
        displayText: String,
        modelText: String,
        history: List<StoredMessage>,
        allowRetry: Boolean = true,
    ) {
        val answerId = UUID.randomUUID().toString()
        _state.update { current ->
            current.copy(
                messages = current.messages + UiMessage(UUID.randomUUID().toString(), "user", displayText) +
                    UiMessage(answerId, "assistant", "", streaming = true),
                generating = true,
                error = null,
                status = null,
            )
        }
        PhoneControl.reset()
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            try {
                if (currentProvider() == null) {
                    runLocal(answerId, modelText, history, allowRetry)
                } else {
                    runRemote(answerId, modelText, history)
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                updateAnswer(answerId, "生成中断：${t.message ?: "未知错误"}", streaming = false)
                _state.update { it.copy(generating = false, error = t.message) }
            } finally {
                QuickStopService.stop(getApplication())
                _state.update { it.copy(toolRunning = false, status = null) }
                sendJob = null
            }
        }
    }

    // ---------------- local engine ----------------

    private suspend fun runLocal(
        answerId: String,
        modelText: String,
        history: List<StoredMessage>,
        allowRetry: Boolean,
    ) {
        primeIfNeeded(history)
        val visible = StringBuilder()
        var userText = modelText
        var retryAllowed = allowRetry
        for (round in 0..MAX_TOOL_ROUNDS) {
            val buffer = StringBuilder()
            var lastFlush = 0L
            var result = engine.generate(userText, prefs.maxTokens, prefs.temperature) { piece ->
                buffer.append(piece)
                val now = System.currentTimeMillis()
                if (now - lastFlush > 80) {
                    lastFlush = now
                    showProgress(answerId, visible.toString(), buffer.toString())
                }
            }

            if (result is GenerateResult.Failed && result.code == 2 && retryAllowed) {
                val shorter = history.takeLast(4)
                _state.update { it.copy(status = "上下文已满，正在压缩历史后重试…") }
                buffer.clear()
                primeWithRetry(shorter)
                retryAllowed = false
                result = engine.generate(userText, prefs.maxTokens, prefs.temperature) { piece ->
                    buffer.append(piece)
                    val now = System.currentTimeMillis()
                    if (now - lastFlush > 80) {
                        lastFlush = now
                        showProgress(answerId, visible.toString(), buffer.toString())
                    }
                }
            }

            if (result is GenerateResult.Failed) {
                val message = when (result.code) {
                    2 -> "内容超出模型上下文，请新建对话或减少资料长度。"
                    3 -> "推理失败，请尝试重新加载模型。"
                    else -> "生成失败（错误码 ${result.code}）。"
                }
                val text = (visible.toString() + ToolProtocol.strip(buffer.toString(), null)).trim()
                updateAnswer(answerId, text.ifBlank { message }, streaming = false)
                _state.update { it.copy(generating = false, error = message) }
                return
            }

            val full = buffer.toString()
            val call = ToolProtocol.parse(full)
            visible.append(ToolProtocol.strip(full, call))
            updateAnswer(answerId, visible.toString(), streaming = true)

            if (call == null || PhoneControl.aborted || round == MAX_TOOL_ROUNDS) break

            val resultText = executeTool(call, answerId)
            if (resultText == null) break
            visible.append("\n\n> ${ToolProtocol.describe(call)}：${resultText.take(300)}\n\n")
            updateAnswer(answerId, visible.toString(), streaming = true)
            userText = "工具「${call.name}」执行结果：\n$resultText\n\n请基于以上结果继续完成用户请求，不要再重复调用相同工具。"
        }

        val finalText = visible.toString().trim().ifBlank { "（没有生成内容）" } +
            if (PhoneControl.aborted) "\n\n（已被紧急停止）" else ""
        updateAnswer(answerId, finalText, streaming = false)
        _state.update { it.copy(generating = false, status = null) }
        persist()
    }

    private suspend fun primeIfNeeded(history: List<StoredMessage>) {
        if (!needPrime) return
        _state.update { it.copy(status = "正在恢复对话上下文…") }
        primeWithRetry(history)
        needPrime = false
        _state.update { it.copy(status = null) }
    }

    private suspend fun primeWithRetry(history: List<StoredMessage>) {
        var current = history
        for (attempt in 0..2) {
            val pairs = current.map { it.role to it.content }
            val code = engine.prime(systemPrompt(), pairs)
            if (code == 0) return
            if (code == 2) {
                current = when (attempt) {
                    0 -> current.takeLast(6)
                    1 -> current.takeLast(2)
                    else -> emptyList()
                }
            } else {
                return
            }
        }
    }

    // ---------------- remote engine --------------

    private suspend fun runRemote(
        answerId: String,
        modelText: String,
        history: List<StoredMessage>,
    ) {
        val provider = currentProvider() ?: return
        val config = prefs.config(provider)
        if (!config.ready) {
            updateAnswer(answerId, "请先在设置里填写 ${provider.label} 的 API Key", streaming = false)
            _state.update { it.copy(generating = false) }
            return
        }

        val messages = ArrayList<ChatMsg>()
        messages += ChatMsg("system", systemPrompt())
        history.forEach { messages += ChatMsg(it.role, it.content) }
        messages += ChatMsg("user", modelText)

        val visible = StringBuilder()
        for (round in 0..MAX_TOOL_ROUNDS) {
            val buffer = StringBuilder()
            var lastFlush = 0L
            val answer = try {
                client.stream(
                    RemoteRequest(
                        baseUrl = config.baseUrl,
                        apiKey = config.apiKey,
                        model = config.model,
                        messages = messages.toList(),
                        temperature = prefs.temperature,
                        maxTokens = prefs.maxTokens,
                    )
                ) { piece ->
                    buffer.append(piece)
                    val now = System.currentTimeMillis()
                    if (now - lastFlush > 80) {
                        lastFlush = now
                        showProgress(answerId, visible.toString(), buffer.toString())
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (PhoneControl.aborted) break
                val message = "请求失败：${e.message ?: "网络错误"}"
                updateAnswer(answerId, (visible.toString() + message).trim(), streaming = false)
                _state.update { it.copy(generating = false, error = message) }
                return
            }

            val call = ToolProtocol.parse(answer)
            visible.append(ToolProtocol.strip(answer, call))
            updateAnswer(answerId, visible.toString(), streaming = true)

            if (call == null || PhoneControl.aborted || round == MAX_TOOL_ROUNDS) break

            val resultText = executeTool(call, answerId)
            if (resultText == null) break
            visible.append("\n\n> ${ToolProtocol.describe(call)}：${resultText.take(300)}\n\n")
            updateAnswer(answerId, visible.toString(), streaming = true)

            messages += ChatMsg("assistant", answer)
            messages += ChatMsg(
                "user",
                "工具「${call.name}」执行结果：\n$resultText\n\n请基于以上结果继续完成用户请求，不要再重复调用相同工具。",
            )
        }

        val finalText = visible.toString().trim().ifBlank { "（没有生成内容）" } +
            if (PhoneControl.aborted) "\n\n（已被紧急停止）" else ""
        updateAnswer(answerId, finalText, streaming = false)
        _state.update { it.copy(generating = false, status = null) }
        persist()
    }

    private fun showProgress(answerId: String, visible: String, buffer: String) {
        val partial = ToolProtocol.strip(buffer, ToolProtocol.parse(buffer))
        updateAnswer(answerId, (visible + partial).trimStart(), streaming = true)
    }

    /**
     * 执行工具；返回 null 表示中止后续轮次。
     */
    private suspend fun executeTool(call: ToolCall, answerId: String): String? {
        val app = getApplication<Application>()
        if (call.name == "shell") {
            val command = ToolProtocol.arg(call, "cmd")
            if (command.isBlank()) return "shell 缺少 cmd 参数"
            appendTerminal("$ $command", "input")
            _state.update { it.copy(status = "执行终端命令：${command.take(40)}…") }
            val result = withContext(Dispatchers.IO) {
                ShellRunner.run(command, _state.value.workingDir)
            }
            appendTerminal(result.output, if (result.ok) "output" else "error")
            _state.update { it.copy(status = null) }
            refreshWorkspace(_state.value.workspacePath)
            return "命令已执行（${if (result.ok) "成功" else "失败"}），工作目录 ${_state.value.workingDir}\n输出：\n${result.output.take(3000)}"
        }

        val phoneAction = PhoneControl.isPhoneAction(call.name)
        _state.update {
            it.copy(
                status = ToolProtocol.describe(call) + "…",
                toolRunning = phoneAction,
            )
        }
        if (phoneAction) {
            if (!PhoneControl.serviceReady()) {
                _state.update { it.copy(status = "无障碍服务未开启，无法操控手机") }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(app, "请先在设置中开启无障碍服务", android.widget.Toast.LENGTH_LONG).show()
                }
                return "无障碍服务未开启，请用户在设置中开启“本地AI 辅助操作”后重试"
            }
            if (!PhoneControl.overlayAllowed(app)) {
                _state.update { it.copy(status = "提示：未授予悬浮窗权限，紧急关闭按钮可能不显示") }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(app, "建议在设置中授予悬浮窗权限，以便随时紧急关闭", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            QuickStopService.start(app)
        }
        val result = withContext(Dispatchers.IO) {
            ToolProtocol.execute(
                app,
                call,
                webEnabled = _state.value.webToolsEnabled,
                phoneEnabled = _state.value.phoneToolsEnabled,
            )
        }
        if (phoneAction) {
            QuickStopService.stop(app)
            _state.update { it.copy(toolRunning = false) }
        }
        _state.update { it.copy(status = null) }
        return result
    }

    private fun updateAnswer(id: String, text: String, streaming: Boolean) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id == id) message.copy(content = text, streaming = streaming) else message
                }
            )
        }
    }

    private fun persist() {
        val messages = _state.value.messages
            .filterNot { it.streaming }
            .map { StoredMessage(it.role, it.content) }
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == "user" }?.content?.take(20) ?: "新对话"
        session = session.copy(title = title, updatedAt = System.currentTimeMillis(), messages = messages)
        chatStore.save(session)
        prefs.lastSessionId = session.id
    }

    fun clearMessages() = newChat()

    // ---------------- settings ----------------

    fun setTemperature(value: Float) {
        prefs.temperature = value
        _state.update { it.copy(temperature = value) }
    }

    fun setMaxTokens(value: Int) {
        prefs.maxTokens = value
        _state.update { it.copy(maxTokens = value) }
    }

    fun setThreads(value: Int) {
        prefs.threads = value
        _state.update { it.copy(threads = value) }
        reloadIfLoaded()
    }

    fun setContextSize(value: Int) {
        prefs.contextSize = value
        _state.update { it.copy(contextSize = value) }
        reloadIfLoaded()
    }

    fun setPrimaryColor(value: Long) {
        prefs.primaryColor = value
        _state.update { it.copy(primaryColor = value) }
    }

    fun setAccentColor(value: Long) {
        prefs.accentColor = value
        _state.update { it.copy(accentColor = value) }
    }

    fun setGlassOpacity(value: Float) {
        prefs.glassOpacity = value
        _state.update { it.copy(glassOpacity = value) }
    }

    fun setWallpaperPath(value: String?) {
        prefs.wallpaperPath = value
        _state.update { it.copy(wallpaperPath = value) }
    }

    fun setWallpaperBlur(value: Int) {
        prefs.wallpaperBlur = value
        _state.update { it.copy(wallpaperBlur = value) }
    }

    private fun reloadIfLoaded() {
        val path = _state.value.selectedModelPath ?: return
        if (!_state.value.modelLoaded) return
        val file = File(path)
        if (file.exists()) {
            needPrime = true
            loadModel(LocalModel(file, file.name.removeSuffix(".gguf"), file.length()))
        }
    }

    fun contextUsage(): Pair<Int, Int> {
        if (!_state.value.modelLoaded) return 0 to 0
        return engine.contextUsed() to engine.contextSize()
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 4
    }
}
