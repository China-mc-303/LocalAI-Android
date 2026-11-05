package com.buxingjie.localai.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = Prefs(application)
    private val repository = ModelRepository(application)
    private val chatStore = ChatStore(application)
    private val parser = DocumentParser(application)
    private val engine = LlamaEngine()

    private val _state = MutableStateFlow(
        ChatUiState(
            workMode = prefs.workMode,
            temperature = prefs.temperature,
            maxTokens = prefs.maxTokens,
            contextSize = prefs.contextSize,
            threads = prefs.threads,
            installedModels = repository.installed(),
            selectedModelPath = prefs.modelPath,
        )
    )
    val state = _state.asStateFlow()

    private var session: ChatSession = prefs.lastSessionId?.let { chatStore.load(it) } ?: chatStore.newSession()
    private var needPrime = true
    private var sendJob: Job? = null

    init {
        val stored = session.messages
        if (stored.isNotEmpty()) {
            _state.update {
                it.copy(messages = stored.map { message ->
                    UiMessage(UUID.randomUUID().toString(), message.role, message.content)
                })
            }
        }
        val path = prefs.modelPath
        if (path != null && File(path).exists()) {
            loadModel(LocalModel(File(path), File(path).name.removeSuffix(".gguf"), File(path).length()))
        } else {
            _state.update { it.copy(selectedModelPath = null) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun stop() {
        engine.cancel()
        _state.update { it.copy(status = "正在停止…") }
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

    // ---------------- models ----------------

    fun refreshModels() {
        _state.update { it.copy(installedModels = repository.installed()) }
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
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.unload()
            needPrime = true
            _state.update { it.copy(modelLoaded = false, modelInfo = "", status = "模型已卸载") }
        }
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            if (_state.value.selectedModelPath == model.path) {
                engine.unload()
                prefs.modelPath = null
                _state.update { it.copy(modelLoaded = false, modelInfo = "", selectedModelPath = null) }
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
        if (!current.modelLoaded) {
            _state.update { it.copy(error = "还没有加载模型，请先到“模型”页加载") }
            return
        }
        val history = current.messages.map { StoredMessage(it.role, it.content) }
        val modelText = buildModelText(text, current.docs)
        startGeneration(text, modelText, history)
    }

    private fun sendPrompt(display: String, modelText: String) {
        if (!_state.value.modelLoaded) {
            _state.update { it.copy(error = "还没有加载模型，请先到“模型”页加载") }
            return
        }
        if (_state.value.generating) return
        val history = _state.value.messages.map { StoredMessage(it.role, it.content) }
        startGeneration(display, modelText, history)
    }

    private fun buildModelText(userText: String, docs: List<ParsedDoc>): String {
        if (docs.isEmpty()) return userText
        val context = TextTools.retrieve(userText, docs.joinToString("\n\n") { it.text }, 3000)
        if (context.isBlank()) return userText
        return Prompts.docAnswer(userText, context)
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

        sendJob = viewModelScope.launch {
            val buffer = StringBuilder()
            var lastFlush = 0L
            try {
                primeIfNeeded(history)

                var result = engine.generate(modelText, prefs.maxTokens, prefs.temperature) { piece ->
                    buffer.append(piece)
                    val now = System.currentTimeMillis()
                    if (now - lastFlush > 80) {
                        lastFlush = now
                        updateAnswer(answerId, buffer.toString(), streaming = true)
                    }
                }

                if (result is GenerateResult.Failed && result.code == 2 && allowRetry) {
                    // 上下文不足：压缩历史后重试一次
                    val shorter = history.takeLast(4)
                    _state.update { it.copy(status = "上下文已满，正在压缩历史后重试…") }
                    buffer.clear()
                    updateAnswer(answerId, "", streaming = true)
                    primeWithRetry(shorter)
                    result = engine.generate(modelText, prefs.maxTokens, prefs.temperature) { piece ->
                        buffer.append(piece)
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 80) {
                            lastFlush = now
                            updateAnswer(answerId, buffer.toString(), streaming = true)
                        }
                    }
                }

                when (result) {
                    GenerateResult.Success -> {
                        val finalText = buffer.toString().ifBlank { "（没有生成内容）" }
                        updateAnswer(answerId, finalText, streaming = false)
                        _state.update { it.copy(generating = false, status = null) }
                        persist()
                    }
                    is GenerateResult.Failed -> {
                        val message = when (result.code) {
                            2 -> "内容超出模型上下文，请新建对话或减少资料长度。"
                            3 -> "推理失败，请尝试重新加载模型。"
                            else -> "生成失败（错误码 ${result.code}）。"
                        }
                        updateAnswer(answerId, message, streaming = false)
                        _state.update { it.copy(generating = false, error = message) }
                    }
                }
            } catch (t: Throwable) {
                updateAnswer(answerId, "生成中断：${t.message ?: "未知错误"}", streaming = false)
                _state.update { it.copy(generating = false, error = t.message) }
            } finally {
                sendJob = null
            }
        }
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
            val code = engine.prime(Prompts.system(_state.value.workMode), pairs)
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
}
