package com.careclose.core.alarms.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File

class ElderAlarmAudioPlayer(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var isStopped = false

    fun start(voiceMediaPath: String?, vibrate: Boolean) {
        stop()
        isStopped = false

        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(
                if (voiceMediaPath != null) AudioAttributes.CONTENT_TYPE_SPEECH
                else AudioAttributes.CONTENT_TYPE_SONIFICATION,
            )
            .build()

        requestFocus(alarmAttributes)
        if (vibrate) {
            startVibration()
        }

        val voiceFile = voiceMediaPath?.let { File(it) }?.takeIf { it.exists() && it.canRead() }
        if (voiceFile != null) {
            playVoiceThenFallbackTone(voiceFile, alarmAttributes)
        } else {
            playLoopingAlarmTone(alarmAttributes)
        }
    }

    fun stop() {
        isStopped = true
        runCatching {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        mediaPlayer = null
        focusRequest?.let { req ->
            runCatching { audioManager?.abandonAudioFocusRequest(req) }
        }
        focusRequest = null
        stopVibration()
    }

    private fun playVoiceThenFallbackTone(voiceFile: File, attributes: AudioAttributes) {
        val player = MediaPlayer()
        mediaPlayer = player
        runCatching {
            player.setAudioAttributes(attributes)
            player.setDataSource(voiceFile.absolutePath)
            player.isLooping = false
            player.setOnCompletionListener {
                if (!isStopped) {
                    playLoopingAlarmTone(attributes)
                }
            }
            player.setOnErrorListener { _, _, _ ->
                if (!isStopped) {
                    playLoopingAlarmTone(attributes)
                }
                true
            }
            player.prepare()
            player.start()
        }.onFailure {
            playLoopingAlarmTone(attributes)
        }
    }

    private fun playLoopingAlarmTone(attributes: AudioAttributes) {
        runCatching {
            mediaPlayer?.release()
        }
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        val player = MediaPlayer()
        mediaPlayer = player
        runCatching {
            player.setAudioAttributes(attributes)
            player.setDataSource(context, uri)
            player.isLooping = true
            player.prepare()
            player.start()
        }.onFailure {
            runCatching { player.release() }
            mediaPlayer = null
        }
    }

    private fun requestFocus(attributes: AudioAttributes) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = req
        audioManager?.requestAudioFocus(req)
    }

    private fun startVibration() {
        val pattern = longArrayOf(0L, 600L, 400L, 600L, 800L)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        getVibrator()?.vibrate(effect)
    }

    private fun stopVibration() {
        getVibrator()?.cancel()
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    }
}

