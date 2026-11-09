package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.Track
import com.example.util.AudioCover
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.roundToInt

@Composable
fun M3MiniPlayer(
    currentTrack: Track?,
    isPlaying: Boolean,
    positionFlow: StateFlow<Long>? = null,
    positionProvider: () -> Long = { 0L },
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onStop: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    isWaveActive: Boolean = false,
    onDislikeWave: (() -> Unit)? = null,
    repeatMode: Boolean,
    onToggleRepeat: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val livePosition = if (positionFlow != null) {
        val flowPos by positionFlow.collectAsStateWithLifecycle()
        flowPos
    } else {
        positionProvider()
    }
    var showQuickActions by remember { mutableStateOf(false) }
    var swipeOffsetX by remember { mutableFloatStateOf(0f) }
    val animatedSwipeOffset by animateFloatAsState(
        targetValue = swipeOffsetX,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "miniPlayerSwipe"
    )

    val motionSpec = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.86f, stiffness = 450f)
    val fadeSpec = spring<Float>(stiffness = 450f)

    AnimatedVisibility(
        visible = currentTrack != null,
        enter = slideInVertically(animationSpec = motionSpec) { it } + fadeIn(animationSpec = fadeSpec),
        exit = slideOutVertically(animationSpec = motionSpec) { it } + fadeOut(animationSpec = fadeSpec),
        modifier = modifier
    ) {
        if (currentTrack == null) return@AnimatedVisibility

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedSwipeOffset.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        swipeOffsetX += delta * 0.5f
                    },
                    onDragStopped = { velocity ->
                        if (swipeOffsetX < -40f || velocity < -300f) {
                            onSkipNext()
                        } else if (swipeOffsetX > 40f || velocity > 300f) {
                            onSkipPrevious()
                        }
                        swipeOffsetX = 0f
                    }
                )
                .clip(RoundedCornerShape(22.dp))
                .animateContentSize(spring(dampingRatio = 0.78f, stiffness = 420f))
                .bouncyCombinedClickable(
                    onClick = {
                        showQuickActions = false
                        onClick()
                    },
                    onLongClick = {
                        showQuickActions = !showQuickActions
                    }
                ),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xC4121214),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(
                1.dp,
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.06f),
                        Color.Transparent
                    )
                )
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(
                    visible = showQuickActions,
                    enter = expandVertically(spring(dampingRatio = 0.78f, stiffness = 420f)) + fadeIn(),
                    exit = shrinkVertically(spring(dampingRatio = 0.85f, stiffness = 450f)) + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.60f))
                            .padding(top = 8.dp, bottom = 6.dp, start = 8.dp, end = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .bouncyCombinedClickable(
                                        scaleDown = 0.90f,
                                        onClick = { onToggleFavorite() },
                                        onLongClick = {
                                            if (isWaveActive) {
                                                onDislikeWave?.invoke()
                                            }
                                        }
                                    ),
                                shape = CircleShape,
                                color = if (isFavorite) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    1.dp,
                                    if (isFavorite) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Лайкнуть (зажмите для дизлайка волны)",
                                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Surface(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .bouncyClickable(scaleDown = 0.90f) { onSkipPrevious() },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.SkipPrevious,
                                        contentDescription = "Назад",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Surface(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .bouncyClickable(scaleDown = 0.92f) { onPlayPause() },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                tonalElevation = 4.dp,
                                shadowElevation = 8.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Пауза" else "Воспроизведение",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Surface(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .bouncyClickable(scaleDown = 0.90f) { onSkipNext() },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.SkipNext,
                                        contentDescription = "Вперёд",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Surface(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .bouncyClickable(scaleDown = 0.90f) { onToggleRepeat() },
                                shape = CircleShape,
                                color = if (repeatMode) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    1.dp,
                                    if (repeatMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (repeatMode) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                        contentDescription = "Зациклить",
                                        tint = if (repeatMode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(
                            thickness = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        val artModel: Any? = remember(
                            currentTrack.path,
                            currentTrack.albumArtPath,
                            currentTrack.albumArtSeed
                        ) {
                            when {
                                !currentTrack.albumArtPath.isNullOrEmpty() -> {
                                    if (currentTrack.albumArtPath.startsWith("/")) File(currentTrack.albumArtPath)
                                    else currentTrack.albumArtPath
                                }
                                currentTrack.path.isNotEmpty() -> AudioCover(currentTrack.path)
                                else -> null
                            }
                        }
                        var isLoadFailed by remember(
                            currentTrack.path,
                            currentTrack.albumArtPath,
                            currentTrack.albumArtSeed
                        ) { mutableStateOf(false) }

                        if (artModel != null && !isLoadFailed) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val imageRequest = remember(artModel) {
                                coil.request.ImageRequest.Builder(context)
                                    .data(artModel)
                                    .size(128, 128)
                                    .crossfade(false)
                                    .build()
                            }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = currentTrack.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onError = { isLoadFailed = true }
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = com.example.R.drawable.ic_notification_logo),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTrack.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = currentTrack.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Surface(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .bouncyClickable(scaleDown = 0.90f) { onPlayPause() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Пауза" else "Воспроизведение",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                val duration = currentTrack.duration.coerceAtLeast(1L)
                LinearProgressIndicator(
                    progress = {
                        val pos = livePosition
                        (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }
    }
}

@Composable
fun M3MiniPlayer(
    currentTrack: Track?,
    isPlaying: Boolean,
    positionProvider: () -> Long,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onStop: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    isWaveActive: Boolean = false,
    onDislikeWave: (() -> Unit)? = null,
    repeatMode: Boolean,
    onToggleRepeat: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    M3MiniPlayer(
        currentTrack = currentTrack,
        isPlaying = isPlaying,
        positionFlow = null,
        positionProvider = positionProvider,
        onPlayPause = onPlayPause,
        onSkipPrevious = onSkipPrevious,
        onSkipNext = onSkipNext,
        onStop = onStop,
        isFavorite = isFavorite,
        onToggleFavorite = onToggleFavorite,
        isWaveActive = isWaveActive,
        onDislikeWave = onDislikeWave,
        repeatMode = repeatMode,
        onToggleRepeat = onToggleRepeat,
        onClick = onClick,
        modifier = modifier
    )
}
