package pl.kamil.tvnakladka

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nakładka na ekranie: zegar, 3 pola na encje, powiadomienia, kamery.
 * Wszystkie metody wywoływać na wątku głównym.
 *
 * Położenie elementów: x i y w procentach (0–100).
 * x=0 – przy lewej krawędzi, x=100 – przy prawej, x=50 – na środku (tak samo y).
 */
@OptIn(UnstableApi::class)
class OverlayManager(private val ctx: Context) {

    companion object {
        /** Aktualnie działająca nakładka (dla ekranu ustawień). */
        var current: OverlayManager? = null

        /** Elementy ustawiane w dowolnym miejscu ekranu. */
        /** Pola na encje. */
        val SLOTS = listOf("e1", "e2", "e3", "e4", "e5")
        val ELEMENTS = listOf("clock") + SLOTS
        val ELEMENT_NAMES = mapOf("clock" to "Zegar") + SLOTS.associateWith { "Encja ${it.drop(1)}" }

        /** Czcionki: id -> nazwa. Id zaczynające się od "asset:" to pliki w assets/fonts. */
        val FONTS = linkedMapOf(
            "sans-serif" to "Roboto",
            "sans-serif-medium" to "Roboto Medium",
            "sans-serif-black" to "Roboto Black",
            "sans-serif-light" to "Roboto Light",
            "sans-serif-thin" to "Roboto Thin",
            "sans-serif-condensed" to "Roboto Condensed",
            "serif" to "Szeryfowa",
            "monospace" to "Maszynowa",
            "casual" to "Odręczna",
            "cursive" to "Pisana",
            "asset:dseg7" to "Cyfrowa 7-segmentowa",
            "asset:dseg14" to "Cyfrowa 14-segmentowa",
        )

        val CLOCK_FORMATS = listOf(
            "HH:mm:ss", "HH:mm", "H:mm:ss", "HH:mm:ss  EEE", "EEE d MMM  HH:mm:ss", "d.MM.yyyy  HH:mm:ss"
        )

        val COLORS = listOf(
            "#FFFFFF" to "Biały", "#FFD54F" to "Żółty", "#FFB74D" to "Pomarańczowy", "#E57373" to "Czerwony",
            "#81C784" to "Zielony", "#4FC3F7" to "Niebieski", "#CE93D8" to "Fioletowy", "#B0BEC5" to "Szary",
            "#000000" to "Czarny"
        )

        /** Kolory tła (przezroczystość ustawia się osobno – "opacity" 0–100 %). */
        val BACKGROUNDS = listOf(
            "#000000" to "Czarne", "#0F2A3F" to "Granatowe", "#37474F" to "Grafitowe",
            "#1B5E20" to "Zielone", "#B71C1C" to "Czerwone", "#FFFFFF" to "Białe"
        )

        private fun defaults(name: String): JSONObject = when (name) {
            "clock" -> JSONObject()
                .put("visible", true).put("x", 100).put("y", 0)
                .put("size", 34).put("font", "sans-serif-medium").put("bold", false)
                .put("color", "#FFFFFF").put("background", "#000000").put("opacity", 60)
                .put("text_opacity", 100).put("style", "solid").put("shadow", false)
                .put("in_group", true)
                .put("format", "HH:mm:ss").put("icon", "")
            "e1", "e2", "e3", "e4", "e5" -> JSONObject()
                .put("visible", true).put("x", 100)
                .put("y", 1 + 8 * (name.drop(1).toInt()))
                // na żądanie: pole pokazuje się dopiero po naciśnięciu przycisku pilota
                .put("on_demand", false).put("demand_seconds", 10)
                .put("in_group", true)
                .put("text_opacity", 100).put("style", "solid").put("shadow", false)
                // kolejne encje w tym samym polu – zmieniają się z animacją
                .put("entity2", "").put("label2", "").put("icon2", "")
                .put("entity3", "").put("label3", "").put("icon3", "")
                .put("entity4", "").put("label4", "").put("icon4", "")
                .put("cycle_seconds", 5).put("transition", "fade").put("transition_ms", 600)
                .put("size", 24).put("font", "sans-serif").put("bold", true)
                .put("color", "#FFFFFF").put("background", "#000000").put("opacity", 60)
                // pobieranie z Home Assistanta
                .put("entity", "").put("label", "").put("icon", "")
                .put("decimals", -1).put("unit", true)
                // co pokazać: value = sama wartość, name_value = nazwa + wartość, name = sama nazwa
                .put("mode", "value")
                // kiedy pokazać: always, above, below, equals, not_equals
                .put("show_when", "always").put("threshold", 0).put("match", "")
            "notify" -> JSONObject()
                .put("corner", "top_start").put("size", 22).put("duration", 8)
                .put("font", "sans-serif").put("background", "#101820").put("opacity", 80)
            "camera" -> JSONObject()
                .put("max_seconds", 30).put("width", 400).put("gap", 10)
                .put("align", "center").put("bottom", 24)
            "general" -> JSONObject().put("margin", 24).put("back_closes", true)
                // przesunięcie całej grupy (zegar + encje) w % szerokości/wysokości ekranu
                .put("group_dx", 0).put("group_dy", 0)
            "ha" -> JSONObject().put("url", "").put("token", "").put("interval", 5)
            // zapisane kamery: c1_name, c1_url ... c6_name, c6_url
            "cams" -> JSONObject().also { o ->
                for (i in 1..CAM_SLOTS) { o.put("c${i}_name", "Kamera $i"); o.put("c${i}_url", "") }
            }
            // przyciski pilota: k1_code, k1_name, k1_action ... (action: none, cam:c1, cams:all, stop)
            "keys" -> JSONObject().also { o ->
                for (i in 1..KEY_SLOTS) { o.put("k${i}_code", 0); o.put("k${i}_name", ""); o.put("k${i}_action", "none") }
            }
            else -> JSONObject()
        }

        val STYLES = listOf(
            "solid" to "Jednolite", "glass" to "Szkło jasne (iOS)", "glass_dark" to "Szkło ciemne (iOS)", "outline" to "Ramka"
        )
        val TRANSITIONS = listOf(
            "fade" to "Zanikanie", "flip_x" to "Obrót 3D w pionie", "flip_y" to "Obrót 3D w poziomie",
            "zoom" to "Powiększenie", "slide_up" to "Przesunięcie w górę", "slide_left" to "Przesunięcie w lewo",
            "none" to "Bez animacji"
        )

        val SECTIONS = listOf("clock", "e1", "e2", "e3", "e4", "e5", "notify", "camera", "general", "ha", "cams", "keys")
        const val CAM_SLOTS = 6
        const val KEY_SLOTS = 6

        /** Kolor tła z przezroczystością: kolor bez kanału alfa + krycie 0–100 %. */
        fun bgColor(c: JSONObject, defColor: Int): Int {
            val base = parseColor(c.optString("background"), defColor)
            val op = c.optDouble("opacity", 60.0).coerceIn(0.0, 100.0)
            return Color.argb((op * 2.55).toInt(), Color.red(base), Color.green(base), Color.blue(base))
        }
    }

