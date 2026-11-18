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
                main.post { overlay.setHaContent(slot, text, icon) }
            } catch (e: Exception) {
                errors++
                Log.w("TvNakladka", "HA $entity: $e")
                status = "Błąd ($entity): ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (errors == 0) {
            status = "OK – ostatnie pobranie " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        }
    }

    /** "label wartość jednostka", z zaokrągleniem, gdy "decimals" >= 0. */
    private fun format(st: JSONObject, c: JSONObject): String {
        val raw = st.optString("state", "")
        val attrs = st.optJSONObject("attributes")
        val unit = if (c.optBoolean("unit", true)) attrs?.optString("unit_of_measurement", "") ?: "" else ""
        val decimals = c.optInt("decimals", -1)
        val value = when {
            raw == "unavailable" || raw == "unknown" -> "—"
            raw == "on" -> "wł."
            raw == "off" -> "wył."
            decimals >= 0 && raw.toDoubleOrNull() != null ->
                String.format(Locale("pl", "PL"), "%.${decimals}f", raw.toDouble())
            else -> raw
        }
        val label = c.optString("label", "").trim()
        return listOf(label, value, unit).filter { it.isNotEmpty() }.joinToString(" ")
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
