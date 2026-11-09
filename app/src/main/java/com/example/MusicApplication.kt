package com.example

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.util.AudioCoverFetcher
import com.example.util.AudioCoverKeyer
import java.io.File

class MusicApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val msg = "Muzix 0.9 Crash on thread '${thread.name}':\n${throwable.stackTraceToString()}"
                Log.e("MuzixApp", msg)
                // краш-лог
                File(filesDir, "crash.log").writeText(msg)
                // копия в Downloads
                val publicDir = getExternalFilesDir(null) ?: filesDir
                File(publicDir, "muzix_crash.txt").writeText(msg)
                val downloadFile = File("/sdcard/Download/muzix_crash.txt")
                downloadFile.writeText(msg)
            } catch (e: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(AudioCoverKeyer())
                add(AudioCoverFetcher.Factory(this@MusicApplication))
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
