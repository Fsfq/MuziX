package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.LyricLine
import com.example.data.Track
import com.example.util.AudioCover
import java.io.File
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class PlayerHeroMode {
    ARTWORK,
    LYRICS
}

@Composable
fun M3PlayerSheet(
    track: Track?,
    isPlaying: Boolean,
    positionFlow: kotlinx.coroutines.flow.StateFlow<Long>? = null,
    positionProvider: () -> Long = { 0L },
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    shuffleMode: Boolean,
    onToggleShuffle: () -> Unit,
    repeatMode: Boolean,
    onToggleRepeat: () -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: () -> Unit,
    lyricsLines: List<LyricLine>,
    activeLyricsIndex: Int,
    visualizerData: ByteArray = ByteArray(0),
    visualizerType: String = "",
    visualizerBandsProvider: () -> FloatArray = { FloatArray(5) { 0.04f } },
    visualizerBandsFlow: kotlinx.coroutines.flow.StateFlow<FloatArray>? = null,
    trackAccentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    secondaryAccentColor: androidx.compose.ui.graphics.Color = trackAccentColor,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onEditTrack: () -> Unit = {},
    onDeleteTrack: () -> Unit = {},
    isWaveStream: Boolean = false,
    onDislikeWave: () -> Unit = {},
    onDownloadWave: () -> Unit = {},
    onVisualizerActiveChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (track == null) return

    var heroMode by remember { mutableStateOf(PlayerHeroMode.ARTWORK) }
    var isVisualizerActive by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    LaunchedEffect(isVisualizerActive) {
        onVisualizerActiveChanged?.invoke(isVisualizerActive)
    }

    val animatedPrimary by animateColorAsState(targetValue = trackAccentColor, animationSpec = tween(500), label = "sheetPrimary")
    val animatedSecondary by animateColorAsState(targetValue = secondaryAccentColor, animationSpec = tween(500), label = "sheetSecondary")
    val surfaceColor = MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier.fillMaxSize(),
        color = surfaceColor
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Свернуть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 48.dp)
                ) {
                    Text(
                        text = "Now Playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist.ifBlank { "Music Library" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = heroMode,
                    transitionSpec = {
                        if (targetState == PlayerHeroMode.LYRICS) {
                            (slideInVertically(animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { height -> height } + fadeIn())
                                .togetherWith(slideOutVertically(animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { height -> -height } + fadeOut())
                        } else {
                            (slideInVertically(animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { height -> -height } + fadeIn())
                                .togetherWith(slideOutVertically(animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { height -> height } + fadeOut())
                        }
                    },
                    label = "heroStageTransition"
                ) { mode ->
                    when (mode) {
                        PlayerHeroMode.LYRICS -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .padding(vertical = 8.dp),
                                shape = RoundedCornerShape(32.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                                )
                            ) {
                                if (lyricsLines.isNotEmpty()) {
                                    val listState = rememberLazyListState()
                                    val coroutineScope = rememberCoroutineScope()

                                    LaunchedEffect(activeLyricsIndex) {
                                        if (activeLyricsIndex in lyricsLines.indices) {
                                            coroutineScope.launch {
                                                listState.animateScrollToItem((activeLyricsIndex - 2).coerceAtLeast(0))
                                            }
                                        }
                                    }

                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        itemsIndexed(lyricsLines) { index, line ->
                                            val isActive = index == activeLyricsIndex
                                            Text(
                                                text = line.text,
                                                style = if (isActive) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onSeek(line.timestampMs) }
                                                    .padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Текст песни отсутствует",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                        PlayerHeroMode.ARTWORK -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth(0.92f)
                                        .aspectRatio(1f)
                                        .shadow(
                                            elevation = 16.dp,
                                            shape = RoundedCornerShape(28.dp),
                                            spotColor = trackAccentColor.copy(alpha = 0.35f)
                                        )
                                        .clip(RoundedCornerShape(28.dp)),
                                    shape = RoundedCornerShape(28.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    val artModel: Any? = remember(track.path, track.albumArtPath, track.albumArtSeed) {
                                        when {
                                            !track.albumArtPath.isNullOrEmpty() -> {
                                                if (track.albumArtPath.startsWith("/")) File(track.albumArtPath) else track.albumArtPath
                                            }
                                            track.path.isNotEmpty() -> AudioCover(track.path)
                                            else -> null
                                        }
                                    }
                                    var isLoadFailed by remember(track.path, track.albumArtPath, track.albumArtSeed) { mutableStateOf(false) }

                                    if (artModel != null && !isLoadFailed) {
                                        AsyncImage(
                                            model = artModel,
                                            contentDescription = track.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            onError = { isLoadFailed = true }
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(id = com.example.R.drawable.ic_logo_white),
                                                contentDescription = null,
                                                modifier = Modifier.size(110.dp),
                                                tint = trackAccentColor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current

                    FilledIconButton(
                        onClick = onShare,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Поделиться",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (isWaveStream) {
                        Surface(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .bouncyCombinedClickable(
                                    scaleDown = 0.92f,
                                    onClick = {
                                        android.widget.Toast.makeText(context, "Зажмите для исключения из волны", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    onLongClick = onDislikeWave
                                ),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ThumbDownOffAlt,
                                    contentDescription = "Не нравится в волне (зажмите)",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .bouncyCombinedClickable(
                                scaleDown = 0.92f,
                                onClick = onToggleFavorite,
                                onLongClick = {
                                    if (isWaveStream) {
                                        onDislikeWave()
                                    }
                                }
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Избранное (зажмите для дизлайка волны)",
                                modifier = Modifier.size(18.dp),
                                tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            M3WavySlider(
                positionFlow = positionFlow,
                positionProvider = positionProvider,
                durationMs = track.duration,
                onSeek = onSeek,
                activeColor = animatedPrimary,
                inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = onPlayPrevious,
                    modifier = Modifier
                        .width(78.dp)
                        .height(58.dp)
                        .bouncyClickable(scaleDown = 0.94f) { onPlayPrevious() },
                    shape = ScallopedPrevButtonShape(cutoutDepthDp = 10.dp, cornerRadiusDp = 28.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Назад",
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .width(104.dp)
                        .height(64.dp)
                        .bouncyClickable(scaleDown = 0.94f) { onTogglePlayPause() },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Воспроизведение",
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                FilledIconButton(
                    onClick = onPlayNext,
                    modifier = Modifier
                        .width(78.dp)
                        .height(58.dp)
                        .bouncyClickable(scaleDown = 0.94f) { onPlayNext() },
                    shape = ScallopedNextButtonShape(cutoutDepthDp = 10.dp, cornerRadiusDp = 28.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Вперед",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onAddToPlaylist,
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            bottomStart = 18.dp,
                            topEnd = 6.dp,
                            bottomEnd = 6.dp
                        ),
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FormatListBulleted,
                                contentDescription = "В плейлист",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onOpenSleepTimer,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = "Таймер сна",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            heroMode = if (heroMode == PlayerHeroMode.LYRICS) PlayerHeroMode.ARTWORK else PlayerHeroMode.LYRICS
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(48.dp),
                        color = if (heroMode == PlayerHeroMode.LYRICS) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ChatBubbleOutline,
                                contentDescription = "Текст песни",
                                tint = if (heroMode == PlayerHeroMode.LYRICS) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onToggleRepeat,
                        shape = RoundedCornerShape(
                            topStart = 6.dp,
                            bottomStart = 6.dp,
                            topEnd = 18.dp,
                            bottomEnd = 18.dp
                        ),
                        modifier = Modifier.size(48.dp),
                        color = if (repeatMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = "Повтор",
                                tint = if (repeatMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Box {
                    FilledIconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Ещё",
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        shape = RoundedCornerShape(18.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        DropdownMenuItem(
                            text = { Text("Эквалайзер") },
                            leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onOpenEqualizer()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (shuffleMode) "Случайно: Вкл" else "Случайно: Выкл") },
                            leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                onToggleShuffle()
                            }
                        )
                        if (isWaveStream) {
                            DropdownMenuItem(
                                text = { Text("Скачать себе") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    onDownloadWave()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Не нравится в волне") },
                                leadingIcon = { Icon(Icons.Default.ThumbDownOffAlt, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    onDislikeWave()
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Редактировать трек") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    onEditTrack()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Поделиться") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    onShare()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить с устройства", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showMoreMenu = false
                                    onDeleteTrack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
fun M3PlayerSheet(
    track: Track?,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    shuffleMode: Boolean,
    onToggleShuffle: () -> Unit,
    repeatMode: Boolean,
    onToggleRepeat: () -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: () -> Unit,
    lyricsLines: List<LyricLine>,
    activeLyricsIndex: Int,
    visualizerData: ByteArray = ByteArray(0),
    visualizerType: String = "",
    visualizerBandsProvider: () -> FloatArray = { FloatArray(5) { 0.04f } },
    visualizerBandsFlow: kotlinx.coroutines.flow.StateFlow<FloatArray>? = null,
    trackAccentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onEditTrack: () -> Unit = {},
    onDeleteTrack: () -> Unit = {},
    isWaveStream: Boolean = false,
    onDislikeWave: () -> Unit = {},
    onDownloadWave: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    M3PlayerSheet(
        track = track,
        isPlaying = isPlaying,
        positionFlow = null,
        positionProvider = positionProvider,
        onSeek = onSeek,
        onTogglePlayPause = onTogglePlayPause,
        onPlayNext = onPlayNext,
        onPlayPrevious = onPlayPrevious,
        shuffleMode = shuffleMode,
        onToggleShuffle = onToggleShuffle,
        repeatMode = repeatMode,
        onToggleRepeat = onToggleRepeat,
        onClose = onClose,
        onShare = onShare,
        onAddToPlaylist = onAddToPlaylist,
        lyricsLines = lyricsLines,
        activeLyricsIndex = activeLyricsIndex,
        visualizerData = visualizerData,
        visualizerType = visualizerType,
        visualizerBandsProvider = visualizerBandsProvider,
        visualizerBandsFlow = visualizerBandsFlow,
        trackAccentColor = trackAccentColor,
        secondaryAccentColor = trackAccentColor,
        onOpenEqualizer = onOpenEqualizer,
        onOpenSleepTimer = onOpenSleepTimer,
        isFavorite = isFavorite,
        onToggleFavorite = onToggleFavorite,
        onEditTrack = onEditTrack,
        onDeleteTrack = onDeleteTrack,
        isWaveStream = isWaveStream,
        onDislikeWave = onDislikeWave,
        onDownloadWave = onDownloadWave,
        onVisualizerActiveChanged = null,
        modifier = modifier
    )
}
