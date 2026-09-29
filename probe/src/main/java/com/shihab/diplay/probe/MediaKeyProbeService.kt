package com.shihab.diplay.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Actual, audible playback makes this a media-routing test, not an inactive session guess. */
class MediaKeyProbeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var tone: File? = null
    private var lastPress: Pair<Long, Int>? = null
    private val expire = Runnable { ProbeState.record("media", "timeout"); stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ProbeState.record("media", "user_stop")
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.media_channel), NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 2, Intent(this, ProbeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_probe).setContentTitle(getString(R.string.media_notification))
                .setContentText(getString(R.string.media_notification_detail)).setContentIntent(open)
                .setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.media_stop), stop).build()).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1, notification)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val audio = getSystemService(AudioManager::class.java)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                    ProbeState.record("audio_focus", "change=$change")
                    // End the diagnostic on any focus loss; never fight the factory phone/alerts.
                    if (change < 0) stopSelf()
                }, handler).build()
            focus = request
            if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                ProbeState.record("media", "focus_denied; routing_not_tested")
                stopSelf()
                return START_NOT_STICKY
            }
            session = MediaSession(this, "DiPlayCapabilityProbe")
            session!!.apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        @Suppress("DEPRECATION")
                        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                        val action = mediaCommand(event.keyCode) ?: return false
                        ProbeState.record("media_key", "key=${event.keyCode} action=${event.action} repeat=${event.repeatCount}")
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            val press = event.downTime to event.keyCode
                            if (press != lastPress) { lastPress = press; command(action) }
                        }
                        return true
                    }
                    override fun onPlay() = command("play")
                    override fun onPause() = command("pause")
                    override fun onSkipToNext() = command("next")
                    override fun onSkipToPrevious() = command("previous")
                    override fun onStop() = command("stop")
                }, handler)
                setPlaybackToLocal(attributes)
                isActive = true
            }
            tone = File(cacheDir, "probe-tone.wav").apply { writeBytes(testTone()) }
            player = MediaPlayer()
            player!!.apply {
                setAudioAttributes(attributes)
                setDataSource(checkNotNull(tone).absolutePath)
                isLooping = true
                setVolume(0.15f, 0.15f)
                setOnErrorListener { _, what, extra ->
                    ProbeState.record("media", "playback_error=$what/$extra")
                    stopSelf()
                    true
                }
                prepare()
                start()
            }
            active = true
            updatePlaybackState(true)
            ProbeState.record("media", "playing; auto_stop_seconds=60")
            handler.postDelayed(expire, 60_000)
        } catch (failure: Exception) {
            ProbeState.record("media", "start_failed=${failure.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun command(command: String) {
        ProbeState.record("media_command", command)
        try {
            val player = player ?: return
            when (command) {
                "play" -> { player.start(); updatePlaybackState(true) }
                "pause" -> { player.pause(); updatePlaybackState(false) }
                "toggle" -> if (player.isPlaying) { player.pause(); updatePlaybackState(false) }
                    else { player.start(); updatePlaybackState(true) }
                "stop" -> stopSelf()
                // No iPhone is connected. A skip restarts the test tone only.
                "next", "previous" -> player.seekTo(0)
            }
        } catch (failure: Exception) {
            ProbeState.record("media", "command_failed=${failure.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun updatePlaybackState(playing: Boolean) {
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }
    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }; player = null
        runCatching { session?.isActive = false }
        runCatching { session?.release() }; session = null
        focus?.let { runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } }
        focus = null
        tone?.delete()
        stopForeground(STOP_FOREGROUND_REMOVE)
        ProbeState.record("media", "stopped; focus_and_session_released")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.shihab.diplay.c11probe.STOP"
        private const val CHANNEL = "media-key-probe"
        @Volatile var active = false
            private set

        internal fun mediaCommand(code: Int): String? = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "toggle"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
            KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
            else -> null
        }

        private fun testTone(): ByteArray {
            val rate = 16_000
            val frames = rate * 2
            return ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(frames * 2)
                repeat(frames) { frame ->
                    val time = frame.toDouble() / rate
                    val envelope = if (time < 0.15) sin(PI * time / 0.15) else 0.0
                    putShort((sin(2 * PI * 660 * time) * envelope * 8000).toInt().toShort())
                }
            }.array()
        }
    }
}
