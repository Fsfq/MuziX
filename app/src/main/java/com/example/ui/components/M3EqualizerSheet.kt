package com.example.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.ViperAudioDsp
import kotlin.math.roundToInt

@Composable
private fun PlayerSegmentedButtonGroup(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    outerCorner: Dp = 14.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            val innerCorner by animateDpAsState(
                targetValue = if (isSelected) 9.dp else 5.dp,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 500f),
                label = "segInnerCorner"
            )
            val shape = when {
                items.size == 1 -> RoundedCornerShape(outerCorner)
                index == 0 -> RoundedCornerShape(
                    topStart = outerCorner,
                    bottomStart = outerCorner,
                    topEnd = innerCorner,
                    bottomEnd = innerCorner
                )
                index == items.lastIndex -> RoundedCornerShape(
                    topStart = innerCorner,
                    bottomStart = innerCorner,
                    topEnd = outerCorner,
                    bottomEnd = outerCorner
                )
                else -> RoundedCornerShape(innerCorner)
            }

            val containerColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                animationSpec = spring(stiffness = 500f),
                label = "segBg"
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = spring(stiffness = 500f),
                label = "segFg"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
                animationSpec = spring(stiffness = 500f),
                label = "segBorder"
            )

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .bouncyClickable(scaleDown = 0.94f) { onSelect(index) },
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                border = BorderStroke(if (isSelected) 1.2.dp else 1.dp, borderColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.5.sp,
                            letterSpacing = (-0.2).sp
                        ),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = contentColor
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3EqualizerSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    state: ViperAudioDsp.State,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectPreset: (String) -> Unit,
    onBandLevelChange: (Int, Float) -> Unit,
    onPreampChange: (Float) -> Unit,
    onBassChange: (Boolean, ViperAudioDsp.BassMode, Int, Float) -> Unit,
    onClarityChange: (Boolean, ViperAudioDsp.ClarityMode, Float) -> Unit,
    onAnalogXChange: (Boolean) -> Unit,
    onSurroundChange: (Short) -> Unit,
    onReverbChange: (Short) -> Unit,
    onReset: () -> Unit
) {
    if (!show) return

    val presets = remember {
        listOf(
            "Flat",
            "Bass Boost",
            "Pop",
            "Rock",
            "Electronic",
            "Jazz",
            "Classical",
            "Vocal Booster",
            "Acoustic",
            "Deep",
            "Loudness"
        )
    }

    val reverbRow1 = remember {
        listOf(
            0.toShort() to "Выкл",
            1.toShort() to "Студия",
            2.toShort() to "Комната"
        )
    }
    val reverbRow2 = remember {
        listOf(
            3.toShort() to "Клуб",
            4.toShort() to "Зал",
            6.toShort() to "Plate"
        )
    }

    val bassFrequencies = remember { listOf(40, 60, 80, 100) }
    val bandLabels = remember { listOf("60 Гц", "230 Гц", "910 Гц", "3.6 кГц", "14 кГц") }
    val tabs = remember { listOf("Эквалайзер", "FsFqX Бас", "Clarity 3D") }
    var selectedTab by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var isProMode by remember { mutableStateOf(prefs.getBoolean("settings_is_pro", false)) }
    var showTutorial by remember { mutableStateOf(!prefs.getBoolean("eq_tutorial_completed", false)) }
    var tutorialStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(show) {
        if (show) {
            isProMode = prefs.getBoolean("settings_is_pro", false)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LaunchedEffect(tutorialStep, showTutorial) {
            if (showTutorial) {
                if (tutorialStep <= 1) {
                    scrollState.animateScrollTo(0)
                } else if (tutorialStep >= 3) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            // шапка эквалайзера
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "FsFqX DSP",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (state.enabled) "Активен" else "Отключен",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (state.enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isProMode) {
                        Surface(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .bouncyClickable(scaleDown = 0.90f) {
                                    selectedTab = 0
                                    tutorialStep = 0
                                    showTutorial = true
                                },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.School,
                                    contentDescription = "Обучение",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .bouncyClickable(scaleDown = 0.90f) { onReset() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Сбросить DSP",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .bouncyClickable(scaleDown = 0.90f) { onDismiss() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier
                            .scale(0.80f)
                            .padding(end = 2.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        onClick = {
                            isProMode = false
                            prefs.edit().putBoolean("settings_is_pro", false).apply()
                        },
                        modifier = Modifier.weight(1f).height(34.dp),
                        shape = RoundedCornerShape(11.dp),
                        color = if (!isProMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = BorderStroke(1.dp, if (!isProMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = if (!isProMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Обычный",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (!isProMode) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isProMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            isProMode = true
                            prefs.edit().putBoolean("settings_is_pro", true).apply()
                        },
                        modifier = Modifier.weight(1f).height(34.dp),
                        shape = RoundedCornerShape(11.dp),
                        color = if (isProMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = BorderStroke(1.dp, if (isProMode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = if (isProMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "PRO",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isProMode) FontWeight.Bold else FontWeight.Medium,
                                color = if (isProMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedVisibility(visible = state.enabled) {
                if (!isProMode) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Пресеты",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(presets) { _, preset ->
                                val isSelected = state.activePreset == preset
                                Surface(
                                    onClick = { onSelectPreset(preset) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                        Text(
                                            text = preset,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "ЧАСТОТЫ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                val bassVal = state.eqBandLevels.getOrElse(0) { 0f }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Бас (Низкие)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = (if (bassVal > 0) "+${bassVal.roundToInt()}" else "${bassVal.roundToInt()}") + " dB",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = bassVal,
                                    onValueChange = {
                                        onBandLevelChange(0, it)
                                        onBandLevelChange(1, it * 0.75f)
                                    },
                                    valueRange = -12f..12f,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    )
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                Spacer(modifier = Modifier.height(8.dp))

                                val midVal = state.eqBandLevels.getOrElse(2) { 0f }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Вокал (Средние)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = (if (midVal > 0) "+${midVal.roundToInt()}" else "${midVal.roundToInt()}") + " dB",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = midVal,
                                    onValueChange = { onBandLevelChange(2, it) },
                                    valueRange = -12f..12f,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    )
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                Spacer(modifier = Modifier.height(8.dp))

                                val trebleVal = state.eqBandLevels.getOrElse(4) { 0f }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Высокие (Четкость)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = (if (trebleVal > 0) "+${trebleVal.roundToInt()}" else "${trebleVal.roundToInt()}") + " dB",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = trebleVal,
                                    onValueChange = {
                                        onBandLevelChange(3, it * 0.75f)
                                        onBandLevelChange(4, it)
                                    },
                                    valueRange = -12f..12f,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "ЭФФЕКТЫ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = "Усиление баса",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Глубокий плотный низ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    Switch(
                                        checked = state.bassEnabled,
                                        onCheckedChange = { enable ->
                                            onBassChange(
                                                enable,
                                                state.bassMode,
                                                state.bassFreq,
                                                if (state.bassGainDb <= 0f) 6f else state.bassGainDb
                                            )
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                                Spacer(modifier = Modifier.height(10.dp))

                                val is3dActive = state.clarityEnabled || state.surroundStrength > 0
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = "Объемный 3D звук",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Пространственное звучание",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    Switch(
                                        checked = is3dActive,
                                        onCheckedChange = { enable ->
                                            onClarityChange(
                                                enable,
                                                state.clarityMode,
                                                if (state.clarityGainDb <= 0f) 5f else state.clarityGainDb
                                            )
                                            onSurroundChange(if (enable) 2.toShort() else 0.toShort())
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        PlayerSegmentedButtonGroup(
                            items = tabs,
                            selectedIndex = selectedTab,
                            onSelect = { selectedTab = it },
                            height = 40.dp,
                            outerCorner = 15.dp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val slideSpring = spring<androidx.compose.ui.unit.IntOffset>(
                            dampingRatio = 0.82f,
                            stiffness = 380f
                        )
                        val fadeSpring = spring<Float>(stiffness = 420f)
                        (slideInHorizontally(animationSpec = slideSpring) { fullWidth ->
                            if (forward) fullWidth / 2 else -fullWidth / 2
                        } + fadeIn(animationSpec = fadeSpring) + scaleIn(
                            initialScale = 0.96f,
                            animationSpec = fadeSpring
                        )).togetherWith(
                            slideOutHorizontally(animationSpec = slideSpring) { fullWidth ->
                                if (forward) -fullWidth / 2 else fullWidth / 2
                            } + fadeOut(animationSpec = fadeSpring) + scaleOut(
                                targetScale = 0.96f,
                                animationSpec = fadeSpring
                            )
                        ).using(SizeTransform(clip = false))
                    },
                    label = "eqPageAnimatedContent"
                ) { tabIndex ->
                    when (tabIndex) {
                        0 -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Пресеты звучания",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    itemsIndexed(presets) { idx, preset ->
                                        val isSelected = state.activePreset == preset
                                        val inner by animateDpAsState(
                                            targetValue = if (isSelected) 9.dp else 5.dp,
                                            animationSpec = spring(dampingRatio = 0.72f, stiffness = 500f),
                                            label = "presetCorner"
                                        )
                                        val shape = when (idx) {
                                            0 -> RoundedCornerShape(
                                                topStart = 14.dp,
                                                bottomStart = 14.dp,
                                                topEnd = inner,
                                                bottomEnd = inner
                                            )
                                            presets.lastIndex -> RoundedCornerShape(
                                                topStart = inner,
                                                bottomStart = inner,
                                                topEnd = 14.dp,
                                                bottomEnd = 14.dp
                                            )
                                            else -> RoundedCornerShape(inner)
                                        }
                                        val bg by animateColorAsState(
                                            targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            animationSpec = spring(stiffness = 500f),
                                            label = "presetBg"
                                        )
                                        val fg by animateColorAsState(
                                            targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            animationSpec = spring(stiffness = 500f),
                                            label = "presetFg"
                                        )
                                        Surface(
                                            modifier = Modifier
                                                .height(36.dp)
                                                .clip(shape)
                                                .bouncyClickable(scaleDown = 0.94f) { onSelectPreset(preset) },
                                            shape = shape,
                                            color = bg,
                                            border = BorderStroke(
                                                if (isSelected) 1.2.dp else 1.dp,
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                            )
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(horizontal = 13.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = preset,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = fg
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                AnimatedVisibility(
                                    visible = showTutorial && tutorialStep == 0,
                                    enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                                    exit = shrinkVertically(spring(dampingRatio = 0.85f, stiffness = 450f)) + fadeOut()
                                ) {
                                    TutorialBubble(
                                        stepIndex = 0,
                                        title = "Предусилитель",
                                        text = "Общая громкость до эквалайзера. Защищает от хрипа и перегруза (клиппинга) при сильном усилении частот.",
                                        tip = "Совет: поднимаешь частоты выше +3 dB — опусти преамп на пару децибел.",
                                        onPrev = {},
                                        onNext = { tutorialStep = 1 },
                                        onSkip = {
                                            showTutorial = false
                                            prefs.edit().putBoolean("eq_tutorial_completed", true).apply()
                                        }
                                    )
                                }

                                val isPreampHighlighted = showTutorial && tutorialStep == 0
                                val preampBg by animateColorAsState(
                                    targetValue = if (isPreampHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
                                    animationSpec = spring(stiffness = 500f),
                                    label = "preampSpotlight"
                                )
                                val preampAlpha by animateFloatAsState(
                                    targetValue = if (showTutorial && !isPreampHighlighted) 0.35f else 1.0f,
                                    animationSpec = spring(stiffness = 400f),
                                    label = "preampAlpha"
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(preampBg)
                                        .border(
                                            width = if (isPreampHighlighted) 1.5.dp else 0.dp,
                                            color = if (isPreampHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.Transparent,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .padding(horizontal = if (isPreampHighlighted) 10.dp else 0.dp, vertical = if (isPreampHighlighted) 6.dp else 0.dp)
                                        .graphicsLayer { alpha = preampAlpha }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Предусилитель (Preamp)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = (if (state.preampDb > 0) "+${String.format("%.1f", state.preampDb)}" else String.format("%.1f", state.preampDb)) + " dB",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Slider(
                                        value = state.preampDb,
                                        onValueChange = onPreampChange,
                                        valueRange = -10f..10f,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = "Полосы частот",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                for (i in 0 until 5) {
                                    val currentVal = state.eqBandLevels.getOrElse(i) { 0f }
                                    val isBandHighlighted = showTutorial && tutorialStep == (i + 1)
                                    val bandBg by animateColorAsState(
                                        targetValue = if (isBandHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
                                        animationSpec = spring(stiffness = 500f),
                                        label = "bandSpotlight_$i"
                                    )
                                    val bandAlpha by animateFloatAsState(
                                        targetValue = if (showTutorial && !isBandHighlighted) 0.35f else 1.0f,
                                        animationSpec = spring(stiffness = 400f),
                                        label = "bandAlpha_$i"
                                    )

                                    val (bandTitle, bandText, bandTip) = when (i) {
                                        0 -> Triple(
                                            "60 Гц • Суб-бас",
                                            "Самый глубокий бас (сабвуфер, 808 бочка). Больше ощущается вибрацией, чем ушами.",
                                            "Совет: добавляет глубины в рэпе и электронике. Убавь, если динамики гудят или хрипят."
                                        )
                                        1 -> Triple(
                                            "230 Гц • Мид-бас и панч",
                                            "Плотность удара бочки, бас-гитара и теплота голоса. Задает треку вес и напор.",
                                            "Совет: избыток делает звук глухим, как из коробки. Убавление убирает мутность."
                                        )
                                        2 -> Triple(
                                            "910 Гц • Средние частоты",
                                            "Основной диапазон вокала, гитар и клавишных. Отвечает за разборчивость слов.",
                                            "Совет: подними на +2 dB, если голос тонет в музыке. Опусти, если звук телефонный."
                                        )
                                        3 -> Triple(
                                            "3.6 кГц • Верхняя середина",
                                            "Ясность речи, звон струн и щелчок барабана. Делает звук близким и ярким.",
                                            "Совет: если согласные (с, ц, щ) свистят и режут слух — убавь на 2–3 dB."
                                        )
                                        else -> Triple(
                                            "14 кГц • Воздух и детали",
                                            "Тарелки ударных, обертоны вокала и открытость сцены. Добавляет верхних частот.",
                                            "Совет: добавляет прозрачности. На старых или шумных треках может усилить шипение."
                                        )
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 400f))
                                    ) {
                                        AnimatedVisibility(
                                            visible = isBandHighlighted,
                                            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(),
                                            exit = shrinkVertically(spring(dampingRatio = 0.85f, stiffness = 450f)) + fadeOut()
                                        ) {
                                            TutorialBubble(
                                                stepIndex = i + 1,
                                                title = bandTitle,
                                                text = bandText,
                                                tip = bandTip,
                                                onPrev = { tutorialStep-- },
                                                onNext = {
                                                    if (tutorialStep < 5) tutorialStep++
                                                    else {
                                                        showTutorial = false
                                                        prefs.edit().putBoolean("eq_tutorial_completed", true).apply()
                                                    }
                                                },
                                                onSkip = {
                                                    showTutorial = false
                                                    prefs.edit().putBoolean("eq_tutorial_completed", true).apply()
                                                }
                                            )
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(bandBg)
                                                .border(
                                                    width = if (isBandHighlighted) 1.5.dp else 0.dp,
                                                    color = if (isBandHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.Transparent,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .padding(horizontal = if (isBandHighlighted) 8.dp else 0.dp, vertical = if (isBandHighlighted) 4.dp else 1.dp)
                                                .graphicsLayer { alpha = bandAlpha },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = bandLabels.getOrElse(i) { "Band $i" },
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.width(58.dp),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Slider(
                                                value = currentVal,
                                                onValueChange = { onBandLevelChange(i, it) },
                                                valueRange = -12f..12f,
                                                modifier = Modifier.weight(1f),
                                                colors = SliderDefaults.colors(
                                                    thumbColor = MaterialTheme.colorScheme.primary,
                                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                                )
                                            )
                                            Text(
                                                text = (if (currentVal > 0) "+${currentVal.roundToInt()}" else "${currentVal.roundToInt()}") + " dB",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.End,
                                                modifier = Modifier.width(44.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        1 -> {
                            val bassModes = ViperAudioDsp.BassMode.values()
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "FsFqX Bass",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Фазокогерентный сабвуфер + лимитер",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = state.bassEnabled,
                                            onCheckedChange = {
                                                onBassChange(it, state.bassMode, state.bassFreq, state.bassGainDb)
                                            },
                                            modifier = Modifier.scale(0.9f),
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = state.bassEnabled) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        Text(
                                            text = "Режим сабвуфера",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        PlayerSegmentedButtonGroup(
                                            items = bassModes.map { it.title },
                                            selectedIndex = bassModes.indexOf(state.bassMode).coerceAtLeast(0),
                                            onSelect = { idx ->
                                                onBassChange(
                                                    state.bassEnabled,
                                                    bassModes[idx],
                                                    state.bassFreq,
                                                    state.bassGainDb
                                                )
                                            },
                                            height = 38.dp,
                                            outerCorner = 14.dp
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Text(
                                            text = "Частота удара (Cutoff)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        PlayerSegmentedButtonGroup(
                                            items = bassFrequencies.map { "$it Гц" },
                                            selectedIndex = bassFrequencies.indexOf(state.bassFreq).coerceAtLeast(1),
                                            onSelect = { idx ->
                                                onBassChange(
                                                    state.bassEnabled,
                                                    state.bassMode,
                                                    bassFrequencies[idx],
                                                    state.bassGainDb
                                                )
                                            },
                                            height = 38.dp,
                                            outerCorner = 14.dp
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Мощность баса",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "+${String.format("%.1f", state.bassGainDb)} dB",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = state.bassGainDb,
                                            onValueChange = {
                                                onBassChange(state.bassEnabled, state.bassMode, state.bassFreq, it)
                                            },
                                            valueRange = 0f..18f,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = SliderDefaults.colors(
                                                thumbColor = MaterialTheme.colorScheme.primary,
                                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                            )
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Text(
                                                text = state.bassMode.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            val clarityModes = ViperAudioDsp.ClarityMode.values()
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "FsFqX Clarity",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Детализация вокала и высоких частот",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = state.clarityEnabled,
                                            onCheckedChange = {
                                                onClarityChange(it, state.clarityMode, state.clarityGainDb)
                                            },
                                            modifier = Modifier.scale(0.9f),
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = state.clarityEnabled) {
                                    Column(modifier = Modifier.padding(top = 12.dp)) {
                                        Text(
                                            text = "Режим четкости",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        PlayerSegmentedButtonGroup(
                                            items = clarityModes.map { it.title },
                                            selectedIndex = clarityModes.indexOf(state.clarityMode).coerceAtLeast(0),
                                            onSelect = { idx ->
                                                onClarityChange(
                                                    state.clarityEnabled,
                                                    clarityModes[idx],
                                                    state.clarityGainDb
                                                )
                                            },
                                            height = 38.dp,
                                            outerCorner = 14.dp
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Уровень четкости",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "+${String.format("%.1f", state.clarityGainDb)} dB",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = state.clarityGainDb,
                                            onValueChange = {
                                                onClarityChange(state.clarityEnabled, state.clarityMode, it)
                                            },
                                            valueRange = 0f..12f,
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = SliderDefaults.colors(
                                                thumbColor = MaterialTheme.colorScheme.primary,
                                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "AnalogX (Ламповое тепло)",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Насыщение четными гармониками",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = state.analogXEnabled,
                                            onCheckedChange = onAnalogXChange,
                                            modifier = Modifier.scale(0.9f),
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                                checkedTrackColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "3D Пространство (Surround)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${(state.surroundStrength.toFloat() / 10f).roundToInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = state.surroundStrength.toFloat(),
                                    onValueChange = { onSurroundChange(it.toInt().toShort()) },
                                    valueRange = 0f..1000f,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Реверберация помещения",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                val row1Idx = reverbRow1.indexOfFirst { it.first == state.reverbPreset }
                                val row2Idx = reverbRow2.indexOfFirst { it.first == state.reverbPreset }

                                PlayerSegmentedButtonGroup(
                                    items = reverbRow1.map { it.second },
                                    selectedIndex = row1Idx,
                                    onSelect = { idx -> onReverbChange(reverbRow1[idx].first) },
                                    height = 36.dp,
                                    outerCorner = 14.dp
                                )
                                Spacer(modifier = Modifier.height(5.dp))
                                PlayerSegmentedButtonGroup(
                                    items = reverbRow2.map { it.second },
                                    selectedIndex = row2Idx,
                                    onSelect = { idx -> onReverbChange(reverbRow2[idx].first) },
                                    height = 36.dp,
                                    outerCorner = 14.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

@Composable
private fun TutorialBubble(
    stepIndex: Int,
    title: String,
    text: String,
    tip: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val bubbleBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bubbleBg,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.2.dp, bubbleBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${stepIndex + 1}/6",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        onClick = onSkip,
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent
                    ) {
                        Text(
                            text = "Пропустить",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = tip,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (stepIndex > 0) {
                        Surface(
                            onClick = onPrev,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.height(30.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Назад",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Surface(
                        onClick = onNext,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(30.dp)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (stepIndex < 5) "Далее →" else "Понятно!",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }

        Canvas(
            modifier = Modifier
                .padding(start = 28.dp)
                .size(width = 16.dp, height = 8.dp)
        ) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
            drawPath(path, color = bubbleBg)
        }
    }
}
