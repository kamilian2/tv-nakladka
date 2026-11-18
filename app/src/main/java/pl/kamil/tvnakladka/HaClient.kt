package pl.kamil.tvnakladka

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samodzielne pobieranie stanów encji z Home Assistanta (REST API + token).
 * Dla pól 1–3, w których ustawiono "entity", co "interval" sekund pobiera stan i wyświetla go.
 */
class HaClient(private val overlay: OverlayManager) {

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val busy = AtomicBoolean(false)
    private var lastPoll = 0L

    @Volatile var status: String = "Nie skonfigurowano"
        private set

    /** Stan każdego pola (do podglądu na stronie). */
    val slotStatus = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start() {
        executor.scheduleWithFixedDelay({ tick() }, 2, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        executor.shutdownNow()
    }

    /** Wymusza odświeżenie przy następnym takcie (np. po zmianie ustawień). */
    fun refreshNow() {
        lastPoll = 0
    }

    private fun tick() {
        if (busy.get()) return
        // ustawienia czytamy na wątku głównym (tam są zmieniane)
        main.post {
            val snap = try { JSONObject(overlay.config.toString()) } catch (e: Exception) { return@post }
            val interval = snap.optJSONObject("ha")?.optDouble("interval", 5.0)?.coerceAtLeast(1.0) ?: 5.0
            if (System.currentTimeMillis() - lastPoll < interval * 1000) return@post
            if (!busy.compareAndSet(false, true)) return@post
            lastPoll = System.currentTimeMillis()
            try {
                executor.execute {
                    try { poll(snap) } finally { busy.set(false) }
                }
            } catch (e: Exception) {
                busy.set(false)
            }
        }
    }

    private fun poll(snap: JSONObject) {
        val ha = snap.optJSONObject("ha") ?: return
        val url = ha.optString("url").trim().trimEnd('/')
        val token = ha.optString("token").trim()
        val slots = listOf("e1", "e2", "e3").filter {
            snap.optJSONObject(it)?.optString("entity", "")?.isNotBlank() == true
        }
        for (s in listOf("e1", "e2", "e3")) if (s !in slots) slotStatus[s] = "brak encji"
        if (slots.isEmpty()) { status = "Brak przypisanych encji"; return }
        if (url.isEmpty() || token.isEmpty()) { status = "Podaj adres i token Home Assistanta"; return }

        var errors = 0
        for (slot in slots) {
            val c = snap.getJSONObject(slot)
            val entity = c.optString("entity").trim()
            try {
                val st = JSONObject(get("$url/api/states/$entity", token))
                val text = format(st, c)
                val icon = c.optString("icon", "").ifBlank {
                    st.optJSONObject("attributes")?.optString("icon", "") ?: ""
                }
                if (text == null) {
                    slotStatus[slot] = "$entity = ${st.optString("state")} → ukryte (warunek)"
                    main.post { overlay.setHaContent(slot, "", "") }
                } else {
                    slotStatus[slot] = "$entity = ${st.optString("state")} → „$text”"
                    main.post { overlay.setHaContent(slot, text, icon) }
                }
            } catch (e: Exception) {
                errors++
                Log.w("TvNakladka", "HA $entity: $e")
                val msg = e.message ?: e.javaClass.simpleName
                status = "Błąd ($entity): $msg"
                slotStatus[slot] = "$entity → błąd: $msg"
                main.post { overlay.setHaContent(slot, "—", "mdi:alert-circle-outline") }
            }
        }
        if (errors == 0) {
            status = "OK – ostatnie pobranie " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        }
    }

    /**
     * Tekst do wyświetlenia albo null, gdy warunek wyświetlania nie jest spełniony.
     * mode: value | name_value | name; show_when: always | above | below | equals | not_equals
     */
    private fun format(st: JSONObject, c: JSONObject): String? {
        val raw = st.optString("state", "")
        val attrs = st.optJSONObject("attributes")
        val num = raw.replace(',', '.').toDoubleOrNull()
        val threshold = c.optDouble("threshold", 0.0)
        val match = c.optString("match", "").trim()

        val show = when (c.optString("show_when", "always")) {
            "above" -> num != null && num > threshold
            "below" -> num != null && num < threshold
            "equals" -> if (match.isNotEmpty()) raw.equals(match, true) else num != null && num == threshold
            "not_equals" -> if (match.isNotEmpty()) !raw.equals(match, true) else !(num != null && num == threshold)
            else -> true
        }
        if (!show) return null

        val unit = if (c.optBoolean("unit", true)) attrs?.optString("unit_of_measurement", "") ?: "" else ""
        val decimals = c.optInt("decimals", -1)
        val value = when {
            raw == "unavailable" || raw == "unknown" -> "—"
            raw == "on" -> "wł."
            raw == "off" -> "wył."
            decimals >= 0 && num != null -> String.format(Locale("pl", "PL"), "%.${decimals}f", num)
            else -> raw
        }
        val valueText = listOf(value, unit).filter { it.isNotEmpty() }.joinToString(" ")
        val name = c.optString("label", "").trim().ifEmpty {
            attrs?.optString("friendly_name", "")?.trim() ?: ""
        }
        return when (c.optString("mode", "value")) {
            "name" -> name.ifEmpty { valueText }
            "name_value" -> listOf(name, valueText).filter { it.isNotEmpty() }.joinToString(" ")
            else -> valueText
        }
    }

    // ---------- dla strony ustawień ----------

    /** Lista encji z HA: [{entity_id, name, state, unit}]. Wywoływać poza wątkiem głównym. */
    fun listEntities(url: String, token: String): JSONArray {
        val arr = JSONArray(get(url.trimEnd('/') + "/api/states", token))
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val a = o.optJSONObject("attributes")
            out.put(JSONObject()
                .put("entity_id", o.optString("entity_id"))
                .put("name", a?.optString("friendly_name", "") ?: "")
                .put("state", o.optString("state"))
                .put("unit", a?.optString("unit_of_measurement", "") ?: ""))
        }
        return out
    }

    /** Sprawdza połączenie z HA. Wywoływać poza wątkiem głównym. */
    fun test(url: String, token: String): String {
        val r = JSONObject(get(url.trimEnd('/') + "/api/", token))
        return r.optString("message", "OK")
    }

    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "application/json")
            val code = c.responseCode
            if (code == 401) throw IllegalStateException("zły token (401)")
            if (code == 404) throw IllegalStateException("nie znaleziono (404) – sprawdź nazwę encji")
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
