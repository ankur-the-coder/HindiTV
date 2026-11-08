package com.example.hinditv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.Call
import okhttp3.Request

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        UserData.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val app = this
        return ImageLoader.Builder(this)
            .callFactory(object : Call.Factory {
                override fun newCall(request: Request): Call = Net.client(app).newCall(request)
            })
            .crossfade(false)
            .build()
    }
}
