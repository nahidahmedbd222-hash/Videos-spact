@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.splitter.NativeVideoSplitter
import java.io.File

data class SplitPartFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var fileList by remember { mutableStateOf<List<SplitPartFile>>(emptyList()) }
    var activePreviewFile by remember { mutableStateOf<File?>(null) }
    
    var renameTargetFile by remember { mutableStateOf<File?>(null) }
    var renameInputName by remember { mutableStateOf("") }
    
    var deleteTargetFile by remember { mutableStateOf<File?>(null) }

    // Scans folder for split parts
    fun loadFiles() {
        val outputFolder = NativeVideoSplitter.getOutputDirectory(context)
        val files = outputFolder.listFiles { f ->
            f.isFile && (f.name.endsWith(".mp4", ignoreCase = true) || 
                         f.name.endsWith(".mkv", ignoreCase = true) || 
                         f.name.endsWith(".mov", ignoreCase = true))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        val parsed = files.map { file ->
            var duration = 0L
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = durStr?.toLong() ?: 0L
            } catch (e: Exception) {
                // fallbacks
            } finally {
                retriever.release()
            }

            SplitPartFile(
                file = file,
                name = file.name,
                sizeBytes = file.length(),
                durationMs = duration
            )
        }
        fileList = parsed
    }

    LaunchedEffect(Unit) {
        loadFiles()
    }

    // Share all files helper
    fun shareAllClips() {
        if (fileList.isEmpty()) return
        val uris = ArrayList<Uri>()
        fileList.forEach { splitItem ->
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, splitItem.file)
            uris.add(uri)
        }

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share All Splits via..."))
    }

    // Share single file helper
    fun shareSingleClip(file: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Split Part via..."))
    }

    // Rename file helper
    fun renameFile(oldFile: File, newNameRaw: String) {
        var cleanNewName = newNameRaw.trim()
        if (cleanNewName.isEmpty()) return
        if (!cleanNewName.contains('.')) {
            val ext = oldFile.extension
            cleanNewName = if (ext.isNotEmpty()) "$cleanNewName.$ext" else "$cleanNewName.mp4"
        }

        val directory = oldFile.parentFile
        val targetFile = File(directory, cleanNewName)
        if (oldFile.renameTo(targetFile)) {
            loadFiles()
        }
    }

    // Delete file helper
    fun deleteFile(file: File) {
        if (file.exists()) {
            file.delete()
            loadFiles()
            if (activePreviewFile == file) {
                activePreviewFile = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Output Clips Preview", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("preview_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadFiles() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh folder")
                    }
                    if (fileList.isNotEmpty()) {
                        IconButton(onClick = { shareAllClips() }, modifier = Modifier.testTag("share_all_button")) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share All Chunks")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Media player container at the top (if any video is clicked)
            AnimatedVisibility(visible = activePreviewFile != null) {
                activePreviewFile?.let { selectedVideo ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(bottom = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .background(androidx.compose.ui.graphics.Color.Black)
                        ) {
                            VideoPlayer(videoFile = selectedVideo)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedVideo.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Ready to play & share",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { shareSingleClip(selectedVideo) }) {
                                Icon(imageVector = Icons.Default.Share, contentDescription = "Share item")
                            }
                            IconButton(
                                onClick = { activePreviewFile = null },
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close player")
                            }
                        }
                        Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }

            if (fileList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Empty Folder",
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "No split clips found in /Movies/VideoSplitter",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Split some videos on the Home screen to view them here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
                ) {
                    items(fileList) { splitItem ->
                        val isPlaying = activePreviewFile == splitItem.file
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { activePreviewFile = splitItem.file },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left Icon indicators
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(
                                            if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Add,
                                        contentDescription = "Playing indicator",
                                        tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = splitItem.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(
                                            text = formatDuration(splitItem.durationMs),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatSize(splitItem.sizeBytes),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row {
                                    IconButton(onClick = { shareSingleClip(splitItem.file) }) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share split",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = {
                                        renameTargetFile = splitItem.file
                                        renameInputName = splitItem.name.substringBeforeLast(".")
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Rename split",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { deleteTargetFile = splitItem.file }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete split",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Rename dialog popup
        if (renameTargetFile != null) {
            AlertDialog(
                onDismissRequest = { renameTargetFile = null },
                title = { Text("Rename Split File") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter a new file name without extension properties:")
                        OutlinedTextField(
                            value = renameInputName,
                            onValueChange = { renameInputName = it },
                            placeholder = { Text("e.g. holiday_video_part") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("rename_input_field")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            renameTargetFile?.let { file ->
                                renameFile(file, renameInputName)
                            }
                            renameTargetFile = null
                        }
                    ) {
                        Text("Rename")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameTargetFile = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete confirmation dialog
        if (deleteTargetFile != null) {
            AlertDialog(
                onDismissRequest = { deleteTargetFile = null },
                title = { Text("Delete split clip?") },
                text = { Text("This will permanently remove the requested video file from your system storage.") },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            deleteTargetFile?.let { file ->
                                deleteFile(file)
                            }
                            deleteTargetFile = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.onError)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTargetFile = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

/**
 * Modern Media3 Embedded Video Player component
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(videoFile: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            prepare()
            playWhenReady = true
        }
    }

    // Reprepare player if video file changes
    LaunchedEffect(videoFile) {
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
