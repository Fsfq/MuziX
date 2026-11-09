package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.Track
import com.example.ui.components.bouncyClickable
import com.example.ui.components.formatDuration
import com.example.util.AudioCover
import java.io.File

// экран волны

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaveScreen(
    isWaveActive: Boolean,
    isWaveLoading: Boolean,
    isPlaying: Boolean,
    waveCharacter: String,
    waveMood: String,
    waveSource: String,
    waveLanguage: String = "Все",
    waveStatusText: String,
    currentTrack: Track?,
    positionFlow: kotlinx.coroutines.flow.StateFlow<Long>? = null,
    onSeek: (Long) -> Unit = {},
    waveLoadingProgress: Float = 0f,
    waveLoadingRemainingText: String = "",
    onSkipWavePreparation: () -> Unit = {},
    visualizerBandsFlow: kotlinx.coroutines.flow.StateFlow<FloatArray> = kotlinx.coroutines.flow.MutableStateFlow(FloatArray(5) { 0.04f }),
    isFavorite: Boolean,
    onToggleWavePlay: () -> Unit,
    onSkipNextWave: () -> Unit,
    onDislikeCurrent: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadCurrentWaveTrack: () -> Unit,
    onApplyWaveSettings: (character: String, mood: String, source: String, language: String) -> Unit,
    onResetBannedWave: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettingsSheet by remember { mutableStateOf(false) }

    val activePlaying = isWaveActive && isPlaying
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    val bands by visualizerBandsFlow.collectAsStateWithLifecycle()
    val targetPulse = if (activePlaying && bands.isNotEmpty()) {
        val b0 = bands.getOrElse(0) { 0.1f }.coerceIn(0f, 1f)
        val b1 = bands.getOrElse(1) { 0.1f }.coerceIn(0f, 1f)
        (b0 * 0.7f + b1 * 0.3f).coerceIn(0f, 1f)
    } else 0f
    val pulseEnergy by animateFloatAsState(
        targetValue = targetPulse,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f),
        label = "wavePulse"
    )

    val smoothedBars = remember { FloatArray(10) { 0.12f } }
    var waveFrameNanos by remember { mutableLongStateOf(0L) }
    var lastWaveNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(activePlaying) {
        if (!activePlaying) {
            for (i in 0 until 10) smoothedBars[i] = 0.12f
            lastWaveNanos = 0L
            return@LaunchedEffect
        }
        while (activePlaying) {
            withFrameNanos { now ->
                waveFrameNanos = now
            }
        }
    }

    val morphProgress by animateFloatAsState(
        targetValue = if (activePlaying) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 320f),
        label = "morphProgress"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "waveMorphSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Моя волна",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .bouncyClickable(scaleDown = 0.93f) { showSettingsSheet = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Настроить волну",
                            tint = primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Настроить",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .padding(horizontal = 6.dp)
                ) {
                    val now = waveFrameNanos
                    val dt = if (lastWaveNanos == 0L || now == 0L) 0.016f else ((now - lastWaveNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                    lastWaveNanos = now

                    val barCount = 10
                    val spacing = 10.dp.toPx()
                    val totalSpacing = spacing * (barCount - 1)
                    val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(8f)
                    val maxBarHeight = size.height * 0.94f
                    val centerY = size.height / 2f
                    val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    val barColor = if (activePlaying) {
                        primary.copy(alpha = 0.50f)
                    } else {
                        primaryContainer.copy(alpha = 0.42f)
                    }

                    for (i in 0 until barCount) {
                        val bandIdx = if (i < 5) i else 9 - i
                        val bandVal = bands.getOrElse(bandIdx) { 0.08f }
                        val target = if (activePlaying) {
                            (bandVal * 0.75f + pulseEnergy * 0.25f + 0.12f).coerceIn(0.12f, 1f)
                        } else {
                            0.12f
                        }

                        val cur = smoothedBars[i]
                        val speed = if (target > cur) 18f else 7f
                        val factor = (1f - kotlin.math.exp(-speed * dt)).coerceIn(0f, 1f)
                        val level = cur + (target - cur) * factor
                        smoothedBars[i] = level

                        val h = (maxBarHeight * level).coerceAtLeast(barWidth * 1.2f)
                        val left = i * (barWidth + spacing)
                        val top = centerY - h / 2f

                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(left, top),
                            size = Size(barWidth, h),
                            cornerRadius = radius
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .clip(CircleShape)
                        .bouncyClickable(scaleDown = 0.92f) { onToggleWavePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    val buttonColor = if (activePlaying) primary else MaterialTheme.colorScheme.surfaceContainerHighest
                    val borderCol = if (activePlaying) primary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val baseR = minOf(cx, cy) * 0.90f
                        val steps = 96
                        val stepAngle = (2.0 * Math.PI / steps).toFloat()

                        val morphPath = Path()
                        for (i in 0 until steps) {
                            val angle = i * stepAngle

                            val r0 = 0.90f + 0.08f * kotlin.math.cos(4f * angle)

                            val r1 = 0.88f + (0.10f + 0.05f * pulseEnergy) * kotlin.math.cos(4f * angle)

                            val r = (1f - morphProgress) * r0 + morphProgress * r1
                            val radius = baseR * r

                            val x = cx + radius * kotlin.math.cos(angle)
                            val y = cy + radius * kotlin.math.sin(angle)

                            if (i == 0) morphPath.moveTo(x, y)
                            else morphPath.lineTo(x, y)
                        }
                        morphPath.close()

                        rotate(degrees = spinAngle * morphProgress, pivot = Offset(cx, cy)) {
                            drawPath(
                                path = morphPath,
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        (if (activePlaying) primary else primaryContainer).copy(alpha = 0.35f),
                                        Color.Transparent
                                    ),
                                    center = Offset(cx, cy),
                                    radius = baseR * 1.15f
                                )
                            )

                            drawPath(
                                path = morphPath,
                                color = buttonColor
                            )

                            drawPath(
                                path = morphPath,
                                color = borderCol,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )
                        }
                    }

                    if (isWaveLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(42.dp),
                            strokeWidth = 3.dp,
                            color = if (activePlaying) MaterialTheme.colorScheme.onPrimary
                            else primary
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (activePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Моя волна",
                                tint = if (activePlaying) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (activePlaying) "ПАУЗА" else "СТАРТ",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (activePlaying) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .bouncyClickable(scaleDown = 0.94f) { showSettingsSheet = true },
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (waveLanguage == "Все") "$waveSource  •  $waveCharacter  •  $waveMood"
                               else "$waveSource  •  $waveCharacter  •  $waveMood  •  $waveLanguage",
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            AnimatedVisibility(
                visible = isWaveLoading,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = 0.85f, stiffness = 450f)) + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(18.dp))
                    WavePreparationProgressCard(
                        progress = waveLoadingProgress,
                        remainingText = waveLoadingRemainingText,
                        statusText = waveStatusText,
                        onSkip = onSkipWavePreparation
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Spacer(modifier = Modifier.height(136.dp))
        }
    }

    if (showSettingsSheet) {
        M3WaveSettingsSheet(
            currentCharacter = waveCharacter,
            currentMood = waveMood,
            currentSource = waveSource,
            currentLanguage = waveLanguage,
            onDismiss = { showSettingsSheet = false },
            onApply = { newChar, newMood, newSource, newLang ->
                showSettingsSheet = false
                onApplyWaveSettings(newChar, newMood, newSource, newLang)
            },
            onResetBans = {
                onResetBannedWave()
                showSettingsSheet = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun M3WaveSettingsSheet(
    currentCharacter: String,
    currentMood: String,
    currentSource: String,
    currentLanguage: String,
    onDismiss: () -> Unit,
    onApply: (String, String, String, String) -> Unit,
    onResetBans: () -> Unit
) {
    val sources = remember { listOf("Сеть", "Локально", "Гибрид") }
    val characters = remember { listOf("Любимое", "Незнакомое", "Популярное") }
    val moods = remember { listOf("Бодрое", "Спокойное", "Мрачное", "Весёлое") }
    val languages = remember { listOf("Все", "Русские", "Английские", "Без слов") }

    val initialSource = remember(currentSource) {
        when (currentSource.lowercase().trim()) {
            "local", "локально", "локальный" -> "Локально"
            "hybrid", "гибрид", "гибридный" -> "Гибрид"
            else -> "Сеть"
        }
    }
    val initialChar = remember(currentCharacter) {
        when (currentCharacter.lowercase().trim()) {
            "discover", "незнакомое", "открытия" -> "Незнакомое"
            "popular", "популярное", "хиты" -> "Популярное"
            else -> "Любимое"
        }
    }
    val initialMood = remember(currentMood) {
        when (currentMood.lowercase().trim()) {
            "calm", "спокойное", "релакс" -> "Спокойное"
            "dark", "мрачное", "дарк" -> "Мрачное"
            "party", "весёлое", "веселое", "пати" -> "Весёлое"
            else -> "Бодрое"
        }
    }
    val initialLang = remember(currentLanguage) {
        when (currentLanguage.lowercase().trim()) {
            "русские", "рус", "ru", "russian" -> "Русские"
            "английские", "англ", "en", "english" -> "Английские"
            "без слов", "инструментал", "instrumental", "beats" -> "Без слов"
            else -> "Все"
        }
    }

    var selectedSource by remember(initialSource) { mutableStateOf(initialSource) }
    var selectedChar by remember(initialChar) { mutableStateOf(initialChar) }
    var selectedMood by remember(initialMood) { mutableStateOf(initialMood) }
    var selectedLang by remember(initialLang) { mutableStateOf(initialLang) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xEA141416),
                            Color(0xF5101012)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.06f),
                                Color.Transparent
                            )
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.28f))
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Настройки волны",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Персональный поток музыки",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Источник треков",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                WaveConnectedRow(
                    options = sources,
                    selected = selectedSource,
                    onSelect = { selectedSource = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Характер потока",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                WaveConnectedRow(
                    options = characters,
                    selected = selectedChar,
                    onSelect = { selectedChar = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Настроение",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                WaveConnectedRow(
                    options = moods,
                    selected = selectedMood,
                    onSelect = { selectedMood = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Язык и вокал",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                WaveConnectedRow(
                    options = languages,
                    selected = selectedLang,
                    onSelect = { selectedLang = it }
                )

                Spacer(modifier = Modifier.height(22.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .bouncyClickable(scaleDown = 0.94f) { onResetBans() },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.20f),
                                    Color.White.copy(alpha = 0.05f)
                                )
                            )
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Сбросить баны",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1.3f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .bouncyClickable(scaleDown = 0.94f) { onApply(selectedChar, selectedMood, selectedSource, selectedLang) },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.22f),
                        border = BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.35f),
                                    Color.White.copy(alpha = 0.10f)
                                )
                            )
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Применить",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun WaveConnectedRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.06f),
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.05f),
                    Color.Transparent
                )
            )
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
        ) {
            val totalWidth = maxWidth
            val count = options.size
            val selectedIndex = remember(options, selected) {
                val idx = options.indexOf(selected)
                if (idx >= 0) idx else 0
            }
            val itemWidth = totalWidth / count
            val pillPadding = 2.dp
            val pillWidth = (itemWidth - pillPadding * 2).coerceAtLeast(0.dp)

            val animatedFraction by animateFloatAsState(
                targetValue = selectedIndex.toFloat(),
                animationSpec = spring(
                    dampingRatio = 0.62f,
                    stiffness = 380f
                ),
                label = "wavePillOffset"
            )

            Surface(
                modifier = Modifier
                    .offset(x = itemWidth * animatedFraction + pillPadding)
                    .width(pillWidth)
                    .fillMaxHeight(),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.20f),
                shadowElevation = 0.dp,
                border = BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.25f)
                )
            ) {}

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { _, item ->
                    val isSel = item == selected
                    val fg by animateColorAsState(
                        targetValue = if (isSel) Color.White else Color.White.copy(alpha = 0.60f),
                        animationSpec = spring(stiffness = 450f),
                        label = "waveItemFg"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .bouncyClickable(scaleDown = 0.93f) { onSelect(item) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = if (count >= 4) 11.5.sp else 12.5.sp,
                                letterSpacing = (-0.2).sp
                            ),
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = fg,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WavePreparationProgressCard(
    progress: Float,
    remainingText: String,
    statusText: String,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "wavePrepProgress"
    )
    val pct = (animatedProgress * 100).toInt()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Подготовка",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (remainingText.isNotBlank()) remainingText else statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .bouncyClickable(scaleDown = 0.92f) { onSkip() },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Скип",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Скип",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Быстрый старт",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}
