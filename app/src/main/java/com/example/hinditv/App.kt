package com.example.hinditv

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
import coil.memory.MemoryCache
import okhttp3.Call
import okhttp3.Request

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Settings.init(this)
        UserData.init(this)
        KeyMap.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try { imageLoader.memoryCache?.clear() } catch (e: Exception) { }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val app = this
        return ImageLoader.Builder(this)
            .callFactory(object : Call.Factory {
                override fun newCall(request: Request): Call = Net.client(app).newCall(request)
            })
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.12).build() }
            .crossfade(false)
            .build()
    }
}
