package com.buxingjie.localai.llm

object NativeLlama {

    init {
        System.loadLibrary("localai")
    }

    external fun nativeInit()

    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Int

    external fun nativeReset(systemPrompt: String): Int

    external fun nativeAppend(role: String, content: String): Int

    external fun nativeSendUser(
        text: String,
        nPredict: Int,
        temperature: Float,
        topP: Float,
        topK: Float,
        minP: Float,
        repeatPenalty: Float,
    ): Int

    external fun nativeNextToken(): String?

    external fun nativeCancel()

    external fun nativeContextUsed(): Int

    external fun nativeContextSize(): Int

    external fun nativeModelInfo(): String

    external fun nativeUnload()

    external fun nativeShutdown()

    external fun nativeSystemInfo(): String
}
