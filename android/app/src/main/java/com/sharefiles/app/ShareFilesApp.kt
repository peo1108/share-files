package com.sharefiles.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner

class ShareFilesApp : Application() {
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        state = AppState(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(state)
    }
}
