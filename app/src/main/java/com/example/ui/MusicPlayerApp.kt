package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Track
import com.example.ui.components.*
import com.example.ui.screens.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerApp(
    viewModel: MusicViewModel = viewModel()
) {
    val context = LocalContext.current

    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val shuffleMode by viewModel.shuffleMode.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()

    val isWaveActive by viewModel.isWaveActive.collectAsStateWithLifecycle()
    val isWaveLoading by viewModel.isWaveLoading.collectAsStateWithLifecycle()
    val waveCharacter by viewModel.waveCharacter.collectAsStateWithLifecycle()
    val waveMood by viewModel.waveMood.collectAsStateWithLifecycle()
    val waveSource by viewModel.waveSource.collectAsStateWithLifecycle()
    val waveLanguage by viewModel.waveLanguage.collectAsStateWithLifecycle()
    val waveStatusText by viewModel.waveStatusText.collectAsStateWithLifecycle()

    val editingTrack by viewModel.editingTrack.collectAsStateWithLifecycle()
    val editingDraft by viewModel.editingDraft.collectAsStateWithLifecycle()
    val isEditorBusy by viewModel.isEditorBusy.collectAsStateWithLifecycle()

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val viewingPlaylist by viewModel.viewingPlaylist.collectAsStateWithLifecycle()
    val selectedPlaylistTracks by viewModel.selectedPlaylistTracks.collectAsStateWithLifecycle()

    val viperDspState by viewModel.viperDspState.collectAsStateWithLifecycle()

    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val favoriteTrackIds by viewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val downloaderFormat by viewModel.downloaderFormat.collectAsStateWithLifecycle()
    val downloadQuality by viewModel.downloadQuality.collectAsStateWithLifecycle()
    val downloaderThreads by viewModel.downloaderThreads.collectAsStateWithLifecycle()
    val saveToRam by viewModel.saveToRam.collectAsStateWithLifecycle()
    val maxDurationLimit by viewModel.maxDurationLimit.collectAsStateWithLifecycle()
    val searchResultsLimit by viewModel.searchResultsLimit.collectAsStateWithLifecycle()

    val monetEnabled by viewModel.monetEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColorIndex by viewModel.accentColorIndex.collectAsStateWithLifecycle()
    val customAccentColor by viewModel.customAccentColor.collectAsStateWithLifecycle()
    val audioBufferSize by viewModel.audioBufferSize.collectAsStateWithLifecycle()
    val crossfadeDuration by viewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val minTrackDurationSec by viewModel.minTrackDurationSec.collectAsStateWithLifecycle()
    val autoplayHeadset by viewModel.autoplayHeadset.collectAsStateWithLifecycle()
    val visualizerType by viewModel.visualizerType.collectAsStateWithLifecycle()
    val volumeNormalize by viewModel.volumeNormalize.collectAsStateWithLifecycle()
    val powerSavingMode by viewModel.powerSavingMode.collectAsStateWithLifecycle()
    val autoScanOnStart by viewModel.autoScanOnStart.collectAsStateWithLifecycle()
    val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val enlargedUi by viewModel.enlargedUi.collectAsStateWithLifecycle()
    val uiScalePercent by viewModel.uiScalePercent.collectAsStateWithLifecycle()
    val dynamicIslandEnabled by viewModel.dynamicIslandEnabled.collectAsStateWithLifecycle()

    val lyricsLines by viewModel.lyricsLines.collectAsStateWithLifecycle()
    val activeLyricsIndex by viewModel.activeLyricsLineIndex.collectAsStateWithLifecycle()
    val currentTrackColor by viewModel.currentTrackColor.collectAsStateWithLifecycle()
    val trackPalette by viewModel.trackPalette.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()
    var isPlayerExpanded by remember { mutableStateOf(false) }

    val isVisualizerNeeded = (pagerState.currentPage == 1 || pagerState.targetPage == 1) || isPlayerExpanded
    LaunchedEffect(isVisualizerNeeded) {
        viewModel.isVisualizerScreenVisible = isVisualizerNeeded
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> viewModel.isAppInForeground = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> viewModel.isAppInForeground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf<Track?>(null) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = isPlayerExpanded) {
        isPlayerExpanded = false
    }

    BackHandler(enabled = !isPlayerExpanded && viewingPlaylist != null) {
        viewModel.closePlaylistViewing()
    }

    BackHandler(enabled = !isPlayerExpanded && viewingPlaylist == null && pagerState.currentPage != 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.scanLocalTracks(context)
        viewModel.requestAllFilesAccessOnceIfNeeded(context)
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Файл удален", Toast.LENGTH_SHORT).show()
        }
        viewModel.clearPendingDeleteIntent()
        viewModel.scanLocalTracks(context)
    }

    val pendingDeleteIntent by viewModel.pendingDeleteIntent.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDeleteIntent) {
        pendingDeleteIntent?.let { pendingIntent ->
            try {
                val intentSenderRequest = androidx.activity.result.IntentSenderRequest
                    .Builder(pendingIntent.intentSender)
                    .build()
                deleteLauncher.launch(intentSenderRequest)
            } catch (_: Exception) {
                viewModel.clearPendingDeleteIntent()
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissionsNeeded = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsNeeded.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            viewModel.scanLocalTracks(context)
            viewModel.requestAllFilesAccessOnceIfNeeded(context)
        }
    }

    val snappySpringOffset = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 450f)
    val snappySpringFade = spring<Float>(stiffness = 450f)

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = viewingPlaylist == null && !isPlayerExpanded
        ) { page ->
            when (page) {
                0 -> TracksScreen(
                    tracks = allTracks,
                    currentTrack = currentTrack,
                    isPlaying = isPlaying,
                    onTrackSelect = { viewModel.selectLocalLibraryTrack(it) },
                    onShuffleAll = { viewModel.shuffleAllTracks() },
                    onScanTracks = { viewModel.scanLocalTracks(context) },
                    onAddToPlaylist = { showAddToPlaylistSheet = it },
                    onShareTrack = { viewModel.shareTrack(context, it) },
                    onDeleteTrack = { viewModel.deleteTrack(context, it) },
                    onEditTrack = { viewModel.openTrackEditor(context, it) },
                    onOpenEqualizer = { showEqualizerSheet = true },
                    onOpenSettings = { showSettingsSheet = true },
                    onOpenSleepTimer = { showSleepTimerDialog = true },
                    favoriteTrackIds = favoriteTrackIds
                )

                1 -> WaveScreen(
                    isWaveActive = isWaveActive,
                    isWaveLoading = isWaveLoading,
                    isPlaying = isPlaying,
                    waveCharacter = waveCharacter,
                    waveMood = waveMood,
                    waveSource = waveSource,
                    waveLanguage = waveLanguage,
                    waveStatusText = waveStatusText,
                    currentTrack = currentTrack,
                    positionFlow = viewModel.currentPosition,
                    onSeek = { viewModel.seekTo(it) },
                    waveLoadingProgress = viewModel.waveLoadingProgress.collectAsStateWithLifecycle().value,
                    waveLoadingRemainingText = viewModel.waveLoadingRemainingText.collectAsStateWithLifecycle().value,
                    onSkipWavePreparation = { viewModel.skipWavePreparation(context) },
                    visualizerBandsFlow = viewModel.visualizerBands,
                    isFavorite = currentTrack?.let { it.id in favoriteTrackIds } ?: false,
                    onToggleWavePlay = { viewModel.toggleWavePlay(context) },
                    onSkipNextWave = { viewModel.skipNextWaveTrack(context) },
                    onDislikeCurrent = { viewModel.dislikeCurrentWaveTrack(context) },
                    onToggleFavorite = { currentTrack?.id?.let { viewModel.toggleFavorite(it) } },
                    onDownloadCurrentWaveTrack = { viewModel.downloadCurrentWaveTrack(context) },
                    onApplyWaveSettings = { ch, md, src, lng -> viewModel.applyWaveSettings(context, ch, md, src, lng) },
                    onResetBannedWave = { viewModel.resetWaveBans(context) },
                    onOpenPlayer = { if (currentTrack != null) isPlayerExpanded = true }
                )

                2 -> PlaylistsScreen(
                    playlists = playlists,
                    viewingPlaylist = viewingPlaylist,
                    playlistTracks = selectedPlaylistTracks,
                    currentTrack = currentTrack,
                    isPlaying = isPlaying,
                    onSelectPlaylist = { pl -> viewModel.selectPlaylistForViewing(pl) },
                    onClosePlaylist = { viewModel.closePlaylistViewing() },
                    onCreatePlaylist = { showCreatePlaylistDialog = true },
                    onDeletePlaylist = { pl -> viewModel.deletePlaylist(pl.id) },
                    onTrackSelect = { tr -> viewModel.selectLocalLibraryTrack(tr) },
                    onShufflePlaylist = { viewModel.shuffleCurrentPlaylist() },
                    onRemoveTrackFromPlaylist = { tr ->
                        viewingPlaylist?.let { pl ->
                            viewModel.removeTrackFromPlaylist(pl.id, tr.id)
                        }
                    },
                    onEditTrack = { tr -> viewModel.openTrackEditor(context, tr) }
                )

                3 -> DownloaderScreen(
                    searchState = searchState,
                    downloadState = downloadState,
                    downloaderFormat = downloaderFormat,
                    downloadQuality = downloadQuality,
                    downloaderThreads = downloaderThreads,
                    saveToRam = saveToRam,
                    maxDurationLimit = maxDurationLimit,
                    searchResultsLimit = searchResultsLimit,
                    onFormatChange = { viewModel.setDownloaderFormat(it) },
                    onQualityChange = { viewModel.setDownloadQuality(it) },
                    onThreadsChange = { viewModel.setDownloaderThreads(it) },
                    onSaveToRamChange = { viewModel.setSaveToRam(it) },
                    onMaxDurationChange = { viewModel.setMaxDurationLimit(it) },
                    onSearchResultsLimitChange = { viewModel.setSearchResultsLimit(it) },
                    onSearch = { query -> viewModel.searchYoutube(query) },
                    onDownload = { result -> viewModel.downloadYoutubeTrack(context, result.url) },
                    onDownloadUrl = { url -> viewModel.downloadYoutubeTrack(context, url) },
                    onPlayTrack = { track -> viewModel.selectLocalLibraryTrack(track) },
                    onResetDownloadState = { viewModel.resetDownloadState() }
                )
            }
        }

        // нижний блок
        AnimatedVisibility(
            visible = !isPlayerExpanded,
            enter = slideInVertically(animationSpec = snappySpringOffset) { it } + fadeIn(animationSpec = snappySpringFade),
            exit = slideOutVertically(animationSpec = snappySpringOffset) { it } + fadeOut(animationSpec = snappySpringFade),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 10.dp, start = 12.dp, end = 12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (currentTrack != null) {
                    val isFav = currentTrack?.id?.let { it in favoriteTrackIds } ?: false
                    M3MiniPlayer(
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        positionProvider = { viewModel.currentPosition.value },
                        positionFlow = viewModel.currentPosition,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onSkipPrevious = { viewModel.playPreviousTrack() },
                        onSkipNext = {
                            if (isWaveActive) viewModel.skipNextWaveTrack(context)
                            else viewModel.playNextTrack()
                        },
                        onStop = { viewModel.stopPlayback() },
                        isFavorite = isFav,
                        onToggleFavorite = { currentTrack?.id?.let { viewModel.toggleFavorite(it) } },
                        isWaveActive = isWaveActive,
                        onDislikeWave = { viewModel.dislikeCurrentWaveTrack(context) },
                        repeatMode = repeatMode,
                        onToggleRepeat = { viewModel.toggleRepeatMode() },
                        onClick = { isPlayerExpanded = true },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                FloatingBottomBar(
                    selectedTab = pagerState.currentPage,
                    onSelectTab = { target ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(target)
                        }
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = isPlayerExpanded,
            enter = slideInVertically(animationSpec = snappySpringOffset) { it } + fadeIn(animationSpec = snappySpringFade),
            exit = slideOutVertically(animationSpec = snappySpringOffset) { it } + fadeOut(animationSpec = snappySpringFade)
        ) {
            val isWaveStream = currentTrack?.id?.startsWith("wave_") == true ||
                currentTrack?.path?.startsWith("http") == true
            M3PlayerSheet(
                track = currentTrack,
                isPlaying = isPlaying,
                positionProvider = { viewModel.currentPosition.value },
                positionFlow = viewModel.currentPosition,
                onSeek = { viewModel.seekTo(it) },
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onPlayNext = {
                    if (isWaveActive) viewModel.skipNextWaveTrack(context)
                    else viewModel.playNextTrack()
                },
                onPlayPrevious = { viewModel.playPreviousTrack() },
                shuffleMode = shuffleMode,
                onToggleShuffle = { viewModel.toggleShuffleMode() },
                repeatMode = repeatMode,
                onToggleRepeat = { viewModel.toggleRepeatMode() },
                onClose = { isPlayerExpanded = false },
                onShare = { currentTrack?.let { viewModel.shareTrack(context, it) } },
                onAddToPlaylist = { showAddToPlaylistSheet = currentTrack },
                lyricsLines = lyricsLines,
                activeLyricsIndex = activeLyricsIndex,
                visualizerData = viewModel.visualizerData.value,
                visualizerType = visualizerType,
                visualizerBandsProvider = { viewModel.visualizerBands.value },
                visualizerBandsFlow = viewModel.visualizerBands,
                trackAccentColor = Color(trackPalette.vibrant),
                secondaryAccentColor = Color(trackPalette.secondary),
                onOpenEqualizer = { showEqualizerSheet = true },
                onOpenSleepTimer = { showSleepTimerDialog = true },
                isFavorite = currentTrack?.let { it.id in favoriteTrackIds } ?: false,
                onToggleFavorite = { currentTrack?.id?.let { viewModel.toggleFavorite(it) } },
                onEditTrack = {
                    currentTrack?.let { viewModel.openTrackEditor(context, it) }
                },
                onDeleteTrack = {
                    currentTrack?.let { tr ->
                        isPlayerExpanded = false
                        viewModel.deleteTrack(context, tr)
                    }
                },
                isWaveStream = isWaveStream,
                onDislikeWave = { viewModel.dislikeCurrentWaveTrack(context) },
                onDownloadWave = { viewModel.downloadCurrentWaveTrack(context) }
            )
        }

        M3EqualizerSheet(
            show = showEqualizerSheet,
            onDismiss = { showEqualizerSheet = false },
            state = viperDspState,
            onToggleEnabled = { viewModel.setViperEnabled(it) },
            onSelectPreset = { viewModel.selectViperPreset(it) },
            onBandLevelChange = { index, level -> viewModel.setViperBandLevel(index, level) },
            onPreampChange = { viewModel.setPreampDb(it) },
            onBassChange = { enabled, mode, freq, gain -> viewModel.setViperBass(enabled, mode, freq, gain) },
            onClarityChange = { enabled, mode, gain -> viewModel.setViperClarity(enabled, mode, gain) },
            onAnalogXChange = { viewModel.setAnalogXEnabled(it) },
            onSurroundChange = { viewModel.setViperSurround(it) },
            onReverbChange = { viewModel.setViperReverb(it) },
            onReset = { viewModel.resetViperDsp() }
        )

        M3TrackEditorSheet(
            track = editingTrack,
            initialDraft = editingDraft,
            isBusy = isEditorBusy,
            onDismiss = { viewModel.closeTrackEditor() },
            onAutoFetchOnline = { title, artist, onResult ->
                viewModel.autoFetchTrackMetadataOnline(context, title, artist, onResult)
            },
            onSave = { tr, draft ->
                viewModel.saveEditedTrack(context, tr, draft)
            }
        )

        M3SettingsSheet(
            show = showSettingsSheet,
            onDismiss = { showSettingsSheet = false },
            monetEnabled = monetEnabled,
            onToggleMonet = { viewModel.setMonetEnabled(it) },
            themeMode = themeMode,
            onSelectThemeMode = { viewModel.setThemeMode(it) },
            accentColorIndex = accentColorIndex,
            onSelectAccentColor = { viewModel.setAccentColorIndex(it) },
            customAccentColor = customAccentColor,
            onSelectCustomAccentColor = { viewModel.setCustomAccentColor(it) },
            crossfadeDuration = crossfadeDuration,
            onChangeCrossfade = { viewModel.setCrossfadeDuration(it) },
            minTrackDurationSec = minTrackDurationSec,
            onChangeMinTrackDuration = { viewModel.setMinTrackDurationSec(it) },
            autoplayHeadset = autoplayHeadset,
            onToggleAutoplayHeadset = { viewModel.toggleAutoplayHeadset() },
            volumeNormalize = volumeNormalize,
            onToggleVolumeNormalize = { viewModel.toggleVolumeNormalize() },
            autoScanOnStart = autoScanOnStart,
            onToggleAutoScanOnStart = { viewModel.setAutoScanOnStart(it) },
            downloaderFormat = downloaderFormat,
            onSelectDownloaderFormat = { viewModel.setDownloaderFormat(it) },
            downloadQuality = downloadQuality,
            onSelectDownloadQuality = { viewModel.setDownloadQuality(it) },
            saveToRam = saveToRam,
            onToggleSaveToRam = { viewModel.setSaveToRam(it) },
            onRescanLibrary = {
                viewModel.scanLocalTracks(context)
                Toast.makeText(context, "Сканирование медиатеки...", Toast.LENGTH_SHORT).show()
            },
            onClearCache = { viewModel.clearDownloadCache(context) },
            enlargedUi = enlargedUi,
            onToggleEnlargedUi = { viewModel.toggleEnlargedUi() },
            uiScalePercent = uiScalePercent,
            onSelectUiScalePercent = { viewModel.setUiScalePercent(it) },
            dynamicIslandEnabled = dynamicIslandEnabled,
            onToggleDynamicIsland = { viewModel.setDynamicIslandEnabled(it) },
            onOpenEqualizer = {
                showSettingsSheet = false
                showEqualizerSheet = true
            }
        )

        CreatePlaylistDialog(
            show = showCreatePlaylistDialog,
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { viewModel.createPlaylist(it) }
        )

        AddToPlaylistSheet(
            track = showAddToPlaylistSheet,
            playlists = playlists,
            onDismiss = { showAddToPlaylistSheet = null },
            onSelectPlaylist = { pl ->
                showAddToPlaylistSheet?.let { tr ->
                    viewModel.addTrackToPlaylist(pl.id, tr)
                }
                showAddToPlaylistSheet = null
            },
            onCreateNewPlaylist = {
                showCreatePlaylistDialog = true
            }
        )

        SleepTimerDialog(
            show = showSleepTimerDialog,
            currentSecondsRemaining = sleepTimerRemaining,
            onDismiss = { showSleepTimerDialog = false },
            onSetTimerMinutes = { mins ->
                viewModel.startSleepTimer(mins)
                showSleepTimerDialog = false
            },
            onCancelTimer = {
                viewModel.cancelSleepTimer()
                showSleepTimerDialog = false
            }
        )
    }
}

@Composable
private fun FloatingBottomBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = remember {
        listOf(
            Triple("Треки", Icons.Default.MusicNote, 0),
            Triple("Волна", Icons.Default.GraphicEq, 1),
            Triple("Плейлисты", Icons.AutoMirrored.Filled.QueueMusic, 2),
            Triple("Загрузка", Icons.Default.Download, 3)
        )
    }

    // рамка навигации
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xB8121214),
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.28f),
                    Color.White.copy(alpha = 0.05f),
                    Color.Transparent
                )
            )
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            val totalWidth = maxWidth
            val tabCount = tabs.size
            val tabWidth = totalWidth / tabCount
            val pillPadding = 3.dp
            val pillWidth = (tabWidth - pillPadding * 2).coerceAtLeast(0.dp)

            // смещение плашки
            val animatedTabFraction by animateFloatAsState(
                targetValue = selectedTab.toFloat(),
                animationSpec = spring(
                    dampingRatio = 0.62f,
                    stiffness = 380f
                ),
                label = "navSpringPill"
            )

            // активная вкладка
            Surface(
                modifier = Modifier
                    .offset(x = tabWidth * animatedTabFraction + pillPadding)
                    .width(pillWidth)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp),
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = 0.16f),
                shadowElevation = 0.dp,
                border = BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.22f)
                )
            ) {}

            // иконки вкладок
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { (title, icon, index) ->
                    val isSelected = selectedTab == index
                    val fg by animateColorAsState(
                        targetValue = if (isSelected) Color.White
                        else Color.White.copy(alpha = 0.55f),
                        animationSpec = spring(stiffness = 450f),
                        label = "tabFg"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 400f),
                        label = "tabScale"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .bouncyClickable(scaleDown = 0.92f) { onSelectTab(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                                tint = fg
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                color = fg
                            )
                        }
                    }
                }
            }
        }
    }
}
