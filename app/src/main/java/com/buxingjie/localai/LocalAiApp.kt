package com.buxingjie.localai

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class LocalAiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { PDFBoxResourceLoader.init(this) }
    }
}
