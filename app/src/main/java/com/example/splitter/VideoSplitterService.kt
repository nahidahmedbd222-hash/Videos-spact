package com.example.splitter

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.database.AppDatabase
import com.example.database.HistoryEntity
import com.example.database.HistoryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class VideoSplitterService : Service() {

    companion object {
        private const val TAG = "VideoSplitterService"
        private const val CHANNEL_ID = "VideoSplitterServiceChannel"
        private const val NOTIFICATION_ID = 4592

        // Live task tracking Flow for Compose ViewModel binding
        val liveProgress = MutableStateFlow<ServiceProgress?>(null)

        // Action controls
        const val ACTION_START_SPLIT = "START_SPLIT"

        // Intent parameters
        const val EXTRA_VIDEO_URIS = "EXTRA_VIDEO_URIS"
        const val EXTRA_INTERVAL_SEC = "EXTRA_INTERVAL_SEC"
        const val EXTRA_INTERVAL_TEXT = "EXTRA_INTERVAL_TEXT"

        /**
         * Safe state model to keep progress
         */
        data class ServiceProgress(
            val percentage: Float,
            val currentPart: Int,
            val totalParts: Int,
            val currentVideoIndex: Int,
            val totalVideos: Int,
            val currentVideoName: String,
            val status: String,
            val isFinished: Boolean = false,
            val isFailed: Boolean = false,
            val errorMsg: String? = null
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SPLIT) {
            val videoUrisStrings = intent.getStringArrayListExtra(EXTRA_VIDEO_URIS) ?: emptyList()
            val intervalSec = intent.getIntExtra(EXTRA_INTERVAL_SEC, 60)
            val intervalText = intent.getStringExtra(EXTRA_INTERVAL_TEXT) ?: "$intervalSec sec"

            val videoUris = videoUrisStrings.map { Uri.parse(it) }

            // Cancel previous background jobs if any
            activeJob?.cancel()
            activeJob = serviceScope.launch {
                processVideoSplitting(videoUris, intervalSec, intervalText)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processVideoSplitting(
        videoUris: List<Uri>,
        intervalSec: Int,
        intervalText: String
    ) {
        val totalVideos = videoUris.size
        if (totalVideos == 0) {
            stopSelf()
            return
        }

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = HistoryRepository(database.historyDao())
        val outputDirectory = NativeVideoSplitter.getOutputDirectory(applicationContext)

        // Start Foreground Service with initial notification
        startForegroundServiceCompat(
            "Video Splitter Pro",
            "Initializing splitter queue for $totalVideos video(s)..."
        )

        try {
            for (index in 0 until totalVideos) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) break

                val originalUri = videoUris[index]
                val currentVideoNumber = index + 1
                
                // Get name of the video
                val rawName = getFileNameFromUri(originalUri) ?: "video_${System.currentTimeMillis()}"
                val fileName = if (rawName.contains('.')) rawName else "$rawName.mp4"

                // Extract details
                val retriever = android.media.MediaMetadataRetriever()
                var sizeBytes: Long = 0
                var originalDurationMs: Long = 0
                try {
                    retriever.setDataSource(applicationContext, originalUri)
                    originalDurationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                    
                    val pfd = applicationContext.contentResolver.openFileDescriptor(originalUri, "r")
                    sizeBytes = pfd?.statSize ?: 0L
                    pfd?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Retriever details failed: ${e.message}")
                } finally {
                    retriever.release()
                }

                updateNotificationProgress(
                    title = "Processing video $currentVideoNumber of $totalVideos",
                    statusText = "Splitting $fileName...",
                    progress = 0f
                )

                liveProgress.value = ServiceProgress(
                    percentage = 0f,
                    currentPart = 0,
                    totalParts = 0,
                    currentVideoIndex = currentVideoNumber,
                    totalVideos = totalVideos,
                    currentVideoName = fileName,
                    status = "Starting split task..."
                )

                // Execute the split natively
                val generatedFiles = NativeVideoSplitter.splitVideo(
                    context = applicationContext,
                    sourceUri = originalUri,
                    segmentDurationSec = intervalSec,
                    outputDir = outputDirectory,
                    basePartName = fileName,
                    onProgress = { partPercent, currentPart, totalParts, status ->
                        // Calculate total aggregate progress if batch processing
                        val aggregatePercent = ((index.toFloat() / totalVideos) * 100f) + (partPercent / totalVideos)
                        
                        updateNotificationProgress(
                            title = "Splitting video $currentVideoNumber/$totalVideos ($currentPart/$totalParts parts)",
                            statusText = fileName,
                            progress = aggregatePercent
                        )

                        liveProgress.value = ServiceProgress(
                            percentage = aggregatePercent,
                            currentPart = currentPart,
                            totalParts = totalParts,
                            currentVideoIndex = currentVideoNumber,
                            totalVideos = totalVideos,
                            currentVideoName = fileName,
                            status = status
                        )
                    }
                )

                // Save report history into Room database
                if (generatedFiles.isNotEmpty()) {
                    val historyRecord = HistoryEntity(
                        originalFileName = fileName,
                        originalDurationMs = originalDurationMs,
                        originalSize = sizeBytes,
                        splitPartCount = generatedFiles.size,
                        intervalText = intervalText,
                        outputFolderPath = outputDirectory.absolutePath
                    )
                    repository.insert(historyRecord)
                }
            }

            // Finished successfully!
            updateNotificationFinished("All tasks completed successfully!", "Split files saved under: /Movies/VideoSplitter")
            liveProgress.value = ServiceProgress(
                percentage = 100f,
                currentPart = 0,
                totalParts = 0,
                currentVideoIndex = totalVideos,
                totalVideos = totalVideos,
                currentVideoName = "",
                status = "Completed successfully!",
                isFinished = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in splitting process", e)
            updateNotificationFailed("Failed: ${e.localizedMessage ?: "Unknown error"}")
            liveProgress.value = ServiceProgress(
                percentage = 0f,
                currentPart = 0,
                totalParts = 0,
                currentVideoIndex = 0,
                totalVideos = 0,
                currentVideoName = "",
                status = "Operation failed",
                isFailed = true,
                errorMsg = e.localizedMessage ?: "Conversion/split error raw"
            )
        } finally {
            stopSelf()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = applicationContext.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun startForegroundServiceCompat(title: String, body: String) {
        val notification = createNotification(title, body, 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(title: String, body: String, progress: Float): Notification {
        val notificationIntent = Intent(this, Class.forName("com.example.MainActivity"))
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)

        if (progress >= 0f) {
            builder.setProgress(100, progress.toInt(), false)
        } else {
            builder.setProgress(100, 0, true) // Indeterminate
        }

        return builder.build()
    }

    private fun updateNotificationProgress(title: String, statusText: String, progress: Float) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(title, statusText, progress)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationFinished(title: String, body: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationIntent = Intent(this, Class.forName("com.example.MainActivity"))
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channel = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, channel)
    }

    private fun updateNotificationFailed(message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Splitter Failed")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Video Splitter Pro Background Processing",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
