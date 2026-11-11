package com.example.ui

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainActivity
import com.example.audio.AudioPlayerManager
import com.example.audio.ViperAudioDsp
import com.example.audio.PlayerHub
import com.example.audio.PlaybackService
import com.example.audio.NativeTrackMatcher
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.content.ComponentName
import com.example.data.*
import com.example.ui.components.TrackEditDraft
import com.example.util.AppUpdateManager
import com.example.util.AppUpdateState
import com.example.util.UpdateInfo
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder

sealed class DownloadState {
    object Idle : DownloadState()
    data class Parsing(val url: String) : DownloadState()
    data class Downloading(val title: String, val progress: Int, val speed: String, val thumbnailUrl: String? = null) : DownloadState()
    data class Success(val track: Track) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

interface ProgressCallback {
    fun onProgress(percent: Int, speedBytesPerSec: Long, etaSeconds: Int)
}

data class YoutubeSearchResult(
    val id: String,
    val title: String,
    val url: String,
    val duration: Double,
    val uploader: String,
    val thumbnail: String = ""
)

sealed class YoutubeSearchState {
    object Idle : YoutubeSearchState()
    object Loading : YoutubeSearchState()
    data class Success(val results: List<YoutubeSearchResult>) : YoutubeSearchState()
    data class Error(val message: String) : YoutubeSearchState()
}

sealed class YtDlpUpdateState {
    object Idle : YtDlpUpdateState()
    object Updating : YtDlpUpdateState()
    data class Success(val message: String) : YtDlpUpdateState()
    data class Error(val message: String) : YtDlpUpdateState()
}

sealed class ServerConnectionState {
    object Checking : ServerConnectionState()
    data class Connected(val version: String) : ServerConnectionState()
    data class Error(val message: String) : ServerConnectionState()
}

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val version: String, val changelog: String, val downloadUrl: String) : UpdateState()
    object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}

