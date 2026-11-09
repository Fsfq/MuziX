package com.example.island

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.audio.PlayerHub
import kotlinx.coroutines.delay

@Composable
fun DynamicIslandOverlay(
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTrack by PlayerHub.currentIslandTrack.collectAsState()
    val isPlaying by PlayerHub.isIslandPlaying.collectAsState()
    val positionMs by PlayerHub.islandPositionMs.collectAsState()
    val fftBands by PlayerHub.islandFft.collectAsState()

    var isExpanded by remember { mutableStateOf(false) }

    // автоскрытие
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            delay(6000)
            isExpanded = false
        }
    }

    if (currentTrack == null) {
        Spacer(modifier = Modifier.size(1.dp))
        return
    }

    val targetWidth by animateDpAsState(
        targetValue = if (isExpanded) 340.dp else 168.dp,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 380f),
        label = "islandWidth"
    )
    val targetHeight by animateDpAsState(
        targetValue = if (isExpanded) 144.dp else 38.dp,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 380f),
        label = "islandHeight"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 26.dp else 19.dp,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 380f),
        label = "islandRadius"
    )

    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .width(targetWidth)
            .height(targetHeight)
            .clip(RoundedCornerShape(cornerRadius))
            .background(if (isExpanded) Color(0xF2121214) else Color(0xFF000000))
            .then(
                if (isExpanded) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { }
                        )
                    }
                } else {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { isExpanded = true },
                            onLongPress = { onOpenApp() }
                        )
                    }
                }
            )
            .padding(if (isExpanded) 12.dp else 6.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                fadeIn(spring(stiffness = 500f)) togetherWith fadeOut(spring(stiffness = 500f))
            },
            label = "islandContent"
        ) { expanded ->
            if (expanded) {
                ExpandedIslandContent(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    onOpenApp = {
                        isExpanded = false
                        onOpenApp()
                    },
                    onCollapse = { isExpanded = false }
                )
            } else {
                CompactIslandContent(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    fftBands = fftBands
                )
            }
        }
    }
}

@Composable
private fun CompactIslandContent(
    track: PlayerHub.IslandTrack?,
    isPlaying: Boolean,
    fftBands: FloatArray
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // обложка
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0xFF222222)),
            contentAlignment = Alignment.Center
        ) {
            if (track?.albumArtPath != null) {
                AsyncImage(
                    model = track.albumArtPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFFF9B9BC),
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // название
        Text(
            text = track?.title?.ifEmpty { "Muzix" } ?: "Muzix",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )

        // полоски эквалайзера
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(end = 4.dp)
        ) {
            val barCount = 4
            for (i in 0 until barCount) {
                val magnitude = if (isPlaying && i < fftBands.size) fftBands[i] else 0.15f
                val barHeight by animateDpAsState(
                    targetValue = if (isPlaying) (magnitude * 18f).coerceIn(4f, 18f).dp else 4.dp,
                    animationSpec = spring(dampingRatio = 0.65f, stiffness = 750f),
                    label = "barHeight$i"
                )
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (isPlaying) Color(0xFF00E676) else Color(0x66FFFFFF))
                )
            }
        }
    }
}

@Composable
private fun ExpandedIslandContent(
    track: PlayerHub.IslandTrack?,
    isPlaying: Boolean,
    positionMs: Long,
    onOpenApp: () -> Unit,
    onCollapse: () -> Unit
) {
    val durationMs = (track?.durationMs ?: 0L).coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // верхняя панель
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF222222)),
                contentAlignment = Alignment.Center
            ) {
                if (track?.albumArtPath != null) {
                    AsyncImage(
                        model = track.albumArtPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFFF9B9BC),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track?.title?.ifEmpty { "Muzix" } ?: "Muzix",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track?.artist?.ifEmpty { "Неизвестный исполнитель" } ?: "Неизвестный исполнитель",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = Color(0xAAFFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onOpenApp,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "В приложение",
                    tint = Color(0xCCFFFFFF),
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onCollapse,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Свернуть",
                    tint = Color(0xCCFFFFFF),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // прогресс трека
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape),
                color = Color(0xFFF9B9BC),
                trackColor = Color(0x33FFFFFF)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimeMs(positionMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color(0x88FFFFFF)
                )
                Text(
                    text = formatTimeMs(durationMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color(0x88FFFFFF)
                )
            }
        }

        // кнопки управления
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { PlayerHub.commandHandler?.onPrevious() },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Назад",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { PlayerHub.commandHandler?.onPlayPause() },
                shape = CircleShape,
                color = Color.White
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Воспроизведение",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            IconButton(
                onClick = { PlayerHub.commandHandler?.onNext() },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Вперед",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(java.util.Locale.US, "%d:%02d", m, s)
}
