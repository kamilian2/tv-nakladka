package pl.kamil.tvnakladka

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Usługa ułatwień dostępu – odbiera przyciski pilota w każdej aplikacji
 * (Netflix, YouTube, TV) i uruchamia przypisane akcje (kamery).
 */
class KeyService : AccessibilityService() {

    companion object {
        @Volatile var running = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        installCrashLogger(this)
        // upewnij się, że nakładka działa
        try { startOverlayService(this) } catch (_: Exception) {}
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val o = OverlayManager.current ?: return false
        return try {
            o.handleKey(event)
        } catch (e: Exception) {
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