data class TrackPalette(
    val dominant: Int = 0xFF8B5CF6.toInt(),
    val vibrant: Int = 0xFF8B5CF6.toInt(),
    val secondary: Int = 0xFF06B6D4.toInt(),
    val accent: Int = 0xFFEC4899.toInt(),
    val darkSurface: Int = 0xFF121216.toInt()
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val db = MusicDatabase.getDatabase(application)
    private val repository = MusicRepository(db.musicDao())
    private val prefs = application.getSharedPreferences("musicx_settings", Context.MODE_PRIVATE)
    private var mediaControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private val CHANNEL_ID = "muzix_playback_channel_v6"
    private val NOTIFICATION_ID = 1001

    private val commandHandler = object : PlayerHub.CommandHandler {
        override fun onPlayPause() = togglePlayPause()
        override fun onNext() {
            if (_isWaveActive.value || _currentTrack.value?.id?.startsWith("wave_") == true) {
                _isWaveActive.value = true
                skipNextWaveTrack(getApplication())
            } else {
                playNextTrack()
            }
        }
        override fun onPrevious() = playPreviousTrack()
        override fun onToggleFavorite() {
            _currentTrack.value?.let { toggleFavorite(it.id) }
        }
        override fun onStop() = stopPlayback()
        override fun onSeekTo(positionMs: Long) = seekTo(positionMs)
    }

    private val headsetPlugReceiver = object : android.content.BroadcastReceiver() {
        private var isFirstRun = true
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                if (state == 1) { // наушники
                    if (isFirstRun) {
                        isFirstRun = false
                        return
                    }
                    if (_autoplayHeadset.value && !_isPlaying.value) {
                        togglePlayPause()
                    }
                }
            }
        }
    }

    // фокус звука
    private fun requestAudioFocus(): Boolean = true

    private fun abandonAudioFocus() {}

    private val _serverConnectionState = MutableStateFlow<ServerConnectionState>(ServerConnectionState.Checking)
    val serverConnectionState: StateFlow<ServerConnectionState> = _serverConnectionState.asStateFlow()

    private val _ytdlpUpdateState = MutableStateFlow<YtDlpUpdateState>(YtDlpUpdateState.Idle)
    val ytdlpUpdateState: StateFlow<YtDlpUpdateState> = _ytdlpUpdateState.asStateFlow()

    private val _searchState = MutableStateFlow<YoutubeSearchState>(YoutubeSearchState.Idle)
    val searchState: StateFlow<YoutubeSearchState> = _searchState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _appUpdateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val appUpdateState: StateFlow<AppUpdateState> = _appUpdateState.asStateFlow()

    // удаление трека
    private val _pendingDeleteIntent = MutableStateFlow<android.app.PendingIntent?>(null)
    val pendingDeleteIntent: StateFlow<android.app.PendingIntent?> = _pendingDeleteIntent.asStateFlow()

    fun clearPendingDeleteIntent() {
        _pendingDeleteIntent.value = null
    }

    private var nextQueueIndex: Int = -1

    // плеер
    private val playerManager = AudioPlayerManager(
        context = application,
        onProgress = { pos ->
            try {
                _currentPosition.value = pos
                updateActiveLyrics(pos)
            } catch (e: Exception) {
                Log.w("MusicViewModel", "progress update failed", e)
            }
        },
        onCompletion = {
            viewModelScope.launch {
                delay(100)
                if (_isWaveActive.value) skipNextWaveTrack(getApplication()) else playNextTrack()
            }
        },
        onPlaybackStarted = { realDuration ->
            val track = _currentTrack.value
            if (track != null && realDuration > 0 && track.duration != realDuration) {
                _currentTrack.value = track.copy(duration = realDuration)
            }
        },
        onPlayWhenReadyChanged = { playWhenReady ->
            if (_isPlaying.value != playWhenReady && _currentTrack.value != null) {
                _isPlaying.value = playWhenReady
                if (!playWhenReady) {
                    prefs.edit().putLong("last_playback_position", _currentPosition.value).apply()
                }
            }
        },
        onTrackTransition = { track -> applyGaplessAdvance(track) },
        onPlaybackError = { code -> handlePlaybackError(code) }
    )

    private val _visualizerData = MutableStateFlow(ByteArray(128) { 128.toByte() })
    val visualizerData: StateFlow<ByteArray> = _visualizerData.asStateFlow()

    private val _visualizerBands = MutableStateFlow(FloatArray(5) { 0.04f })
    val visualizerBands: StateFlow<FloatArray> = _visualizerBands.asStateFlow()
    private var lastRealVisualizerTimeMs = 0L

    private var visualizerSimJob: Job? = null

    var isAppInForeground: Boolean = true
        set(value) {
            field = value
            playerManager.isAppInForeground = value
            syncVisualizerEngine()
        }

    var isVisualizerScreenVisible: Boolean = false
        set(value) {
            field = value
            syncVisualizerEngine()
        }

    private val cachedMagnitudes = FloatArray(128)
    private var lastVisualizerEmitTime = 0L

    private fun processFftData(fft: ByteArray) {
        try {
            if (fft.size < 4) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastVisualizerEmitTime < 28L) return
            lastVisualizerEmitTime = now

            val n = (fft.size / 2).coerceAtMost(128)
            if (n < 2) return

            for (k in 1 until n) {
                val r = kotlin.math.abs(fft[2 * k].toFloat())
                val i = kotlin.math.abs(fft[2 * k + 1].toFloat())
                // расчет спектра
                cachedMagnitudes[k] = if (r > i) r + 0.4f * i else i + 0.4f * r
            }

            fun avgMag(from: Int, to: Int): Float {
                val start = from.coerceIn(1, n - 1)
                val end = to.coerceIn(start, n - 1)
                var sum = 0f
                for (i in start..end) sum += cachedMagnitudes[i]
                return sum / (end - start + 1)
            }

            val b0 = kotlin.math.sqrt((avgMag(1, 2) / 38f).coerceAtLeast(0f)).coerceIn(0.04f, 0.98f)
            val b1 = kotlin.math.sqrt((avgMag(3, 6) / 26f).coerceAtLeast(0f)).coerceIn(0.04f, 0.96f)
            val b2 = kotlin.math.sqrt((avgMag(7, 16) / 15f).coerceAtLeast(0f)).coerceIn(0.04f, 0.96f)
            val b3 = kotlin.math.sqrt((avgMag(17, 42) / 6.5f).coerceAtLeast(0f)).coerceIn(0.04f, 0.96f)
            val b4 = kotlin.math.sqrt((avgMag(43, 110) / 2.4f).coerceAtLeast(0f)).coerceIn(0.04f, 0.96f)

            lastRealVisualizerTimeMs = now
            _visualizerBands.value = floatArrayOf(b0, b1, b2, b3, b4)
        } catch (e: Exception) {
        }
    }

    fun syncVisualizerEngine() {
        val shouldRun = isAppInForeground && isVisualizerScreenVisible && _isPlaying.value
        if (shouldRun) {
            lastRealVisualizerTimeMs = 0L
            playerManager.setVisualizerDataListener { processFftData(it) }
        } else {
            visualizerSimJob?.cancel()
            visualizerSimJob = null
            playerManager.setVisualizerDataListener { }
            PlayerHub.processor.fftListener = null
            val cur = _visualizerBands.value
            if (cur.any { it > 0.05f }) {
                _visualizerBands.value = FloatArray(5) { 0.04f }
            }
        }
    }

    private val _currentTrackColor = MutableStateFlow<Int>(0xFF8B5CF6.toInt())
    val currentTrackColor: StateFlow<Int> = _currentTrackColor.asStateFlow()

    private val _trackPalette = MutableStateFlow(TrackPalette())
    val trackPalette: StateFlow<TrackPalette> = _trackPalette.asStateFlow()

    private var rawScannedTracks: List<Track> = emptyList()
    private val _minTrackDurationSec = MutableStateFlow(0) // секунды
    val minTrackDurationSec: StateFlow<Int> = _minTrackDurationSec.asStateFlow()

    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _playbackQueue = MutableStateFlow<List<Track>>(emptyList())
    val playbackQueue: StateFlow<List<Track>> = _playbackQueue.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(-1)

    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    private val _isWaveActive = MutableStateFlow(false)
    val isWaveActive: StateFlow<Boolean> = _isWaveActive.asStateFlow()

    private val _isWaveLoading = MutableStateFlow(false)
    val isWaveLoading: StateFlow<Boolean> = _isWaveLoading.asStateFlow()

    private val _waveLoadingProgress = MutableStateFlow(0f)
    val waveLoadingProgress: StateFlow<Float> = _waveLoadingProgress.asStateFlow()

    private val _waveLoadingRemainingText = MutableStateFlow("")
    val waveLoadingRemainingText: StateFlow<String> = _waveLoadingRemainingText.asStateFlow()

    companion object {
        fun normalizeWaveSource(s: String?): String = when (s?.lowercase()?.trim()) {
            "local", "локально", "локальный" -> "Локально"
            "hybrid", "гибрид", "гибридный" -> "Гибрид"
            else -> "Сеть"
        }
        fun normalizeWaveCharacter(c: String?): String = when (c?.lowercase()?.trim()) {
            "discover", "незнакомое", "открытия" -> "Незнакомое"
            "popular", "популярное", "хиты" -> "Популярное"
            else -> "Любимое"
        }
        fun normalizeWaveMood(m: String?): String = when (m?.lowercase()?.trim()) {
            "calm", "спокойное", "релакс" -> "Спокойное"
            "dark", "мрачное", "дарк" -> "Мрачное"
            "party", "весёлое", "веселое", "пати" -> "Весёлое"
            else -> "Бодрое"
        }
        fun normalizeWaveLanguage(l: String?): String = when (l?.lowercase()?.trim()) {
            "русские", "рус", "ru", "russian" -> "Русские"
            "английские", "англ", "en", "english" -> "Английские"
            "без слов", "инструментал", "instrumental", "beats" -> "Без слов"
            else -> "Все"
        }
    }

    private val _waveCharacter = MutableStateFlow(normalizeWaveCharacter(prefs.getString("wave_character", "Любимое")))
    val waveCharacter: StateFlow<String> = _waveCharacter.asStateFlow()

    private val _waveMood = MutableStateFlow(normalizeWaveMood(prefs.getString("wave_mood", "Бодрое")))
    val waveMood: StateFlow<String> = _waveMood.asStateFlow()

    private val _waveSource = MutableStateFlow(normalizeWaveSource(prefs.getString("wave_source", "Сеть")))
    val waveSource: StateFlow<String> = _waveSource.asStateFlow()

    private val _waveLanguage = MutableStateFlow(normalizeWaveLanguage(prefs.getString("wave_language", "Все")))
    val waveLanguage: StateFlow<String> = _waveLanguage.asStateFlow()

    private val _waveStatusText = MutableStateFlow("Нажми на сферу для запуска персонального потока")
    val waveStatusText: StateFlow<String> = _waveStatusText.asStateFlow()

    private val waveCandidatePool = java.util.Collections.synchronizedList(mutableListOf<org.json.JSONObject>())
    private val waveSeenKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var waveStartJob: Job? = null
    private var wavePrebufferJob: Job? = null

    data class WaveSessionFeedback(val artist: String, val title: String, val score: Float, val timeRatio: Float)
    private val waveSessionFeedbacks = java.util.Collections.synchronizedList(mutableListOf<WaveSessionFeedback>())
    private var lastPositiveTrackJson: String = "{}"

    private fun recordWaveTrackFeedback(track: Track?, isNaturalCompletion: Boolean, isDislike: Boolean = false, isLike: Boolean = false) {
        if (track == null || !track.id.startsWith("wave_")) return
        val artist = track.artist.trim()
        val title = track.title.trim()
        if (artist.isEmpty()) return

        val pos = _currentPosition.value
        val dur = track.duration
        val ratio = if (dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
        val playedSec = pos / 1000L

        val score = when {
            isDislike -> -10.0f
            isLike -> 5.0f
            isNaturalCompletion -> 3.0f
            playedSec < 18L -> -3.0f
            playedSec < 45L -> -1.0f
            ratio >= 0.75f -> 2.5f
            else -> 0.5f
        }

        synchronized(waveSessionFeedbacks) {
            waveSessionFeedbacks.add(WaveSessionFeedback(artist, title, score, ratio))
            if (waveSessionFeedbacks.size > 50) {
                waveSessionFeedbacks.removeAt(0)
            }
        }

        if (score >= 2.0f) {
            try {
                val obj = org.json.JSONObject()
                obj.put("artist", artist)
                obj.put("title", title)
                val vid = if (track.sourceUrl?.contains("v=") == true) {
                    track.sourceUrl.substringAfter("v=").substringBefore("&")
                } else if (track.id.startsWith("wave_")) {
                    track.id.removePrefix("wave_").substringBefore("_")
                } else ""
                if (vid.length == 11) {
                    obj.put("video_id", vid)
                }
                lastPositiveTrackJson = obj.toString()
            } catch (e: Exception) {
                Log.w("MusicViewModel", "Failed to update last positive track", e)
            }
        }
    }

    private val waveDataFile by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Muzix/wave_data.json")
    }

    private fun saveWavePersistentData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = waveDataFile.parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                val json = org.json.JSONObject()
                val seenArr = org.json.JSONArray()
                synchronized(waveSeenKeys) {
                    waveSeenKeys.forEach { seenArr.put(it) }
                }
                json.put("seen_keys", seenArr)

                val bannedArr = org.json.JSONArray()
                getBannedWaveSet().forEach { bannedArr.put(it) }
                json.put("banned_items", bannedArr)

                val historyArr = org.json.JSONArray()
                _playbackQueue.value.takeLast(50).forEach { tr ->
                    val obj = org.json.JSONObject()
                    obj.put("id", tr.id)
                    obj.put("title", tr.title)
                    obj.put("artist", tr.artist)
                    obj.put("duration", tr.duration)
                    obj.put("path", tr.path)
                    obj.put("albumArtPath", tr.albumArtPath ?: "")
                    obj.put("album", tr.album ?: "")
                    obj.put("sourceUrl", tr.sourceUrl ?: "")
                    historyArr.put(obj)
                }
                json.put("history", historyArr)
                json.put("character", _waveCharacter.value)
                json.put("mood", _waveMood.value)
                json.put("source", _waveSource.value)
                json.put("language", _waveLanguage.value)
                json.put("last_positive_track", lastPositiveTrackJson)

                waveDataFile.writeText(json.toString(2), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w("MusicViewModel", "Failed to save persistent wave data", e)
            }
        }
    }

    private fun loadWavePersistentData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!waveDataFile.exists() || waveDataFile.length() == 0L) return@launch
                val text = waveDataFile.readText(Charsets.UTF_8)
                val json = org.json.JSONObject(text)

                val seenArr = json.optJSONArray("seen_keys")
                if (seenArr != null) {
                    synchronized(waveSeenKeys) {
                        for (i in 0 until seenArr.length()) {
                            waveSeenKeys.add(seenArr.getString(i))
                        }
                    }
                }

                val bannedArr = json.optJSONArray("banned_items")
                if (bannedArr != null) {
                    val bannedSet = mutableSetOf<String>()
                    for (i in 0 until bannedArr.length()) {
                        bannedSet.add(bannedArr.getString(i))
                    }
                    prefs.edit().putStringSet("wave_banned_items", bannedSet).apply()
                }

                val savedChar = json.optString("character")
                if (!savedChar.isNullOrEmpty()) _waveCharacter.value = normalizeWaveCharacter(savedChar)
                val savedMood = json.optString("mood")
                if (!savedMood.isNullOrEmpty()) _waveMood.value = normalizeWaveMood(savedMood)
                val savedSource = json.optString("source")
                if (!savedSource.isNullOrEmpty()) _waveSource.value = normalizeWaveSource(savedSource)
                val savedLang = json.optString("language")
                if (!savedLang.isNullOrEmpty()) _waveLanguage.value = normalizeWaveLanguage(savedLang)
                val savedPositive = json.optString("last_positive_track", "{}")
                if (savedPositive.isNotBlank() && savedPositive != "{}") {
                    lastPositiveTrackJson = savedPositive
                }

                val historyArr = json.optJSONArray("history")
                if (historyArr != null && historyArr.length() > 0 && _playbackQueue.value.isEmpty()) {
                    val restored = mutableListOf<Track>()
                    for (i in 0 until historyArr.length()) {
                        val obj = historyArr.getJSONObject(i)
                        restored.add(
                            Track(
                                id = obj.optString("id"),
                                title = obj.optString("title"),
                                artist = obj.optString("artist"),
                                duration = obj.optLong("duration", 180000L),
                                path = obj.optString("path"),
                                isLocal = false,
                                albumArtSeed = obj.optString("title").hashCode(),
                                albumArtPath = obj.optString("albumArtPath").ifBlank { null },
                                album = obj.optString("album", ""),
                                sourceUrl = obj.optString("sourceUrl").ifBlank { null }
                            )
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (_playbackQueue.value.isEmpty()) {
                            _playbackQueue.value = restored
                            _currentQueueIndex.value = (restored.size - 1).coerceAtLeast(0)
                            val lastTrack = restored.lastOrNull()
                            _currentTrack.value = lastTrack
                            if (lastTrack != null && lastTrack.id.startsWith("wave_")) {
                                _currentPosition.value = 0L
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MusicViewModel", "Failed to load persistent wave data", e)
            }
        }
    }

    private val _editingTrack = MutableStateFlow<Track?>(null)
    val editingTrack: StateFlow<Track?> = _editingTrack.asStateFlow()

    private val _editingDraft = MutableStateFlow<TrackEditDraft?>(null)
    val editingDraft: StateFlow<TrackEditDraft?> = _editingDraft.asStateFlow()

    private val _isEditorBusy = MutableStateFlow(false)
    val isEditorBusy: StateFlow<Boolean> = _isEditorBusy.asStateFlow()

    private val _repeatMode = MutableStateFlow(false) // режим повтора
    val repeatMode: StateFlow<Boolean> = _repeatMode.asStateFlow()

    private val _favoriteTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteTrackIds: StateFlow<Set<String>> = _favoriteTrackIds.asStateFlow()

    // скачанный трек
    private val _lastDownloadedTrack = MutableStateFlow<Track?>(null)
    val lastDownloadedTrack: StateFlow<Track?> = _lastDownloadedTrack.asStateFlow()

    // настройки
    private val _sleepTimerRemaining = MutableStateFlow<Int?>(null)
    val sleepTimerRemaining: StateFlow<Int?> = _sleepTimerRemaining.asStateFlow()

    private val _audioBufferSize = MutableStateFlow("Optimal")
    val audioBufferSize: StateFlow<String> = _audioBufferSize.asStateFlow()

    private val _powerSavingMode = MutableStateFlow(false)
    val powerSavingMode: StateFlow<Boolean> = _powerSavingMode.asStateFlow()

    private val _autoScanOnStart = MutableStateFlow(true)
    val autoScanOnStart: StateFlow<Boolean> = _autoScanOnStart.asStateFlow()

    private val _downloadQuality = MutableStateFlow("192 kbps")
    val downloadQuality: StateFlow<String> = _downloadQuality.asStateFlow()

    private val _accentColorIndex = MutableStateFlow(0)
    val accentColorIndex: StateFlow<Int> = _accentColorIndex.asStateFlow()

    private val _customAccentColor = MutableStateFlow(0xFFF9B9BC)
    val customAccentColor: StateFlow<Long> = _customAccentColor.asStateFlow()

    private val _monetEnabled = MutableStateFlow(true)
    val monetEnabled: StateFlow<Boolean> = _monetEnabled.asStateFlow()

    private val _enlargedUi = MutableStateFlow(false)
    val enlargedUi: StateFlow<Boolean> = _enlargedUi.asStateFlow()

    private val _uiScalePercent = MutableStateFlow(12.0f)
    val uiScalePercent: StateFlow<Float> = _uiScalePercent.asStateFlow()

    fun setUiScalePercent(percent: Float) {
        val rounded = (kotlin.math.round(percent * 2f) / 2f).coerceIn(0f, 20f)
        _uiScalePercent.value = rounded
        prefs.edit().putFloat("ui_scale_percent", rounded).apply()
    }

    fun toggleEnlargedUi() {
        val next = !_enlargedUi.value
        _enlargedUi.value = next
        prefs.edit().putBoolean("enlarged_ui", next).apply()
    }

    private val _dynamicIslandEnabled = MutableStateFlow(prefs.getBoolean("dynamic_island_enabled", false))
    val dynamicIslandEnabled: StateFlow<Boolean> = _dynamicIslandEnabled.asStateFlow()

    fun setDynamicIslandEnabled(enabled: Boolean) {
        _dynamicIslandEnabled.value = enabled
        prefs.edit().putBoolean("dynamic_island_enabled", enabled).apply()
        if (enabled) {
            com.example.island.DynamicIslandService.start(getApplication())
        } else {
            com.example.island.DynamicIslandService.stop(getApplication())
        }
    }

    private val _themeMode = MutableStateFlow("System")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _crossfadeDuration = MutableStateFlow(0)
    val crossfadeDuration: StateFlow<Int> = _crossfadeDuration.asStateFlow()

    private val _autoplayHeadset = MutableStateFlow(false)
    val autoplayHeadset: StateFlow<Boolean> = _autoplayHeadset.asStateFlow()

    private val _visualizerType = MutableStateFlow("Circle")
    val visualizerType: StateFlow<String> = _visualizerType.asStateFlow()

    private val _superBass = MutableStateFlow(false)
    val superBass: StateFlow<Boolean> = _superBass.asStateFlow()

    private var preSuperBassBassStrength: Short = 0
    private var preSuperBassEqLevels: IntArray = intArrayOf(0, 0, 0, 0, 0)
    private var preSuperBassPreset: String = "Flat"

    private val _volumeNormalize = MutableStateFlow(false)
    val volumeNormalize: StateFlow<Boolean> = _volumeNormalize.asStateFlow()

    private var sleepTimerJob: Job? = null

    private val _lyricsLines = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyricsLines: StateFlow<List<LyricLine>> = _lyricsLines.asStateFlow()

    private val _activeLyricsLineIndex = MutableStateFlow(-1)
    val activeLyricsLineIndex: StateFlow<Int> = _activeLyricsLineIndex.asStateFlow()

    private val _rawLrcText = MutableStateFlow("")
    val rawLrcText: StateFlow<String> = _rawLrcText.asStateFlow()

    val playlists: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    val selectedPlaylistTracks: StateFlow<List<Track>> = _selectedPlaylistTracks.asStateFlow()

    private val _viewingPlaylist = MutableStateFlow<PlaylistEntity?>(null)
    val viewingPlaylist: StateFlow<PlaylistEntity?> = _viewingPlaylist.asStateFlow()

    // эквалайзер
    private val _viperDspState = MutableStateFlow(ViperAudioDsp.State())
    val viperDspState: StateFlow<ViperAudioDsp.State> = _viperDspState.asStateFlow()

    private val _equalizerEnabled = MutableStateFlow(true)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()

    private val _equalizerBands = MutableStateFlow<List<Int>>(emptyList())
    val equalizerBands: StateFlow<List<Int>> = _equalizerBands.asStateFlow()

    private val _equalizerLevels = MutableStateFlow<IntArray>(IntArray(5) { 0 })
    val equalizerLevels: StateFlow<ShortArray> = _equalizerLevels
        .map { arr -> arr.map { it.toShort() }.toShortArray() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShortArray(5) { 0 })

    private val _activePreset = MutableStateFlow("Flat")
    val activePreset: StateFlow<String> = _activePreset.asStateFlow()
    private val _bassBoostStrength = MutableStateFlow<Short>(0)
    val bassBoostStrength: StateFlow<Short> = _bassBoostStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow<Short>(0)
    val virtualizerStrength: StateFlow<Short> = _virtualizerStrength.asStateFlow()

    private val _reverbPreset = MutableStateFlow<Short>(0)
    val reverbPreset: StateFlow<Short> = _reverbPreset.asStateFlow()

    private val _saveToRam = MutableStateFlow(false)
    val saveToRam: StateFlow<Boolean> = _saveToRam.asStateFlow()

    private val _downloaderFormat = MutableStateFlow("mp3")
    val downloaderFormat: StateFlow<String> = _downloaderFormat.asStateFlow()

    private val _downloaderThreads = MutableStateFlow(4)
    val downloaderThreads: StateFlow<Int> = _downloaderThreads.asStateFlow()

    private val _maxDurationLimit = MutableStateFlow(10)
    val maxDurationLimit: StateFlow<Int> = _maxDurationLimit.asStateFlow()

    private val _searchResultsLimit = MutableStateFlow(5)
    val searchResultsLimit: StateFlow<Int> = _searchResultsLimit.asStateFlow()



    private val presetLevels = mapOf(
        "Flat" to intArrayOf(0, 0, 0, 0, 0),
        "Pop" to intArrayOf(2, 1, -1, -2, 1),
        "Rock" to intArrayOf(4, 2, -3, 2, 5),
        "Jazz" to intArrayOf(3, 1, 2, 1, -2),
        "Classical" to intArrayOf(4, 3, 1, 2, 3),
        "Bass Boost" to intArrayOf(6, 3, 0, 0, -2),
        "Electronic" to intArrayOf(5, 3, 1, 3, 5),
        "Vocal Booster" to intArrayOf(-2, 0, 4, 3, -1),
        "Deep" to intArrayOf(4, 3, -1, -2, -3),
        "Acoustic" to intArrayOf(3, 1, 2, 2, 3),
        "Treble Booster" to intArrayOf(-3, -2, 1, 4, 6),
        "Loudness" to intArrayOf(4, 1, -1, 1, 4)
    )

    init {
        PlayerHub.commandHandler = commandHandler
        connectMediaSession()
        checkForAppUpdate(silent = true)

        // автоплей гарнитуры
        try {
            val plugFilter = android.content.IntentFilter(Intent.ACTION_HEADSET_PLUG)
            ContextCompat.registerReceiver(
                application,
                headsetPlugReceiver,
                plugFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Failed to register headset plug receiver", e)
        }

        viewModelScope.launch {
            _isPlaying.collect {
                syncVisualizerEngine()
            }
        }


        // синхронизация медиа
        viewModelScope.launch {
            combine(currentTrack, isPlaying) { track, playing -> Pair(track, playing) }
                .collect { (track, playing) -> updatePlaybackMetadata(track, playing) }
        }

        // динамический остров
        viewModelScope.launch {
            currentTrack.collect { track ->
                PlayerHub.currentIslandTrack.value = track?.let {
                    PlayerHub.IslandTrack(
                        id = it.id,
                        title = it.title,
                        artist = it.artist,
                        album = it.album,
                        durationMs = it.duration,
                        albumArtPath = it.albumArtPath
                    )
                }
            }
        }
        viewModelScope.launch {
            isPlaying.collect { playing ->
                PlayerHub.isIslandPlaying.value = playing
                if (playing && _dynamicIslandEnabled.value) {
                    com.example.island.DynamicIslandService.start(getApplication())
                }
            }
        }
        viewModelScope.launch {
            currentPosition.collect { pos ->
                PlayerHub.islandPositionMs.value = pos
            }
        }

        // чтение настроек
        _favoriteTrackIds.value = prefs.getStringSet("favorite_tracks", emptySet()) ?: emptySet()
        _saveToRam.value = prefs.getBoolean("save_to_ram", false)
        _downloaderFormat.value = prefs.getString("downloader_format", "mp3") ?: "mp3"
        _downloaderThreads.value = prefs.getInt("downloader_threads", 4)
        _maxDurationLimit.value = prefs.getInt("max_duration_limit", 10)
        _searchResultsLimit.value = prefs.getInt("search_results_limit", 5)

        _repeatMode.value = prefs.getBoolean("repeat_mode", false)
        _shuffleMode.value = prefs.getBoolean("shuffle_mode", false)
        _minTrackDurationSec.value = prefs.getInt("min_track_duration_sec", 0)
        _powerSavingMode.value = prefs.getBoolean("power_saving_mode", false)
        _autoScanOnStart.value = prefs.getBoolean("auto_scan_on_start", true)
        _audioBufferSize.value = prefs.getString("audio_buffer_size", "Optimal") ?: "Optimal"
        _downloadQuality.value = prefs.getString("download_quality", "192 kbps") ?: "192 kbps"
        _accentColorIndex.value = prefs.getInt("accent_color_index", 0)
        _customAccentColor.value = prefs.getLong("custom_accent_color", 0xFFF9B9BCL)
        _monetEnabled.value = prefs.getBoolean("monet_enabled", true)
        _enlargedUi.value = prefs.getBoolean("enlarged_ui", false)
        _uiScalePercent.value = prefs.getFloat("ui_scale_percent", 12.0f)
        _themeMode.value = prefs.getString("theme_mode", "System") ?: "System"
        _crossfadeDuration.value = prefs.getInt("crossfade_duration", 0)
        _autoplayHeadset.value = prefs.getBoolean("autoplay_headset", false)
        _visualizerType.value = prefs.getString("visualizer_type", "Circle") ?: "Circle"
        _volumeNormalize.value = prefs.getBoolean("volume_normalize", false)

        val eqEnabled = prefs.getBoolean("equalizer_enabled", true)
        _equalizerEnabled.value = eqEnabled

        val savedPreset = prefs.getString("equalizer_preset", "Flat") ?: "Flat"
        _activePreset.value = savedPreset
        _bassBoostStrength.value = prefs.getInt("bass_boost_strength", 0).toShort()
        _virtualizerStrength.value = prefs.getInt("virtualizer_strength", 0).toShort()
        _reverbPreset.value = prefs.getInt("reverb_preset", 0).toShort()

        // чтение DSP
        val vEnabled = prefs.getBoolean("viper_enabled", eqEnabled)
        val vBassEnabled = prefs.getBoolean("viper_bass_enabled", true)
        val vBassModeStr = prefs.getString("viper_bass_mode", ViperAudioDsp.BassMode.PURE_BASS.name) ?: ViperAudioDsp.BassMode.PURE_BASS.name
        val vBassMode = try { ViperAudioDsp.BassMode.valueOf(vBassModeStr) } catch (_: Exception) { ViperAudioDsp.BassMode.PURE_BASS }
        val vBassFreq = prefs.getInt("viper_bass_freq", 60)
        val vBassGain = prefs.getFloat("viper_bass_gain", 6f)

        val vClarityEnabled = prefs.getBoolean("viper_clarity_enabled", true)
        val vClarityModeStr = prefs.getString("viper_clarity_mode", ViperAudioDsp.ClarityMode.OZONE.name) ?: ViperAudioDsp.ClarityMode.OZONE.name
        val vClarityMode = try { ViperAudioDsp.ClarityMode.valueOf(vClarityModeStr) } catch (_: Exception) { ViperAudioDsp.ClarityMode.OZONE }
        val vClarityGain = prefs.getFloat("viper_clarity_gain", 4f)

        val vAnalogX = prefs.getBoolean("viper_analogx", false)
        val vPreamp = prefs.getFloat("viper_preamp", 0f)
        val vSurround = _virtualizerStrength.value
        val vReverb = _reverbPreset.value

        val eqLevels = FloatArray(5) { i ->
            prefs.getFloat("viper_band_$i", presetLevels[savedPreset]?.getOrNull(i)?.toFloat() ?: 0f)
        }

        val loadedViperState = ViperAudioDsp.State(
            enabled = vEnabled,
            bassEnabled = vBassEnabled,
            bassMode = vBassMode,
            bassFreq = vBassFreq,
            bassGainDb = vBassGain,
            clarityEnabled = vClarityEnabled,
            clarityMode = vClarityMode,
            clarityGainDb = vClarityGain,
            analogXEnabled = vAnalogX,
            preampDb = vPreamp,
            eqBandLevels = eqLevels,
            activePreset = savedPreset,
            surroundStrength = vSurround,
            reverbPreset = vReverb
        )
        _viperDspState.value = loadedViperState

        playerManager.setAudioBufferSize(_audioBufferSize.value)
        playerManager.setCrossfadeDuration(_crossfadeDuration.value)
        playerManager.setVolumeNormalize(_volumeNormalize.value)
        playerManager.setViperDspState(loadedViperState)

        // стартовая очередь
        _allTracks.value = emptyList()
        _playbackQueue.value = emptyList()

        _equalizerBands.value = playerManager.getEqualizerBandFrequencies()
        updateEqualizerLevelsFromManager()

        // автоскан треков
        if (_autoScanOnStart.value) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(application, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(application, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            if (hasPermission) {
                scanLocalTracks(application)
            }
        }

        checkServerStatus()

        // история волны
        loadWavePersistentData()
    }

    fun scanLocalTracks(context: Context) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        viewModelScope.launch(Dispatchers.IO) {
            val localList = mutableListOf<Track>()

            // скан Muzix
            try {
                val publicMuzixDir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Muzix")
                if (publicMuzixDir.exists()) {
                    publicMuzixDir.listFiles { _, name ->
                        val low = name.lowercase()
                        low.endsWith(".mp3") || low.endsWith(".m4a") || low.endsWith(".webm") || low.endsWith(".ogg") || low.endsWith(".flac")
                    }?.forEach { file ->
                        val base = file.nameWithoutExtension
                        val artist = if (base.contains(" - ")) base.substringBefore(" - ").trim() else "Muzix Downloads"
                        val title = if (base.contains(" - ")) base.substringAfter(" - ").trim() else base
                        val coverFile = java.io.File(file.parent, "$base.jpg")
                        val albumArtPath = if (coverFile.exists()) coverFile.absolutePath else null
                        val trackId = "file_${file.absolutePath.hashCode().toString().replace("-", "n")}_${file.nameWithoutExtension.hashCode().toString().replace("-", "n")}"
                        if (localList.none { it.path == file.absolutePath }) localList.add(
                            Track(
                                id = trackId,
                                title = title,
                                artist = artist,
                                duration = 180000L,
                                path = file.absolutePath,
                                isLocal = true,
                                albumArtSeed = title.hashCode(),
                                albumArtPath = albumArtPath
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error scanning public Music/Muzix directory", e)
            }

            // скан загрузок
            try {
                val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                if (downloadDir != null && downloadDir.exists()) {
                    downloadDir.listFiles { _, name -> name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".webm") || name.endsWith(".3gp") }?.forEach { origFile ->
                        var file = origFile
                        val rawTitle = origFile.nameWithoutExtension.substringBefore(" - ")
                        val rawArtist = origFile.nameWithoutExtension.substringAfter(" - ", "YouTube Downloader")
                        val exported = publishToPublicMusicFolder(context, origFile, rawTitle, rawArtist, 180000L)
                        if (exported != null && !exported.startsWith("content://")) {
                            val pubF = java.io.File(exported)
                            if (pubF.exists() && pubF.length() > 0) {
                                try { origFile.delete() } catch (_: Exception) {}
                                file = pubF
                            }
                        }
                        val title = file.nameWithoutExtension.substringBefore(" - ")
                        val artist = file.nameWithoutExtension.substringAfter(" - ", "YouTube Downloader")
                        val coverFile = java.io.File(file.parent, file.nameWithoutExtension + ".jpg")
                        val albumArtPath = if (coverFile.exists()) coverFile.absolutePath else null
                        val trackId = "file_${file.absolutePath.hashCode().toString().replace("-", "n")}_${file.nameWithoutExtension.hashCode().toString().replace("-", "n")}"
                        if (localList.none { it.path == file.absolutePath }) localList.add(
                            Track(
                                id = trackId,
                                title = title,
                                artist = artist,
                                duration = 180000L,
                                path = file.absolutePath,
                                isLocal = true,
                                albumArtSeed = title.hashCode(),
                                albumArtPath = albumArtPath
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error scanning app-local downloads directory", e)
            }

            // скан кэша
            try {
                val cacheDir = context.cacheDir
                if (cacheDir != null && cacheDir.exists()) {
                    cacheDir.listFiles { _, name -> name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".webm") || name.endsWith(".3gp") }?.forEach { file ->
                        val title = file.nameWithoutExtension.substringBefore(" - ")
                        val artist = file.nameWithoutExtension.substringAfter(" - ", "YouTube Cache")
                        val coverFile = java.io.File(file.parent, file.nameWithoutExtension + ".jpg")
                        val albumArtPath = if (coverFile.exists()) coverFile.absolutePath else null
                        val trackId = "cache_${file.absolutePath.hashCode().toString().replace("-", "n")}_${file.nameWithoutExtension.hashCode().toString().replace("-", "n")}"
                        localList.add(
                            Track(
                                id = trackId,
                                title = title,
                                artist = artist,
                                duration = 180000L,
                                path = file.absolutePath,
                                isLocal = true,
                                albumArtSeed = title.hashCode(),
                                albumArtPath = albumArtPath
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error scanning cache directory", e)
            }

            // скан медиатеки
            if (hasPermission) {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA
                )

                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

                try {
                    context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                        while (cursor.moveToNext()) {
                            val mediaRawId = cursor.getLong(idColumn)
                            val id = "media_$mediaRawId"
                            val title = cursor.getString(titleColumn) ?: "Без названия"
                            val artist = cursor.getString(artistColumn) ?: "Неизвестный исполнитель"
                            val duration = cursor.getLong(durationColumn)
                            val rawPath = cursor.getString(dataColumn) ?: ""
                            val contentUri = android.content.ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                mediaRawId
                            ).toString()
                            val playPath = if (contentUri.isNotEmpty()) contentUri else rawPath

                            if (localList.none { it.id == id || it.path == playPath || (rawPath.isNotEmpty() && it.path == rawPath) }) {
                                localList.add(
                                    Track(
                                        id = id,
                                        title = title,
                                        artist = artist,
                                        duration = duration,
                                        path = playPath,
                                        isLocal = true,
                                        albumArtSeed = title.hashCode()
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error reading local audio", e)
                }
            }

            withContext(Dispatchers.Main) {
                rawScannedTracks = localList
                updateFilteredTracks()
            }
        }
    }

    private fun updateFilteredTracks() {
        val minSec = _minTrackDurationSec.value
        val minMs = minSec * 1000L
        val filtered = if (minSec > 0) {
            rawScannedTracks.filter { it.duration >= minMs }
        } else {
            rawScannedTracks
        }
        _allTracks.value = filtered

        val seedable = filtered.filter { 
            it.duration in 60_000L..600_000L && 
            it.artist.isNotBlank() &&
            !NativeTrackMatcher.isJunk(it.artist, it.title, it.path)
        }.map { 
            NativeTrackMatcher.cleanTrack(it.artist, it.title).first 
        }.filter { it.isNotBlank() && it.length >= 2 }.distinct().take(30).toSet()

        if (seedable.isNotEmpty()) {
            prefs.edit().putStringSet("cached_seed_artists", seedable).apply()
        }

        if (!_isWaveActive.value) {
            val currentQueue = _playbackQueue.value.filter { !it.id.startsWith("wave_") }.toMutableList()
            var queueChanged = false
            filtered.forEach { track ->
                if (currentQueue.none { it.path == track.path }) {
                    currentQueue.add(track)
                    queueChanged = true
                }
            }
            if (queueChanged || _playbackQueue.value.isEmpty()) {
                _playbackQueue.value = currentQueue
            }
        }

        val currentIndex = _playbackQueue.value.indexOfFirst { it.id == _currentTrack.value?.id }
        if (currentIndex != -1) {
            _currentQueueIndex.value = currentIndex
        }

        if (_currentTrack.value == null && filtered.isNotEmpty()) {
            val savedTrackId = prefs.getString("last_played_track_id", null)
            val toRestore = if (savedTrackId != null) filtered.find { it.id == savedTrackId } else null
            if (toRestore != null) {
                selectTrack(toRestore, autoPlay = false)
                val savedPos = prefs.getLong("last_playback_position", 0L)
                _currentPosition.value = savedPos.coerceIn(0L, toRestore.duration)
            }
        }
    }

    fun setMinTrackDurationSec(seconds: Int) {
        _minTrackDurationSec.value = seconds
        prefs.edit().putInt("min_track_duration_sec", seconds).apply()
        updateFilteredTracks()
    }

    fun selectLocalLibraryTrack(track: Track) {
        if (_isWaveActive.value) {
            _isWaveActive.value = false
            _isWaveLoading.value = false
            _waveLoadingProgress.value = 0f
            _waveLoadingRemainingText.value = ""
            waveStartJob?.cancel()
            wavePrebufferJob?.cancel()
            _waveStatusText.value = "Поток остановлен"
            _playbackQueue.value = _allTracks.value
        }
        selectTrack(track, autoPlay = true)
    }

    fun selectTrack(track: Track, autoPlay: Boolean = true) {
        _currentTrack.value = track
        _currentPosition.value = 0L
        _activeLyricsLineIndex.value = -1
        if (!track.id.startsWith("wave_")) {
            prefs.edit().putString("last_played_track_id", track.id).apply()
        }

        val index = _playbackQueue.value.indexOfFirst { it.id == track.id }
        if (index != -1) {
            _currentQueueIndex.value = index
        } else {
            val updatedQueue = _playbackQueue.value.toMutableList()
            updatedQueue.add(track)
            _playbackQueue.value = updatedQueue
            _currentQueueIndex.value = updatedQueue.size - 1
        }

        loadLyricsForTrack(track)

        if (autoPlay) {
            if (requestAudioFocus()) {
                playerManager.playTrack(track)
                _isPlaying.value = true
            } else {
                _isPlaying.value = false
            }
        } else {
            playerManager.prepareTrack(track)
            _isPlaying.value = false
        }
        scheduleNextTrack()
        if (_isWaveActive.value && track.id.startsWith("wave_")) {
            ensureWavePrebuffer(getApplication())
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pausePlayback()
        } else {
            val track = _currentTrack.value
            if (track != null) {
                if (track.id.startsWith("wave_") && !_isWaveActive.value) {
                    startOnlineWave(getApplication(), forceRestart = true)
                    return
                }
                if (requestAudioFocus()) {
                    playerManager.resume(track)
                    _isPlaying.value = true
                }
            } else if (_playbackQueue.value.isNotEmpty()) {
                selectTrack(_playbackQueue.value[0])
            }
        }
    }

    fun pausePlayback() {
        if (_isPlaying.value) {
            playerManager.pause()
            _isPlaying.value = false
            abandonAudioFocus()
            prefs.edit().putLong("last_playback_position", _currentPosition.value).apply()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Muzix Playback"
            val descriptionText = "Controls music playback"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun generateNotificationCover(title: String, artist: String, seed: Int): android.graphics.Bitmap {
        val size = 512
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // фон обложки
        val h = (Math.abs(seed) % 360).toFloat()
        val baseColor = android.graphics.Color.HSVToColor(floatArrayOf(h, 0.45f, 0.22f))
        val ambientColor = android.graphics.Color.HSVToColor(floatArrayOf((h + 35f) % 360f, 0.65f, 0.38f))
        val darkBg = android.graphics.Color.argb(255, 18, 18, 22)

        paint.color = darkBg
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(ambientColor, baseColor, darkBg),
            floatArrayOf(0f, 0.50f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = android.graphics.RadialGradient(
            size * 0.3f, size * 0.3f, size * 0.65f,
            android.graphics.Color.argb(60, 255, 255, 255),
            android.graphics.Color.TRANSPARENT,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(size * 0.3f, size * 0.3f, size * 0.65f, paint)

        // логотип
        try {
            val context = getApplication<Application>()
            val logoBmp = android.graphics.BitmapFactory.decodeResource(context.resources, com.example.R.drawable.ic_logo_white)
            if (logoBmp != null) {
                val logoTargetSize = (size * 0.52f).toInt()
                val scaledLogo = android.graphics.Bitmap.createScaledBitmap(logoBmp, logoTargetSize, logoTargetSize, true)
                val left = (size - logoTargetSize) / 2f
                val top = (size - logoTargetSize) / 2f
                paint.shader = null
                paint.color = android.graphics.Color.WHITE
                canvas.drawBitmap(scaledLogo, left, top, paint)
                if (scaledLogo != logoBmp) scaledLogo.recycle()
                logoBmp.recycle()
            }
        } catch (e: Exception) {
            // заглушка
        }

        return bitmap
    }

    private var cachedNotificationTrackId: String? = null
    private var cachedNotificationBitmap: android.graphics.Bitmap? = null

    private fun getTrackArtworkBitmap(track: Track): android.graphics.Bitmap {
        if (cachedNotificationTrackId == track.id && cachedNotificationBitmap != null) {
            return cachedNotificationBitmap!!
        }

        var artBitmap: android.graphics.Bitmap? = null
        val context = getApplication<Application>()

        // веб-обложка
        if (!track.albumArtPath.isNullOrEmpty()) {
            try {
                if (track.albumArtPath.startsWith("http://") || track.albumArtPath.startsWith("https://")) {
                    val conn = URL(track.albumArtPath).openConnection()
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.getInputStream().use { stream ->
                        artBitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    }
                } else {
                    artBitmap = android.graphics.BitmapFactory.decodeFile(track.albumArtPath)
                }
            } catch (_: Exception) {}
        }

        // файл рядом
        if (artBitmap == null && track.isLocal && track.path.isNotEmpty() && !track.path.startsWith("content://")) {
            try {
                val sidecar = File(track.path.substringBeforeLast('.') + ".jpg")
                if (sidecar.exists() && sidecar.length() > 256) {
                    artBitmap = android.graphics.BitmapFactory.decodeFile(sidecar.absolutePath)
                }
            } catch (_: Exception) {}
        }

        val uri = if (track.path.startsWith("content://")) android.net.Uri.parse(track.path) else null

        // теги файла
        if (artBitmap == null && track.isLocal && track.path.isNotEmpty()) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (uri != null) {
                    retriever.setDataSource(context, uri)
                } else {
                    retriever.setDataSource(track.path)
                }
                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
                    artBitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, decodeOptions)
                }
            } catch (e: Exception) {
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        }

        // превью android
        if (artBitmap == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && uri != null) {
            try {
                artBitmap = context.contentResolver.loadThumbnail(uri, android.util.Size(1024, 1024), null)
            } catch (e: Exception) {}
        }

        // база mediastore
        if (artBitmap == null && uri != null) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.Audio.Media.ALBUM_ID),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToNext()) {
                        val albumId = cursor.getLong(0)
                        if (albumId > 0) {
                            val albumArtUri = android.content.ContentUris.withAppendedId(
                                android.net.Uri.parse("content://media/external/audio/albumart"),
                                albumId
                            )
                            context.contentResolver.openInputStream(albumArtUri)?.use { stream ->
                                artBitmap = android.graphics.BitmapFactory.decodeStream(stream)
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        val finalBitmap = artBitmap ?: generateNotificationCover(track.title, track.artist, track.albumArtSeed)

        // сжатие обложки
        val scaledBitmap = try {
            val maxDim = 300
            val w = finalBitmap.width
            val h = finalBitmap.height
            if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / maxOf(w, h)
                android.graphics.Bitmap.createScaledBitmap(
                    finalBitmap,
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                finalBitmap
            }
        } catch (e: Exception) {
            finalBitmap
        }

        cachedNotificationTrackId = track.id
        cachedNotificationBitmap = scaledBitmap
        return scaledBitmap
    }

    fun toggleFavorite(trackId: String) {
        val current = _favoriteTrackIds.value.toMutableSet()
        val isNowFavorite = if (current.contains(trackId)) {
            current.remove(trackId)
            false
        } else {
            current.add(trackId)
            true
        }
        _favoriteTrackIds.value = current
        prefs.edit().putStringSet("favorite_tracks", current).apply()

        val cur = _currentTrack.value
        if (cur != null && cur.id == trackId) {
            PlayerHub.refreshFavorite(isNowFavorite)
            if (isNowFavorite && _isWaveActive.value) {
                recordWaveTrackFeedback(cur, isNaturalCompletion = false, isLike = true)
            }
        }
    }

    private fun extractArtworkPalette(bitmap: android.graphics.Bitmap?): TrackPalette {
        if (bitmap == null) return TrackPalette()
        return try {
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 32, 32, false)
            val hsv = FloatArray(3)
            var vibrantColor = 0xFF8B5CF6.toInt()
            var secondaryColor = 0xFF06B6D4.toInt()
            var dominantColor = 0xFF8B5CF6.toInt()
            var maxVibrantScore = -1f
            var maxSecondaryScore = -1f
            var vibrantHue = -1f

            val sampledPixels = mutableListOf<Int>()
            for (x in 0 until 32 step 2) {
                for (y in 0 until 32 step 2) {
                    val pixel = scaled.getPixel(x, y)
                    if (android.graphics.Color.alpha(pixel) < 128) continue
                    sampledPixels.add(pixel)
                    android.graphics.Color.colorToHSV(pixel, hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val value = hsv[2]

                    if (value in 0.28f..0.95f && sat > 0.25f) {
                        val score = sat * 2.4f + value
                        if (score > maxVibrantScore) {
                            maxVibrantScore = score
                            vibrantColor = pixel
                            vibrantHue = hue
                        }
                    }
                }
            }

            if (sampledPixels.isNotEmpty()) {
                dominantColor = vibrantColor
                for (pixel in sampledPixels) {
                    android.graphics.Color.colorToHSV(pixel, hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val value = hsv[2]
                    val hueDiff = if (vibrantHue >= 0) {
                        val diff = kotlin.math.abs(hue - vibrantHue)
                        if (diff > 180f) 360f - diff else diff
                    } else 90f

                    if (value in 0.25f..0.95f && sat > 0.25f && hueDiff > 35f) {
                        val score = sat * 2.0f + value + (hueDiff / 180f)
                        if (score > maxSecondaryScore) {
                            maxSecondaryScore = score
                            secondaryColor = pixel
                        }
                    }
                }
            }

            if (scaled != bitmap) scaled.recycle()

            android.graphics.Color.colorToHSV(vibrantColor, hsv)
            val darkHsv = floatArrayOf(hsv[0], (hsv[1] * 0.45f).coerceIn(0.12f, 0.4f), 0.10f)
            val darkSurface = android.graphics.Color.HSVToColor(darkHsv)

            val accentHsv = floatArrayOf((hsv[0] + 45f) % 360f, (hsv[1] * 1.1f).coerceIn(0.4f, 1f), (hsv[2] * 1.05f).coerceIn(0.5f, 1f))
            val accentColor = android.graphics.Color.HSVToColor(accentHsv)

            TrackPalette(
                dominant = dominantColor,
                vibrant = vibrantColor,
                secondary = if (maxSecondaryScore > 0) secondaryColor else accentColor,
                accent = accentColor,
                darkSurface = darkSurface
            )
        } catch (e: Exception) {
            TrackPalette()
        }
    }

    private fun extractDominantColor(bitmap: android.graphics.Bitmap?): Int {
        return extractArtworkPalette(bitmap).vibrant
    }

    private var lastMetaTrackId: String? = null
    private var artworkJob: Job? = null

    private fun connectMediaSession() {
        try {
            val app = getApplication<Application>()
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            mediaControllerFuture = MediaController.Builder(app, token).buildAsync()
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Failed to connect media session", e)
        }
    }

    private fun updatePlaybackMetadata(track: Track?, playing: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                com.example.widget.WidgetBridge.publish(
                    app,
                    track?.title ?: "Muzix",
                    track?.artist ?: "Ничего не играет",
                    playing && track != null
                )
            } catch (e: Exception) {
                Log.w("MusicViewModel", "Widget update failed", e)
            }
        }
        if (track == null) {
            lastMetaTrackId = null
            artworkJob?.cancel()
            return
        }
        PlayerHub.refreshFavorite(_favoriteTrackIds.value.contains(track.id))
        if (lastMetaTrackId == track.id) return
        lastMetaTrackId = track.id
        artworkJob?.cancel()
        artworkJob = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try { getTrackArtworkBitmap(track) } catch (e: Exception) { null }
            }
            val palette = extractArtworkPalette(bitmap)
            _trackPalette.value = palette
            _currentTrackColor.value = palette.vibrant
            playerManager.updateNotificationArtwork(track, bitmap)
        }
    }

    private fun computeNextIndex(): Int {
        val queue = _playbackQueue.value
        if (queue.isEmpty()) return -1
        if (_isWaveActive.value) {
            val next = _currentQueueIndex.value + 1
            return if (next < queue.size) next else -1
        }
        if (_shuffleMode.value) return queue.indices.random()
        var next = _currentQueueIndex.value + 1
        if (next >= queue.size) next = 0
        return next
    }

    // предзагрузка трека
    private fun scheduleNextTrack() {
        if (_repeatMode.value) {
            playerManager.setRepeatOne(true)
            playerManager.setNextTrack(null)
            nextQueueIndex = -1
            return
        }
        playerManager.setRepeatOne(false)
        val idx = computeNextIndex()
        nextQueueIndex = idx
        val candidate = _playbackQueue.value.getOrNull(idx)
        playerManager.setNextTrack(
            if (candidate != null && candidate.id != _currentTrack.value?.id) candidate else null
        )
    }

    private fun applyGaplessAdvance(track: Track) {
        val previous = _currentTrack.value
        if (_isWaveActive.value && previous != null && previous.id.startsWith("wave_")) {
            recordWaveTrackFeedback(previous, isNaturalCompletion = true)
        }
        _currentTrack.value = track
        _currentPosition.value = 0L
        _activeLyricsLineIndex.value = -1
        if (!track.id.startsWith("wave_")) {
            prefs.edit().putString("last_played_track_id", track.id).apply()
        }
        val index = _playbackQueue.value.indexOfFirst { it.id == track.id }
        if (index != -1) _currentQueueIndex.value = index
        loadLyricsForTrack(track)
        scheduleNextTrack()
        if (_isWaveActive.value && track.id.startsWith("wave_")) {
            _waveStatusText.value = "В эфире • ${_waveCharacter.value} • ${_waveMood.value}"
            ensureWavePrebuffer(getApplication())
        }
    }

    fun playNextTrack() {
        if (_isWaveActive.value || _currentTrack.value?.id?.startsWith("wave_") == true) {
            _isWaveActive.value = true
            skipNextWaveTrack(getApplication())
            return
        }
        val queue = _playbackQueue.value
        if (queue.isEmpty()) return

        if (_repeatMode.value) {
            // повтор трека
            val track = _currentTrack.value
            if (track != null) {
                selectTrack(track)
                return
            }
        }

        val nextIndex = if (nextQueueIndex in queue.indices) nextQueueIndex else computeNextIndex()

        if (nextIndex in queue.indices) {
            selectTrack(queue[nextIndex])
        }
    }

    fun playPreviousTrack() {
        val queue = _playbackQueue.value
        if (queue.isEmpty()) return

        var prevIndex = _currentQueueIndex.value - 1
        if (_shuffleMode.value) {
            prevIndex = (queue.indices).random()
        } else if (prevIndex < 0) {
            prevIndex = queue.size - 1 // по кругу
        }

        if (prevIndex in queue.indices) {
            selectTrack(queue[prevIndex])
        }
    }

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
        _currentPosition.value = positionMs
        updateActiveLyrics(positionMs)
        prefs.edit().putLong("last_playback_position", positionMs).apply()
    }

    fun toggleShuffleMode() {
        val next = !_shuffleMode.value
        _shuffleMode.value = next
        prefs.edit().putBoolean("shuffle_mode", next).apply()
        scheduleNextTrack()
    }

    fun toggleRepeatMode() {
        val next = !_repeatMode.value
        _repeatMode.value = next
        prefs.edit().putBoolean("repeat_mode", next).apply()
        scheduleNextTrack()
    }

    private fun loadLyricsForTrack(track: Track) {
        viewModelScope.launch {
            val dbLyric = repository.getSyncedLyrics(track.id)
            val diskLrc = withContext(Dispatchers.IO) {
                val realPath = resolveRealFilePath(getApplication(), track)
                val targetPath = realPath.ifBlank { track.path }
                if (targetPath.isNotEmpty() && !targetPath.startsWith("http")) {
                    val lrcFile = File(targetPath.substringBeforeLast('.') + ".lrc")
                    if (lrcFile.exists()) {
                        try { lrcFile.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                    } else ""
                } else ""
            }
            val effectiveText = when {
                diskLrc.isNotBlank() -> diskLrc
                dbLyric != null && dbLyric.lyricsJson.isNotBlank() -> dbLyric.lyricsJson
                else -> ""
            }
            if (effectiveText.isNotBlank()) {
                val parsed = if (effectiveText.contains("[")) {
                    parseLrc(effectiveText)
                } else {
                    effectiveText.lines().filter { it.isNotBlank() }.mapIndexed { idx, line ->
                        LyricLine(timestampMs = idx * 4000L, text = line.trim())
                    }
                }
                _lyricsLines.value = parsed
                _rawLrcText.value = effectiveText
            } else {
                val template = """
                    [00:00.00] Ищем синхронный текст...
                """.trimIndent()
                _lyricsLines.value = parseLrc(template)
                _rawLrcText.value = ""
                fetchOnlineLyricsForTrack(track)
            }
        }
    }

    private fun fetchOnlineLyricsForTrack(track: Track) {
        val artist = track.artist.trim()
        val title = track.title.trim()
        if (artist.isBlank() || title.isBlank() || artist.equals("unknown", ignoreCase = true) || artist.equals("unknown artist", ignoreCase = true)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cleanArtist = artist.replace(Regex("(?i)\\b(feat|ft|featuring)\\b.*"), "").trim()
                val cleanTitle = title.replace(Regex("\\[.*?\\]|\\(.*?\\)"), "").trim()
                val durSec = (track.duration / 1000L).toInt()

                var lrcResult = ""

                // поиск текста
                try {
                    val urlStr = "https://lrclib.net/api/get?artist_name=" +
                            URLEncoder.encode(cleanArtist, "UTF-8") +
                            "&track_name=" + URLEncoder.encode(cleanTitle, "UTF-8") +
                            (if (durSec > 0) "&duration=$durSec" else "")
                    val conn = (URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", "Muzix/1.0.2 (Android)")
                    }
                    if (conn.responseCode == 200) {
                        val respText = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(respText)
                        val synced = json.optString("syncedLyrics", "")
                        val plain = json.optString("plainLyrics", "")
                        lrcResult = if (synced.isNotBlank()) synced else plain
                    }
                    conn.disconnect()
                } catch (_: Exception) {}

                // поиск LRCLIB
                if (lrcResult.isBlank()) {
                    try {
                        val qUrl = "https://lrclib.net/api/search?q=" + URLEncoder.encode("$cleanArtist $cleanTitle", "UTF-8")
                        val conn = (URL(qUrl).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout = 4000
                            setRequestProperty("User-Agent", "Muzix/1.0.2 (Android)")
                        }
                        if (conn.responseCode == 200) {
                            val respText = conn.inputStream.bufferedReader().use { it.readText() }
                            val arr = org.json.JSONArray(respText)
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                val syn = item.optString("syncedLyrics", "")
                                val pl = item.optString("plainLyrics", "")
                                if (syn.isNotBlank()) {
                                    lrcResult = syn
                                    break
                                } else if (lrcResult.isBlank() && pl.isNotBlank()) {
                                    lrcResult = pl
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) {}
                }

                // поиск Yandex/Deezer
                if (lrcResult.isBlank() && ensurePython()) {
                    try {
                        val py = Python.getInstance()
                        val mod = py.getModule("tag_editor")
                        val cacheDir = getApplication<Application>().cacheDir.absolutePath
                        val res = mod.callAttr("search_online_metadata", cleanTitle, cleanArtist, cacheDir).toString()
                        val obj = org.json.JSONObject(res)
                        if (obj.optString("status") == "success") {
                            val data = obj.optJSONObject("data")
                            val l = data?.optString("lyrics", "") ?: ""
                            if (l.isNotBlank()) lrcResult = l
                        }
                    } catch (_: Exception) {}
                }

                if (lrcResult.isNotBlank()) {
                    repository.saveSyncedLyrics(track.id, lrcResult)
                    if (_currentTrack.value?.id == track.id) {
                        val parsed = if (lrcResult.contains("[")) {
                            parseLrc(lrcResult)
                        } else {
                            lrcResult.lines().filter { it.isNotBlank() }.mapIndexed { idx, line ->
                                LyricLine(timestampMs = idx * 4000L, text = line.trim())
                            }
                        }
                        withContext(Dispatchers.Main) {
                            _lyricsLines.value = parsed
                            _rawLrcText.value = lrcResult
                        }
                    }
                } else if (_currentTrack.value?.id == track.id && _rawLrcText.value.isBlank()) {
                    withContext(Dispatchers.Main) {
                        val fallback = """
                            [00:00.00] Текст песни не найден в сети
                            [00:05.00] Можно добавить вручную через редактор
                        """.trimIndent()
                        _lyricsLines.value = parseLrc(fallback)
                    }
                }
            } catch (e: Exception) {
                Log.w("MusicViewModel", "Online lyrics note", e)
            }
        }
    }

    fun saveLyrics(rawText: String) {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            repository.saveSyncedLyrics(track.id, rawText)
            _rawLrcText.value = rawText
            _lyricsLines.value = parseLrc(rawText)
        }
    }

    private fun updateActiveLyrics(positionMs: Long) {
        val lines = _lyricsLines.value
        if (lines.isEmpty()) {
            _activeLyricsLineIndex.value = -1
            return
        }

        var activeIndex = -1
        for (i in lines.indices) {
            if (positionMs >= lines[i].timestampMs) {
                activeIndex = i
            } else {
                break
            }
        }
        _activeLyricsLineIndex.value = activeIndex
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (_viewingPlaylist.value?.id == playlistId) {
                _viewingPlaylist.value = null
                _selectedPlaylistTracks.value = emptyList()
            }
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, track)
            // обновление списка
            if (_viewingPlaylist.value?.id == playlistId) {
                loadPlaylistTracks(playlistId)
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
            // обновление списка
            if (_viewingPlaylist.value?.id == playlistId) {
                loadPlaylistTracks(playlistId)
            }
        }
    }

    fun selectPlaylistForViewing(playlist: PlaylistEntity) {
        _viewingPlaylist.value = playlist
        loadPlaylistTracks(playlist.id)
    }

    fun closePlaylistViewing() {
        playlistTracksJob?.cancel()
        _viewingPlaylist.value = null
        _selectedPlaylistTracks.value = emptyList()
    }

    fun playPlaylist(playlistTracks: List<Track>) {
        if (playlistTracks.isEmpty()) return
        _playbackQueue.value = playlistTracks
        selectTrack(playlistTracks[0])
    }

    private var playlistTracksJob: Job? = null

    private fun loadPlaylistTracks(playlistId: Long) {
        playlistTracksJob?.cancel()
        playlistTracksJob = viewModelScope.launch {
            repository.getTracksForPlaylist(playlistId).collect { list ->
                _selectedPlaylistTracks.value = list.map { pt ->
                    Track(
                        id = pt.trackId,
                        title = pt.title,
                        artist = pt.artist,
                        duration = pt.duration,
                        path = pt.path,
                        isLocal = pt.isLocal,
                        albumArtSeed = pt.albumArtSeed
                    )
                }
            }
        }
    }

    fun selectEqualizerPreset(presetName: String) {
        _activePreset.value = presetName
        val levels = presetLevels[presetName] ?: return
        for (i in levels.indices) {
            setEqualizerBandLevel(i, levels[i])
        }
        prefs.edit().putString("equalizer_preset", presetName).apply()
    }

    fun setEqualizerBandLevel(bandIndex: Int, levelDb: Int) {
        val currentLevels = _equalizerLevels.value.clone()
        if (bandIndex in currentLevels.indices) {
            currentLevels[bandIndex] = levelDb
            _equalizerLevels.value = currentLevels
            playerManager.setBandLevel(bandIndex, levelDb)
            
            // свой пресет
            val isCustom = presetLevels.values.none { it.contentEquals(currentLevels) }
            if (isCustom) {
                _activePreset.value = "Custom"
            }
        }
    }

    private fun updateEqualizerLevelsFromManager() {
        _equalizerLevels.value = playerManager.getBandLevels()
    }

    fun setBassBoostStrength(strength: Short) {
        _bassBoostStrength.value = strength
        playerManager.setBassBoostStrength(strength)
        prefs.edit().putInt("bass_boost_strength", strength.toInt()).apply()
    }

    fun toggleSuperBass() {
        val nextState = !_superBass.value
        _superBass.value = nextState

        if (nextState) {
            preSuperBassBassStrength = _bassBoostStrength.value
            preSuperBassEqLevels = playerManager.getBandLevels().clone()
            preSuperBassPreset = _activePreset.value

            _bassBoostStrength.value = 1000.toShort()
            playerManager.setBassBoostStrength(1000.toShort())

            setEqualizerBandLevel(0, 15)
            setEqualizerBandLevel(1, 12)
            _activePreset.value = "SUPER BASS"
        } else {
            _bassBoostStrength.value = preSuperBassBassStrength
            playerManager.setBassBoostStrength(preSuperBassBassStrength)

            _activePreset.value = preSuperBassPreset
            for (i in 0 until 5) {
                setEqualizerBandLevel(i, preSuperBassEqLevels[i])
            }
        }
        prefs.edit().putBoolean("super_bass", nextState).apply()
    }

    fun setVirtualizerStrength(strength: Short) {
        _virtualizerStrength.value = strength
        playerManager.setVirtualizerStrength(strength)
        prefs.edit().putInt("virtualizer_strength", strength.toInt()).apply()
    }


    fun setEqualizerEnabled(enabled: Boolean) {
        _equalizerEnabled.value = enabled
        playerManager.setEqualizerEnabled(enabled)
        prefs.edit().putBoolean("equalizer_enabled", enabled).apply()
    }

    fun setReverbPreset(preset: Short) {
        _reverbPreset.value = preset
        playerManager.setReverbPreset(preset)
        prefs.edit().putInt("reverb_preset", preset.toInt()).apply()
    }

    fun resetEqualizer() {
        resetViperDsp()
    }


    fun setViperEnabled(enabled: Boolean) {
        val newState = _viperDspState.value.copy(enabled = enabled)
        _viperDspState.value = newState
        _equalizerEnabled.value = enabled
        playerManager.setViperDspState(newState)
        prefs.edit().putBoolean("viper_enabled", enabled).putBoolean("equalizer_enabled", enabled).apply()
    }

    fun setViperBass(enabled: Boolean, mode: ViperAudioDsp.BassMode, freq: Int, gainDb: Float) {
        val newState = _viperDspState.value.copy(
            bassEnabled = enabled,
            bassMode = mode,
            bassFreq = freq,
            bassGainDb = gainDb
        )
        _viperDspState.value = newState
        playerManager.setViperDspState(newState)
        prefs.edit()
            .putBoolean("viper_bass_enabled", enabled)
            .putString("viper_bass_mode", mode.name)
            .putInt("viper_bass_freq", freq)
            .putFloat("viper_bass_gain", gainDb)
            .apply()
    }

    fun setViperClarity(enabled: Boolean, mode: ViperAudioDsp.ClarityMode, gainDb: Float) {
        val newState = _viperDspState.value.copy(
            clarityEnabled = enabled,
            clarityMode = mode,
            clarityGainDb = gainDb
        )
        _viperDspState.value = newState
        playerManager.setViperDspState(newState)
        prefs.edit()
            .putBoolean("viper_clarity_enabled", enabled)
            .putString("viper_clarity_mode", mode.name)
            .putFloat("viper_clarity_gain", gainDb)
            .apply()
    }

    fun setAnalogXEnabled(enabled: Boolean) {
        val newState = _viperDspState.value.copy(analogXEnabled = enabled)
        _viperDspState.value = newState
        playerManager.setViperDspState(newState)
        prefs.edit().putBoolean("viper_analogx", enabled).apply()
    }

    fun setPreampDb(gainDb: Float) {
        val newState = _viperDspState.value.copy(preampDb = gainDb)
        _viperDspState.value = newState
        playerManager.setViperDspState(newState)
        prefs.edit().putFloat("viper_preamp", gainDb).apply()
    }

    fun setViperBandLevel(bandIndex: Int, levelDb: Float) {
        val currentLevels = _viperDspState.value.eqBandLevels.clone()
        if (bandIndex in currentLevels.indices) {
            currentLevels[bandIndex] = levelDb
            val newState = _viperDspState.value.copy(
                eqBandLevels = currentLevels,
                activePreset = "Custom"
            )
            _viperDspState.value = newState
            _activePreset.value = "Custom"
            playerManager.setViperDspState(newState)
            prefs.edit()
                .putFloat("viper_band_$bandIndex", levelDb)
                .putString("equalizer_preset", "Custom")
                .apply()
        }
    }

    fun selectViperPreset(presetName: String) {
        _activePreset.value = presetName
        val levels = presetLevels[presetName] ?: return
        val floatLevels = FloatArray(levels.size) { levels[it].toFloat() }
        val newState = _viperDspState.value.copy(
            eqBandLevels = floatLevels,
            activePreset = presetName
        )
        _viperDspState.value = newState
        playerManager.setViperDspState(newState)
        for (i in floatLevels.indices) {
            prefs.edit().putFloat("viper_band_$i", floatLevels[i]).apply()
        }
        prefs.edit().putString("equalizer_preset", presetName).apply()
    }

    fun setViperSurround(strength: Short) {
        val newState = _viperDspState.value.copy(surroundStrength = strength)
        _viperDspState.value = newState
        _virtualizerStrength.value = strength
        playerManager.setViperDspState(newState)
        prefs.edit().putInt("virtualizer_strength", strength.toInt()).apply()
    }

    fun setViperReverb(preset: Short) {
        val newState = _viperDspState.value.copy(reverbPreset = preset)
        _viperDspState.value = newState
        _reverbPreset.value = preset
        playerManager.setViperDspState(newState)
        prefs.edit().putInt("reverb_preset", preset.toInt()).apply()
    }

    fun resetViperDsp() {
        val defaultState = ViperAudioDsp.State(
            enabled = true,
            bassEnabled = true,
            bassMode = ViperAudioDsp.BassMode.PURE_BASS,
            bassFreq = 60,
            bassGainDb = 6f,
            clarityEnabled = true,
            clarityMode = ViperAudioDsp.ClarityMode.OZONE,
            clarityGainDb = 4f,
            analogXEnabled = false,
            preampDb = 0f,
            eqBandLevels = FloatArray(5) { 0f },
            activePreset = "Flat",
            surroundStrength = 0,
            reverbPreset = 0
        )
        _viperDspState.value = defaultState
        _activePreset.value = "Flat"
        _virtualizerStrength.value = 0
        _reverbPreset.value = 0
        _equalizerEnabled.value = true
        playerManager.setViperDspState(defaultState)
        prefs.edit()
            .putBoolean("viper_enabled", true)
            .putBoolean("equalizer_enabled", true)
            .putBoolean("viper_bass_enabled", true)
            .putString("viper_bass_mode", defaultState.bassMode.name)
            .putInt("viper_bass_freq", defaultState.bassFreq)
            .putFloat("viper_bass_gain", defaultState.bassGainDb)
            .putBoolean("viper_clarity_enabled", true)
            .putString("viper_clarity_mode", defaultState.clarityMode.name)
            .putFloat("viper_clarity_gain", defaultState.clarityGainDb)
            .putBoolean("viper_analogx", false)
            .putFloat("viper_preamp", 0f)
            .putString("equalizer_preset", "Flat")
            .putInt("virtualizer_strength", 0)
            .putInt("reverb_preset", 0)
            .apply()
        for (i in 0 until 5) {
            prefs.edit().putFloat("viper_band_$i", 0f).apply()
        }
    }

    fun setSaveToRam(value: Boolean) {
        _saveToRam.value = value
        prefs.edit().putBoolean("save_to_ram", value).apply()
    }

    fun setDownloaderFormat(value: String) {
        _downloaderFormat.value = value
        prefs.edit().putString("downloader_format", value).apply()
    }

    fun setDownloaderThreads(value: Int) {
        _downloaderThreads.value = value
        prefs.edit().putInt("downloader_threads", value).apply()
    }

    fun setMaxDurationLimit(value: Int) {
        _maxDurationLimit.value = value
        prefs.edit().putInt("max_duration_limit", value).apply()
    }

    fun setSearchResultsLimit(value: Int) {
        _searchResultsLimit.value = value
        prefs.edit().putInt("search_results_limit", value).apply()
    }

    fun setMonetEnabled(enabled: Boolean) {
        _monetEnabled.value = enabled
        prefs.edit().putBoolean("monet_enabled", enabled).apply()
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun shuffleAllTracks() {
        _isWaveActive.value = false
        val list = _allTracks.value
        if (list.isNotEmpty()) {
            val shuffled = list.shuffled()
            _playbackQueue.value = shuffled
            selectTrack(shuffled[0])
        }
    }

    private fun getBannedWaveSet(): MutableSet<String> {
        return (prefs.getStringSet("wave_banned_items", emptySet()) ?: emptySet()).toMutableSet()
    }

    fun resetWaveBans(context: Context) {
        prefs.edit().remove("wave_banned_items").apply()
        waveSeenKeys.clear()
        Toast.makeText(context, "Список исключений волны очищен", Toast.LENGTH_SHORT).show()
    }

    fun applyWaveSettings(
        context: Context,
        character: String,
        mood: String,
        source: String = _waveSource.value,
        language: String = _waveLanguage.value
    ) {
        val normChar = normalizeWaveCharacter(character)
        val normMood = normalizeWaveMood(mood)
        val normSource = normalizeWaveSource(source)
        val normLang = normalizeWaveLanguage(language)
        _waveCharacter.value = normChar
        _waveMood.value = normMood
        _waveSource.value = normSource
        _waveLanguage.value = normLang
        prefs.edit()
            .putString("wave_character", normChar)
            .putString("wave_mood", normMood)
            .putString("wave_source", normSource)
            .putString("wave_language", normLang)
            .apply()
        // сброс кэша
        synchronized(waveSeenKeys) {
            waveSeenKeys.clear()
        }
        startOnlineWave(context, forceRestart = true)
    }

    fun toggleWavePlay(context: Context) {
        if (_isWaveLoading.value) {
            waveStartJob?.cancel()
            wavePrebufferJob?.cancel()
            _isWaveLoading.value = false
            _waveLoadingProgress.value = 0f
            _waveLoadingRemainingText.value = ""
            _isWaveActive.value = false
            _waveStatusText.value = "Пауза"
            return
        }
        val cur = _currentTrack.value
        if (_isWaveActive.value && cur != null && cur.id.startsWith("wave_")) {
            if (_isPlaying.value) {
                pausePlayback()
                _waveStatusText.value = "Пауза • ${_waveSource.value} • ${_waveCharacter.value}"
            } else {
                togglePlayPause()
                _waveStatusText.value = "В эфире • ${_waveSource.value} • ${_waveCharacter.value} • ${_waveLanguage.value}"
                ensureWavePrebuffer(context)
            }
        } else {
            startOnlineWave(context, forceRestart = true)
        }
    }

    @Volatile
    private var waveHybridTurnLocal = false

    private fun resolveNextLocalWaveTrack(): Track? {
        val all = _allTracks.value
        if (all.isEmpty()) return null
        val favIds = _favoriteTrackIds.value
        val banned = getBannedWaveSet()
        val seen = synchronized(waveSeenKeys) { waveSeenKeys.toSet() }

        val pool = all.filter { tr ->
            val pairKey = "${tr.artist.lowercase().trim()} - ${tr.title.lowercase().trim()}"
            val artistLow = tr.artist.lowercase().trim()
            tr.duration in 60_000L..600_000L && pairKey !in banned && artistLow !in banned && tr.id !in seen
        }.ifEmpty {
            synchronized(waveSeenKeys) { waveSeenKeys.clear() }
            all.filter { tr ->
                val pairKey = "${tr.artist.lowercase().trim()} - ${tr.title.lowercase().trim()}"
                tr.duration in 60_000L..600_000L && pairKey !in banned
            }
        }.ifEmpty { all }

        if (pool.isEmpty()) return null

        val cur = _currentTrack.value
        val curArtist = cur?.artist?.lowercase()?.trim() ?: ""

        val chosen = when (normalizeWaveCharacter(_waveCharacter.value)) {
            "Любимое" -> {
                val favs = pool.filter { it.id in favIds }
                if (favs.isNotEmpty() && Math.random() < 0.75) favs.random() else pool.random()
            }
            "Незнакомое" -> {
                val nonFav = pool.filter { it.id !in favIds }
                if (nonFav.isNotEmpty()) nonFav.random() else pool.random()
            }
            else -> {
                val sameArtistPool = if (curArtist.isNotEmpty() && curArtist != "unknown artist" && curArtist != "youtube") {
                    pool.filter { it.artist.lowercase().trim() == curArtist }
                } else emptyList()

                if (sameArtistPool.isNotEmpty() && Math.random() < 0.35) {
                    sameArtistPool.random()
                } else {
                    pool.shuffled().firstOrNull() ?: pool.random()
                }
            }
        }
        chosen?.let {
            synchronized(waveSeenKeys) { waveSeenKeys.add(it.id) }
        }
        return chosen
    }

    private fun resolveNextWaveTrack(): Track? {
        val mode = normalizeWaveSource(_waveSource.value)
        return when (mode) {
            "Локально" -> resolveNextLocalWaveTrack()
            "Гибрид" -> {
                waveHybridTurnLocal = !waveHybridTurnLocal
                if (waveHybridTurnLocal) {
                    resolveNextLocalWaveTrack() ?: resolveNextWaveTrackFromPool()
                } else {
                    resolveNextWaveTrackFromPool() ?: resolveNextLocalWaveTrack()
                }
            }
            else -> resolveNextWaveTrackFromPool()
        }
    }

    fun skipWavePreparation(context: Context) {
        waveStartJob?.cancel()
        _isWaveLoading.value = false
        _waveLoadingProgress.value = 0f
        _waveLoadingRemainingText.value = ""
        _isWaveActive.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val instant = resolveNextLocalWaveTrack()
                ?: _allTracks.value.firstOrNull { it.duration in 60_000L..600_000L }
                ?: _allTracks.value.firstOrNull()

            withContext(Dispatchers.Main) {
                if (instant != null) {
                    _playbackQueue.value = listOf(instant)
                    _currentQueueIndex.value = 0
                    _waveStatusText.value = "В эфире (быстрый старт) • ${_waveCharacter.value}"
                    selectTrack(instant, autoPlay = true)
                    Toast.makeText(context, "Быстрый старт активирован", Toast.LENGTH_SHORT).show()
                } else {
                    _waveStatusText.value = "Медиатека пуста для быстрого старта"
                    Toast.makeText(context, "Локальных треков не найдено", Toast.LENGTH_SHORT).show()
                }
            }
            if (normalizeWaveSource(_waveSource.value) != "Локально") {
                try {
                    if (ensurePython()) {
                        refillWaveCandidatesIfNeeded()
                    }
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Background wave candidate fill failed", e)
                }
            }
        }
    }

    private fun startOnlineWave(context: Context, forceRestart: Boolean = false) {
        waveStartJob?.cancel()
        wavePrebufferJob?.cancel()
        if (forceRestart) {
            waveCandidatePool.clear()
        }
        _isWaveActive.value = true
        _isWaveLoading.value = true
        _waveLoadingProgress.value = 0.15f
        _waveLoadingRemainingText.value = "Скан треков"
        _waveStatusText.value = "Скан"

        waveStartJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val mode = normalizeWaveSource(_waveSource.value)
                if (_allTracks.value.isEmpty()) {
                    for (w in 0..10) {
                        if (_allTracks.value.isNotEmpty()) break
                        delay(200)
                    }
                }
                val firstTrack = if (mode == "Локально") {
                    _waveLoadingProgress.value = 0.50f
                    _waveLoadingRemainingText.value = "Локальный трек"
                    resolveNextLocalWaveTrack()
                } else {
                    _waveLoadingProgress.value = 0.30f
                    _waveLoadingRemainingText.value = "Анализ вкуса"
                    if (ensurePython()) {
                        _waveLoadingProgress.value = 0.55f
                        _waveLoadingRemainingText.value = "Поиск в сети"
                        refillWaveCandidatesIfNeeded()
                        _waveLoadingProgress.value = 0.85f
                        _waveLoadingRemainingText.value = "Буферизация"
                        resolveNextWaveTrack()
                    } else if (mode == "Гибрид") {
                        _waveLoadingProgress.value = 0.65f
                        _waveLoadingRemainingText.value = "Локальный трек"
                        resolveNextLocalWaveTrack()
                    } else null
                } ?: if (mode == "Локально" || mode == "Гибрид") {
                    resolveNextLocalWaveTrack()
                        ?: _allTracks.value.firstOrNull { it.duration in 60_000L..600_000L }
                        ?: _allTracks.value.firstOrNull()
                } else null

                withContext(Dispatchers.Main) {
                    _waveLoadingProgress.value = 1.0f
                    _waveLoadingRemainingText.value = ""
                    _isWaveLoading.value = false
                    if (firstTrack != null) {
                        _playbackQueue.value = listOf(firstTrack)
                        _currentQueueIndex.value = 0
                        _waveStatusText.value = "В эфире • ${_waveSource.value} • ${_waveCharacter.value} • ${_waveLanguage.value}"
                        selectTrack(firstTrack, autoPlay = true)
                        ensureWavePrebuffer(context)
                    } else {
                        _isWaveActive.value = false
                        val msg = if (mode == "Локально") "Локальная медиатека пуста" else "Медиатека пуста"
                        _waveStatusText.value = msg
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Wave start failed", e)
                withContext(Dispatchers.Main) {
                    _isWaveLoading.value = false
                    _waveLoadingProgress.value = 0f
                    _waveLoadingRemainingText.value = ""
                    _waveStatusText.value = "Ошибка при запуске волны"
                }
            }
        }
    }

    private fun refillWaveCandidatesIfNeeded() {
        if (waveCandidatePool.size >= 14) return
        val all = _allTracks.value
        val favIds = _favoriteTrackIds.value
        
        // Жесткая фильтрация нативным парсером звонков, диктофона, нейрослопа и шума
        val validLocal = all.filter {
            it.duration in 60_000L..600_000L &&
            it.artist.isNotBlank() &&
            !NativeTrackMatcher.isJunk(it.artist, it.title, it.path)
        }

        // Извлечение и очистка артистов через Native C++ движок
        val cleanLocalPairs = validLocal.map { 
            val clean = NativeTrackMatcher.cleanTrack(it.artist, it.title)
            it to clean.first
        }.filter { it.second.isNotBlank() && it.second.length >= 2 }

        val favArtists = cleanLocalPairs.filter { it.first.id in favIds }
            .map { it.second }.distinct().shuffled().take(12)
        val localArtists = cleanLocalPairs.map { it.second }.distinct().shuffled().take(12)

        // Чистые пресеты без шаблонного трэпа и гитарного релакса (только если нет локальных треков)
        val fallbackMoodSeeds = when (_waveMood.value) {
            "Бодрое" -> listOf("DVRST", "T3NZU", "Sayfalse", "Kordhell", "Skrillex", "The Prodigy", "Виктор Цой", "Кино", "Апология")
            "Спокойное" -> listOf("Молчат Дома", "Сироткин", "Дайте танк (!)", "Буерак", "C418", "HOME", "Saypink!")
            "Мрачное" -> listOf("Crystal Castles", "Молчат Дома", "Ploho", "IC3PEAK", "Mareux", "Буерак", "Cupastate", "SKWLKR")
            "Весёлое" -> listOf("Daft Punk", "Gorillaz", "The Weeknd", "DEAD BLONDE", "GONE.Fludd", "The Hatters")
            else -> listOf("Виктор Цой", "DVRST", "Молчат Дома", "Daft Punk", "The Weeknd", "T3NZU")
        }

        // Если есть локальные треки или избранное — строим волну СТРОГО по вкусу пользователя
        val seedArtists = if (favArtists.isNotEmpty() || localArtists.isNotEmpty()) {
            (favArtists + localArtists).distinct().take(15)
        } else {
            fallbackMoodSeeds.take(10)
        }
        val banned = getBannedWaveSet().toList()
        val seen = synchronized(waveSeenKeys) { waveSeenKeys.toList() }

        val feedbackJson = synchronized(waveSessionFeedbacks) {
            val arr = org.json.JSONArray()
            for (fb in waveSessionFeedbacks) {
                val o = org.json.JSONObject()
                o.put("artist", fb.artist)
                o.put("title", fb.title)
                o.put("score", fb.score.toDouble())
                o.put("time_ratio", fb.timeRatio.toDouble())
                arr.put(o)
            }
            arr.toString()
        }

        try {
            val py = Python.getInstance()
            val waveMod = py.getModule("wave_engine")
            val rawJson = waveMod.callAttr(
                "fetch_wave_candidates",
                org.json.JSONArray(seedArtists).toString(),
                org.json.JSONArray(favArtists).toString(),
                org.json.JSONArray(banned).toString(),
                org.json.JSONArray(seen).toString(),
                _waveCharacter.value,
                _waveMood.value,
                20,
                feedbackJson,
                lastPositiveTrackJson,
                _waveLanguage.value
            ).toString()

            val arr = org.json.JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                waveCandidatePool.add(arr.getJSONObject(i))
            }
            Log.d("MusicViewModel", "Wave candidates refilled, pool size = ${waveCandidatePool.size}")
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Failed to refill wave candidates from Python", e)
        }
    }

    private fun resolveNextWaveTrackFromPool(): Track? {
        val py = Python.getInstance()
        val waveMod = py.getModule("wave_engine")

        var attempts = 0
        while (attempts < 6) {
            attempts++
            if (waveCandidatePool.isEmpty()) {
                refillWaveCandidatesIfNeeded()
            }
            if (waveCandidatePool.isEmpty()) break

            val cand = waveCandidatePool.removeAt(0)
            val key = cand.optString("key", "")
            if (key.isNotEmpty()) waveSeenKeys.add(key)

            val artist = cand.optString("artist", "")
            val title = cand.optString("title", "")
            val album = cand.optString("album", "")
            val coverUrl = cand.optString("cover_url", "")
            val durMs = cand.optLong("duration_ms", 180_000L)
            val videoId = cand.optString("video_id", "")

            try {
                val resStr = waveMod.callAttr("resolve_wave_stream", artist, title, coverUrl, durMs, videoId).toString()
                val obj = org.json.JSONObject(resStr)
                if (obj.optString("status") == "success") {
                    val streamUrl = obj.optString("stream_url", "")
                    if (streamUrl.isNotEmpty()) {
                        val ua = obj.optString("user_agent", "")
                        if (ua.isNotEmpty()) {
                            PlayerHub.streamUserAgent = ua
                        }
                        val vid = obj.optString("video_id", videoId.ifBlank { System.currentTimeMillis().toString() })
                        val finalDur = obj.optLong("duration_ms", durMs)
                        if (finalDur > 600_000L) {
                            // фильтр длительности
                            continue
                        }
                        val resTitle = obj.optString("title", title).ifBlank { title }
                        val resArtist = obj.optString("artist", artist).ifBlank { artist }
                        if (NativeTrackMatcher.isJunk(resArtist, resTitle)) {
                            Log.d("MusicViewModel", "Filtered junk stream track: $resArtist - $resTitle")
                            continue
                        }
                        return Track(
                            id = "wave_${vid}_${System.nanoTime() and 0xFFFF}",
                            title = resTitle,
                            artist = resArtist,
                            duration = finalDur.coerceAtLeast(30_000L),
                            path = streamUrl,
                            isLocal = false,
                            albumArtSeed = title.hashCode(),
                            albumArtPath = obj.optString("cover_url", coverUrl).ifBlank { null },
                            album = album,
                            sourceUrl = obj.optString("source_url", "")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("MusicViewModel", "Wave resolve attempt $attempts failed", e)
            }
        }
        return null
    }

    private fun ensureWavePrebuffer(context: Context) {
        if (!_isWaveActive.value || wavePrebufferJob?.isActive == true) return
        wavePrebufferJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val mode = normalizeWaveSource(_waveSource.value)
                if (mode != "Локально" && !ensurePython()) return@launch
                while (_isWaveActive.value) {
                    val q = _playbackQueue.value
                    val curIdx = q.indexOfFirst { it.id == _currentTrack.value?.id }.coerceAtLeast(0)
                    val ahead = (q.size - 1) - curIdx
                    if (ahead >= 7) break

                    val nextWaveTrack = resolveNextWaveTrack() ?: break
                    withContext(Dispatchers.Main) {
                        if (_isWaveActive.value) {
                            val updated = _playbackQueue.value.toMutableList()
                            updated.add(nextWaveTrack)
                            _playbackQueue.value = updated
                            scheduleNextTrack()
                            saveWavePersistentData()
                        }
                    }
                    if (ahead >= 2) {
                        delay(2500L)
                    }
                }
            } catch (e: Exception) {
                Log.w("MusicViewModel", "Wave prebuffer note", e)
            }
        }
    }

    fun skipNextWaveTrack(context: Context) {
        if (!_isWaveActive.value && _currentTrack.value?.id?.startsWith("wave_") != true) {
            playNextTrack()
            return
        }
        _isWaveActive.value = true
        val cur = _currentTrack.value
        recordWaveTrackFeedback(cur, isNaturalCompletion = false)
        val q = _playbackQueue.value
        val curIdx = q.indexOfFirst { it.id == _currentTrack.value?.id }
        if (curIdx != -1 && curIdx + 1 < q.size) {
            selectTrack(q[curIdx + 1], autoPlay = true)
            ensureWavePrebuffer(context)
            saveWavePersistentData()
        } else {
            playerManager.pause()
            playerManager.setNextTrack(null)
            _isWaveLoading.value = true
            _waveLoadingProgress.value = 0.35f
            _waveLoadingRemainingText.value = "Поиск трека"
            _waveStatusText.value = "Поиск"
            viewModelScope.launch(Dispatchers.IO) {
                val nextTrack = try {
                    val mode = normalizeWaveSource(_waveSource.value)
                    if (mode == "Локально") {
                        _waveLoadingProgress.value = 0.60f
                        _waveLoadingRemainingText.value = "Локальный трек"
                        resolveNextLocalWaveTrack()
                    } else {
                        if (ensurePython()) {
                            _waveLoadingProgress.value = 0.55f
                            _waveLoadingRemainingText.value = "Поиск в сети"
                            refillWaveCandidatesIfNeeded()
                            _waveLoadingProgress.value = 0.85f
                            _waveLoadingRemainingText.value = "Буферизация"
                            resolveNextWaveTrack()
                        } else null
                    } ?: if (mode == "Локально" || mode == "Гибрид") {
                        resolveNextLocalWaveTrack()
                            ?: _allTracks.value.firstOrNull { it.duration in 60_000L..600_000L }
                            ?: _allTracks.value.firstOrNull()
                    } else null
                } catch (_: Exception) { null }
                withContext(Dispatchers.Main) {
                    _isWaveLoading.value = false
                    _waveLoadingProgress.value = 1.0f
                    _waveLoadingRemainingText.value = ""
                    if (nextTrack != null) {
                        val updated = _playbackQueue.value.toMutableList()
                        updated.add(nextTrack)
                        _playbackQueue.value = updated
                        _waveStatusText.value = "В эфире • ${_waveSource.value} • ${_waveCharacter.value} • ${_waveLanguage.value}"
                        selectTrack(nextTrack, autoPlay = true)
                        ensureWavePrebuffer(context)
                        saveWavePersistentData()
                    } else {
                        _waveStatusText.value = "Не удалось загрузить трек"
                    }
                }
            }
        }
    }

    fun dislikeCurrentWaveTrack(context: Context) {
        val cur = _currentTrack.value ?: return
        recordWaveTrackFeedback(cur, isNaturalCompletion = false, isDislike = true)
        val banned = getBannedWaveSet()
        val artistLow = cur.artist.lowercase().trim()
        val titleLow = cur.title.lowercase().trim()
        val pairKey = "$artistLow - $titleLow"
        banned.add(pairKey)
        if (artistLow.isNotBlank()) banned.add(artistLow)
        prefs.edit().putStringSet("wave_banned_items", banned).apply()

        synchronized(waveCandidatePool) {
            waveCandidatePool.removeAll {
                val candArtist = it.optString("artist", "").lowercase().trim()
                val candTitle = it.optString("title", "").lowercase().trim()
                val reason = it.optString("reason", "").lowercase()
                candArtist == artistLow || "$candArtist - $candTitle" == pairKey || (artistLow.isNotBlank() && reason.contains(artistLow))
            }
        }
        val curIdx = _playbackQueue.value.indexOfFirst { it.id == cur.id }
        _playbackQueue.value = _playbackQueue.value.filterIndexed { idx, t ->
            val tArtist = t.artist.lowercase().trim()
            val tPair = "$tArtist - ${t.title.lowercase().trim()}"
            idx <= curIdx || (tArtist != artistLow && tPair != pairKey)
        }

        saveWavePersistentData()
        Toast.makeText(context, "Удалено из волны и похожих: ${cur.artist} — ${cur.title}", Toast.LENGTH_SHORT).show()
        skipNextWaveTrack(context)
    }

    fun downloadCurrentWaveTrack(context: Context) {
        val cur = _currentTrack.value ?: return
        val target = cur.sourceUrl?.takeIf { it.isNotBlank() } ?: "${cur.artist} - ${cur.title}"
        Toast.makeText(context, "Сохраняем в Music/Muzix: ${cur.title}", Toast.LENGTH_SHORT).show()
        downloadYoutubeTrack(context, target)
    }

    private fun handlePlaybackError(code: String) {
        val cur = _currentTrack.value
        if (_isWaveActive.value || cur?.id?.startsWith("wave_") == true) {
            Log.w("MusicViewModel", "Wave stream error ($code), auto-advancing to fresh stream")
            _isWaveActive.value = true
            skipNextWaveTrack(getApplication())
        }
    }

    fun shuffleCurrentPlaylist() {
        val list = _selectedPlaylistTracks.value
        if (list.isNotEmpty()) {
            val shuffled = list.shuffled()
            _playbackQueue.value = shuffled
            selectTrack(shuffled[0])
        }
    }

    fun toggleAutoplayHeadset() {
        setAutoplayHeadset(!_autoplayHeadset.value)
    }

    fun toggleVolumeNormalize() {
        setVolumeNormalize(!_volumeNormalize.value)
    }

    fun togglePowerSavingMode() {
        setPowerSavingMode(!_powerSavingMode.value)
    }

    fun stopPlayback() {
        playerManager.stop()
        _currentTrack.value = null
        _isPlaying.value = false
    }

    fun shareTrack(context: Context, track: Track) {
        if (track.path.isEmpty() && track.id.toLongOrNull() == null) {
            Toast.makeText(context, "Невозможно поделиться этим файлом", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = if (track.path.startsWith("content://")) {
                Uri.parse(track.path)
            } else {
                val file = java.io.File(track.path)
                if (!file.exists()) {
                    val longId = track.id.toLongOrNull()
                    if (longId != null) {
                        android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, longId)
                    } else {
                        Toast.makeText(context, "Файл не найден на диске", Toast.LENGTH_SHORT).show()
                        return
                    }
                } else {
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Поделиться треком"))
        } catch (e: Exception) {
            Log.e("MusicViewModel", "Failed to share track", e)
            Toast.makeText(context, "Не удалось отправить файл: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requestAllFilesAccessOnceIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val alreadyPrompted = prefs.getBoolean("all_files_access_prompted", false)
            if (!alreadyPrompted) {
                prefs.edit().putBoolean("all_files_access_prompted", true).apply()
                openAllFilesAccessSettings(context)
            }
        }
    }

    fun openAllFilesAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Toast.makeText(
                    context,
                    "Включите доступ ко всем файлам для мгновенного удаления и редактора тегов",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {}
            }
        }
    }

    private fun resolveRealFilePath(context: Context, track: Track): String {
        if (track.path.isNotEmpty() && !track.path.startsWith("content://") && !track.path.startsWith("http")) {
            return track.path
        }
        val queryUri = if (track.path.startsWith("content://")) {
            Uri.parse(track.path)
        } else {
            track.id.toLongOrNull()?.let {
                android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
            }
        } ?: return ""

        try {
            context.contentResolver.query(
                queryUri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0) ?: ""
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    fun deleteTrack(context: Context, track: Track) {
        if (track.path.isEmpty() && track.id.toLongOrNull() == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            openAllFilesAccessSettings(context)
            return
        }

        // удаление трека
        rawScannedTracks = rawScannedTracks.filter { it.id != track.id && it.path != track.path }
        _allTracks.value = _allTracks.value.filter { it.id != track.id && it.path != track.path }
        _playbackQueue.value = _playbackQueue.value.filter { it.id != track.id && it.path != track.path }
        if (_currentTrack.value?.id == track.id) {
            stopPlayback()
        }

        // удаление файла
        viewModelScope.launch(Dispatchers.IO) {
            delay(150)
            val realPath = resolveRealFilePath(context, track)
            var deleted = false

            if (realPath.isNotEmpty()) {
                try {
                    val file = File(realPath)
                    val parent = file.parentFile
                    val baseName = file.nameWithoutExtension
                    if (parent != null) {
                        try { File(parent, "$baseName.jpg").takeIf { it.exists() }?.delete() } catch (_: Exception) {}
                        try { File(parent, "$baseName.lrc").takeIf { it.exists() }?.delete() } catch (_: Exception) {}
                    }
                    if (file.exists()) {
                        deleted = file.delete()
                    }
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Direct file delete error", e)
                }
            }

            // чистка mediastore
            try {
                val resolver = context.contentResolver
                val longId = track.id.toLongOrNull()
                if (longId != null) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, longId)
                    val rows = resolver.delete(uri, null, null)
                    if (rows > 0) deleted = true
                } else if (realPath.isNotEmpty()) {
                    val rows = resolver.delete(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Audio.Media.DATA} = ?",
                        arrayOf(realPath)
                    )
                    if (rows > 0) deleted = true
                }
            } catch (e: Exception) {
                Log.w("MusicViewModel", "MediaStore row cleanup note", e)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (deleted) "Трек удален" else "Не удалось удалить файл",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun openTrackEditor(context: Context, track: Track) {
        if (track.path.startsWith("http://") || track.path.startsWith("https://")) {
            Toast.makeText(context, "Сначала скачайте трек на устройство", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            openAllFilesAccessSettings(context)
            return
        }

        val realPath = resolveRealFilePath(context, track)
        val resolvedTrack = if (realPath.isNotEmpty() && track.path != realPath) track.copy(path = realPath) else track
        _editingTrack.value = resolvedTrack
        _editingDraft.value = TrackEditDraft(
            title = resolvedTrack.title,
            artist = resolvedTrack.artist,
            album = resolvedTrack.album,
            filename = if (realPath.isNotEmpty()) File(realPath).nameWithoutExtension else resolvedTrack.title,
            extension = if (realPath.contains('.')) "." + realPath.substringAfterLast('.') else ""
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (realPath.isEmpty() || !ensurePython()) return@launch
                val py = Python.getInstance()
                val mod = py.getModule("tag_editor")
                val res = org.json.JSONObject(mod.callAttr("read_track_metadata", realPath).toString())
                if (res.optString("status") == "success") {
                    val draft = TrackEditDraft(
                        title = res.optString("title", "").ifBlank { resolvedTrack.title },
                        artist = res.optString("artist", "").ifBlank { resolvedTrack.artist },
                        album = res.optString("album", "").ifBlank { resolvedTrack.album },
                        filename = res.optString("filename", File(realPath).nameWithoutExtension),
                        extension = res.optString("extension", ""),
                        lyrics = res.optString("lyrics", "")
                    )
                    withContext(Dispatchers.Main) {
                        if (_editingTrack.value?.id == resolvedTrack.id) {
                            _editingDraft.value = draft
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MusicViewModel", "read_track_metadata note", e)
            }
        }
    }

    fun closeTrackEditor() {
        _editingTrack.value = null
        _editingDraft.value = null
        _isEditorBusy.value = false
    }

    fun autoFetchTrackMetadataOnline(
        context: Context,
        title: String,
        artist: String,
        onResult: (TrackEditDraft) -> Unit
    ) {
        if (_isEditorBusy.value) return
        _isEditorBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!ensurePython()) {
                    withContext(Dispatchers.Main) { _isEditorBusy.value = false }
                    return@launch
                }
                val py = Python.getInstance()
                val mod = py.getModule("tag_editor")
                val resJson = mod.callAttr(
                    "search_online_metadata",
                    title,
                    artist,
                    context.cacheDir.absolutePath
                ).toString()
                val obj = org.json.JSONObject(resJson)
                withContext(Dispatchers.Main) {
                    _isEditorBusy.value = false
                    if (obj.optString("status") == "success") {
                        onResult(
                            TrackEditDraft(
                                title = obj.optString("title", title),
                                artist = obj.optString("artist", artist),
                                album = obj.optString("album", ""),
                                lyrics = obj.optString("lyrics", ""),
                                newCoverPath = obj.optString("cover_path", "")
                            )
                        )
                        Toast.makeText(context, "Обложка и метаданные найдены", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Ничего не найдено в сети", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isEditorBusy.value = false
                    Toast.makeText(context, "Ошибка поиска метаданных", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun saveEditedTrack(context: Context, track: Track, draft: TrackEditDraft) {
        if (_isEditorBusy.value) return
        val realPath = resolveRealFilePath(context, track)
        if (realPath.isEmpty()) {
            Toast.makeText(context, "Не удалось получить прямой путь к файлу", Toast.LENGTH_SHORT).show()
            return
        }

        _isEditorBusy.value = true
        val isCurrent = _currentTrack.value?.id == track.id
        val wasPlaying = _isPlaying.value
        val savedPosition = _currentPosition.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!ensurePython()) {
                    withContext(Dispatchers.Main) { _isEditorBusy.value = false }
                    return@launch
                }
                val py = Python.getInstance()
                val mod = py.getModule("tag_editor")
                val resJson = mod.callAttr(
                    "save_track_metadata",
                    realPath,
                    draft.title,
                    draft.artist,
                    draft.album,
                    draft.lyrics,
                    draft.newCoverPath,
                    draft.filename
                ).toString()
                val obj = org.json.JSONObject(resJson)
                if (obj.optString("status") != "success") {
                    withContext(Dispatchers.Main) {
                        _isEditorBusy.value = false
                        Toast.makeText(
                            context,
                            obj.optString("message", "Ошибка сохранения"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val finalPath = obj.optString("filepath", realPath).ifBlank { realPath }
                val sidecarCover = obj.optString("sidecar_cover", "")
                val newSeed = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()

                val updatedTrack = track.copy(
                    title = draft.title.ifBlank { track.title },
                    artist = draft.artist.ifBlank { track.artist },
                    album = draft.album,
                    path = finalPath,
                    albumArtPath = sidecarCover.ifBlank { track.albumArtPath },
                    albumArtSeed = newSeed
                )

                // запись текста
                repository.saveSyncedLyrics(updatedTrack.id, draft.lyrics)

                // скан файла
                try {
                    val mediaId = if (track.path.startsWith("content://")) {
                        track.path.substringAfterLast('/').toLongOrNull()
                    } else if (track.id.startsWith("media_")) {
                        track.id.removePrefix("media_").toLongOrNull()
                    } else null

                    if (mediaId != null && mediaId > 0) {
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            mediaId
                        )
                        val values = android.content.ContentValues().apply {
                            put(MediaStore.Audio.Media.TITLE, updatedTrack.title)
                            put(MediaStore.Audio.Media.ARTIST, updatedTrack.artist)
                            if (updatedTrack.album.isNotBlank()) {
                                put(MediaStore.Audio.Media.ALBUM, updatedTrack.album)
                            }
                            if (finalPath != realPath) {
                                put(MediaStore.Audio.Media.DATA, finalPath)
                                put(MediaStore.Audio.Media.DISPLAY_NAME, File(finalPath).name)
                            }
                        }
                        context.contentResolver.update(uri, values, null, null)
                    }
                } catch (_: Exception) {}

                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(finalPath, realPath),
                    null,
                    null
                )

                cachedNotificationTrackId = null
                cachedNotificationBitmap = null
                lastMetaTrackId = null

                withContext(Dispatchers.Main) {
                    try {
                        coil.Coil.imageLoader(context).memoryCache?.clear()
                    } catch (_: Exception) {}

                    val isTarget: (Track) -> Boolean = {
                        it.id == track.id || (it.path.isNotEmpty() && (it.path == track.path || it.path == realPath))
                    }
                    rawScannedTracks = rawScannedTracks.map { if (isTarget(it)) updatedTrack else it }
                    _allTracks.value = _allTracks.value.map { if (isTarget(it)) updatedTrack else it }
                    _playbackQueue.value = _playbackQueue.value.map { if (isTarget(it)) updatedTrack else it }

                    if (isCurrent) {
                        _currentTrack.value = updatedTrack
                        loadLyricsForTrack(updatedTrack)
                        if (finalPath != realPath) {
                            playerManager.playTrack(updatedTrack)
                            playerManager.seekTo(savedPosition)
                            if (!wasPlaying) playerManager.pause()
                        } else {
                            updatePlaybackMetadata(updatedTrack, wasPlaying)
                        }
                    }

                    closeTrackEditor()
                    Toast.makeText(context, "Трек успешно обновлен", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "saveEditedTrack failed", e)
                withContext(Dispatchers.Main) {
                    _isEditorBusy.value = false
                    Toast.makeText(context, "Ошибка записи тегов: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun publishToPublicMusicFolder(
        context: Context,
        sourceFile: File,
        title: String,
        artist: String,
        durationMs: Long
    ): String? {
        val ext = sourceFile.extension.lowercase().ifEmpty { "m4a" }
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "webm" -> "audio/webm"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/mp4"
        }
        val safeArtist = artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(40)
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(65)
        val fileName = if (safeArtist.isNotEmpty() && !safeTitle.contains(safeArtist, ignoreCase = true)) {
            "$safeArtist - $safeTitle.$ext"
        } else {
            "$safeTitle.$ext"
        }

        // запись в Muzix
        try {
            val publicMusicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val muzixDir = File(publicMusicRoot, "Muzix")
            if (!muzixDir.exists()) muzixDir.mkdirs()
            val targetFile = File(muzixDir, fileName)
            sourceFile.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            val sidecarSrc = File(sourceFile.parentFile, sourceFile.nameWithoutExtension + ".jpg")
            if (sidecarSrc.exists()) {
                val sidecarDst = File(muzixDir, targetFile.nameWithoutExtension + ".jpg")
                try { sidecarSrc.copyTo(sidecarDst, overwrite = true) } catch (_: Exception) {}
            }
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mime),
                null
            )
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w("MusicViewModel", "Direct public Music/Muzix write fallback to MediaStore", e)
        }

        // вставка mediastore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.TITLE, title)
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    put(MediaStore.Audio.Media.ALBUM, "Muzix Downloads")
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    if (durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Muzix")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    val publicPath = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "Muzix/$fileName"
                    )
                    return if (publicPath.exists()) publicPath.absolutePath else itemUri.toString()
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "MediaStore public save failed", e)
            }
        }
        return null
    }

    fun clearDownloadCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            try {
                context.cacheDir?.listFiles()?.forEach { file ->
                    val low = file.name.lowercase()
                    if (low.endsWith(".mp3") || low.endsWith(".m4a") || low.endsWith(".webm") || low.endsWith(".jpg") || low.endsWith(".part")) {
                        if (file.delete()) count++
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Failed to clear cache", e)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Кэш очищен (удалено файлов: $count)", Toast.LENGTH_SHORT).show()
                scanLocalTracks(context)
            }
        }
    }

    fun downloadYoutubeTrack(context: Context, queryOrUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadState.value = DownloadState.Parsing(queryOrUrl)
            try {
                val isLink = queryOrUrl.contains("youtube.com") || queryOrUrl.contains("youtu.be") || queryOrUrl.startsWith("http")
                
                val musicDir = if (_saveToRam.value) {
                    context.cacheDir
                } else {
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: throw Exception("Не удалось получить доступ к файловой системе")
                }
                if (!musicDir.exists()) musicDir.mkdirs()

                _downloadState.value = DownloadState.Parsing("Инициализация локального Python...")
                if (!ensurePython()) throw IllegalStateException("Python не запущен")
                val py = Python.getInstance()
                val downloaderModule = py.getModule("youtube_downloader")

                var title = if (!isLink) queryOrUrl else "YouTube Аудио"
                val thumbnailUrl: String? = null
                _downloadState.value = DownloadState.Downloading(title, 0, "Подключение к YouTube...", thumbnailUrl)
                
                val progressListener = object : ProgressCallback {
                    override fun onProgress(percent: Int, speedBytesPerSec: Long, etaSeconds: Int) {
                        val speedStr = when {
                            speedBytesPerSec > 1024 * 1024 -> String.format("%.1f МБ/с", speedBytesPerSec / (1024.0 * 1024.0))
                            speedBytesPerSec > 1024 -> String.format("%.0f КБ/с", speedBytesPerSec / 1024.0)
                            speedBytesPerSec > 0 -> "$speedBytesPerSec Б/с"
                            else -> ""
                        }
                        val etaStr = if (etaSeconds > 0) {
                            if (etaSeconds >= 60) "${etaSeconds / 60}м ${etaSeconds % 60}с" else "${etaSeconds}с"
                        } else ""

                        val statusParts = mutableListOf<String>()
                        if (speedStr.isNotEmpty()) statusParts.add(speedStr)
                        if (etaStr.isNotEmpty()) statusParts.add("осталось $etaStr")
                        val speedAndEta = statusParts.joinToString(" • ")

                        _downloadState.value = DownloadState.Downloading(title, percent, speedAndEta, thumbnailUrl)
                    }
                }

                // запуск загрузки
                val resultPy = downloaderModule.callAttr(
                    "download_audio_native",
                    queryOrUrl,
                    !isLink,
                    musicDir.absolutePath,
                    progressListener,
                    _downloaderFormat.value,
                    _downloaderThreads.value,
                    _maxDurationLimit.value,
                    _downloadQuality.value
                )

                val resultObj = org.json.JSONObject(resultPy.toString())
                val status = resultObj.optString("status", "error")
                if (status == "success") {
                    val filepath = resultObj.optString("filepath")
                    title = resultObj.optString("title", "YouTube Track")
                    val artist = resultObj.optString("uploader", "YouTube Downloader")
                    val file = File(filepath)
                    
                    // длительность трека
                    var duration = 180000L
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(filepath)
                        val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        retriever.release()
                        if (durationStr != null) {
                            duration = durationStr.toLong()
                        }
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Failed to extract duration for $filepath", e)
                    }

                    // экспорт трека
                    var finalPath = file.absolutePath
                    var artPath: String? = null
                    val privSidecar = File(file.parentFile, file.nameWithoutExtension + ".jpg")
                    if (privSidecar.exists()) artPath = privSidecar.absolutePath

                    if (!_saveToRam.value) {
                        val publicSaved = publishToPublicMusicFolder(context, file, title, artist, duration)
                        if (publicSaved != null) {
                            finalPath = publicSaved
                            if (!publicSaved.startsWith("content://")) {
                                val pubFile = File(publicSaved)
                                val pubSidecar = File(pubFile.parentFile, pubFile.nameWithoutExtension + ".jpg")
                                if (pubSidecar.exists()) artPath = pubSidecar.absolutePath
                                if (pubFile.exists() && file.exists() && pubFile.absolutePath != file.absolutePath) {
                                    try { file.delete() } catch (_: Exception) {}
                                }
                            }
                        }
                    }

                    val finalTrack = Track(
                        id = "file_${finalPath.hashCode().toString().replace("-", "n")}_${file.nameWithoutExtension.hashCode().toString().replace("-", "n")}",
                        title = title,
                        artist = artist,
                        duration = duration,
                        path = finalPath,
                        isLocal = true,
                        albumArtSeed = title.hashCode(),
                        albumArtPath = artPath
                    )

                    withContext(Dispatchers.Main) {
                        _lastDownloadedTrack.value = finalTrack
                        _downloadState.value = DownloadState.Success(finalTrack)
                        Toast.makeText(
                            context,
                            if (_saveToRam.value) "Сохранено в кэш RAM" else "Сохранено на устройство: Music/Muzix",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModelScope.launch {
                            val lyrics = """
                                [00:00.00] (Вступление загруженного трека)
                                [00:05.00] Вы слушаете: $title
                                [00:10.00] Исполнитель: $artist
                                [00:15.00] Сохранено на устройство в папку: Music/Muzix
                                [00:20.00] Вся обработка выполнена прямо на устройстве.
                                [00:27.00] (Приятного прослушивания)
                            """.trimIndent()
                            repository.saveSyncedLyrics(finalTrack.id, lyrics)
                        }
                        scanLocalTracks(context)
                    }
                } else {
                    val msg = resultObj.optString("message", "Неизвестная ошибка загрузки")
                    throw Exception(msg)
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Download error", e)
                withContext(Dispatchers.Main) {
                    _downloadState.value = DownloadState.Error(e.message ?: "Неизвестная ошибка загрузки")
                }
            }
        }
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    fun searchYoutube(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = YoutubeSearchState.Loading
            try {
                if (!ensurePython()) throw IllegalStateException("Python не запущен")
                val py = Python.getInstance()
                val downloaderModule = py.getModule("youtube_downloader")
                val resultsJson = downloaderModule.callAttr("search_audio", query, _searchResultsLimit.value).toString()
                val results = mutableListOf<YoutubeSearchResult>()
                val trimmed = resultsJson.trim()

                if (trimmed.startsWith("{")) {
                    val jsonObj = org.json.JSONObject(trimmed)
                    if (jsonObj.has("error") && jsonObj.optJSONArray("results")?.length() ?: 0 == 0) {
                        val errorMsg = jsonObj.optString("error", "Ошибка при поиске на YouTube")
                        withContext(Dispatchers.Main) {
                            _searchState.value = YoutubeSearchState.Error(errorMsg)
                        }
                        return@launch
                    }
                    val arr = jsonObj.optJSONArray("results")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            results.add(
                                YoutubeSearchResult(
                                    id = item.optString("id"),
                                    title = item.optString("title"),
                                    url = item.optString("url"),
                                    duration = item.optDouble("duration", 0.0),
                                    uploader = item.optString("uploader"),
                                    thumbnail = item.optString("thumbnail", "")
                                )
                            )
                        }
                    }
                } else if (trimmed.startsWith("[")) {
                    val resultsArray = org.json.JSONArray(trimmed)
                    for (i in 0 until resultsArray.length()) {
                        val item = resultsArray.getJSONObject(i)
                        results.add(
                            YoutubeSearchResult(
                                id = item.optString("id"),
                                title = item.optString("title"),
                                url = item.optString("url"),
                                duration = item.optDouble("duration", 0.0),
                                uploader = item.optString("uploader"),
                                thumbnail = item.optString("thumbnail", "")
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    _searchState.value = YoutubeSearchState.Success(results)
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Search error", e)
                withContext(Dispatchers.Main) {
                    _searchState.value = YoutubeSearchState.Error("Не удалось получить результаты: ${e.localizedMessage}")
                }
            }
        }
    }

    fun resetSearchState() {
        _searchState.value = YoutubeSearchState.Idle
    }

    fun setAudioBufferSize(size: String) {
        _audioBufferSize.value = size
        playerManager.setAudioBufferSize(size)
        prefs.edit().putString("audio_buffer_size", size).apply()
    }

    fun setPowerSavingMode(enabled: Boolean) {
        _powerSavingMode.value = enabled
        prefs.edit().putBoolean("power_saving_mode", enabled).apply()
    }

    fun setAutoScanOnStart(enabled: Boolean) {
        _autoScanOnStart.value = enabled
        prefs.edit().putBoolean("auto_scan_on_start", enabled).apply()
    }

    fun setDownloadQuality(quality: String) {
        _downloadQuality.value = quality
        prefs.edit().putString("download_quality", quality).apply()
    }

    fun setAccentColorIndex(index: Int) {
        _accentColorIndex.value = index
        prefs.edit().putInt("accent_color_index", index).apply()
    }

    fun setCustomAccentColor(color: Long) {
        _customAccentColor.value = color
        prefs.edit().putLong("custom_accent_color", color).apply()
    }

    fun setCrossfadeDuration(durationMs: Int) {
        _crossfadeDuration.value = durationMs
        playerManager.setCrossfadeDuration(durationMs)
        prefs.edit().putInt("crossfade_duration", durationMs).apply()
    }

    fun setAutoplayHeadset(enabled: Boolean) {
        _autoplayHeadset.value = enabled
        prefs.edit().putBoolean("autoplay_headset", enabled).apply()
    }

    fun setVisualizerType(type: String) {
        _visualizerType.value = type
        prefs.edit().putString("visualizer_type", type).apply()
    }

    fun setVolumeNormalize(enabled: Boolean) {
        _volumeNormalize.value = enabled
        playerManager.setVolumeNormalize(enabled)
        prefs.edit().putBoolean("volume_normalize", enabled).apply()
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerRemaining.value = minutes * 60
        sleepTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val remaining = _sleepTimerRemaining.value ?: 0
                if (remaining <= 1) {
                    _sleepTimerRemaining.value = null
                    if (_isPlaying.value) {
                        togglePlayPause()
                    }
                    break
                } else {
                    _sleepTimerRemaining.value = remaining - 1
                }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemaining.value = null
    }

    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    // проверка обновлений
    fun checkForAppUpdate(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _appUpdateState.value = AppUpdateState.Checking
            val info = AppUpdateManager.checkUpdate()
            if (info != null) {
                _appUpdateState.value = AppUpdateState.Available(info)
            } else {
                if (!silent) _appUpdateState.value = AppUpdateState.UpToDate
            }
        }
    }

    // скачивание и запуск установки
    fun startAppUpdate(context: Context, info: UpdateInfo) {
        viewModelScope.launch {
            _appUpdateState.value = AppUpdateState.Downloading(0f)
            val file = AppUpdateManager.downloadApk(context, info) { progress ->
                _appUpdateState.value = AppUpdateState.Downloading(progress)
            }
            if (file != null && file.exists()) {
                _appUpdateState.value = AppUpdateState.ReadyToInstall(file)
                AppUpdateManager.installApk(context, file)
            } else {
                _appUpdateState.value = AppUpdateState.Error("Не удалось скачать файл обновления")
            }
        }
    }

    // закрыть диалог
    fun dismissAppUpdate() {
        _appUpdateState.value = AppUpdateState.Idle
    }

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            _updateState.value = UpdateState.Checking
            try {
                // проверка сети
                val url = URL("https://www.google.com")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.responseCode

                delay(1000)

                withContext(Dispatchers.Main) {
                    _updateState.value = UpdateState.UpToDate
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Update check failed", e)
                withContext(Dispatchers.Main) {
                    _updateState.value = UpdateState.Error("Не удалось связаться с сервером обновлений. Проверьте подключение к Интернету.")
                }
            }
        }
    }

    private val pythonLock = Any()

    // запуск chaquopy
    private fun ensurePython(): Boolean = synchronized(pythonLock) {
        if (Python.isStarted()) return true
        try {
            Python.start(AndroidPlatform(getApplication()))
            true
        } catch (e: Throwable) {
            Log.e("MusicViewModel", "Chaquopy Python init failed", e)
            false
        }
    }

    fun checkServerStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _serverConnectionState.value = ServerConnectionState.Checking
            delay(500)
            withContext(Dispatchers.Main) {
                if (Python.isStarted()) {
                    _serverConnectionState.value = ServerConnectionState.Connected("Local Python (Chaquopy)")
                } else {
                    _serverConnectionState.value = ServerConnectionState.Connected("Local Python (Chaquopy), запуск по требованию")
                }
            }
        }
    }

    fun updateYtDlp() {
        viewModelScope.launch(Dispatchers.IO) {
            _ytdlpUpdateState.value = YtDlpUpdateState.Updating
            try {
                if (!ensurePython()) throw IllegalStateException("Python не запущен")
                val py = Python.getInstance()
                val downloaderModule = py.getModule("youtube_downloader")
                val resultPy = downloaderModule.callAttr("update_ytdlp_runtime")
                val resultObj = org.json.JSONObject(resultPy.toString())
                val status = resultObj.optString("status", "error")
                
                withContext(Dispatchers.Main) {
                    if (status == "success") {
                        val version = resultObj.optString("version", "unknown")
                        _ytdlpUpdateState.value = YtDlpUpdateState.Success("Компоненты обновлены до версии $version!")
                        _serverConnectionState.value = ServerConnectionState.Connected("Local Python (Chaquopy) v$version")
                    } else {
                        val msg = resultObj.optString("message", "Неизвестная ошибка")
                        _ytdlpUpdateState.value = YtDlpUpdateState.Error("Ошибка обновления: $msg")
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Failed to update yt-dlp", e)
                withContext(Dispatchers.Main) {
                    _ytdlpUpdateState.value = YtDlpUpdateState.Error("Ошибка при обновлении: ${e.message}")
                }
            }
        }
    }

    fun resetYtDlpUpdateState() {
        _ytdlpUpdateState.value = YtDlpUpdateState.Idle
    }

    override fun onCleared() {
        try {
            getApplication<Application>().unregisterReceiver(headsetPlugReceiver)
        } catch (e: Exception) {}
        PlayerHub.commandHandler = null
        mediaControllerFuture?.let { MediaController.releaseFuture(it) }
        playerManager.stop()
        playerManager.release()
        super.onCleared()
    }
}
