package pl.kamil.tvnakladka

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Ekran ustawień na telewizorze (pilotem). Zmiany widać od razu na nakładce po prawej stronie.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var panel: LinearLayout
    private lateinit var info: TextView
    private lateinit var rows: LinearLayout
    private val tabButtons = LinkedHashMap<String, Button>()
    private var tab = "clock"
    private var waitTries = 0

    private val tabs = linkedMapOf(
        "clock" to "Zegar", "e1" to "Encja 1", "e2" to "Encja 2", "e3" to "Encja 3",
        "camera" to "Kamery", "other" to "Inne"
    )

    private val overlay get() = OverlayManager.current

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32f), dp(24f), dp(32f), dp(24f))
            setBackgroundColor(Color.parseColor("#F00F2A3F"))
        }
        val screenW = resources.displayMetrics.widthPixels
        root.addView(panel, FrameLayout.LayoutParams((screenW * 0.56).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))

        panel.addView(TextView(this).apply {
            text = "TV Nakładka – ustawienia"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        })
        info = TextView(this).apply {
            setTextColor(Color.parseColor("#B0D8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6f), 0, dp(10f))
        }
        panel.addView(info)

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((key, name) in tabs) {
            val b = smallButton(name) { selectTab(key) }
            tabButtons[key] = b
            tabRow.addView(b)
        }
        panel.addView(tabRow)

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        panel.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(smallButton("Test na ekranie") { test() })
        bottom.addView(smallButton("Domyślne (ta zakładka)") { resetTab() })
        bottom.addView(smallButton("Uprawnienie") { openOverlaySettings() })
        bottom.addView(smallButton("Bez usypiania") { askIgnoreBattery() })
        panel.addView(bottom)

        setContentView(root)
        startOverlayService(this)
        waitForOverlay()
    }

    override fun onResume() {
        super.onResume()
        overlay?.setEditMode(true)
        refreshInfo()
    }

    override fun onPause() {
        overlay?.setEditMode(false)
        super.onPause()
    }

    /** Czeka, aż usługa wystartuje i nakładka będzie gotowa. */
    private fun waitForOverlay() {
        val o = overlay
        if (o != null) {
            o.attach()
            o.setEditMode(true)
            refreshInfo()
            selectTab(tab)
            tabButtons[tab]?.requestFocus()
            return
        }
        if (waitTries++ < 20) {
            handler.postDelayed({ waitForOverlay() }, 300)
        } else {
            refreshInfo()
        }
    }

    private fun refreshInfo() {
        val ips = localIpAddresses()
        val sb = StringBuilder()
        if (ips.isEmpty()) sb.append("Brak sieci") else {
            sb.append("Strona ustawień i API: ")
            sb.append(ips.joinToString("  ") { "http://$it:$API_PORT" })
        }
        val perm = overlay?.hasPermission() ?: (android.os.Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))
        if (!perm) {
            sb.append("\n\n⚠ Brak uprawnienia do nakładki. Z komputera:\n")
            sb.append("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
        }
        info.text = sb.toString()
    }

    // ---------------- zakładki ----------------

    private fun selectTab(key: String) {
        tab = key
        for ((k, b) in tabButtons) b.alpha = if (k == key) 1f else 0.55f
        rows.removeAllViews()
        if (overlay == null) {
            rows.addView(label("Nakładka nie działa – sprawdź uprawnienie."))
            return
        }
        val fonts = OverlayManager.FONTS.toList()
        when (key) {
            "clock", "e1", "e2", "e3" -> {
                toggleRow("Widoczny", key, "visible")
                numberRow("Poziomo (0 = lewo, 100 = prawo)", key, "x", 0, 100, 1, 5)
                numberRow("Pionowo (0 = góra, 100 = dół)", key, "y", 0, 100, 1, 5)
                numberRow("Wielkość czcionki", key, "size", 10, 160, 1, 5)
                choiceRow("Krój czcionki", key, "font", fonts)
                toggleRow("Pogrubienie", key, "bold")
                choiceRow("Kolor tekstu", key, "color", OverlayManager.COLORS)
                choiceRow("Tło", key, "background", OverlayManager.BACKGROUNDS)
                if (key == "clock") {
                    choiceRow("Format", key, "format", OverlayManager.CLOCK_FORMATS.map { it to it })
                } else {
                    rows.addView(label("Treść (tekst i ikonę) wysyła Home Assistant – slot ${key.drop(1)}."))
                }
            }
            "camera" -> {
                numberRow("Szerokość okienka", "camera", "width", 160, 900, 10, 50)
                numberRow("Odstęp między okienkami", "camera", "gap", 0, 80, 2, 10)
                choiceRow("Wyrównanie na dole", "camera", "align",
                    listOf("start" to "Do lewej", "center" to "Na środku", "end" to "Do prawej"))
                numberRow("Odległość od dołu", "camera", "bottom", 0, 400, 4, 20)
                numberRow("Maksymalny czas (s)", "camera", "max_seconds", 5, 120, 1, 5)
            }
            "other" -> {
                numberRow("Margines od krawędzi ekranu", "general", "margin", 0, 120, 2, 10)
                choiceRow("Powiadomienia – róg", "notify", "corner", listOf(
                    "top_start" to "Lewy górny", "top_center" to "Górny środek", "top_end" to "Prawy górny",
                    "bottom_start" to "Lewy dolny", "bottom_center" to "Dolny środek", "bottom_end" to "Prawy dolny"
                ))
                numberRow("Powiadomienia – wielkość", "notify", "size", 10, 60, 1, 5)
                numberRow("Powiadomienia – czas (s)", "notify", "duration", 2, 120, 1, 5)
                choiceRow("Powiadomienia – czcionka", "notify", "font", fonts)
                choiceRow("Powiadomienia – tło", "notify", "background", OverlayManager.BACKGROUNDS)
            }
        }
    }

    private fun resetTab() {
        val o = overlay ?: return
        val sections = if (tab == "other") listOf("general", "notify") else listOf(tab)
        for (s in sections) o.resetConfig(s)
        o.setEditMode(true)
        selectTab(tab)
    }

    // ---------------- wiersze ustawień ----------------

    private fun rowBase(title: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3f), 0, dp(3f))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        row.addView(value, LinearLayout.LayoutParams(dp(200f), ViewGroup.LayoutParams.WRAP_CONTENT))
        rows.addView(row)
        return row to value
    }

    private fun numberRow(title: String, sec: String, key: String, min: Int, max: Int, small: Int, big: Int) {
        val (row, value) = rowBase(title)
        fun cur() = overlay?.cfg(sec)?.optDouble(key, 0.0)?.toInt() ?: 0
        fun show() { value.text = cur().toString() }
        fun change(d: Int) {
            overlay?.set(sec, key, (cur() + d).coerceIn(min, max))
            show()
        }
        row.addView(smallButton("−$big") { change(-big) })
        row.addView(smallButton("−$small") { change(-small) })
        row.addView(smallButton("+$small") { change(small) })
        row.addView(smallButton("+$big") { change(big) })
        show()
    }

    private fun choiceRow(title: String, sec: String, key: String, options: List<Pair<String, String>>) {
        val (row, value) = rowBase(title)
        fun idx(): Int {
            val v = overlay?.cfg(sec)?.optString(key, "") ?: ""
            return options.indexOfFirst { it.first.equals(v, ignoreCase = true) }
        }
        fun show() {
            val i = idx()
            value.text = if (i >= 0) options[i].second else (overlay?.cfg(sec)?.optString(key, "") ?: "")
            if (key == "color" || key == "background") {
                value.setTextColor(parseColor(options.getOrNull(i)?.first, Color.WHITE).let {
                    if (key == "background" || Color.alpha(it) < 60) Color.parseColor("#FFD54F") else it
                })
            }
        }
        fun change(d: Int) {
            val i = idx().coerceAtLeast(0)
            val n = ((i + d) % options.size + options.size) % options.size
            overlay?.set(sec, key, options[n].first)
            show()
        }
        row.addView(smallButton("  ◀  ") { change(-1) })
        row.addView(smallButton("  ▶  ") { change(1) })
        show()
    }

    private fun toggleRow(title: String, sec: String, key: String) {
        val (row, value) = rowBase(title)
        fun cur() = overlay?.cfg(sec)?.optBoolean(key, false) ?: false
        fun show() { value.text = if (cur()) "Tak" else "Nie" }
        row.addView(smallButton("Zmień") {
            overlay?.set(sec, key, !cur())
            show()
        })
        show()
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#B0D8F0"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(10f), 0, 0)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        setTextColor(Color.WHITE)
        background = focusBackground()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6f); topMargin = dp(3f); bottomMargin = dp(3f) }
    }

    /** Tło przycisku: wyraźnie podświetlone, gdy wybrane pilotem. */
    private fun focusBackground() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat(); setColor(Color.parseColor("#18BCF2"))
        })
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat(); setColor(Color.parseColor("#1590C0"))
        })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat(); setColor(Color.parseColor("#2A4A63"))
        })
    }

    // ---------------- akcje ----------------

    private fun test() {
        val o = overlay ?: return
        o.attach()
        o.setEntity(JSONObject().put("slot", 1).put("text", "5 °C").put("icon", "mdi:weather-partly-cloudy").put("expiration", 30))
        o.setEntity(JSONObject().put("slot", 2).put("text", "3450 W").put("icon", "mdi:solar-power").put("color", "#FFD54F").put("expiration", 30))
        o.setEntity(JSONObject().put("slot", 3).put("text", "Zmywarka: koniec").put("icon", "mdi:dishwasher").put("expiration", 30))
        o.notify(JSONObject().put("title", "TV Nakładka").put("message", "Powiadomienie testowe").put("icon", "mdi:home-assistant"))
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

    @Suppress("unused")
    private fun View.hide() { visibility = View.GONE }
}
