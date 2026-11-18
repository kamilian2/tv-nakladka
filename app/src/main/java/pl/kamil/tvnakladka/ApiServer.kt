package pl.kamil.tvnakladka

import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proste API dla Home Assistanta (POST z JSON-em).
 *
 *  GET  /               stan aplikacji
 *  POST /tile           kafelek: {id, text, icon, color, background, size, expiration, visible}
 *  POST /tile/clear     {id} albo {} = wszystkie
 *  POST /notify         powiadomienie: {id, title, message, icon, corner, duration, color, background, size}
 *  POST /camera         kamery: {streams:[url|{url,label}], duration, width, position}
 *  POST /camera/stop
 *  POST /clock          ustawienia zegara: {visible, corner, format, size, color, background, icon}
 *  POST /settings       wszystkie ustawienia naraz: {clock:{..}, tiles:{..}, notify:{..}, camera:{..}, margin}
 */
class ApiServer(
    port: Int,
    private val overlay: OverlayManager,
    private val versionName: String,
) : NanoHTTPD(port) {

    private val main = Handler(Looper.getMainLooper())

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/').ifEmpty { "/" }
            if (session.method == Method.OPTIONS) return json(JSONObject().put("ok", true))

            val body = readBody(session)
            val j = if (body.isBlank()) JSONObject() else JSONObject(body)

            if (session.method == Method.GET && uri == "/") {
                return json(onMain { status() })
            }

            val result = onMain {
                if (!overlay.attach()) {
                    return@onMain JSONObject()
                        .put("ok", false)
                        .put("error", "Brak uprawnienia 'wyświetlanie nad innymi aplikacjami' – nadaj je przez ADB")
                }
                when (uri) {
                    "/tile", "/notify_fixed" -> ok().put("id", overlay.setTile(j))
                    "/tile/clear" -> {
                        val id = j.optString("id", "")
                        if (id.isEmpty()) overlay.clearTiles() else overlay.removeTile(id)
                        ok()
                    }
                    "/notify" -> ok().put("id", overlay.notify(j))
                    "/notify/clear" -> { overlay.removeNotification(j.optString("id")); ok() }
                    "/camera" -> { overlay.showCameras(j); ok() }
                    "/camera/stop" -> { overlay.stopCameras(); ok() }
                    "/clock" -> { overlay.updateConfig(JSONObject().put("clock", j)); ok() }
                    "/settings" -> { overlay.updateConfig(j); ok().put("config", overlay.config) }
                    "/" -> status()
                    else -> JSONObject().put("ok", false).put("error", "Nieznany adres: $uri")
                }
            }
            val httpStatus = if (result.optBoolean("ok", false)) Response.Status.OK else Response.Status.BAD_REQUEST
            json(result, httpStatus)
        } catch (e: Exception) {
            json(JSONObject().put("ok", false).put("error", e.toString()), Response.Status.INTERNAL_ERROR)
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
        .put("tiles", JSONArray(overlay.tileIds()))
        .put("config", overlay.config)

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
        return r
    }
}
