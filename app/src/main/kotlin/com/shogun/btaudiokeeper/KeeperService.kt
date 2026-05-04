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
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.random.Random

class KeeperService : Service() {

    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!running) startStream()
        return START_STICKY
    }

    override fun onDestroy() {
        stopStream()
        Prefs.setRunning(this, false)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val stopIntent = Intent(this, KeeperService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_keeper_on)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    /**
     * Stream low-amplitude white noise through AudioTrack.
     *
     * Pure-zero PCM gets treated as silence by BT codecs and the speaker amp goes
     * back to standby — the exact behavior we're suppressing. Sub-audible dither
     * keeps the signal "live".
     *
     * USAGE_ASSISTANCE_SONIFICATION + no MediaSession + no audio focus: media-button
     * events only route to apps holding a MediaSession, so Audible keeps full
     * ownership of play/pause from the BT remote and lockscreen.
     */
    private fun startStream() {
        running = true
        Prefs.setRunning(this, true)
        val amplitude = Prefs.amplitude(this)
        worker = thread(name = "bt-keeper", isDaemon = true) {
            val sampleRate = 44100
            val channelMask = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            val bufSize = maxOf(minBuf, sampleRate / 5 * 2)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
                while (running) {
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
        running = false
        worker?.join(500)
        worker = null
        track = null
    }

    companion object {
        const val ACTION_STOP = "com.shogun.btaudiokeeper.STOP"
        private const val CHANNEL_ID = "bt-keeper"
        private const val NOTIF_ID = 1
    }
}
