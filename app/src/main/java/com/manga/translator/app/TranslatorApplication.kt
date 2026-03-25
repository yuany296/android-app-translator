package com.manga.translator.app

import android.app.Application

class TranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
