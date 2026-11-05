package com.buxingjie.localai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class GenerateResult {
    data object Success : GenerateResult()
    data class Failed(val code: Int) : GenerateResult()
}

class LlamaEngine {

    private val mutex = Mutex()

    var loaded: Boolean = false
        private set

    var modelInfo: String = ""
        private set

    var modelPath: String? = null
        private set

    suspend fun loadModel(path: String, contextSize: Int, threads: Int): Int =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                NativeLlama.nativeInit()
                loaded = false
                val code = NativeLlama.nativeLoadModel(path, contextSize, threads, 0)
                loaded = code == 0
                modelPath = if (loaded) path else null
                modelInfo = if (loaded) NativeLlama.nativeModelInfo() else ""
                code
            }
        }

    suspend fun prime(systemPrompt: String, history: List<Pair<String, String>>): Int =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!loaded) return@withLock 1
                val reset = NativeLlama.nativeReset(systemPrompt)
                if (reset != 0) return@withLock reset
                for ((role, content) in history) {
                    val code = NativeLlama.nativeAppend(role, content)
                    if (code != 0) return@withLock code
                }
                0
            }
        }

    suspend fun generate(
        text: String,
        maxTokens: Int,
        temperature: Float,
        onToken: (String) -> Unit,
    ): GenerateResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!loaded) return@withLock GenerateResult.Failed(1)
            val code = NativeLlama.nativeSendUser(
                text = text,
                nPredict = maxTokens,
                temperature = temperature,
                topP = 0.9f,
                topK = 40f,
                minP = 0.05f,
                repeatPenalty = 1.1f,
            )
            if (code != 0) return@withLock GenerateResult.Failed(code)

            while (true) {
                val piece = NativeLlama.nativeNextToken() ?: break
                if (piece.isNotEmpty()) onToken(piece)
            }
            GenerateResult.Success
        }
    }

    fun cancel() = NativeLlama.nativeCancel()

    suspend fun unload() = withContext(Dispatchers.Default) {
        mutex.withLock {
            NativeLlama.nativeUnload()
            loaded = false
            modelPath = null
            modelInfo = ""
        }
    }

    fun contextUsed(): Int = NativeLlama.nativeContextUsed()

    fun contextSize(): Int = NativeLlama.nativeContextSize()
}
