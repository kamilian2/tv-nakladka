package pl.kamil.tvnakladka

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import java.net.Inet4Address
import java.net.NetworkInterface

const val API_PORT = 5002

fun startOverlayService(ctx: Context) {
    val i = Intent(ctx, OverlayService::class.java)
    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
}

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

/** "#RRGGBB", "#AARRGGBB", "RRGGBB" albo nazwa koloru ("red"). */
fun parseColor(s: String?, def: Int): Int {
    if (s.isNullOrBlank()) return def
    return try {
        val c = s.trim()
        if (c.startsWith("#") || c.all { it.isLetter() }) Color.parseColor(c) else Color.parseColor("#$c")
    } catch (e: Exception) {
        def
    }
}

/** Czas w sekundach: 30, "30", "30s", "5m", "2h", "1d". */
fun parseSeconds(v: Any?, def: Long): Long {
    if (v == null) return def
    if (v is Number) return v.toLong()
    val s = v.toString().trim().lowercase()
    if (s.isEmpty()) return def
    val num = s.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return def
    val mult = when (s.drop(s.takeWhile { it.isDigit() || it == '.' }.length).trim()) {
        "", "s", "sek" -> 1.0
        "m", "min" -> 60.0
        "h", "godz" -> 3600.0
        "d" -> 86400.0
        else -> 1.0
    }
    return (num * mult).toLong()
}

fun localIpAddresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress ?: "" }
        .filter { it.isNotEmpty() }
} catch (e: Exception) {
    emptyList()
}

/** Ikony Material Design ("mdi:solar-power") z czcionki w assets. */
object Mdi {
    @Volatile private var map: Map<String, Int>? = null
    var typeface: Typeface? = null
        private set

    fun init(ctx: Context) {
        if (map != null) return
        synchronized(this) {
            if (map != null) return
            val m = HashMap<String, Int>(8000)
            ctx.assets.open("mdi.txt").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val i = line.indexOf(' ')
                    if (i > 0) line.substring(i + 1).toIntOrNull(16)?.let { m[line.substring(0, i)] = it }
                }
            }
            typeface = Typeface.createFromAsset(ctx.assets, "mdi.ttf")
            map = m
        }
    }

    fun glyph(name: String): String? {
        val cp = map?.get(name.removePrefix("mdi:").trim()) ?: return null
        return String(Character.toChars(cp))
    }
}
