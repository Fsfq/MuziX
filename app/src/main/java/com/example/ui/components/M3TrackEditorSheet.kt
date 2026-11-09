package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.Track
import com.example.util.AudioCover
import java.io.File
import java.io.FileOutputStream

data class TrackEditDraft(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val filename: String = "",
    val extension: String = "",
    val lyrics: String = "",
    val newCoverPath: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun M3TrackEditorSheet(
    track: Track?,
    initialDraft: TrackEditDraft?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onAutoFetchOnline: (title: String, artist: String, onResult: (TrackEditDraft) -> Unit) -> Unit,
    onSave: (Track, TrackEditDraft) -> Unit
) {
    if (track == null) return

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var title by remember(track.id, initialDraft) {
        mutableStateOf(initialDraft?.title?.ifBlank { track.title } ?: track.title)
    }
    var artist by remember(track.id, initialDraft) {
        mutableStateOf(initialDraft?.artist?.ifBlank { track.artist } ?: track.artist)
    }
    var album by remember(track.id, initialDraft) {
        mutableStateOf(initialDraft?.album?.ifBlank { track.album } ?: track.album)
    }
    val defaultBaseName = remember(track.path) {
        if (track.path.isNotEmpty() && !track.path.startsWith("content://") && !track.path.startsWith("http")) {
            File(track.path).nameWithoutExtension
        } else {
            track.title
        }
    }
    val defaultExt = remember(track.path) {
        if (track.path.isNotEmpty() && track.path.contains('.')) {
            "." + track.path.substringAfterLast('.')
        } else {
            ""
        }
    }
    var filename by remember(track.id, initialDraft) {
        mutableStateOf(initialDraft?.filename?.ifBlank { defaultBaseName } ?: defaultBaseName)
    }
    var lyrics by remember(track.id, initialDraft) {
        mutableStateOf(initialDraft?.lyrics ?: "")
    }
    var newCoverPath by remember(track.id, initialDraft) {
        mutableStateOf(initialDraft?.newCoverPath ?: "")
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val tempFile = File(context.cacheDir, "picked_cover_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 128) {
                    newCoverPath = tempFile.absolutePath
                }
            } catch (_: Exception) {
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isBusy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Редактор трека",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Обложка, теги, имя файла и текст",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { if (!isBusy) onDismiss() }) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

                        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    val previewModel: Any? = remember(newCoverPath, track.path, track.albumArtPath, track.albumArtSeed) {
                        when {
                            newCoverPath.isNotEmpty() -> File(newCoverPath)
                            !track.albumArtPath.isNullOrEmpty() -> {
                                if (track.albumArtPath.startsWith("/")) File(track.albumArtPath) else track.albumArtPath
                            }
                            track.path.isNotEmpty() -> AudioCover(track.path)
                            else -> null
                        }
                    }
                    var loadFailed by remember(previewModel) { mutableStateOf(false) }

                    if (previewModel != null && !loadFailed) {
                        AsyncImage(
                            model = previewModel,
                            contentDescription = "Обложка",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onError = { loadFailed = true }
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = com.example.R.drawable.ic_notification_logo),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .bouncyClickable(scaleDown = 0.94f) {
                                if (!isBusy) {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Выбрать из галереи",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .bouncyClickable(scaleDown = 0.94f) {
                                if (!isBusy) {
                                    onAutoFetchOnline(title, artist) { fetched ->
                                        if (fetched.title.isNotBlank()) title = fetched.title
                                        if (fetched.artist.isNotBlank()) artist = fetched.artist
                                        if (fetched.album.isNotBlank()) album = fetched.album
                                        if (fetched.newCoverPath.isNotBlank()) newCoverPath = fetched.newCoverPath
                                        if (fetched.lyrics.isNotBlank()) lyrics = fetched.lyrics
                                    }
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Найти HD и текст",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Название") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text("Исполнитель") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = album,
                onValueChange = { album = it },
                label = { Text("Альбом") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (!track.path.startsWith("content://") && !track.path.startsWith("http")) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    label = { Text("Имя файла на устройстве") },
                    suffix = {
                        if (defaultExt.isNotEmpty()) {
                            Text(
                                text = defaultExt,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = lyrics,
                onValueChange = { lyrics = it },
                label = { Text("Текст песни (LRC / обычный)") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight()
                        .clip(
                            RoundedCornerShape(
                                topStart = 22.dp,
                                bottomStart = 22.dp,
                                topEnd = 6.dp,
                                bottomEnd = 6.dp
                            )
                        )
                        .bouncyClickable(scaleDown = 0.94f) {
                            if (!isBusy) onDismiss()
                        },
                    shape = RoundedCornerShape(
                        topStart = 22.dp,
                        bottomStart = 22.dp,
                        topEnd = 6.dp,
                        bottomEnd = 6.dp
                    ),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Отмена",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1.4f)
                        .fillMaxHeight()
                        .clip(
                            RoundedCornerShape(
                                topStart = 6.dp,
                                bottomStart = 6.dp,
                                topEnd = 22.dp,
                                bottomEnd = 22.dp
                            )
                        )
                        .bouncyClickable(scaleDown = 0.94f) {
                            if (!isBusy) {
                                onSave(
                                    track,
                                    TrackEditDraft(
                                        title = title.trim().ifBlank { track.title },
                                        artist = artist.trim().ifBlank { track.artist },
                                        album = album.trim(),
                                        filename = filename.trim(),
                                        extension = defaultExt,
                                        lyrics = lyrics,
                                        newCoverPath = newCoverPath
                                    )
                                )
                            }
                        },
                    shape = RoundedCornerShape(
                        topStart = 6.dp,
                        bottomStart = 6.dp,
                        topEnd = 22.dp,
                        bottomEnd = 22.dp
                    ),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Сохранение...",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Сохранить в файл",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}
