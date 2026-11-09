package com.example.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import java.io.File

// модель источника
data class AudioCover(val uriString: String)

// ключ кэша
class AudioCoverKeyer : Keyer<AudioCover> {
    override fun key(data: AudioCover, options: Options): String {
        val raw = data.uriString
        if (raw.isEmpty() || raw.startsWith("content://") || raw.startsWith("http")) {
            return "audio_cover_$raw"
        }
        val audioFile = File(raw)
        val audioMod = if (audioFile.exists()) audioFile.lastModified() else 0L
        val base = raw.substringBeforeLast('.')
        val sidecar = File("$base.jpg")
        val sidecarMod = if (sidecar.exists()) sidecar.lastModified() else 0L
        return "audio_cover_${raw}_${audioMod}_$sidecarMod"
    }
}

// загрузчик обложек Coil
class AudioCoverFetcher(
    private val context: Context,
    private val data: AudioCover,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val uriString = data.uriString
        if (uriString.isEmpty() || uriString.startsWith("http://") || uriString.startsWith("https://")) {
            return null
        }

        val uri = if (uriString.startsWith("content://")) Uri.parse(uriString) else null
        var bitmap: Bitmap? = null

        // путь из content-uri
        var filePath: String? = if (uri == null) uriString else null
        if (filePath == null && uri != null) {
            try {
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(0)
                        if (!path.isNullOrBlank()) filePath = path
                    }
                }
            } catch (_: Exception) {}
        }

        // файл рядом
        if (filePath != null) {
            try {
                val base = filePath!!.substringBeforeLast('.')
                val sidecar = File("$base.jpg")
                if (sidecar.exists() && sidecar.length() > 256) {
                    val decodeOptions = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    bitmap = BitmapFactory.decodeFile(sidecar.absolutePath, decodeOptions)
                }
            } catch (_: Exception) {
            }
        }

        // теги аудиофайла
        if (bitmap == null) {
            val retriever = MediaMetadataRetriever()
            try {
                if (filePath != null && File(filePath!!).exists()) {
                    retriever.setDataSource(filePath!!)
                } else if (uri != null) {
                    retriever.setDataSource(context, uri)
                } else {
                    retriever.setDataSource(uriString)
                }
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    val decodeOptions = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, decodeOptions)
                }
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        // поиск в MediaStore
        if (bitmap == null && uri != null) {
            try {
                var albumName: String? = null
                var albumId: Long = 0L
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToNext()) {
                        albumName = cursor.getString(0)
                        albumId = cursor.getLong(1)
                    }
                }
                val isGenericAlbum = albumName.isNullOrBlank() ||
                    albumName.equals("Music", ignoreCase = true) ||
                    albumName.equals("Download", ignoreCase = true) ||
                    albumName.equals("Downloads", ignoreCase = true) ||
                    albumName.equals("Muzix Downloads", ignoreCase = true) ||
                    albumName.equals("Unknown", ignoreCase = true) ||
                    albumName.startsWith("<unknown", ignoreCase = true)

                if (!isGenericAlbum && albumId > 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            bitmap = context.contentResolver.loadThumbnail(uri, Size(1024, 1024), null)
                        } catch (_: Exception) {}
                    }
                    if (bitmap == null) {
                        val albumArtUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        )
                        context.contentResolver.openInputStream(albumArtUri)?.use { stream ->
                            bitmap = BitmapFactory.decodeStream(stream)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return bitmap?.let {
            DrawableResult(
                drawable = BitmapDrawable(context.resources, it),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<AudioCover> {
        override fun create(data: AudioCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return AudioCoverFetcher(context, data, options)
        }
    }
}
