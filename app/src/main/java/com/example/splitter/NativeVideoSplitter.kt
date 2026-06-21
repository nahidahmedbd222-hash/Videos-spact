package com.example.splitter

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object NativeVideoSplitter {
    private const val TAG = "NativeVideoSplitter"

    /**
     * Resolves the target directory under /Movies/VideoSplitter/ or app-specific fallback.
     */
    fun getOutputDirectory(context: Context): File {
        // Attempt to use system public Movies directory
        val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val targetDir = File(publicMovies, "VideoSplitter")
        
        try {
            if (!targetDir.exists()) {
                val created = targetDir.mkdirs()
                if (!created && !targetDir.exists()) {
                    // Fallback to app-specific external movies directory if public is restricted
                    return File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoSplitter").apply {
                        if (!exists()) mkdirs()
                    }
                }
            }
            return targetDir
        } catch (e: Exception) {
            Log.e(TAG, "Public Movies access denied: ${e.message}. Using fallback app dir.", e)
            return File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VideoSplitter").apply {
                if (!exists()) mkdirs()
            }
        }
    }

    /**
     * Native media stream splitter using MediaExtractor and MediaMuxer.
     * Splits source visual media into clean chunks without ANY transcoding (Zero quality loss, high-speed!).
     */
    fun splitVideo(
        context: Context,
        sourceUri: Uri,
        segmentDurationSec: Int,
        outputDir: File,
        basePartName: String,
        onProgress: (progress: Float, currentPart: Int, totalParts: Int, status: String) -> Unit
    ): List<File> {
        val splitFiles = mutableListOf<File>()
        
        // 1. Copy Uri source to a temporary accessible file inside cache directory
        val tempInputFile = File(context.cacheDir, "splitter_temp_input.mp4")
        if (tempInputFile.exists()) {
            tempInputFile.delete()
        }
        
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(tempInputFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Failed to open video source stream.")

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(tempInputFile.absolutePath)
        } catch (e: Exception) {
            tempInputFile.delete()
            throw IllegalArgumentException("Unrecognized video format or file corruption: ${e.message}")
        }

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        var durationUs: Long = 0

        // Search tracks
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = format
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
            } else if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
            }
        }

        if (videoTrackIndex == -1) {
            extractor.release()
            tempInputFile.delete()
            throw IllegalArgumentException("No valid video track detected in the file container.")
        }

        // If duration wasn't parsed from track, try reading duration from metadata
        if (durationUs <= 0) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(tempInputFile.absolutePath)
                val timeString = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationUs = (timeString?.toLong() ?: 0L) * 1000L
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed metadata extraction: ${e.message}")
            }
        }

        if (durationUs <= 0) {
            // Safe fallback duration (e.g. 5 minutes) if metadata and track formats lack details
            durationUs = 300_000_000L
        }

        val segmentDurationUs = segmentDurationSec * 1_000_000L
        val totalParts = Math.max(1, Math.ceil(durationUs.toDouble() / segmentDurationUs).toInt())

        val buffer = ByteBuffer.allocate(4 * 1024 * 1024) // 4MB buffer is plenty for heavy 4K streams
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            for (part in 0 until totalParts) {
                val partIndex = part + 1
                val startTimeUs = part * segmentDurationUs
                val endTimeUs = Math.min((part + 1) * segmentDurationUs, durationUs)

                // Save sequentially: video_part_01.mp4, video_part_02.mp4...
                // Clean the base file prefix from trailing extensions if existing
                val cleanPrefix = basePartName.substringBeforeLast(".")
                val outputPartName = "${cleanPrefix}_part_${String.format("%02d", partIndex)}.mp4"
                val partFile = File(outputDir, outputPartName)
                if (partFile.exists()) {
                    partFile.delete()
                }

                val muxer = MediaMuxer(partFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                
                var videoTrackMuxIdx = -1
                var audioTrackMuxIdx = -1

                videoTrackMuxIdx = muxer.addTrack(videoFormat!!)
                if (audioTrackIndex != -1 && audioFormat != null) {
                    audioTrackMuxIdx = muxer.addTrack(audioFormat)
                }

                muxer.start()

                // Register tracks for extraction
                extractor.unselectTrack(videoTrackIndex)
                if (audioTrackIndex != -1) {
                    extractor.unselectTrack(audioTrackIndex)
                }
                
                extractor.selectTrack(videoTrackIndex)
                if (audioTrackIndex != -1) {
                    extractor.selectTrack(audioTrackIndex)
                }

                // Seek to the start of this chunk's keyframes
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                var firstVideoSampleTimeUs: Long? = null
                var firstAudioSampleTimeUs: Long? = null

                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    
                    if (bufferInfo.size < 0) {
                        break // End of media
                    }

                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs > endTimeUs) {
                        break // Done with segment time boundary
                    }

                    val trackIdx = extractor.sampleTrackIndex
                    bufferInfo.flags = extractor.sampleFlags

                    if (trackIdx == videoTrackIndex) {
                        if (sampleTimeUs >= startTimeUs) {
                            if (firstVideoSampleTimeUs == null) {
                                firstVideoSampleTimeUs = sampleTimeUs
                            }
                            bufferInfo.presentationTimeUs = sampleTimeUs - firstVideoSampleTimeUs!!
                            if (bufferInfo.presentationTimeUs < 0) bufferInfo.presentationTimeUs = 0
                            muxer.writeSampleData(videoTrackMuxIdx, buffer, bufferInfo)
                        }
                    } else if (trackIdx == audioTrackIndex && audioTrackMuxIdx != -1) {
                        if (sampleTimeUs >= startTimeUs) {
                            if (firstAudioSampleTimeUs == null) {
                                firstAudioSampleTimeUs = sampleTimeUs
                            }
                            bufferInfo.presentationTimeUs = sampleTimeUs - firstAudioSampleTimeUs!!
                            if (bufferInfo.presentationTimeUs < 0) bufferInfo.presentationTimeUs = 0
                            muxer.writeSampleData(audioTrackMuxIdx, buffer, bufferInfo)
                        }
                    }

                    extractor.advance()
                }

                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Muxer stop fail: empty chunk?", e)
                } finally {
                    muxer.release()
                }

                if (partFile.exists() && partFile.length() > 0) {
                    splitFiles.add(partFile)
                }

                // Trigger MediaScanner connection to make it instantly visible in Android's gallery UI
                android.media.MediaScannerConnection.scanFile(
                    context, 
                    arrayOf(partFile.absolutePath), 
                    arrayOf("video/mp4")
                ) { path, uri ->
                    Log.d(TAG, "Scanned: $path -> Uri: $uri")
                }

                val percentage = (partIndex.toFloat() / totalParts) * 100f
                onProgress(percentage, partIndex, totalParts, "Splitting: Part $partIndex of $totalParts completed.")
            }
        } finally {
            extractor.release()
            tempInputFile.delete()
        }

        return splitFiles
    }
}
