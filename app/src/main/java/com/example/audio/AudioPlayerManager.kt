package com.example.audio

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.example.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

enum class PlayerState {
    IDLE,
    PREPARING,
    PREPARED,
    PLAYING,
    PAUSED,
    STOPPED,
    ERROR
}

// фасад плеера
@OptIn(UnstableApi::class)
class AudioPlayerManager(
    private val context: Context,
    private val onProgress: (Long) -> Unit,
    private val onCompletion: () -> Unit,
    private val onPlaybackStarted: ((durationMs: Long) -> Unit)? = null,
    private val onPlayWhenReadyChanged: ((Boolean) -> Unit)? = null,
    private val onTrackTransition: ((Track) -> Unit)? = null,
    private val onPlaybackError: ((String) -> Unit)? = null
) {
    private val player: ExoPlayer = PlayerHub.player(context)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val knownTracks = HashMap<String, Track>()

    private var progressJob: Job? = null
    private var fadeJob: Job? = null

    private var equalizerEnabled = true
    private var viperDspState = ViperAudioDsp.State()
    private val equalizerBandLevels = IntArray(5) { 0 }
    private var legacyBassBoost: Short = 0
    private var virtualizerStrength: Short = 0
    private var reverbPreset: Short = 0
    private var volumeNormalize = false
    private var replayGainDb = 0f
    private var crossfadeDurationMs = 0
    private var startedNotified = false
    private var hadError = false

    var currentTrack: Track? = null
        private set

    @Volatile
    var state: PlayerState = PlayerState.IDLE
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                refreshState()
                onCompletion()
                return
            }
            if (playbackState == Player.STATE_READY) {
                if (player.isPlaying) startProgressTracker()
                if (!startedNotified) {
                    startedNotified = true
                    val duration = player.duration
                    if (duration > 0) {
                        try {
                            onPlaybackStarted?.invoke(duration)
                        } catch (e: Exception) {
                            Log.e(TAG, "onPlaybackStarted failed", e)
                        }
                    }
                }
            }
            refreshState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshState()
            if (isPlaying) startProgressTracker()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            refreshState()
            onPlayWhenReadyChanged?.invoke(playWhenReady)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val track = knownTracks[mediaItem.mediaId] ?: return
                currentTrack = track
                startedNotified = false
                scope.launch {
                    val index = player.currentMediaItemIndex
                    if (index > 0) player.removeMediaItems(0, index)
                    if (crossfadeDurationMs > 0) fadeIn(crossfadeDurationMs)
                    onTrackTransition?.invoke(track)
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            replayGainDb = extractReplayGain(tracks) ?: 0f
            pushDsp()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: ${error.errorCodeName}", error)
            hadError = true
            state = PlayerState.ERROR
            onPlaybackError?.invoke(error.errorCodeName)
        }
    }

    private var visualizerListener: ((ByteArray) -> Unit)? = null

    init {
        player.addListener(listener)
    }

    fun setVisualizerDataListener(listener: (ByteArray) -> Unit) {
        visualizerListener = listener
        PlayerHub.processor.fftListener = listener
    }

    fun setDucked(ducked: Boolean) {
        // фокус звука
    }

    private fun refreshState() {
        state = when {
            hadError && player.playbackState == Player.STATE_IDLE -> PlayerState.ERROR
            currentTrack == null && player.mediaItemCount == 0 -> if (state == PlayerState.STOPPED) PlayerState.STOPPED else PlayerState.IDLE
            player.playbackState == Player.STATE_BUFFERING -> if (player.isPlaying) PlayerState.PLAYING else PlayerState.PREPARING
            player.playbackState == Player.STATE_READY -> if (player.playWhenReady) PlayerState.PLAYING else PlayerState.PAUSED
            player.playbackState == Player.STATE_ENDED -> PlayerState.PAUSED
            else -> state
        }
    }

    private fun resolveUri(track: Track): Uri {
        val path = track.path.trim()
        if (path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://")) {
            return Uri.parse(path)
        }
        if (path.isNotEmpty()) {
            val file = File(path)
            if (file.exists() && file.canRead()) return Uri.fromFile(file)
        }
        val longId = track.id.toLongOrNull()
        if (longId != null && longId > 0 && !path.startsWith("/")) {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, longId)
        }
        if (path.isNotEmpty()) return Uri.fromFile(File(path))
        throw IllegalArgumentException("Empty audio path and invalid track ID for track: ${track.title}")
    }

    private fun buildItem(track: Track, artwork: ByteArray? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle("Muzix")
            .setIsPlayable(true)
        if (artwork != null) {
            metadata.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(resolveUri(track))
            .setMediaMetadata(metadata.build())
            .build()
    }

    fun playTrack(track: Track) = loadTrack(track, playNow = true)

    fun prepareTrack(track: Track) = loadTrack(track, playNow = false)

    private fun loadTrack(track: Track, playNow: Boolean) {
        fadeJob?.cancel()
        hadError = false
        startedNotified = false
        replayGainDb = 0f
        currentTrack = track
        val isNetwork = track.path.startsWith("http://") || track.path.startsWith("https://")
        PlayerHub.updateWakeMode(isNetwork)
        knownTracks.clear()
        knownTracks[track.id] = track
        try {
            player.volume = 1f
            player.setMediaItem(buildItem(track))
            player.prepare()
            player.playWhenReady = playNow
            if (playNow && crossfadeDurationMs > 0) fadeIn(crossfadeDurationMs)
            state = PlayerState.PREPARING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load track: ${track.title}", e)
            hadError = true
            state = PlayerState.ERROR
        }
    }

    // бесшовный переход
    fun setNextTrack(track: Track?) {
        try {
            val index = player.currentMediaItemIndex
            if (player.mediaItemCount == 0 || index < 0) return
            val tail = player.mediaItemCount - (index + 1)
            if (tail > 0) player.removeMediaItems(index + 1, player.mediaItemCount)
            if (track != null && track.id != currentTrack?.id) {
                knownTracks[track.id] = track
                player.addMediaItem(buildItem(track))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not preload next track", e)
        }
    }

    fun setRepeatOne(enabled: Boolean) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // пуш обложки
    fun updateNotificationArtwork(track: Track, bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            if (player.currentMediaItem?.mediaId != track.id) return
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val index = player.currentMediaItemIndex
            player.replaceMediaItem(index, buildItem(track, stream.toByteArray()))
        } catch (e: Exception) {
            Log.w(TAG, "Could not update notification artwork", e)
        }
    }

    var isAppInForeground: Boolean = true
        set(value) {
            field = value
            if (value) {
                if (player.isPlaying) startProgressTracker()
            } else {
                progressJob?.cancel()
            }
        }

    fun startProgressTracker() {
        if (!isAppInForeground) return
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && isAppInForeground && (player.isPlaying || player.playWhenReady)) {
                try {
                    onProgress(player.currentPosition)
                } catch (e: Exception) {
                    Log.w(TAG, "onProgress failed", e)
                }
                delay(150)
            }
        }
    }

    fun pause() {
        fadeJob?.cancel()
        player.pause()
        player.volume = 1f
    }

    fun resume(fallbackTrack: Track? = null) {
        val track = currentTrack ?: fallbackTrack ?: return
        fadeJob?.cancel()
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE || hadError) {
            playTrack(track)
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
        }
        if (crossfadeDurationMs > 0) {
            player.volume = 0f
            player.play()
            fadeIn(crossfadeDurationMs)
        } else {
            player.volume = 1f
            player.play()
        }
        startProgressTracker()
    }

    fun seekTo(positionMs: Long) {
        try {
            if (player.mediaItemCount > 0) player.seekTo(positionMs)
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed", e)
        }
        onProgress(positionMs)
    }

    fun stop() {
        fadeJob?.cancel()
        progressJob?.cancel()
        player.volume = 1f
        try {
            player.stop()
            player.clearMediaItems()
        } catch (e: Exception) {
            Log.w(TAG, "Stop failed", e)
        }
        knownTracks.clear()
        currentTrack = null
        state = PlayerState.STOPPED
    }

    fun release() {
        fadeJob?.cancel()
        progressJob?.cancel()
        player.removeListener(listener)
        PlayerHub.processor.fftListener = null
        scope.cancel()
    }

    private fun fadeIn(durationMs: Int) {
        fadeJob?.cancel()
        fadeJob = scope.launch { fadeVolume(0f, 1f, durationMs) }
    }

    private suspend fun fadeVolume(from: Float, to: Float, durationMs: Int) {
        val steps = 20
        val stepDelay = (durationMs / steps).toLong().coerceAtLeast(10L)
        for (i in 0..steps) {
            if (!scope.isActive) return
            player.volume = from + (to - from) * (i / steps.toFloat())
            delay(stepDelay)
        }
    }

    fun setCrossfadeDuration(durationMs: Int) {
        crossfadeDurationMs = durationMs
    }

    fun setAudioBufferSize(size: String) {
        // буферы экзоплеера
    }

    private fun pushDsp() {
        val extraBass = (legacyBassBoost.toFloat() / 1000f) * 9f
        PlayerHub.processor.update(
            state = viperDspState,
            userBandsDb = equalizerBandLevels,
            surround = virtualizerStrength.toInt(),
            reverbPreset = reverbPreset.toInt(),
            extraBassDb = if (viperDspState.enabled) extraBass else 0f,
            replayGainDb = if (volumeNormalize) replayGainDb else 0f
        )
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        equalizerEnabled = enabled
        viperDspState = viperDspState.copy(enabled = enabled)
        pushDsp()
    }

    fun isEqualizerEnabled(): Boolean = equalizerEnabled

    fun setBandLevel(band: Int, levelDb: Int) {
        if (band in equalizerBandLevels.indices) {
            equalizerBandLevels[band] = levelDb
            pushDsp()
        }
    }

    fun getBandLevels(): IntArray = equalizerBandLevels

    fun getEqualizerBandFrequencies(): List<Int> = listOf(60, 230, 910, 4000, 14000)

    fun getBandLevelRange(): Pair<Int, Int> = Pair(-15, 15)

    fun setBassBoostStrength(strength: Short) {
        legacyBassBoost = strength
        pushDsp()
    }

    fun getBassBoostStrength(): Short = legacyBassBoost

    fun setVirtualizerStrength(strength: Short) {
        virtualizerStrength = strength
        pushDsp()
    }

    fun getVirtualizerStrength(): Short = virtualizerStrength

    fun setReverbPreset(preset: Short) {
        reverbPreset = preset
        pushDsp()
    }

    fun getReverbPreset(): Short = reverbPreset

    fun setVolumeNormalize(enabled: Boolean) {
        volumeNormalize = enabled
        pushDsp()
    }

    fun setViperDspState(state: ViperAudioDsp.State) {
        viperDspState = state
        equalizerEnabled = state.enabled
        for (i in state.eqBandLevels.indices) {
            if (i in equalizerBandLevels.indices) {
                equalizerBandLevels[i] = state.eqBandLevels[i].toInt()
            }
        }
        virtualizerStrength = if (state.enabled) state.surroundStrength else 0
        reverbPreset = if (state.enabled) state.reverbPreset else 0
        pushDsp()
    }

    fun getViperDspState(): ViperAudioDsp.State = viperDspState

    fun applyAudioEffects() = pushDsp()

    private fun extractReplayGain(tracks: Tracks): Float? {
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                for (e in 0 until metadata.length()) {
                    val text: String? = when (val entry = metadata.get(e)) {
                        is VorbisComment ->
                            if (entry.key.equals("REPLAYGAIN_TRACK_GAIN", ignoreCase = true)) entry.value else null
                        is TextInformationFrame ->
                            if (entry.id == "TXXX" && entry.description.equals("REPLAYGAIN_TRACK_GAIN", ignoreCase = true)) {
                                entry.values.firstOrNull()
                            } else null
                        is InternalFrame ->
                            if (entry.description.equals("replaygain_track_gain", ignoreCase = true)) entry.text else null
                        else -> null
                    }
                    val parsed = text?.let { GAIN_REGEX.find(it)?.value?.toFloatOrNull() }
                    if (parsed != null) return parsed.coerceIn(-15f, 12f)
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AudioPlayerManager"
        private val GAIN_REGEX = Regex("-?\\d+(\\.\\d+)?")
    }
}