    private val tag = "TvNakladka"
    private val handler = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = ctx.getSharedPreferences("nakladka", Context.MODE_PRIVATE)
    private val assetFonts = HashMap<String, Typeface>()

    private var root: FrameLayout? = null
    private val corners = HashMap<String, LinearLayout>()

    private class Element(val pill: LinearLayout, val inner: LinearLayout, val icon: TextView, val text: TextView)
    private val elements = HashMap<String, Element>()

    /** Treść pól na encje (z HA): tekst, ikona, opcjonalny kolor. */
    /** Jedna pozycja do wyświetlenia w polu (pole może mieć kilka i zmieniać je z animacją). */
    class Item(val text: String, val icon: String = "", val color: String = "", val ok: Boolean = true)

    private class Content {
        var items: List<Item> = emptyList()
        var index = 0
        var lastSwitch = 0L
        var animating = false
    }
    private val contents = HashMap<String, Content>()
    private val contentExpiry = HashMap<String, Runnable>()

    private var clockFormat = SimpleDateFormat("HH:mm:ss", Locale("pl", "PL"))

    private val notifications = HashMap<String, View>()
    private val notificationExpiry = HashMap<String, Runnable>()

    private var cameraRow: LinearLayout? = null
    private val players = mutableListOf<ExoPlayer>()
    private val stopCamerasRunnable = Runnable { stopCameras() }

    /** Tryb edycji: pokazuje puste pola z podpisami, żeby było widać, gdzie są. */
    var editMode = false
        private set
    private val editOff = Runnable { setEditMode(false) }

