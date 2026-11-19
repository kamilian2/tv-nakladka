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
    private lateinit var sectionTitle: TextView
    private var previewMode = false
    private val tabButtons = LinkedHashMap<String, Button>()
    private var tab = "clock"
    private var waitTries = 0

    private val tabs = linkedMapOf(
        "clock" to "Zegar", "e1" to "Encja 1", "e2" to "Encja 2", "e3" to "Encja 3", "e4" to "Encja 4", "e5" to "Encja 5",
        "group" to "Przesuń wszystko", "camera" to "Kamery", "keys" to "Pilot i kamery", "other" to "Powiadomienia i inne", "ha" to "Home Assistant"
    )

    private val overlay get() = OverlayManager.current

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger(this)

        val root = FrameLayout(this)
        // cały ekran: menu po lewej, ustawienia po prawej – nic nie jest ukryte ani przyciemnione
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F5081420"))
        }
        // prawa część ekranu zostaje odsłonięta – tam widać zegar i pola (podgląd na żywo)
        val screenW = resources.displayMetrics.widthPixels
        root.addView(panel, FrameLayout.LayoutParams((screenW * 0.74).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(24f), dp(20f), dp(20f))
            setBackgroundColor(Color.parseColor("#0F2A3F"))
        }
        panel.addView(ScrollView(this).apply { addView(menu); isFillViewport = true },
            LinearLayout.LayoutParams(dp(230f), ViewGroup.LayoutParams.MATCH_PARENT))

        menu.addView(TextView(this).apply {
            text = "TV Nakładka"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6f), 0, 0, dp(16f))
        })
        for ((key, name) in tabs) {
            val b = menuButton(name) { selectTab(key) }
            tabButtons[key] = b
            menu.addView(b)
        }
        menu.addView(View(this), LinearLayout.LayoutParams(1, dp(20f)))
        menu.addView(menuButton("👁  Podgląd (ukryj panel)") { preview() })
        menu.addView(menuButton("Test na ekranie") { test() })
        menu.addView(menuButton("Domyślne – ta sekcja") { resetTab() })
        menu.addView(menuButton("Ułatwienia dostępu") { openAccessibility() })
        menu.addView(menuButton("Uprawnienie nakładki") { openOverlaySettings() })
        menu.addView(menuButton("Bez usypiania") { askIgnoreBattery() })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(20f), dp(28f), dp(12f))
        }
        panel.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        sectionTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(sectionTitle)
        info = TextView(this).apply {
            setTextColor(Color.parseColor("#C8E6FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dp(4f), 0, dp(12f))
        }
        content.addView(info)

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(24f))
        }
        content.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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
        val crash = lastCrash(this)
        if (crash.isNotBlank()) {
            sb.append("\n\nOstatni błąd: ")
            sb.append(crash.lines().take(3).joinToString(" | "))
        }
        info.text = sb.toString()
    }

    // ---------------- zakładki ----------------

    private fun selectTab(key: String) {
        tab = key
        for ((k, b) in tabButtons) b.isSelected = k == key
        sectionTitle.text = tabs[key] ?: ""
        rows.removeAllViews()
        if (overlay == null) {
            rows.addView(label("Nakładka nie działa – sprawdź uprawnienie."))
            return
        }
        val o = overlay!!
        val fonts = OverlayManager.FONTS.toList()
        when (key) {
            "clock", "e1", "e2", "e3", "e4", "e5" -> {
                toggleRow("Widoczny", key, "visible")
                numberRow("Poziomo (0 = lewo, 100 = prawo)", key, "x", 0, 100, 1, 5)
                numberRow("Pionowo (0 = góra, 100 = dół)", key, "y", 0, 100, 1, 5)
                numberRow("Wielkość czcionki", key, "size", 10, 160, 1, 5)
                choiceRow("Krój czcionki", key, "font", fonts)
                toggleRow("Pogrubienie", key, "bold")
                choiceRow("Kolor tekstu", key, "color", OverlayManager.COLORS)
                choiceRow("Kolor tła", key, "background", OverlayManager.BACKGROUNDS)
                numberRow("Krycie tła % (0 = bez tła)", key, "opacity", 0, 100, 5, 20)
                choiceRow("Styl tła", key, "style", OverlayManager.STYLES)
                numberRow("Krycie tekstu %", key, "text_opacity", 5, 100, 5, 20)
                toggleRow("Cień pod tekstem", key, "shadow")
                if (key == "clock") {
                    choiceRow("Format", key, "format", OverlayManager.CLOCK_FORMATS.map { it to it })
                } else {
                    val ent = o.cfg(key).optString("entity", "")
                    rows.addView(label(
                        if (ent.isBlank()) "Encja HA: nie wybrano. Wybierzesz ją na stronie http://IP:$API_PORT (zakładka pola) albo HA wyśle treść sam."
                        else "Encja HA: $ent  (zmiana na stronie ustawień)"
                    ))
                    choiceRow("Co pokazać", key, "mode", listOf(
                        "value" to "Sama wartość", "name_value" to "Nazwa + wartość", "name" to "Sama nazwa"))
                    numberRow("Miejsca po przecinku (-1 = bez zmian)", key, "decimals", -1, 3, 1, 1)
                    toggleRow("Pokaż jednostkę", key, "unit")
                    choiceRow("Kiedy pokazywać", key, "show_when", listOf(
                        "always" to "Zawsze", "above" to "Wartość > próg", "below" to "Wartość < próg",
                        "equals" to "Stan = próg/tekst", "not_equals" to "Stan ≠ próg/tekst"))
                    numberRow("Próg", key, "threshold", -1000, 100000, 1, 10)
                    val extra = (2..4).mapNotNull { n -> o.cfg(key).optString("entity$n", "").ifBlank { null } }
                    rows.addView(label(if (extra.isEmpty())
                        "Kolejne encje w tym polu (zmiana z animacją): brak – dodasz je na stronie ustawień."
                        else "Kolejne encje w tym polu: " + extra.joinToString(", ")))
                    numberRow("Zmiana encji co (s)", key, "cycle_seconds", 1, 120, 1, 5)
                    choiceRow("Animacja zmiany", key, "transition", OverlayManager.TRANSITIONS)
                    numberRow("Czas animacji (ms)", key, "transition_ms", 100, 3000, 100, 500)
                    toggleRow("Tylko na żądanie (przycisk pilota)", key, "on_demand")
                    numberRow("Na żądanie – czas pokazania (s, 0 = do ponownego naciśnięcia)", key, "demand_seconds", 0, 300, 1, 10)
                    rows.addView(label("Stan: " + (o.ha?.slotStatus?.get(key) ?: "-")))
                }
            }
            "group" -> {
                rows.addView(label("Przesuwa zegar i encje razem – ich układ względem siebie zostaje taki sam. Elementy przy krawędzi ekranu zatrzymują się na niej."))
                numberRow("W prawo / w lewo (%)", "general", "group_dx", -100, 100, 1, 5)
                numberRow("W dół / w górę (%)", "general", "group_dy", -100, 100, 1, 5)
                for (n in OverlayManager.ELEMENTS) {
                    toggleRow("Przesuwaj: " + (OverlayManager.ELEMENT_NAMES[n] ?: n), n, "in_group")
                }
                val (r1, v1) = rowBase("Zapisz położenie na stałe i wyzeruj przesunięcie")
                v1.text = ""
                r1.addView(smallButton("Zapisz") { o.applyGroupOffset(); selectTab("group") })
                val (r2, v2) = rowBase("Cofnij przesunięcie")
                v2.text = ""
                r2.addView(smallButton("Wyzeruj") {
                    o.updateConfig(JSONObject().put("general", JSONObject().put("group_dx", 0).put("group_dy", 0)))
                    selectTab("group")
                })
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
                numberRow("Powiadomienia – krycie tła %", "notify", "opacity", 0, 100, 5, 20)
            }
            "keys" -> {
                rows.addView(label(
                    if (KeyService.running) "Usługa przycisków: włączona ✔"
                    else "Usługa przycisków: WYŁĄCZONA – przyciski działają tylko w tym oknie. Włącz ją przyciskiem „Ułatwienia dostępu” na dole albo przez ADB (instrukcja na stronie ustawień)."
                ))
                val actions = o.keyActions()
                for (i in 1..OverlayManager.KEY_SLOTS) {
                    val (row, value) = rowBase("Przycisk $i")
                    value.text = o.cfg("keys").optString("k${i}_name", "").ifBlank { "nie przypisano" }
                    row.addView(smallButton("Naucz") { learn("k$i") })
                    row.addView(smallButton("Usuń") {
                        o.updateConfig(JSONObject().put("keys", JSONObject()
                            .put("k${i}_code", 0).put("k${i}_name", "").put("k${i}_action", "none")))
                        selectTab("keys")
                    })
                    choiceRow("   akcja", "keys", "k${i}_action", actions)
                }
                toggleRow("Wstecz zamyka kamery", "general", "back_closes")
                rows.addView(label("Adresy kamer (RTSP) i ich nazwy ustawisz na stronie ustawień – zakładka „Pilot i kamery”. Ponowne naciśnięcie przycisku zamyka kamerę."))
            }
            "ha" -> {
                val h = o.cfg("ha")
                rows.addView(label("Adres: " + h.optString("url", "").ifBlank { "(nie ustawiono)" }))
                rows.addView(label("Token: " + if (h.optString("token", "").isBlank()) "(nie ustawiono)" else "zapisany"))
                rows.addView(label("Stan: " + (o.ha?.status ?: "-")))
                numberRow("Odświeżanie co (s)", "ha", "interval", 1, 300, 1, 10)
                rows.addView(label("Adres, token i encje najwygodniej ustawić w przeglądarce: " +
                    localIpAddresses().joinToString(" ") { "http://$it:$API_PORT" } + " – zakładka Home Assistant."))
            }
        }
    }

    private fun resetTab() {
        val o = overlay ?: return
        val sections = when (tab) {
            "other" -> listOf("general", "notify")
            "group" -> listOf("group_none")
            "ha" -> listOf("ha_none") // adresu i tokenu nie kasujemy przyciskiem
            else -> listOf(tab)
        }
        for (s in sections) o.resetConfig(s)
        o.setEditMode(true)
        selectTab(tab)
    }

    // ---------------- wiersze ustawień ----------------

    /** Wiersz: u góry nazwa i aktualna wartość, pod spodem przyciski (zwracany kontener). */
    private fun rowBase(title: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setPadding(dp(12f), 0, 0, 0)
        }
        header.addView(value, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(header)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f), 0, 0)
        }
        box.addView(buttons)
        rows.addView(box)
        rows.addView(View(this).apply { setBackgroundColor(Color.parseColor("#22FFFFFF")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)))
        return buttons to value
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
        row.addView(smallButton("◀") { change(-1) })
        row.addView(smallButton("▶") { change(1) })
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
        setTextColor(Color.parseColor("#C8E6FA"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setLineSpacing(0f, 1.2f)
        setPadding(dp(4f), dp(12f), 0, dp(8f))
    }

    /** Kolor tekstu przycisku: ciemny na podświetleniu, żółty dla wybranej sekcji. */
    private fun buttonTextColors() = android.content.res.ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf()
        ),
        intArrayOf(Color.parseColor("#04212E"), Color.parseColor("#FFD54F"), Color.WHITE)
    )

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        typeface = Typeface.DEFAULT_BOLD
        minWidth = 0
        minimumWidth = dp(70f)
        minHeight = 0
        minimumHeight = 0
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        setTextColor(buttonTextColors())
        background = focusBackground()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(10f) }
    }

    /** Przycisk w menu po lewej – na całą szerokość. */
    private fun menuButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16f), dp(12f), dp(12f), dp(12f))
        setTextColor(buttonTextColors())
        background = focusBackground()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6f) }
    }

    /** Tło przycisku: jaskrawe, gdy wybrane pilotem; wyróżnione dla aktywnej sekcji. */
    private fun focusBackground() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat(); setColor(Color.parseColor("#4DD8FF"))
            setStroke(dp(3f), Color.WHITE)
        })
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat(); setColor(Color.parseColor("#1590C0"))
        })
        addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat(); setColor(Color.parseColor("#1E4E70"))
            setStroke(dp(2f), Color.parseColor("#FFD54F"))
        })
        addState(intArrayOf(), GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat(); setColor(Color.parseColor("#24435C"))
        })
    }

    /** Chowa panel, żeby obejrzeć nakładkę na pełnym ekranie. Dowolny przycisk wraca. */
    private fun preview() {
        previewMode = true
        panel.visibility = View.INVISIBLE
        Toast.makeText(this, "Podgląd – naciśnij dowolny przycisk, aby wrócić", Toast.LENGTH_LONG).show()
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

    /** Czeka na naciśnięcie przycisku pilota (łapie go usługa albo to okno). */
    private fun learn(slot: String) {
        val o = overlay ?: return
        o.startLearning(slot)
        fun poll() {
            if (o.learning == null) { selectTab("keys"); return }
            handler.postDelayed({ poll() }, 400)
        }
        handler.postDelayed({ poll() }, 400)
    }

    /** Gdy usługa przycisków nie działa, nauka przycisku działa w tym oknie. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (previewMode) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                previewMode = false
                panel.visibility = View.VISIBLE
            }
            return true
        }
        val o = overlay
        if (o != null && (o.learning != null || event.action == android.view.KeyEvent.ACTION_UP)) {
            if (o.handleKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Użyj ADB – instrukcja na stronie ustawień", Toast.LENGTH_LONG).show()
        }
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
