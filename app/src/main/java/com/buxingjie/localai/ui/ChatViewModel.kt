package com.buxingjie.localai.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buxingjie.localai.data.AiProvider
import com.buxingjie.localai.data.ChatSession
import com.buxingjie.localai.data.ChatStore
import com.buxingjie.localai.data.LocalModel
import com.buxingjie.localai.data.ModelRepository
import com.buxingjie.localai.data.Prefs
import com.buxingjie.localai.data.RemoteModel
import com.buxingjie.localai.data.StoredMessage
import com.buxingjie.localai.doc.DocumentParser
import com.buxingjie.localai.doc.ParsedDoc
import com.buxingjie.localai.doc.TextTools
import com.buxingjie.localai.llm.GenerateResult
import com.buxingjie.localai.llm.LlamaEngine
import com.buxingjie.localai.llm.Prompts
import com.buxingjie.localai.net.ChatMsg
import com.buxingjie.localai.net.RemoteChatClient
import com.buxingjie.localai.net.RemoteRequest
import com.buxingjie.localai.service.QuickStopService
import com.buxingjie.localai.tools.PhoneControl
import com.buxingjie.localai.tools.ToolCall
import com.buxingjie.localai.tools.ToolProtocol
import com.buxingjie.localai.tools.WebFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(stopReceiver) }
        client.cancel()
        super.onCleared()
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
        return if (web || phone) {
            base + "\n\n" + ToolProtocol.PROMPT +
                (if (web) "\n\n（联网查看网页已开启）" else "\n\n（联网查看网页已关闭）") +
                (if (phone) "\n（手机操控已开启）" else "\n（手机操控已关闭）")
        } else {
            base
        }
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