    /** Zapisane ustawienia (tylko zmienione pola). */
    private val stored: JSONObject = try {
        JSONObject(prefs.getString("config", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    init {
        for (e in SLOTS) contents[e] = Content()
    }

    // ================= ustawienia =================

    /** Ustawienia sekcji: domyślne + zapisane. */
    fun cfg(section: String): JSONObject {
        val d = defaults(section)
        stored.optJSONObject(section)?.let { s -> for (k in s.keys()) d.put(k, s.get(k)) }
        return d
    }

    /** Pełne ustawienia (do API i strony WWW). */
    val config: JSONObject
        get() = JSONObject().also { o -> for (s in SECTIONS) o.put(s, cfg(s)) }

    /** Łączy podane ustawienia z zapisanymi: {"clock":{"size":40}, "e1":{"x":0}}. */
    fun updateConfig(patch: JSONObject) {
        for (key in patch.keys()) {
            val v = patch.get(key)
            if (v !is JSONObject) continue
            val sec = stored.optJSONObject(key) ?: JSONObject().also { stored.put(key, it) }
            for (k in v.keys()) {
                // pusty token nie kasuje zapisanego (strona nie zna zapisanego tokenu)
                if (key == "ha" && k == "token" && v.optString(k).isBlank()) continue
                if (key == "ha" && k == "token_set") continue
                sec.put(k, v.get(k))
            }
        }
        prefs.edit().putString("config", stored.toString()).apply()
        // usunięto encję HA z pola -> wyczyść jego treść
        for (s in SLOTS) {
            val p = patch.optJSONObject(s) ?: continue
            if (p.has("entity") && p.optString("entity").isBlank()) clearEntity(s)
        }
        refreshAll()
        ha?.refreshNow()
    }

    /** Klient Home Assistanta (ustawiany przez usługę). */
    var ha: HaClient? = null

    /** Wpisuje przesunięcie grupy na stałe w położenie elementów i zeruje przesunięcie. */
    fun applyGroupOffset() {
        val g = cfg("general")
        val dx = g.optDouble("group_dx", 0.0)
        val dy = g.optDouble("group_dy", 0.0)
        val r = root
        val patch = JSONObject().put("general", JSONObject().put("group_dx", 0).put("group_dy", 0))
        val m = ctx.dp(g.optDouble("margin", 24.0).toFloat())
        for (name in ELEMENTS) {
            val c = cfg(name)
            if (!c.optBoolean("in_group", true)) continue
            val el = elements[name]
            // przelicz tak, żeby element został dokładnie tam, gdzie jest teraz
            val nx: Double
            val ny: Double
            if (r != null && el != null && r.width > 0) {
                val freeW = (r.width - 2 * m - el.pill.width).coerceAtLeast(1)
                val freeH = (r.height - 2 * m - el.pill.height).coerceAtLeast(1)
                nx = ((el.pill.translationX - m) / freeW * 100.0)
                ny = ((el.pill.translationY - m) / freeH * 100.0)
            } else {
                nx = c.optDouble("x", 100.0) + dx
                ny = c.optDouble("y", 0.0) + dy
            }
            patch.put(name, JSONObject()
                .put("x", Math.round(nx.coerceIn(0.0, 100.0) * 10) / 10.0)
                .put("y", Math.round(ny.coerceIn(0.0, 100.0) * 10) / 10.0))
        }
        updateConfig(patch)
    }

    fun set(section: String, key: String, value: Any) {
        updateConfig(JSONObject().put(section, JSONObject().put(key, value)))
    }

    /** Przywraca ustawienia domyślne sekcji (albo wszystkich, gdy section == null). */
    fun resetConfig(section: String?) {
        if (section == null) {
            for (k in stored.keys().asSequence().toList()) stored.remove(k)
        } else {
            stored.remove(section)
        }
        prefs.edit().putString("config", stored.toString()).apply()
        refreshAll()
    }

    fun setEditMode(on: Boolean, seconds: Long = 0) {
        handler.removeCallbacks(editOff)
        editMode = on
        if (on && seconds > 0) handler.postDelayed(editOff, seconds * 1000)
        refreshAll()
    }

    // ================= okno nakładki =================

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    val isAttached get() = root != null

    @SuppressLint("RtlHardcoded")
    fun attach(): Boolean {
        if (root != null) return true
        if (!hasPermission()) return false
        Mdi.init(ctx)

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT

        val r = FrameLayout(ctx)

        // rogi – tylko dla powiadomień
        for (c in listOf("top_start", "top_center", "top_end", "bottom_start", "bottom_center", "bottom_end")) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = horizontalGravity(c)
            }
            r.addView(box, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, cornerGravity(c)
            ))
            corners[c] = box
        }

        // zegar i 3 pola na encje – w dowolnym miejscu
        for (name in ELEMENTS) {
            val icon = TextView(ctx).apply { includeFontPadding = false }
            val text = TextView(ctx).apply {
                includeFontPadding = false
                fontFeatureSettings = "tnum" // cyfry o stałej szerokości
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon)
                addView(text)
            }
            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable()
                clipChildren = true
                clipToPadding = true
                cameraDistance = 12000f * ctx.resources.displayMetrics.density
                addView(inner)
            }
            r.addView(pill, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT
            ))
            pill.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position(name) }
            elements[name] = Element(pill, inner, icon, text)
        }
        r.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> for (n in ELEMENTS) position(n) }

        try {
            wm.addView(r, lp)
        } catch (e: Exception) {
            Log.e(tag, "Nie można dodać nakładki", e)
            corners.clear(); elements.clear()
            return false
        }
        root = r
        current = this
        refreshAll()
        handler.post(clockTick)
        handler.post(cycleTick)
        return true
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        stopCameras()
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        if (current === this) current = null
        corners.clear(); elements.clear()
        notifications.clear(); notificationExpiry.clear()
    }

    private fun cornerGravity(c: String): Int {
        val v = if (c.startsWith("bottom")) Gravity.BOTTOM else Gravity.TOP
        return v or horizontalGravity(c)
    }

    private fun horizontalGravity(c: String): Int = when {
        c.endsWith("start") -> Gravity.START
        c.endsWith("center") -> Gravity.CENTER_HORIZONTAL
        else -> Gravity.END
    }

    private fun normalizeCorner(c: String?, def: String): String {
        val v = (c ?: "").trim().lowercase()
            .replace("left", "start").replace("right", "end")
            .replace("-", "_").replace(" ", "_")
        return if (corners.containsKey(v)) v else def
    }

    fun typeface(font: String, bold: Boolean): Typeface {
        if (font.startsWith("asset:")) {
            val n = font.removePrefix("asset:")
            val tf = assetFonts.getOrPut(n) {
                try { Typeface.createFromAsset(ctx.assets, "fonts/$n.ttf") } catch (e: Exception) { Typeface.DEFAULT }
            }
            return tf
        }
        return Typeface.create(font, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    // ================= zegar i encje =================

    private fun refreshAll() {
        if (root == null) return
        for (name in ELEMENTS) styleElement(name)
        for (b in corners.values) {
            val m = ctx.dp(cfg("general").optDouble("margin", 24.0).toFloat())
            (b.layoutParams as FrameLayout.LayoutParams).setMargins(m, m, m, m)
            b.requestLayout()
        }
        applyCameraLayout()
    }

    /** Pozycje pola, które można teraz pokazać. */
    private fun pool(name: String): List<Item> {
        val content = contents[name] ?: return emptyList()
        val forced = name in demandShown || editMode
        return content.items.filter { it.text.isNotEmpty() && (it.ok || forced) }
    }

    private fun styleElement(name: String) {
        val el = elements[name] ?: return
        val c = cfg(name)
        val size = c.optDouble("size", 24.0).toFloat()
        val content = contents[name]
        val pool = pool(name)
        val item = if (pool.isNotEmpty()) pool[(content?.index ?: 0) % pool.size] else null

        val baseColor = parseColor(item?.color?.takeIf { it.isNotBlank() } ?: c.optString("color"), Color.WHITE)
        val textAlpha = (c.optDouble("text_opacity", 100.0).coerceIn(5.0, 100.0) * 2.55).toInt()
        val color = Color.argb(textAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        val tf = typeface(c.optString("font", "sans-serif"), c.optBoolean("bold", false))
        val style = c.optString("style", "solid")

        el.text.typeface = tf
        el.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        el.text.setTextColor(color)
        val shadow = c.optBoolean("shadow", false) || style.startsWith("glass")
        if (shadow) {
            el.text.setShadowLayer(ctx.dp(3f).toFloat(), 0f, ctx.dp(1f).toFloat(), Color.argb(170, 0, 0, 0))
            el.icon.setShadowLayer(ctx.dp(3f).toFloat(), 0f, ctx.dp(1f).toFloat(), Color.argb(170, 0, 0, 0))
        } else {
            el.text.setShadowLayer(0f, 0f, 0f, 0)
            el.icon.setShadowLayer(0f, 0f, 0f, 0)
        }

        val iconName = if (name == "clock") c.optString("icon", "") else (item?.icon ?: "")
        setIcon(el.icon, iconName, size, color)

        el.pill.background = background(c, size)
        val padH = ctx.dp(size * 0.5f)
        val padV = ctx.dp(size * 0.22f)
        el.pill.setPadding(padH, padV, padH, padV)

        if (name == "clock") {
            clockFormat = try {
                SimpleDateFormat(c.optString("format", "HH:mm:ss"), Locale("pl", "PL"))
            } catch (e: Exception) {
                SimpleDateFormat("HH:mm:ss", Locale("pl", "PL"))
            }
            el.text.text = clockFormat.format(Date())
            el.pill.visibility = if (c.optBoolean("visible", true)) View.VISIBLE else View.GONE
        } else {
            val forced = name in demandShown
            val demandOk = !c.optBoolean("on_demand", false) || forced || editMode
            val visibleOk = c.optBoolean("visible", true) || forced
            val show = visibleOk && demandOk && (item != null || editMode)
            el.text.text = item?.text ?: if (editMode) ELEMENT_NAMES[name] else ""
            el.pill.visibility = if (show) View.VISIBLE else View.GONE
        }
        position(name)
    }

    /** Tło: jednolite, szkło (jasne/ciemne, jak w iOS), sama ramka. */
    private fun background(c: JSONObject, size: Float): GradientDrawable {
        val bg = bgColor(c, Color.BLACK)
        val radius = ctx.dp(size * 0.45f).toFloat()
        val a = Color.alpha(bg)
        fun mix(col: Int, with: Int, f: Float, alpha: Int) = Color.argb(
            alpha.coerceIn(0, 255),
            (Color.red(col) * (1 - f) + Color.red(with) * f).toInt(),
            (Color.green(col) * (1 - f) + Color.green(with) * f).toInt(),
            (Color.blue(col) * (1 - f) + Color.blue(with) * f).toInt()
        )
        return when (c.optString("style", "solid")) {
            "glass" -> GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(mix(bg, Color.WHITE, 0.55f, a + 70), mix(bg, Color.WHITE, 0.25f, a + 20), mix(bg, Color.WHITE, 0.1f, a))
            ).apply {
                cornerRadius = radius
                setStroke(ctx.dp(1.5f), Color.argb(150, 255, 255, 255))
            }
            "glass_dark" -> GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(mix(bg, Color.WHITE, 0.22f, a + 30), mix(bg, Color.BLACK, 0.2f, a), mix(bg, Color.BLACK, 0.45f, a + 20))
            ).apply {
                cornerRadius = radius
                setStroke(ctx.dp(1.2f), Color.argb(90, 255, 255, 255))
            }
            "outline" -> GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.argb(a / 3, Color.red(bg), Color.green(bg), Color.blue(bg)))
                setStroke(ctx.dp(2f), parseColor(c.optString("color"), Color.WHITE))
            }
            else -> GradientDrawable().apply {
                cornerRadius = radius
                setColor(bg)
            }
        }
    }

    /** Co pół sekundy sprawdza, czy pole z kilkoma encjami ma przejść do następnej. */
    private val cycleTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (name in SLOTS) {
                val content = contents[name] ?: continue
                val el = elements[name] ?: continue
                if (content.animating || el.pill.visibility != View.VISIBLE) continue
                val pool = pool(name)
                if (pool.size < 2) continue
                val every = cfg(name).optDouble("cycle_seconds", 5.0).coerceAtLeast(1.0) * 1000
                if (now - content.lastSwitch >= every) {
                    content.lastSwitch = now
                    animateNext(name)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    /** Przejście do następnej encji w polu z wybraną animacją. */
    private fun animateNext(name: String) {
        val el = elements[name] ?: return
        val content = contents[name] ?: return
        val type = cfg(name).optString("transition", "fade")
        val d = cfg(name).optLong("transition_ms", 600).coerceIn(100, 3000) / 2
        val p = el.pill
        val inner = el.inner
        fun advance() {
            content.index = (content.index + 1) % Int.MAX_VALUE
            styleElement(name)
        }
        fun done() { content.animating = false }
        content.animating = true
        val dist = p.height.toFloat().coerceAtLeast(40f)
        when (type) {
            "none" -> { advance(); done() }
            "flip_x" -> p.animate().rotationX(90f).setDuration(d).withEndAction {
                advance(); p.rotationX = -90f
                p.animate().rotationX(0f).setDuration(d).withEndAction { done() }.start()
            }.start()
            "flip_y" -> p.animate().rotationY(90f).setDuration(d).withEndAction {
                advance(); p.rotationY = -90f
                p.animate().rotationY(0f).setDuration(d).withEndAction { done() }.start()
            }.start()
            "zoom" -> p.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(d).withEndAction {
                advance()
                p.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(d).withEndAction { done() }.start()
            }.start()
            "slide_up" -> inner.animate().translationY(-dist).alpha(0f).setDuration(d).withEndAction {
                advance(); inner.translationY = dist
                inner.animate().translationY(0f).alpha(1f).setDuration(d).withEndAction { done() }.start()
            }.start()
            "slide_left" -> inner.animate().translationX(-p.width.toFloat()).alpha(0f).setDuration(d).withEndAction {
                advance(); inner.translationX = p.width.toFloat()
                inner.animate().translationX(0f).alpha(1f).setDuration(d).withEndAction { done() }.start()
            }.start()
            else -> p.animate().alpha(0f).setDuration(d).withEndAction {
                advance()
                p.animate().alpha(1f).setDuration(d).withEndAction { done() }.start()
            }.start()
        }
    }

    /** Ustawia element w miejscu x%, y% ekranu (z marginesem bezpieczeństwa). */
    private fun position(name: String) {
        val r = root ?: return
        val el = elements[name] ?: return
        val c = cfg(name)
        val m = ctx.dp(cfg("general").optDouble("margin", 24.0).toFloat())
        val x = c.optDouble("x", 100.0).coerceIn(0.0, 100.0) / 100.0
        val y = c.optDouble("y", 0.0).coerceIn(0.0, 100.0) / 100.0
        val freeW = (r.width - 2 * m - el.pill.width).coerceAtLeast(0)
        val freeH = (r.height - 2 * m - el.pill.height).coerceAtLeast(0)
        var tx = m + freeW * x
        var ty = m + freeH * y
        // wspólne przesunięcie grupy – układ elementów względem siebie się nie zmienia
        if (c.optBoolean("in_group", true)) {
            val g = cfg("general")
            tx += g.optDouble("group_dx", 0.0) / 100.0 * (r.width - 2 * m)
            ty += g.optDouble("group_dy", 0.0) / 100.0 * (r.height - 2 * m)
            tx = tx.coerceIn(m.toDouble(), (m + freeW).toDouble())
            ty = ty.coerceIn(m.toDouble(), (m + freeH).toDouble())
        }
        el.pill.translationX = tx.toFloat()
        el.pill.translationY = ty.toFloat()
    }

    private fun setIcon(tv: TextView, icon: String?, sizeSp: Float, color: Int) {
        if (icon.isNullOrBlank()) {
            tv.visibility = View.GONE
            return
        }
        val glyph = if (icon.startsWith("mdi:")) Mdi.glyph(icon) else null
        if (glyph != null) {
            tv.typeface = Mdi.typeface
            tv.text = glyph
        } else {
            tv.typeface = Typeface.DEFAULT
            tv.text = if (icon.startsWith("mdi:")) "" else icon
        }
        tv.setTextColor(color)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tv.setPadding(0, 0, ctx.dp(sizeSp * 0.35f), 0)
        tv.visibility = if (tv.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private val clockTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            elements["clock"]?.text?.text = clockFormat.format(Date(now))
            handler.postDelayed(this, 1000 - (now % 1000) + 10)
        }
    }

    /** Zamienia "1", 1, "e1", "encja1" na "e1". */
    private fun slotName(v: Any?): String? {
        val s = v?.toString()?.trim()?.lowercase() ?: return null
        val digit = s.lastOrNull { it.isDigit() } ?: return null
        val n = "e$digit"
        return if (n in contents) n else null
    }

    /** Treść pola: {slot:1, text, icon, color, expiration, visible:false = wyczyść}. */
    fun setEntity(j: JSONObject): String {
        val slot = slotName(if (j.has("slot")) j.opt("slot") else j.opt("id"))
            ?: throw IllegalArgumentException("Podaj slot: 1–${SLOTS.size}")
        if (j.has("visible") && !j.optBoolean("visible", true)) {
            clearEntity(slot)
            return slot
        }
        val c = contents[slot]!!
        c.items = listOf(Item(j.optString("text", j.optString("message", "")), j.optString("icon", ""), j.optString("color", "")))
        c.index = 0

        contentExpiry.remove(slot)?.let { handler.removeCallbacks(it) }
        val secs = parseSeconds(if (j.has("expiration")) j.opt("expiration") else null, 0)
        if (secs > 0) {
            val r = Runnable { clearEntity(slot) }
            contentExpiry[slot] = r
            handler.postDelayed(r, secs * 1000)
        }
        styleElement(slot)
        return slot
    }

    fun clearEntity(slotOrAll: Any?) {
        val list = if (slotOrAll == null || slotOrAll.toString().isBlank()) contents.keys.toList()
        else listOfNotNull(slotName(slotOrAll))
        for (s in list) {
            contentExpiry.remove(s)?.let { handler.removeCallbacks(it) }
            contents[s] = Content()
            styleElement(s)
        }
    }

    /** Pozycje pola pobrane z Home Assistanta (wywołuje HaClient). */
    fun setHaItems(slot: String, items: List<Item>) {
        val c = contents[slot] ?: return
        val same = c.items.size == items.size && c.items.zip(items).all { (x, y) ->
            x.text == y.text && x.icon == y.icon && x.ok == y.ok
        }
        if (same) return
        c.items = items
        if (!c.animating) styleElement(slot)
    }

    fun entitiesJson(): JSONObject = JSONObject().also { o ->
        for ((k, v) in contents) {
            val first = v.items.firstOrNull()
            o.put(k, JSONObject().put("text", first?.text ?: "").put("icon", first?.icon ?: "")
                .put("items", v.items.size))
        }
    }

    // ================= powiadomienia =================

    fun notify(j: JSONObject): String {
        val id = j.optString("id", "").ifEmpty { "n_" + System.currentTimeMillis() }
        val n = cfg("notify")
        val corner = normalizeCorner(j.optString("corner", n.optString("corner")), "top_start")
        val size = j.optDouble("size", n.optDouble("size", 22.0)).toFloat()
        val color = parseColor(j.optString("color", ""), Color.WHITE)
        val bg = parseColor(j.optString("background", ""), bgColor(n, Color.parseColor("#101820")))
        val duration = parseSeconds(j.opt("duration"), n.optLong("duration", 8))
        val tf = typeface(n.optString("font", "sans-serif"), false)

        removeNotification(id)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(18f), ctx.dp(12f), ctx.dp(18f), ctx.dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12f).toFloat()
                setColor(bg)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ctx.dp(6f); bottomMargin = ctx.dp(6f) }
        }
        val icon = TextView(ctx)
        setIcon(icon, j.optString("icon", ""), size * 1.6f, color)
        card.addView(icon)

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = j.optString("title", "")
        val message = j.optString("message", "")
        if (title.isNotBlank()) col.addView(TextView(ctx).apply {
            text = title
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = Typeface.create(tf, Typeface.BOLD)
            maxWidth = ctx.dp(520f)
        })
        if (message.isNotBlank()) col.addView(TextView(ctx).apply {
            text = message
            setTextColor(color)
            alpha = 0.9f
            typeface = tf
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.8f)
            maxWidth = ctx.dp(520f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(col)

        val box = corners[corner] ?: return id
        if (corner.startsWith("bottom")) box.addView(card, 0) else box.addView(card)
        notifications[id] = card

        if (duration > 0) {
            val r = Runnable { removeNotification(id) }
            notificationExpiry[id] = r
            handler.postDelayed(r, duration * 1000)
        }
        return id
    }

    fun removeNotification(id: String) {
        notificationExpiry.remove(id)?.let { handler.removeCallbacks(it) }
        notifications.remove(id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    // ================= kamery (zawsze na dole) =================

    fun showCameras(j: JSONObject) {
        val c = cfg("camera")
        val list = mutableListOf<String>()
        val labels = mutableListOf<String>()
        val arr: JSONArray? = j.optJSONArray("streams")
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            if (item is JSONObject) {
                list.add(item.optString("url")); labels.add(item.optString("label", ""))
            } else if (item != null && item.toString().isNotBlank()) {
                list.add(item.toString()); labels.add("")
            }
        }
        if (j.optString("url").isNotBlank()) { list.add(j.optString("url")); labels.add(j.optString("label", "")) }
        // zapisane kamery po numerze/id: "cams": [1, "c2"]
        j.optJSONArray("cams")?.let { ids ->
            val saved = cfg("cams")
            for (i in 0 until ids.length()) {
                val d = ids.opt(i)?.toString()?.lastOrNull { it.isDigit() } ?: continue
                val url = saved.optString("c${d}_url", "")
                if (url.isNotBlank()) { list.add(url); labels.add("") }
            }
        }
        if (list.isEmpty()) throw IllegalArgumentException("Brak adresów kamer (streams albo cams)")

        val maxSec = c.optLong("max_seconds", 30)
        val wanted = parseSeconds(j.opt("duration"), maxSec)
        val duration = if (maxSec > 0) minOf(wanted, maxSec) else wanted
        val widthDp = j.optDouble("width", c.optDouble("width", 400.0)).toFloat()
        val gapDp = c.optDouble("gap", 10.0).toFloat()

        stopCameras()
        val r = root ?: return

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val w = ctx.dp(widthDp)
        val h = w * 9 / 16
        list.forEachIndexed { i, url ->
            val cell = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(10f).toFloat()
                    setColor(Color.BLACK)
                }
                clipToOutline = true
            }
            val tv = TextureView(ctx)
            cell.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val label = labels.getOrElse(i) { "" }
            if (label.isNotBlank()) {
                cell.addView(TextView(ctx).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(ctx.dp(8f), ctx.dp(3f), ctx.dp(8f), ctx.dp(3f))
                    setBackgroundColor(Color.parseColor("#88000000"))
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                ))
            }
            row.addView(cell, LinearLayout.LayoutParams(w, h).apply {
                if (i > 0) marginStart = ctx.dp(gapDp)
            })
            startPlayer(url, tv)
        }

        r.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cameraRow = row
        applyCameraLayout()

        handler.removeCallbacks(stopCamerasRunnable)
        if (duration > 0) handler.postDelayed(stopCamerasRunnable, duration * 1000)
    }

    /** Kamery zawsze przy dolnej krawędzi; wyrównanie poziome z ustawień. */
    private fun applyCameraLayout() {
        val row = cameraRow ?: return
        val c = cfg("camera")
        val align = when (c.optString("align", "center")) {
            "start", "left" -> Gravity.START
            "end", "right" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        val m = ctx.dp(cfg("general").optDouble("margin", 24.0).toFloat())
        val lp = row.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or align
        lp.setMargins(m, m, m, ctx.dp(c.optDouble("bottom", 24.0).toFloat()))
        row.layoutParams = lp
    }

    private fun buildSource(url: String): MediaSource {
        val item = MediaItem.fromUri(url)
        return if (url.startsWith("rtsp", ignoreCase = true)) {
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(ctx).createMediaSource(item)
        }
    }

    private fun startPlayer(url: String, tv: TextureView) {
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 3000, 300, 300)
            .build()
        val player = ExoPlayer.Builder(ctx).setLoadControl(load).build()
        player.volume = 0f
        player.setVideoTextureView(tv)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(tag, "Błąd kamery $url: ${error.errorCodeName}")
                handler.postDelayed({
                    if (players.contains(player)) {
                        player.setMediaSource(buildSource(url))
                        player.prepare()
                        player.play()
                    }
                }, 2000)
            }
        })
        player.setMediaSource(buildSource(url))
        player.playWhenReady = true
        player.prepare()
        players.add(player)
    }

    fun stopCameras() {
        activeKeyAction = null
        handler.removeCallbacks(stopCamerasRunnable)
        val list = players.toList()
        players.clear()
        for (p in list) try { p.release() } catch (_: Exception) {}
        cameraRow?.let { (it.parent as? ViewGroup)?.removeView(it) }
        cameraRow = null
    }

    val camerasActive get() = cameraRow != null

    // ================= przyciski pilota =================

    /** Slot (k1..k6), dla którego czekamy na naciśnięcie przycisku. */
    var learning: String? = null
        private set
    var lastLearned: String = ""
        private set
    private var consumeUpFor = -1
    private var activeKeyAction: String? = null
    private val learnTimeout = Runnable {
        if (learning != null) {
            learning = null
            notify(JSONObject().put("title", "Nie naciśnięto przycisku").put("icon", "mdi:remote").put("duration", 3))
        }
    }

    fun startLearning(slot: String) {
        val d = slot.lastOrNull { it.isDigit() } ?: return
        learning = "k$d"
        lastLearned = ""
        handler.removeCallbacks(learnTimeout)
        handler.postDelayed(learnTimeout, 15_000)
        notify(JSONObject().put("id", "learn").put("title", "Naciśnij przycisk na pilocie")
            .put("message", "dla pozycji $d (15 s)").put("icon", "mdi:remote").put("duration", 15))
    }

    /**
     * Obsługa przycisku pilota. Zwraca true, gdy przycisk został "zużyty"
     * (telewizor go wtedy nie dostanie, np. nie przełączy kanału).
     */
    fun handleKey(e: android.view.KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == android.view.KeyEvent.ACTION_DOWN
        if (!down && code == consumeUpFor) { consumeUpFor = -1; return true }
        if (!down) return false

        // nauka przycisku
        learning?.let { slot ->
            if (code == android.view.KeyEvent.KEYCODE_BACK) {
                learning = null
                handler.removeCallbacks(learnTimeout)
                removeNotification("learn")
                consumeUpFor = code
                return true
            }
            val name = android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            learning = null
            handler.removeCallbacks(learnTimeout)
            updateConfig(JSONObject().put("keys", JSONObject()
                .put("${slot}_code", code).put("${slot}_name", name)))
            lastLearned = "$slot:$name"
            removeNotification("learn")
            notify(JSONObject().put("title", "Zapisano przycisk").put("message", name)
                .put("icon", "mdi:check-circle").put("duration", 3))
            consumeUpFor = code
            return true
        }

        // Wstecz zamyka kamery
        if (code == android.view.KeyEvent.KEYCODE_BACK && camerasActive &&
            cfg("general").optBoolean("back_closes", true)) {
            stopCameras()
            consumeUpFor = code
            return true
        }

        // przypisane przyciski
        val keys = cfg("keys")
        for (i in 1..KEY_SLOTS) {
            if (keys.optInt("k${i}_code", 0) != code) continue
            val action = keys.optString("k${i}_action", "none")
            if (action == "none") continue
            if (e.repeatCount == 0) runKeyAction(action)
            consumeUpFor = code
            return true
        }
        return false
    }

    // ---------- pola "na żądanie" ----------
    private val demandShown = HashSet<String>()
    private val demandHide = HashMap<String, Runnable>()

    /** Pokazuje/ukrywa pole na żądanie (przycisk pilota albo API). seconds<0 = z ustawień pola. */
    fun toggleDemand(slot: String, forceShow: Boolean = false, seconds: Long = -1) {
        if (slot !in SLOTS) return
        demandHide.remove(slot)?.let { handler.removeCallbacks(it) }
        if (slot in demandShown && !forceShow) {
            demandShown.remove(slot)
        } else {
            demandShown.add(slot)
            val secs = if (seconds >= 0) seconds else cfg(slot).optLong("demand_seconds", 10)
            if (secs > 0) {
                val r = Runnable { demandShown.remove(slot); demandHide.remove(slot); styleElement(slot) }
                demandHide[slot] = r
                handler.postDelayed(r, secs * 1000)
            }
            ha?.refreshNow()
            if (contents[slot]?.items.isNullOrEmpty()) {
                notify(JSONObject().put("title", ELEMENT_NAMES[slot] ?: slot)
                    .put("message", "Brak danych – przypisz encję HA do tego pola")
                    .put("icon", "mdi:alert-circle-outline").put("duration", 4))
            }
        }
        styleElement(slot)
    }

    /** cam:c1 – jedna kamera, cams:all – wszystkie zapisane, stop – zamknij, show:e4 – pokaż pole, show:all. Ponowne naciśnięcie zamyka. */
    fun runKeyAction(action: String) {
        if (action.startsWith("show:")) {
            val what = action.removePrefix("show:")
            if (what == "all") {
                val list = SLOTS.filter { cfg(it).optBoolean("on_demand", false) }
                val anyShown = list.any { it in demandShown }
                for (s in list) if (anyShown) { if (s in demandShown) toggleDemand(s) } else toggleDemand(s, forceShow = true)
            } else {
                toggleDemand(what)
            }
            return
        }
        if (action == "stop" || (camerasActive && activeKeyAction == action)) {
            stopCameras()
            return
        }
        val saved = cfg("cams")
        val ids = when {
            action.startsWith("cam:") -> listOf(action.removePrefix("cam:"))
            action == "cams:all" -> (1..CAM_SLOTS).map { "c$it" }.filter { saved.optString("${it}_url").isNotBlank() }
            else -> emptyList()
        }
        if (ids.isEmpty()) return
        try {
            showCameras(JSONObject().put("cams", JSONArray(ids)))
            activeKeyAction = action
        } catch (e: Exception) {
            notify(JSONObject().put("title", "Brak adresu kamery").put("message", "Ustaw go na stronie ustawień")
                .put("icon", "mdi:cctv-off").put("duration", 5))
        }
    }

    /** Lista akcji do wyboru (wartość -> nazwa). */
    fun keyActions(): List<Pair<String, String>> {
        val saved = cfg("cams")
        val list = mutableListOf("none" to "Brak")
        for (i in 1..CAM_SLOTS) list.add("cam:c$i" to saved.optString("c${i}_name", "Kamera $i"))
        list.add("cams:all" to "Wszystkie kamery")
        list.add("stop" to "Zamknij kamery")
        for (s in SLOTS) {
            val c = cfg(s)
            val n = c.optString("label", "").ifBlank { c.optString("entity", "") }.ifBlank { "Encja ${s.drop(1)}" }
            list.add("show:$s" to "Pokaż: $n")
        }
        list.add("show:all" to "Pokaż wszystkie pola „na żądanie”")
        return list
    }
}
