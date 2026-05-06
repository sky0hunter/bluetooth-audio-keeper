package com.shogun.btaudiokeeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.random.Random

class KeeperService : Service() {

    @Volatile private var streamRunning = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    private var state: Prefs.State = Prefs.State.IDLE
    private var audioCallback: AudioManager.AudioPlaybackCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Wall-clock millis of last state transition. Used to debounce the audio-playback
     *  callback during the brief window where our own teardown still shows up in
     *  [AudioManager.getActivePlaybackConfigurations]. */
    @Volatile private var lastTransitionAt = 0L

    private val audioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                transitionTo(Prefs.State.IDLE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_MANUAL -> transitionTo(Prefs.State.STREAMING)
            ACTION_START_AUTO -> transitionTo(Prefs.State.WATCHING)
            else -> {
                // System-restarted us (intent is null on START_STICKY redelivery without args).
                // Restore from prefs: if user wanted Auto, go back to WATCHING; if Manual, STREAMING.
                val target = if (Prefs.mode(this) == Prefs.Mode.AUTO)
                    Prefs.State.WATCHING else Prefs.State.STREAMING
                transitionTo(target)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterAudioCallback()
        stopStream()
        Prefs.setState(this, Prefs.State.IDLE)
        super.onDestroy()
    }

    private fun transitionTo(target: Prefs.State) {
        if (state == target) {
            if (target != Prefs.State.IDLE) updateNotification()
            return
        }
        state = target
        lastTransitionAt = System.currentTimeMillis()
        when (target) {
            Prefs.State.IDLE -> {
                unregisterAudioCallback()
                stopStream()
                Prefs.setState(this, Prefs.State.IDLE)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            Prefs.State.WATCHING -> {
                stopStream()
                startForegroundCompat(buildNotification(Prefs.State.WATCHING))
                registerAudioCallback()
                Prefs.setState(this, Prefs.State.WATCHING)
            }
            Prefs.State.STREAMING -> {
                startForegroundCompat(buildNotification(Prefs.State.STREAMING))
                if (!streamRunning) startStream()
                if (Prefs.mode(this) == Prefs.Mode.AUTO) registerAudioCallback()
                else unregisterAudioCallback()
                Prefs.setState(this, Prefs.State.STREAMING)
            }
        }
    }

    private fun updateNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(state))
    }

    private fun buildNotification(s: Prefs.State): Notification {
        val stopIntent = Intent(this, KeeperService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = when (s) {
            Prefs.State.WATCHING -> getString(R.string.notif_watching)
            Prefs.State.STREAMING -> getString(R.string.notif_streaming)
            else -> getString(R.string.notif_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_keeper_on)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .build()
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: declare both types since we may transition; system honors whichever is
            // active. mediaPlayback while STREAMING; specialUse covers the WATCHING window.
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(NOTIF_ID, notif, types)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun registerAudioCallback() {
        if (audioCallback != null) return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                val externalActive = isExternalMediaActive(configs)
                mainHandler.post {
                    if (state == Prefs.State.IDLE) return@post
                    // Skip events that fire mid-teardown — our own AudioTrack lingers briefly
                    // after stopStream() returns, and would re-trigger the wrong transition.
                    if (System.currentTimeMillis() - lastTransitionAt < 1200) return@post
                    if (externalActive && state == Prefs.State.WATCHING) {
                        transitionTo(Prefs.State.STREAMING)
                    } else if (!externalActive && state == Prefs.State.STREAMING &&
                        Prefs.mode(this@KeeperService) == Prefs.Mode.AUTO
                    ) {
                        transitionTo(Prefs.State.WATCHING)
                    }
                }
            }
        }
        audioCallback = cb
        audioManager.registerAudioPlaybackCallback(cb, mainHandler)
        cb.onPlaybackConfigChanged(audioManager.activePlaybackConfigurations.toMutableList())
    }

    /** Count USAGE_MEDIA / USAGE_GAME configs, subtract our own track if it's running.
     *  AudioPlaybackConfiguration doesn't expose a public `clientUid` or `sessionId`, so the
     *  count-delta approach is the cleanest way to ignore ourselves. */
    private fun isExternalMediaActive(configs: List<AudioPlaybackConfiguration>): Boolean {
        val total = configs.count { isMediaUsage(it.audioAttributes.usage) }
        val ours = if (streamRunning) 1 else 0
        return total > ours
    }

    private fun unregisterAudioCallback() {
        audioCallback?.let { runCatching { audioManager.unregisterAudioPlaybackCallback(it) } }
        audioCallback = null
    }

    private fun isMediaUsage(usage: Int): Boolean = when (usage) {
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME -> true
        else -> false
    }

    /**
     * Stream low-amplitude white noise through AudioTrack.
     *
     * Pure-zero PCM gets treated as silence by BT codecs and the speaker amp goes
     * back to standby — the exact behavior we're suppressing. Sub-audible dither
     * keeps the signal "live".
     *
     * USAGE_MEDIA (not SONIFICATION) is required for the stream to actually route to the
     * A2DP speaker. SONIFICATION-tagged streams stay on the phone's internal speaker on
     * most BT setups, which is why earlier versions had no audible effect. We don't
     * register a MediaSession and don't request audio focus, so media-button events from
     * the BT remote / lockscreen still go to whichever player Audible / Spotify holds the
     * session for.
     */
    private fun startStream() {
        streamRunning = true
        val amplitude = Prefs.amplitude(this)
        worker = thread(name = "bt-keeper", isDaemon = true) {
            val sampleRate = 44100
            val channelMask = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            val bufSize = maxOf(minBuf, sampleRate / 5 * 2)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(encoding)
                .setChannelMask(channelMask)
                .build()

            val t = AudioTrack(
                attrs, format, bufSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track = t
            t.play()

            val chunk = ShortArray(bufSize / 2)
            val rng = Random(System.nanoTime())
            try {
                while (streamRunning) {
                    for (i in chunk.indices) {
                        chunk[i] = (rng.nextInt(amplitude * 2 + 1) - amplitude).toShort()
                    }
                    val written = t.write(chunk, 0, chunk.size)
                    if (written < 0) break
                }
            } finally {
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }
    }

    private fun stopStream() {
        if (!streamRunning && worker == null) return
        streamRunning = false
        worker?.join(500)
        worker = null
        track = null
    }

    companion object {
        const val ACTION_STOP = "com.shogun.btaudiokeeper.STOP"
        const val ACTION_START_MANUAL = "com.shogun.btaudiokeeper.START_MANUAL"
        const val ACTION_START_AUTO = "com.shogun.btaudiokeeper.START_AUTO"
        private const val CHANNEL_ID = "bt-keeper"
        private const val NOTIF_ID = 1
    }
}
