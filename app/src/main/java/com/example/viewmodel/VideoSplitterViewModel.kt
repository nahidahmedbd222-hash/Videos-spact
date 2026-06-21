package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.database.AppDatabase
import com.example.database.HistoryEntity
import com.example.database.HistoryRepository
import com.example.splitter.NativeVideoSplitter
import com.example.splitter.VideoSplitterService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectedVideo(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val thumbnail: Bitmap? = null
)

class VideoSplitterViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    private val database = AppDatabase.getDatabase(context)
    private val repository = HistoryRepository(database.historyDao())

    // Theme logic - true = dark theme, false = light theme
    val isDarkTheme = MutableStateFlow(true)

    // Selection flow
    private val _selectedVideos = MutableStateFlow<List<SelectedVideo>>(emptyList())
    val selectedVideos: StateFlow<List<SelectedVideo>> = _selectedVideos.asStateFlow()

    // Interval flow: preset split seconds or custom
    val selectedIntervalSec = MutableStateFlow(60) // Default 1 min (60 seconds)
    val customMinutes = MutableStateFlow("")
    val customSeconds = MutableStateFlow("")

    // Estimated remaining calculation stats
    private var batchStartTime: Long = 0L

    // Service binding flow
    val currentProgress: StateFlow<VideoSplitterService.Companion.ServiceProgress?> = 
        VideoSplitterService.liveProgress.asStateFlow()

    // History Flow from Room Database
    val historyItems: StateFlow<List<HistoryEntity>> = repository.allHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun toggleTheme() {
        isDarkTheme.value = !isDarkTheme.value
    }

    /**
     * Resolves selected media videos, fetches details and pre-renders local bitmaps
     */
    fun selectVideos(uris: List<Uri>) {
        viewModelScope.launch {
            val resolvedList = uris.map { uri ->
                withContext(Dispatchers.IO) {
                    val name = getFileName(uri) ?: "Selected Video"
                    var duration = 0L
                    var size = 0L
                    var thumb: Bitmap? = null

                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        duration = durationStr?.toLong() ?: 0L
                        
                        // Extract frame at first second as thumbnail
                        thumb = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        size = pfd?.statSize ?: 0L
                        pfd?.close()
                    } catch (e: Exception) {
                        Log.e("VideoSplitterViewModel", "Metadata extraction failed: ${e.message}")
                    } finally {
                        retriever.release()
                    }

                    SelectedVideo(
                        uri = uri,
                        name = name,
                        durationMs = duration,
                        sizeBytes = size,
                        thumbnail = thumb
                    )
                }
            }
            _selectedVideos.value = resolvedList
        }
    }

    fun removeSelectedVideo(video: SelectedVideo) {
        _selectedVideos.value = _selectedVideos.value.filter { it != video }
    }

    fun clearSelections() {
        _selectedVideos.value = emptyList()
    }

    fun startSplittingProcess() {
        val uris = _selectedVideos.value.map { it.uri.toString() }
        if (uris.isEmpty()) return

        // Resolve interval text for reports
        val sec = selectedIntervalSec.value
        val intervalText = when (sec) {
            60 -> "1 Min"
            120 -> "2 Min"
            300 -> "5 Min"
            600 -> "10 Min"
            900 -> "15 Min"
            else -> {
                if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
            }
        }

        batchStartTime = System.currentTimeMillis()

        val intent = Intent(context, VideoSplitterService::class.java).apply {
            action = VideoSplitterService.ACTION_START_SPLIT
            putStringArrayListExtra(VideoSplitterService.EXTRA_VIDEO_URIS, ArrayList(uris))
            putExtra(VideoSplitterService.EXTRA_INTERVAL_SEC, sec)
            putExtra(VideoSplitterService.EXTRA_INTERVAL_TEXT, intervalText)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Calculates the estimated duration remaining for split task
     */
    fun calculateRemainingTimeFormatted(progressPercent: Float): String {
        if (progressPercent <= 1f) return "Calculating..."
        val elapsedTime = System.currentTimeMillis() - batchStartTime
        val estimatedTotal = (elapsedTime / progressPercent) * 100f
        val remainingMs = (estimatedTotal - elapsedTime).toLong()

        if (remainingMs <= 0L) return "Finished shortly..."
        
        val totalSec = remainingMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) "${min}m ${sec}s remaining" else "${sec}s remaining"
    }

    fun deleteHistoryRecord(historyItem: HistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(historyItem)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAll()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        result = it.getString(idx)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
