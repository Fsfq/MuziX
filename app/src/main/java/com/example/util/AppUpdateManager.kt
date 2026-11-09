package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// данные обновления
data class UpdateInfo(
    val versionName: String,
    val releaseTag: String,
    val changelog: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long
)

// состояния обновления
sealed class AppUpdateState {
    object Idle : AppUpdateState()
    object Checking : AppUpdateState()
    object UpToDate : AppUpdateState()
    data class Available(val info: UpdateInfo) : AppUpdateState()
    data class Downloading(val progress: Float) : AppUpdateState()
    data class ReadyToInstall(val file: File) : AppUpdateState()
    data class Error(val message: String) : AppUpdateState()
}

// менеджер обновлений
object AppUpdateManager {

    private const val GITHUB_API_URL = "https://api.github.com/repos/Fsfq/MuziX/releases/latest"

    // проверка версии
    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "MuziX-App")
            }
            if (conn.responseCode != 200) return@withContext null

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(jsonStr)

            val tagName = obj.optString("tag_name", "").trim()
            val cleanRemote = tagName.removePrefix("v").removePrefix("V").trim()
            val currentVersion = BuildConfig.VERSION_NAME.trim()

            if (!isNewer(cleanRemote, currentVersion)) {
                return@withContext null
            }

            val body = obj.optString("body", "Новая версия MuziX")
            val assets = obj.optJSONArray("assets") ?: return@withContext null

            val abis = Build.SUPPORTED_ABIS.toList()
            val isArm64 = abis.any { it.contains("arm64") }

            var bestUrl = ""
            var bestName = ""
            var bestSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)

                if (!name.endsWith(".apk", ignoreCase = true)) continue

                if (isArm64) {
                    if (name.contains("arm64", ignoreCase = true) || bestUrl.isEmpty()) {
                        bestUrl = downloadUrl
                        bestName = name
                        bestSize = size
                        if (name.contains("arm64", ignoreCase = true)) break
                    }
                } else {
                    if (name.contains("arm32", ignoreCase = true) || bestUrl.isEmpty()) {
                        bestUrl = downloadUrl
                        bestName = name
                        bestSize = size
                        if (name.contains("arm32", ignoreCase = true)) break
                    }
                }
            }

            if (bestUrl.isBlank()) return@withContext null

            UpdateInfo(
                versionName = cleanRemote,
                releaseTag = tagName,
                changelog = body,
                downloadUrl = bestUrl,
                fileName = bestName,
                sizeBytes = bestSize
            )
        } catch (_: Exception) {
            null
        }
    }

    // скачивание APK
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates")
            if (!dir.exists()) dir.mkdirs()
            val targetFile = File(dir, "MuziX_${info.versionName}.apk")

            val url = URL(info.downloadUrl)
            var conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MuziX-App")

            var redirectCount = 0
            while (conn.responseCode in 300..399 && redirectCount < 5) {
                val newUrl = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(newUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "MuziX-App")
                redirectCount++
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            onProgress(totalRead.toFloat() / totalBytes.toFloat())
                        }
                    }
                }
            }
            targetFile
        } catch (_: Exception) {
            null
        }
    }

    // установка APK
    fun installApk(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    // сравнение версий
    private fun isNewer(remote: String, current: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
