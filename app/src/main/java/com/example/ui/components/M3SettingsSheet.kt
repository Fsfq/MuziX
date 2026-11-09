package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.screens.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3SettingsSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    monetEnabled: Boolean,
    onToggleMonet: (Boolean) -> Unit,
    themeMode: String,
    onSelectThemeMode: (String) -> Unit,
    accentColorIndex: Int,
    onSelectAccentColor: (Int) -> Unit,
    customAccentColor: Long = 0xFFF9B9BCL,
    onSelectCustomAccentColor: (Long) -> Unit = {},
    crossfadeDuration: Int,
    onChangeCrossfade: (Int) -> Unit,
    minTrackDurationSec: Int = 0,
    onChangeMinTrackDuration: (Int) -> Unit = {},
    autoplayHeadset: Boolean,
    onToggleAutoplayHeadset: () -> Unit,
    volumeNormalize: Boolean,
    onToggleVolumeNormalize: () -> Unit,
    autoScanOnStart: Boolean = true,
    onToggleAutoScanOnStart: (Boolean) -> Unit = {},
    downloaderFormat: String = "mp3",
    onSelectDownloaderFormat: (String) -> Unit = {},
    downloadQuality: String = "320 kbps",
    onSelectDownloadQuality: (String) -> Unit = {},
    saveToRam: Boolean = false,
    onToggleSaveToRam: (Boolean) -> Unit = {},
    onRescanLibrary: () -> Unit = {},
    onClearCache: () -> Unit = {},
    enlargedUi: Boolean = false,
    onToggleEnlargedUi: () -> Unit = {},
    uiScalePercent: Float = 12.0f,
    onSelectUiScalePercent: (Float) -> Unit = {},
    dynamicIslandEnabled: Boolean = false,
    onToggleDynamicIsland: (Boolean) -> Unit = {},
    onOpenEqualizer: (() -> Unit)? = null
) {
    if (!show) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            SettingsScreen(
                monetEnabled = monetEnabled,
                onToggleMonet = onToggleMonet,
                themeMode = themeMode,
                onSelectThemeMode = onSelectThemeMode,
                accentColorIndex = accentColorIndex,
                onSelectAccentColor = onSelectAccentColor,
                customAccentColor = customAccentColor,
                onSelectCustomAccentColor = onSelectCustomAccentColor,
                crossfadeDuration = crossfadeDuration,
                onChangeCrossfade = onChangeCrossfade,
                minTrackDurationSec = minTrackDurationSec,
                onChangeMinTrackDuration = onChangeMinTrackDuration,
                autoplayHeadset = autoplayHeadset,
                onToggleAutoplayHeadset = onToggleAutoplayHeadset,
                volumeNormalize = volumeNormalize,
                onToggleVolumeNormalize = onToggleVolumeNormalize,
                autoScanOnStart = autoScanOnStart,
                onToggleAutoScanOnStart = onToggleAutoScanOnStart,
                downloaderFormat = downloaderFormat,
                onSelectDownloaderFormat = onSelectDownloaderFormat,
                downloadQuality = downloadQuality,
                onSelectDownloadQuality = onSelectDownloadQuality,
                saveToRam = saveToRam,
                onToggleSaveToRam = onToggleSaveToRam,
                onRescanLibrary = onRescanLibrary,
                onClearCache = onClearCache,
                enlargedUi = enlargedUi,
                onToggleEnlargedUi = onToggleEnlargedUi,
                uiScalePercent = uiScalePercent,
                onSelectUiScalePercent = onSelectUiScalePercent,
                dynamicIslandEnabled = dynamicIslandEnabled,
                onToggleDynamicIsland = onToggleDynamicIsland,
                onOpenEqualizer = onOpenEqualizer,
                onClose = onDismiss
            )
        }
    }
}
