package com.spotmy.app

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.min

class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var activePlayer: ExoPlayer? = null
    private var incomingPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fadeStartedAt = 0L
    private var fadeDurationMs = 0L
    private var fadeSource: ExoPlayer? = null
    private var fadeTarget: ExoPlayer? = null
    private var lastItemIndex = C.INDEX_UNSET

    private val crossfadeTick = object : Runnable {
        override fun run() {
            val source = activePlayer ?: return
            val prefs = getSharedPreferences("spotmy_playback", MODE_PRIVATE)
            val enabled = prefs.getBoolean("crossfade_enabled", true)
            val configuredMs = prefs.getInt("crossfade_seconds", 5).coerceIn(2, 12) * 1000L
            if (!enabled || !source.isPlaying || source.repeatMode == Player.REPEAT_MODE_ONE ||
                source.mediaItemCount < 2 || source.duration <= 0L) {
                cancelFade()
                handler.postDelayed(this, 200L)
                return
            }

            val index = source.currentMediaItemIndex
            if (index != lastItemIndex && fadeTarget != null) cancelFade()
            lastItemIndex = index

            if (fadeTarget == null) {
                val nextIndex = source.nextMediaItemIndex
                val remaining = source.duration - source.currentPosition
                val fadeMs = min(configuredMs, (source.duration / 2L).coerceAtLeast(1000L))
                if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until source.mediaItemCount && remaining in 1..fadeMs) {
                    startFade(source, nextIndex, fadeMs)
                }
            } else {
                val target = fadeTarget
                val origin = fadeSource
                if (target == null || origin == null || activePlayer !== origin || !origin.isPlaying) {
                    cancelFade()
                } else {
                    val elapsed = (System.currentTimeMillis() - fadeStartedAt).coerceAtLeast(0L)
                    val progress = (elapsed.toFloat() / fadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    origin.volume = 1f - progress
                    target.volume = progress
                    if (progress >= 1f) finishFade(origin, target)
                }
            }
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activePlayer = createPlayer()
        mediaSession = MediaSession.Builder(this, activePlayer!!).build()
        handler.post(crossfadeTick)
    }

    private fun createPlayer() = ExoPlayer.Builder(this).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        setHandleAudioBecomingNoisy(true)
        volume = 1f
    }

    private fun startFade(source: ExoPlayer, nextIndex: Int, durationMs: Long) {
        cancelFade()
        val target = createPlayer()
        val items = (0 until source.mediaItemCount).map { source.getMediaItemAt(it) }
        target.setMediaItems(items, nextIndex, 0L)
        target.shuffleModeEnabled = false
        target.repeatMode = Player.REPEAT_MODE_OFF
        target.volume = 0f
        target.prepare()
        target.playWhenReady = true
        incomingPlayer = target
        fadeSource = source
        fadeTarget = target
        fadeStartedAt = System.currentTimeMillis()
        fadeDurationMs = durationMs
    }

    private fun finishFade(source: ExoPlayer, target: ExoPlayer) {
        if (activePlayer !== source || incomingPlayer !== target) {
            cancelFade()
            return
        }
        source.volume = 0f
        target.volume = 1f
        target.shuffleModeEnabled = source.shuffleModeEnabled
        target.repeatMode = source.repeatMode
        mediaSession?.setPlayer(target)
        activePlayer = target
        incomingPlayer = null
        fadeSource = null
        fadeTarget = null
        lastItemIndex = target.currentMediaItemIndex
        source.release()
    }

    private fun cancelFade() {
        val target = incomingPlayer
        if (target != null) {
            target.stop()
            target.release()
        }
        activePlayer?.volume = 1f
        incomingPlayer = null
        fadeSource = null
        fadeTarget = null
        fadeStartedAt = 0L
        fadeDurationMs = 0L
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacks(crossfadeTick)
        incomingPlayer?.release()
        incomingPlayer = null
        mediaSession?.release()
        mediaSession = null
        activePlayer?.release()
        activePlayer = null
        super.onDestroy()
    }
}
