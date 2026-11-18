package pl.kamil.tvnakladka

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Uruchamia nakładkę po starcie telewizora i po aktualizacji aplikacji. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            startOverlayService(context)
        } catch (e: Exception) {
            Log.e("TvNakladka", "Autostart nie powiódł się", e)
        }
    }
}
