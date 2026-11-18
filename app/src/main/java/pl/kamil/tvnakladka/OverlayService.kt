package pl.kamil.tvnakladka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD

/** Usługa w tle: trzyma nakładkę na ekranie i serwer API. */
class OverlayService : Service() {

    private lateinit var overlay: OverlayManager
    private var server: ApiServer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Co 30 s sprawdza, czy nakładka i serwer działają (np. po nadaniu uprawnienia). */
    private val watchdog = object : Runnable {
        override fun run() {
            overlay.attach()
            if (server?.isAlive != true) startServer()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        overlay = OverlayManager(this)
        overlay.attach()
        startServer()
        handler.postDelayed(watchdog, 30_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlay.attach()
        if (server?.isAlive != true) startServer()
        return START_STICKY
    }

    private fun startServer() {
        try {
            server?.stop()
        } catch (_: Exception) {}
        server = try {
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (e: Exception) { "?" }
            ApiServer(API_PORT, overlay, version).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
        } catch (e: Exception) {
            Log.e("TvNakladka", "Nie udało się uruchomić serwera API", e)
            null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { server?.stop() } catch (_: Exception) {}
        overlay.detach()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val chId = "nakladka"
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(chId, "TV Nakładka", NotificationManager.IMPORTANCE_MIN)
            )
            Notification.Builder(this, chId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("TV Nakładka działa")
            .setContentText("API na porcie $API_PORT")
            .setSmallIcon(R.drawable.ic_app)
            .setOngoing(true)
            .build()
    }
}
