package com.careclose.feature.caregiver.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class CaregiverVoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(targetFile: File) {
        stopRecording()
        targetFile.parentFile?.mkdirs()
        outputFile = targetFile

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(22_050)
            setMaxDuration(15_000)
            setOutputFile(targetFile.absolutePath)
            prepare()
            start()
        }
        recorder = mr
    }

    fun stopRecording(): File? {
        val current = recorder ?: return outputFile
        runCatching { current.stop() }
        runCatching { current.reset() }
        runCatching { current.release() }
        recorder = null
        return outputFile?.takeIf { it.exists() && it.length() > 0L }
    }

    fun release() {
        stopRecording()
    }
}

class CaregiverVoicePreviewPlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, onCompleted: () -> Unit = {}) {
        stop()
        if (!file.exists()) {
            onCompleted()
            return
        }
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                stop()
                onCompleted()
            }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                onCompleted()
                true
            }
            mp.prepare()
            mp.start()
        }.onFailure {
            stop()
            onCompleted()
        }
    }

    fun stop() {
        runCatching {
            player?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        player = null
    }
}

object PillPhotoProcessor {

    private const val MAX_DIM_PX = 720

    fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int = 82): ByteArray {
        val scaled = scaleDownIfNeeded(bitmap)
        return ByteArrayOutputStream().use { baos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            baos.toByteArray()
        }
    }

    fun compressUriToJpeg(context: Context, uri: Uri, quality: Int = 82): ByteArray? {
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null
        return compressBitmapToJpeg(decoded, quality)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= MAX_DIM_PX) return bitmap
        val ratio = MAX_DIM_PX.toFloat() / maxSide.toFloat()
        val newWidth = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

