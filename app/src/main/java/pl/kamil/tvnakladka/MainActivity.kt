package pl.kamil.tvnakladka

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Ekran ustawień: pokazuje adres API, stan uprawnień i pozwala zrobić test. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f), dp(32f), dp(48f), dp(32f))
            setBackgroundColor(Color.parseColor("#0F2A3F"))
        }
        col.addView(TextView(this).apply {
            text = "TV Nakładka"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
        })
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(16f), 0, dp(16f))
            setLineSpacing(0f, 1.3f)
        }
        col.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        buttons.addView(button("Uruchom / odśwież") { start() })
        buttons.addView(button("Test na ekranie") { test() })
        buttons.addView(button("Uprawnienie") { openOverlaySettings() })
        buttons.addView(button("Bez usypiania") { askIgnoreBattery() })
        col.addView(buttons)

        setContentView(ScrollView(this).apply { addView(col) })
        start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(12f) }
    }

    private fun hasPermission() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun start() {
        try {
            startOverlayService(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Błąd: $e", Toast.LENGTH_LONG).show()
        }
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 800)
    }

    private fun refresh() {
        val ips = localIpAddresses()
        val sb = StringBuilder()
        sb.append("Adres API dla Home Assistanta:\n")
        if (ips.isEmpty()) sb.append("  (brak sieci)\n")
        for (ip in ips) sb.append("  http://$ip:$API_PORT\n")
        sb.append("\nUprawnienie do nakładki: ")
        sb.append(if (hasPermission()) "TAK ✔" else "BRAK ✘")
        if (!hasPermission()) {
            sb.append("\n\nNadaj je z komputera:\n")
            sb.append("  adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\n")
            sb.append("potem kliknij „Uruchom / odśwież”.")
        }
        sb.append("\n\nZegar i nakładka uruchamiają się same po włączeniu telewizora.")
        status.text = sb.toString()
    }

    private fun test() {
        Thread {
            try {
                post("/tile", JSONObject()
                    .put("id", "test")
                    .put("icon", "mdi:check-circle")
                    .put("text", "Nakładka działa")
                    .put("expiration", 10))
                post("/notify", JSONObject()
                    .put("title", "TV Nakładka")
                    .put("message", "Powiadomienie testowe")
                    .put("icon", "mdi:home-assistant")
                    .put("duration", 6))
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Test nieudany: $e", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun post(path: String, body: JSONObject) {
        val c = URL("http://127.0.0.1:$API_PORT$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 3000
        c.readTimeout = 5000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        c.inputStream.use { it.readBytes() }
        c.disconnect()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "Ten telewizor nie ma tego ekranu – użyj komendy ADB", Toast.LENGTH_LONG).show()
        }
    }

    private fun askIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "Użyj ADB: dumpsys deviceidle whitelist +$packageName", Toast.LENGTH_LONG).show()
        }
    }
}
