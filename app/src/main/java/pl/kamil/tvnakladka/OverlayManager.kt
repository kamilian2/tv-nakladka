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
        val ELEMENTS = listOf("clock", "e1", "e2", "e3")
        val ELEMENT_NAMES = mapOf("clock" to "Zegar", "e1" to "Encja 1", "e2" to "Encja 2", "e3" to "Encja 3")

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

        val BACKGROUNDS = listOf(
            "#00000000" to "Brak", "#55000000" to "Czarne 33%", "#99000000" to "Czarne 60%",
            "#CC000000" to "Czarne 80%", "#CC0F2A3F" to "Granatowe", "#99FFFFFF" to "Białe 60%"
        )

        private fun defaults(name: String): JSONObject = when (name) {
            "clock" -> JSONObject()
                .put("visible", true).put("x", 100).put("y", 0)
                .put("size", 34).put("font", "sans-serif-medium").put("bold", false)
                .put("color", "#FFFFFF").put("background", "#99000000")
                .put("format", "HH:mm:ss").put("icon", "")
            "e1", "e2", "e3" -> JSONObject()
                .put("visible", true).put("x", 100)
                .put("y", when (name) { "e1" -> 9; "e2" -> 17; else -> 25 })
                .put("size", 24).put("font", "sans-serif").put("bold", true)
                .put("color", "#FFFFFF").put("background", "#99000000")
            "notify" -> JSONObject()
                .put("corner", "top_start").put("size", 22).put("duration", 8)
                .put("font", "sans-serif").put("background", "#CC101820")
            "camera" -> JSONObject()
                .put("max_seconds", 30).put("width", 400).put("gap", 10)
                .put("align", "center").put("bottom", 24)
            "general" -> JSONObject().put("margin", 24)
            else -> JSONObject()
        }

        val SECTIONS = listOf("clock", "e1", "e2", "e3", "notify", "camera", "general")
    }

    private val tag = "TvNakladka"
    private val handler = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = ctx.getSharedPreferences("nakladka", Context.MODE_PRIVATE)
    private val assetFonts = HashMap<String, Typeface>()

    private var root: FrameLayout? = null
    private val corners = HashMap<String, LinearLayout>()

    private class Element(val pill: LinearLayout, val icon: TextView, val text: TextView)
    private val elements = HashMap<String, Element>()

    /** Treść pól na encje (z HA): tekst, ikona, opcjonalny kolor. */
    private class Content(var text: String = "", var icon: String = "", var color: String = "")
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
        for (e in listOf("e1", "e2", "e3")) contents[e] = Content()
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
            for (k in v.keys()) sec.put(k, v.get(k))
        }
        prefs.edit().putString("config", stored.toString()).apply()
        refreshAll()
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
            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable()
                addView(icon)
                addView(text)
            }
            r.addView(pill, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT
            ))
            pill.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position(name) }
            elements[name] = Element(pill, icon, text)
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

    private fun styleElement(name: String) {
        val el = elements[name] ?: return
        val c = cfg(name)
        val size = c.optDouble("size", 24.0).toFloat()
        val content = contents[name]
        val color = parseColor(
            content?.color?.takeIf { it.isNotBlank() } ?: c.optString("color"), Color.WHITE
        )
        val tf = typeface(c.optString("font", "sans-serif"), c.optBoolean("bold", false))

        el.text.typeface = tf
        el.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        el.text.setTextColor(color)

        val iconName = if (name == "clock") c.optString("icon", "") else (content?.icon ?: "")
        setIcon(el.icon, iconName, size, color)

        val bg = parseColor(c.optString("background"), Color.parseColor("#99000000"))
        (el.pill.background as GradientDrawable).apply {
            setColor(bg)
            cornerRadius = ctx.dp(size * 0.45f).toFloat()
            setStroke(if (editMode) ctx.dp(2f) else 0, Color.parseColor("#FF18BCF2"))
        }
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
            val txt = content?.text ?: ""
            val show = c.optBoolean("visible", true) && (txt.isNotEmpty() || editMode)
            el.text.text = if (txt.isEmpty() && editMode) ELEMENT_NAMES[name] else txt
            el.pill.visibility = if (show) View.VISIBLE else View.GONE
        }
        position(name)
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
        el.pill.translationX = (m + freeW * x).toFloat()
        el.pill.translationY = (m + freeH * y).toFloat()
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
            ?: throw IllegalArgumentException("Podaj slot: 1, 2 albo 3")
        if (j.has("visible") && !j.optBoolean("visible", true)) {
            clearEntity(slot)
            return slot
        }
        val c = contents[slot]!!
        c.text = j.optString("text", j.optString("message", ""))
        c.icon = j.optString("icon", "")
        c.color = j.optString("color", "")

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

    fun entitiesJson(): JSONObject = JSONObject().also { o ->
        for ((k, v) in contents) o.put(k, JSONObject().put("text", v.text).put("icon", v.icon).put("color", v.color))
    }

    // ================= powiadomienia =================

    fun notify(j: JSONObject): String {
        val id = j.optString("id", "").ifEmpty { "n_" + System.currentTimeMillis() }
        val n = cfg("notify")
        val corner = normalizeCorner(j.optString("corner", n.optString("corner")), "top_start")
        val size = j.optDouble("size", n.optDouble("size", 22.0)).toFloat()
        val color = parseColor(j.optString("color", ""), Color.WHITE)
        val bg = parseColor(j.optString("background", ""), parseColor(n.optString("background"), Color.parseColor("#CC101820")))
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
        if (list.isEmpty()) throw IllegalArgumentException("Brak adresów kamer (streams)")

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
        handler.removeCallbacks(stopCamerasRunnable)
        val list = players.toList()
        players.clear()
        for (p in list) try { p.release() } catch (_: Exception) {}
        cameraRow?.let { (it.parent as? ViewGroup)?.removeView(it) }
        cameraRow = null
    }

    val camerasActive get() = cameraRow != null
}
