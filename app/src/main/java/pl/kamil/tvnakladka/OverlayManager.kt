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
 * Cała nakładka na ekranie. Wszystkie metody wywoływać na wątku głównym.
 */
@OptIn(UnstableApi::class)
class OverlayManager(private val ctx: Context) {

    private val tag = "TvNakladka"
    private val handler = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = ctx.getSharedPreferences("nakladka", Context.MODE_PRIVATE)

    private var root: FrameLayout? = null
    private val corners = HashMap<String, LinearLayout>()

    private var clockPill: LinearLayout? = null
    private var clockIcon: TextView? = null
    private var clockText: TextView? = null
    private var clockFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale("pl", "PL"))

    private var tilesBox: LinearLayout? = null
    private val tiles = LinkedHashMap<String, View>()
    private val tileExpiry = HashMap<String, Runnable>()

    private val notifications = HashMap<String, View>()
    private val notificationExpiry = HashMap<String, Runnable>()

    private var cameraRow: LinearLayout? = null
    private val players = mutableListOf<ExoPlayer>()
    private val stopCamerasRunnable = Runnable { stopCameras() }

    /** Ustawienia trwałe (zapisywane w pamięci TV). */
    var config: JSONObject = try {
        JSONObject(prefs.getString("config", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }
        private set

    // ---------- ustawienia ----------

    private fun section(name: String): JSONObject =
        config.optJSONObject(name) ?: JSONObject().also { config.put(name, it) }

    private fun clockCfg() = section("clock")
    private fun tilesCfg() = section("tiles")
    private fun notifyCfg() = section("notify")
    private fun cameraCfg() = section("camera")

    private val clockCorner get() = clockCfg().optString("corner", "top_end")
    private val tilesCorner get() = tilesCfg().optString("corner", "").ifEmpty { clockCorner }
    private val margin get() = config.optInt("margin", 28).toFloat()

    /** Łączy podane ustawienia z obecnymi (tylko podane pola) i zapisuje. */
    fun updateConfig(patch: JSONObject) {
        for (key in patch.keys()) {
            val v = patch.get(key)
            if (v is JSONObject) {
                val sec = section(key)
                for (k in v.keys()) sec.put(k, v.get(k))
            } else {
                config.put(key, v)
            }
        }
        prefs.edit().putString("config", config.toString()).apply()
        if (root != null) {
            applyClockStyle()
            placeBoxes()
        }
    }

    // ---------- okno nakładki ----------

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

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

        // zegar
        val icon = TextView(ctx).apply { typeface = Mdi.typeface; setTextColor(Color.WHITE) }
        val text = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum" // cyfry o stałej szerokości – sekundy nie "skaczą"
            includeFontPadding = false
        }
        clockIcon = icon
        clockText = text
        clockPill = pill().apply { addView(icon); addView(text) }
        tilesBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        try {
            wm.addView(r, lp)
        } catch (e: Exception) {
            Log.e(tag, "Nie można dodać nakładki", e)
            corners.clear()
            return false
        }
        root = r
        applyClockStyle()
        placeBoxes()
        handler.post(clockTick)
        return true
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        stopCameras()
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        corners.clear()
        tiles.clear(); tileExpiry.clear()
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

    /** Ustawia zegar i kafelki w odpowiednich rogach oraz marginesy. */
    private fun placeBoxes() {
        val clock = clockPill ?: return
        val tb = tilesBox ?: return
        (clock.parent as? ViewGroup)?.removeView(clock)
        (tb.parent as? ViewGroup)?.removeView(tb)

        val m = ctx.dp(margin)
        for ((_, box) in corners) {
            (box.layoutParams as FrameLayout.LayoutParams).setMargins(m, m, m, m)
            box.requestLayout()
        }

        val cc = normalizeCorner(clockCorner, "top_end")
        val tc = normalizeCorner(tilesCorner, cc)
        val clockBox = corners[cc]!!
        val tilesTarget = corners[tc]!!

        if (clockCfg().optBoolean("visible", true)) clockBox.addView(clock, 0)
        if (tc == cc) {
            val idx = if (clock.parent == clockBox) 1 else 0
            tilesTarget.addView(tb, idx)
        } else {
            tilesTarget.addView(tb, 0)
        }
    }

    private fun pill(bg: Int = Color.parseColor("#99000000")): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14f), ctx.dp(6f), ctx.dp(14f), ctx.dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12f).toFloat()
                setColor(bg)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = ctx.dp(6f) }
        }

    private fun setPillColor(v: View, color: Int) {
        (v.background as? GradientDrawable)?.setColor(color)
    }

    /** Ustawia ikonę: "mdi:nazwa" albo dowolny tekst/emoji. */
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
        tv.setPadding(0, 0, ctx.dp(8f), 0)
        tv.visibility = if (tv.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- zegar ----------

    private fun applyClockStyle() {
        val c = clockCfg()
        val size = c.optDouble("size", 30.0).toFloat()
        val color = parseColor(c.optString("color", ""), Color.WHITE)
        clockFormat = try {
            SimpleDateFormat(c.optString("format", "HH:mm:ss"), Locale("pl", "PL"))
        } catch (e: Exception) {
            SimpleDateFormat("HH:mm:ss", Locale("pl", "PL"))
        }
        clockText?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
        }
        clockIcon?.let { setIcon(it, c.optString("icon", ""), size * 0.9f, color) }
        clockPill?.let { setPillColor(it, parseColor(c.optString("background", ""), Color.parseColor("#99000000"))) }
        clockText?.text = clockFormat.format(Date())
    }

    private val clockTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            clockText?.text = clockFormat.format(Date(now))
            // następne odświeżenie tuż po pełnej sekundzie
            handler.postDelayed(this, 1000 - (now % 1000) + 10)
        }
    }

    // ---------- kafelki ----------

    fun setTile(j: JSONObject): String {
        val id = j.optString("id", "").ifEmpty { "tile_" + System.currentTimeMillis() }
        if (j.has("visible") && !j.optBoolean("visible", true)) {
            removeTile(id)
            return id
        }
        val box = tilesBox ?: return id
        val t = tilesCfg()
        val size = j.optDouble("size", t.optDouble("size", 24.0)).toFloat()
        val color = parseColor(j.optString("color", ""), parseColor(t.optString("color", ""), Color.WHITE))
        val bg = parseColor(j.optString("background", ""), parseColor(t.optString("background", ""), Color.parseColor("#99000000")))
        val text = j.optString("text", j.optString("message", ""))

        val view = tiles[id] ?: pill().also { p ->
            p.addView(TextView(ctx))
            p.addView(TextView(ctx).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                fontFeatureSettings = "tnum"
                includeFontPadding = false
            })
            box.addView(p)
            tiles[id] = p
        }
        val p = view as LinearLayout
        setPillColor(p, bg)
        setIcon(p.getChildAt(0) as TextView, j.optString("icon", ""), size, color)
        (p.getChildAt(1) as TextView).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }

        tileExpiry.remove(id)?.let { handler.removeCallbacks(it) }
        val secs = parseSeconds(if (j.has("expiration")) j.opt("expiration") else null, 0)
        if (secs > 0) {
            val r = Runnable { removeTile(id) }
            tileExpiry[id] = r
            handler.postDelayed(r, secs * 1000)
        }
        return id
    }

    fun removeTile(id: String) {
        tileExpiry.remove(id)?.let { handler.removeCallbacks(it) }
        tiles.remove(id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun clearTiles() {
        for (id in tiles.keys.toList()) removeTile(id)
    }

    fun tileIds(): List<String> = tiles.keys.toList()

    // ---------- powiadomienia ----------

    fun notify(j: JSONObject): String {
        val id = j.optString("id", "").ifEmpty { "n_" + System.currentTimeMillis() }
        val n = notifyCfg()
        val corner = normalizeCorner(j.optString("corner", n.optString("corner", "top_end")), "top_end")
        val size = j.optDouble("size", n.optDouble("size", 22.0)).toFloat()
        val color = parseColor(j.optString("color", ""), Color.WHITE)
        val bg = parseColor(j.optString("background", ""), parseColor(n.optString("background", ""), Color.parseColor("#CC101820")))
        val duration = parseSeconds(j.opt("duration"), n.optLong("duration", 8))

        removeNotification(id)

        val card = pill(bg).apply {
            setPadding(ctx.dp(18f), ctx.dp(12f), ctx.dp(18f), ctx.dp(12f))
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
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxWidth = ctx.dp(520f)
        })
        if (message.isNotBlank()) col.addView(TextView(ctx).apply {
            text = message
            setTextColor(color)
            alpha = 0.9f
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

    // ---------- kamery ----------

    /**
     * Pokazuje 1..N okienek z kamerami obok siebie.
     * streams: lista adresów (rtsp://, http(s)://...m3u8)
     */
    fun showCameras(j: JSONObject) {
        val c = cameraCfg()
        val list = mutableListOf<String>()
        val labels = mutableListOf<String>()
        val arr: JSONArray? = j.optJSONArray("streams")
        if (arr != null) for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            if (item is JSONObject) {
                list.add(item.optString("url"))
                labels.add(item.optString("label", ""))
            } else {
                list.add(item.toString())
                labels.add("")
            }
        }
        if (j.has("url")) { list.add(j.optString("url")); labels.add(j.optString("label", "")) }
        if (list.isEmpty()) return

        val maxSec = c.optLong("max_seconds", 30)
        val wanted = parseSeconds(j.opt("duration"), maxSec)
        val duration = if (maxSec > 0) minOf(wanted, maxSec) else wanted
        val widthDp = j.optDouble("width", c.optDouble("width", 400.0)).toFloat()
        val gapDp = c.optDouble("gap", 10.0).toFloat()
        val position = normalizeCorner(j.optString("position", c.optString("position", "bottom_end")), "bottom_end")

        stopCameras()
        val r = root ?: return

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val w = ctx.dp(widthDp)
        val h = w * 9 / 16
        list.forEachIndexed { i, url ->
            val cell = FrameLayout(ctx).apply {
                setBackgroundColor(Color.BLACK)
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(10f).toFloat()
                    setColor(Color.BLACK)
                }
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

        val m = ctx.dp(margin)
        r.addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            cornerGravity(position)
        ).apply { setMargins(m, m, m, m) })
        cameraRow = row

        handler.removeCallbacks(stopCamerasRunnable)
        if (duration > 0) handler.postDelayed(stopCamerasRunnable, duration * 1000)
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
