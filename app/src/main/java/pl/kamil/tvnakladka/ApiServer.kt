package pl.kamil.tvnakladka

import android.content.Context
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Serwer HTTP: strona ustawień (GET /) i API dla Home Assistanta (POST z JSON-em).
 *
 *  GET  /               strona ustawień w przeglądarce
 *  GET  /status         stan aplikacji
 *  GET  /config         wszystkie ustawienia + listy czcionek/kolorów
 *  POST /settings       zmiana ustawień: {"clock":{"size":40,"x":100,"y":0}, "e1":{...}, "camera":{...}}
 *  POST /settings/reset {"section":"clock"} albo {} = wszystko
 *  POST /entity         treść pola: {"slot":1, "text":"5 °C", "icon":"mdi:weather-cloudy", "color", "expiration"}
 *  POST /entity/clear   {"slot":1} albo {} = wszystkie
 *  POST /notify         powiadomienie: {title, message, icon, corner, duration, color, background, size}
 *  POST /camera         kamery na dole: {"streams":[url | {url,label}], "duration":30, "width":400}
 *  POST /camera/stop
 *  POST /edit           tryb edycji (widać puste pola): {"on":true, "seconds":120}
 */
class ApiServer(
    port: Int,
    private val ctx: Context,
    private val overlay: OverlayManager,
    private val versionName: String,
) : NanoHTTPD(port) {

    private val main = Handler(Looper.getMainLooper())

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/').ifEmpty { "/" }
            if (session.method == Method.OPTIONS) return json(JSONObject().put("ok", true))

            if (session.method == Method.GET) {
                return when (uri) {
                    "/", "/index.html" -> html()
                    "/status" -> json(onMain { status() })
                    "/config" -> json(onMain { configWithOptions() })
                    else -> json(JSONObject().put("ok", false).put("error", "Nieznany adres: $uri"), Response.Status.NOT_FOUND)
                }
            }

            val body = readBody(session)
            val j = if (body.isBlank()) JSONObject() else JSONObject(body)

            val result = onMain {
                if (!overlay.attach()) {
                    return@onMain JSONObject()
                        .put("ok", false)
                        .put("error", "Brak uprawnienia 'wyświetlanie nad innymi aplikacjami' – nadaj je przez ADB")
                }
                when (uri) {
                    "/settings" -> { overlay.updateConfig(j); ok().put("config", overlay.config) }
                    "/settings/reset" -> {
                        overlay.resetConfig(j.optString("section", "").ifEmpty { null })
                        ok().put("config", overlay.config)
                    }
                    "/clock" -> { overlay.updateConfig(JSONObject().put("clock", j)); ok() }
                    "/entity", "/tile", "/notify_fixed" -> ok().put("slot", overlay.setEntity(j))
                    "/entity/clear", "/tile/clear" ->
                        { overlay.clearEntity(if (j.has("slot")) j.opt("slot") else j.opt("id")); ok() }
                    "/notify" -> ok().put("id", overlay.notify(j))
                    "/notify/clear" -> { overlay.removeNotification(j.optString("id")); ok() }
                    "/camera" -> { overlay.showCameras(j); ok() }
                    "/camera/stop" -> { overlay.stopCameras(); ok() }
                    "/edit" -> {
                        overlay.setEditMode(j.optBoolean("on", true), parseSeconds(j.opt("seconds"), 120))
                        ok()
                    }
                    else -> JSONObject().put("ok", false).put("error", "Nieznany adres: $uri")
                }
            }
            val httpStatus = if (result.optBoolean("ok", false)) Response.Status.OK else Response.Status.BAD_REQUEST
            json(result, httpStatus)
        } catch (e: Exception) {
            json(JSONObject().put("ok", false).put("error", e.message ?: e.toString()), Response.Status.BAD_REQUEST)
        }
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun status(): JSONObject = JSONObject()
        .put("ok", true)
        .put("app", "tv-nakladka")
        .put("version", versionName)
        .put("permission", overlay.hasPermission())
        .put("overlay", overlay.isAttached)
        .put("cameras", overlay.camerasActive)
        .put("entities", overlay.entitiesJson())

    private fun configWithOptions(): JSONObject {
        fun pairs(list: List<Pair<String, String>>) = JSONArray().also { a ->
            for ((v, n) in list) a.put(JSONObject().put("value", v).put("name", n))
        }
        return JSONObject()
            .put("ok", true)
            .put("version", versionName)
            .put("config", overlay.config)
            .put("fonts", pairs(OverlayManager.FONTS.toList()))
            .put("formats", JSONArray(OverlayManager.CLOCK_FORMATS))
            .put("colors", pairs(OverlayManager.COLORS))
            .put("backgrounds", pairs(OverlayManager.BACKGROUNDS))
            .put("entities", overlay.entitiesJson())
    }

    private fun html(): Response {
        val page = ctx.assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page)
    }

    /** Czyta treść jako UTF-8 (NanoHTTPD domyślnie psuje polskie znaki). */
    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        val input = session.inputStream
        while (read < len) {
            val r = input.read(buf, read, len - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun onMain(block: () -> JSONObject): JSONObject {
        var result: JSONObject? = null
        var error: Exception? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                result = block()
            } catch (e: Exception) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("Przekroczono czas")
        error?.let { throw it }
        return result ?: JSONObject().put("ok", false)
    }

    private fun json(o: JSONObject, status: Response.Status = Response.Status.OK): Response {
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return r
    }
}
