package com.spotmy.app

import android.os.Handler
import android.os.Looper
import android.media.audiofx.Equalizer
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
    private var fadeTargetIndex = C.INDEX_UNSET
    private var lastItemIndex = C.INDEX_UNSET
    private var equalizer: Equalizer? = null
    private var lastBass = Int.MIN_VALUE
    private var lastTreble = Int.MIN_VALUE
    private var lastProfile = ""

    private val crossfadeTick = object : Runnable {
        override fun run() {
            val source = activePlayer ?: return
            val prefs = getSharedPreferences("spotmy_playback", MODE_PRIVATE)
            applyAudioProfile(source, prefs)
            val stopAt = prefs.getLong("sleep_timer_end", 0L)
            if (stopAt > 0L && System.currentTimeMillis() >= stopAt) {
                source.pause()
                source.seekTo(0L)
                prefs.edit().remove("sleep_timer_end").putBoolean("stop_on_track_end", false).apply()
                cancelFade()
            }
            if (prefs.getBoolean("stop_on_track_end", false) && source.playbackState == Player.STATE_ENDED) {
                source.pause()
                source.seekTo(0L)
                prefs.edit().putBoolean("stop_on_track_end", false).apply()
            }
            val enabled = prefs.getBoolean("crossfade_enabled", true)
            val configuredMs = prefs.getInt("crossfade_seconds", 5).coerceIn(2, 12) * 1000L
            if (!enabled || !source.isPlaying || source.repeatMode == Player.REPEAT_MODE_ONE ||
                source.mediaItemCount < 2 || source.duration <= 0L) {
                cancelFade()
                handler.postDelayed(this, 200L)
                return
            }

            val index = source.currentMediaItemIndex
            if (index != lastItemIndex && fadeTarget != null) {
                val target = fadeTarget
                val origin = fadeSource
                // The source may reach its natural end a fraction before the wall-clock
                // fade ramp reaches 100%. Promote the already-playing incoming player
                // instead of cancelling it and restarting the next track at position 0.
                if (target != null && origin === source && index == fadeTargetIndex) {
                    finishFade(source, target)
                    handler.postDelayed(this, 100L)
                    return
                } else {
                    cancelFade()
                }
            }
            lastItemIndex = index

            if (fadeTarget == null) {
                val nextIndex = source.nextMediaItemIndex
                val remaining = source.duration - source.currentPosition
                val fadeMs = min(configuredMs, (source.duration / 2L).coerceAtLeast(1000L))
                if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until source.mediaItemCount && remaining in 1..fadeMs) {
                    startFade(source, nextIndex, remaining)
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

    private fun applyAudioProfile(player: ExoPlayer, prefs: android.content.SharedPreferences) {
        val profile = prefs.getString("audio_profile", "custom") ?: "custom"
        val bass = when (profile) { "bass" -> 75; "vocal" -> 15; "treble" -> -20; else -> prefs.getInt("bass_level", 0) }.coerceIn(-100, 100)
        val treble = when (profile) { "bass" -> -10; "vocal" -> 25; "treble" -> 75; else -> prefs.getInt("treble_level", 0) }.coerceIn(-100, 100)
        if (lastBass == bass && lastTreble == treble && lastProfile == profile && equalizer != null) return
        runCatching { equalizer?.release() }
        equalizer = runCatching {
            Equalizer(0, player.audioSessionId).apply {
                enabled = true
                val range = bandLevelRange
                for (band in 0 until numberOfBands) {
                    val frequency = getCenterFreq(band.toShort()).toInt()
                    val adjustment = if (frequency < 250) bass else if (frequency > 4000) treble else (bass + treble) / 4
                    val level = (adjustment * range[1] / 100).coerceIn(range[0].toInt(), range[1].toInt())
                    setBandLevel(band.toShort(), level.toShort())
                }
            }
        }.getOrNull()
        lastBass = bass
        lastTreble = treble
        lastProfile = profile
    }

    private fun createPlayer(handleAudioFocus: Boolean = true): ExoPlayer {
        val createdPlayer = ExoPlayer.Builder(this).build()
        createdPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            handleAudioFocus
        )
        createdPlayer.setHandleAudioBecomingNoisy(true)
        createdPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED || this@MusicService.activePlayer !== createdPlayer) return
                val prefs = getSharedPreferences("spotmy_playback", MODE_PRIVATE)
                if (prefs.getBoolean("stop_on_track_end", false)) return
                handler.post {
                    if (this@MusicService.activePlayer !== createdPlayer ||
                        createdPlayer.playbackState != Player.STATE_ENDED ||
                        createdPlayer.mediaItemCount == 0 ||
                        prefs.getBoolean("stop_on_track_end", false)) return@post
                    val library = runCatching { loadTracks(this@MusicService) }.getOrDefault(emptyList())
                    val currentUri = createdPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
                    val candidates = library.filter { it.uri.toString() != currentUri }
                    val nextTrack = (candidates.ifEmpty { library }).randomOrNull() ?: return@post
                    val nextItem = androidx.media3.common.MediaItem.Builder()
                        .setMediaId(nextTrack.id.toString())
                        .setUri(nextTrack.uri)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(nextTrack.title)
                                .setArtist(nextTrack.artist)
                                .setAlbumTitle(nextTrack.album)
                                .setArtworkUri(nextTrack.artworkUri)
                                .build()
                        ).build()
                    createdPlayer.setMediaItem(nextItem)
                    createdPlayer.prepare()
                    createdPlayer.play()
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val prefs = getSharedPreferences("spotmy_playback", MODE_PRIVATE)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && prefs.getBoolean("stop_on_track_end", false)) {
                    this@MusicService.activePlayer?.pause()
                    this@MusicService.activePlayer?.seekTo(0L)
                    prefs.edit().putBoolean("stop_on_track_end", false).apply()
                }
            }
        })
        createdPlayer.volume = 1f
        return createdPlayer
    }

    private fun startFade(source: ExoPlayer, nextIndex: Int, durationMs: Long) {
        cancelFade()
        val target = createPlayer(handleAudioFocus = false)
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
        fadeTargetIndex = nextIndex
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
        target.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true
        )
        target.shuffleModeEnabled = source.shuffleModeEnabled
        target.repeatMode = source.repeatMode
        mediaSession?.setPlayer(target)
        activePlayer = target
        incomingPlayer = null
        fadeSource = null
        fadeTarget = null
        fadeTargetIndex = C.INDEX_UNSET
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
        fadeTargetIndex = C.INDEX_UNSET
        fadeStartedAt = 0L
        fadeDurationMs = 0L
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacks(crossfadeTick)
        incomingPlayer?.release()
        incomingPlayer = null
        runCatching { equalizer?.release() }
        equalizer = null
        mediaSession?.release()
        mediaSession = null
        activePlayer?.release()
        activePlayer = null
        super.onDestroy()
    }
}
