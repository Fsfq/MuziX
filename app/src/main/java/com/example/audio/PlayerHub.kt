package com.example.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

// синглтон плеера
@OptIn(UnstableApi::class)
object PlayerHub {

    interface CommandHandler {
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
        fun onToggleFavorite()
        fun onStop()
        fun onSeekTo(positionMs: Long) {}
    }

    data class IslandTrack(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L,
        val albumArtPath: String? = null
    )

    val currentIslandTrack = kotlinx.coroutines.flow.MutableStateFlow<IslandTrack?>(null)
    val isIslandPlaying = kotlinx.coroutines.flow.MutableStateFlow(false)
    val islandPositionMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val islandFft = kotlinx.coroutines.flow.MutableStateFlow(FloatArray(4) { 0.15f })

    fun seekTo(positionMs: Long) {
        commandHandler?.onSeekTo(positionMs) ?: player?.seekTo(positionMs)
    }

    val processor = FsFqXAudioProcessor()

    @Volatile
    var streamUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile
    var commandHandler: CommandHandler? = null

    @Volatile
    var favoriteState: Boolean = false

    @Volatile
    var layoutRefresh: (() -> Unit)? = null

    private var player: ExoPlayer? = null

    // текущий инстанс
    @Synchronized
    fun peek(): ExoPlayer? = player

    @Synchronized
    fun player(context: Context): ExoPlayer {
        player?.let { return it }
        val appContext = context.applicationContext
        val renderers = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(processor))
                .build()
        }
        val httpFactory = DataSource.Factory {
            DefaultHttpDataSource.Factory()
                .setUserAgent(streamUserAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .createDataSource()
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(appContext, httpFactory)
        )
        val created = ExoPlayer.Builder(appContext, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = created
        return created
    }

    fun updateWakeMode(isNetwork: Boolean) {
        player?.setWakeMode(if (isNetwork) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
    }

    fun refreshFavorite(isFavorite: Boolean) {
        favoriteState = isFavorite
        layoutRefresh?.invoke()
    }
}
