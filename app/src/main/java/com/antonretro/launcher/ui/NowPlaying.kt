package com.antonretro.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.Perf
import com.antonretro.launcher.data.MusicRepo
import com.antonretro.launcher.data.Song
import com.antonretro.launcher.kit.Backdrop
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit

/**
 * Plays music (one queue at a time) with Android's own player, which also plays MIDI (Anthony's songs).
 * Keeps playing when Now Playing is closed; the launcher shows a "Now playing" chip.
 */
class MusicPlayer(private val ctx: Context) {
    var queue: List<Song> = emptyList(); private set
    var index = 0; private set
    var shuffle = false; var repeat = 0          // 0 off, 1 all, 2 one
    private var mp: MediaPlayer? = null
    var onChanged: (() -> Unit)? = null
    val current: Song? get() = queue.getOrNull(index)
    val playing: Boolean get() = try { mp?.isPlaying == true } catch (e: Exception) { false }
    val active: Boolean get() = mp != null
    val sessionId: Int get() = try { mp?.audioSessionId ?: 0 } catch (e: Exception) { 0 }
    val position: Int get() = try { mp?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    val duration: Int get() = try { mp?.duration ?: 0 } catch (e: Exception) { 0 }

    fun play(list: List<Song>, i: Int) { queue = list; index = i.coerceIn(0, list.size - 1); start() }

    private fun start() {
        stopPlayer()
        val s = current ?: return
        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                if (s.asset != null) ctx.assets.openFd(s.asset).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) } else setDataSource(s.file!!.path)
                setOnCompletionListener { next(auto = true) }
                setOnErrorListener { _, w, x -> Perf.note("music error $w/$x on ${s.title}"); next(auto = true); true }
                prepare(); start()
            }
        } catch (e: Exception) { Perf.note("music can't play ${s.title}: $e"); mp = null }
        onChanged?.invoke()
    }

    fun toggle() { mp?.let { if (it.isPlaying) it.pause() else it.start() } ?: start(); onChanged?.invoke() }
    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && repeat == 2) { start(); return }
        index = if (shuffle && queue.size > 1) { var n: Int; do { n = (queue.indices).random() } while (n == index); n }
                else if (index + 1 < queue.size) index + 1 else if (repeat == 1 || !auto) 0 else { stop(); return }
        start()
    }
    fun previous() { if (position > 3000) mp?.seekTo(0) else { index = if (index > 0) index - 1 else queue.size - 1; start() } }
    fun seekBy(ms: Int) { mp?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, it.duration.coerceAtLeast(0))) } }
    private fun stopPlayer() { try { mp?.release() } catch (_: Exception) {}; mp = null }
    fun stop() { stopPlayer(); onChanged?.invoke() }
}

/**
 * Now Playing: the visualizer fills the screen behind (Anthony's own engine, in a WebView, fed from the player's
 * audio), with the song, a progress line and the controls on a glass bar. Falls back to the blurred wallpaper if
 * the visualizer can't run. Left/Right moves between controls, OK presses, Back closes (the music keeps playing).
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class NowPlaying(ctx: Context, private val music: MusicPlayer) : FrameLayout(ctx) {
    val showing get() = visibility == View.VISIBLE
    var onClose: (() -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var vis: Visualizer? = null
    private val bg = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = Kit.text(ctx, 34f, true)
    private val artist = Kit.text(ctx, 20f, true, Kit.TEXT_DIM)
    private val times = Kit.text(ctx, 16f, true, Kit.TEXT_DIM)
    private val track = FrameLayout(ctx); private val fill = View(ctx)
    private val styleText = Kit.text(ctx, 16f, true, Kit.TEXT_DIM)
    private class Ctl(val view: TextView, val run: () -> Unit, val side: ((Int) -> Unit)? = null)
    private val ctls = ArrayList<Ctl>()
    private val shade = View(ctx)
    private val bar = LinearLayout(ctx)
    /** The bar hides after a few seconds without input so the visualizer fills the screen (Anthony: "I really want that"). */
    private var controlsShown = true
    private val autoHide = Runnable { setControls(false, "after 5 s") }
    private fun setControls(show: Boolean, why: String = "") {
        if (show && !controlsShown || !show) Perf.note("now playing bar ${if (show) "shown" else "hidden"} $why")
        controlsShown = show
        for (v in listOf<View>(shade, bar)) { v.animate().cancel(); v.animate().alpha(if (show) 1f else 0f).setDuration(Kit.t(if (show) 200 else 600)).start() }
        ui.removeCallbacks(autoHide); if (show) ui.postDelayed(autoHide, 5000)
    }
    /** Any key or mouse movement: bring the bar back. Returns true if it was hidden (that input only wakes it). */
    private var wakeLogged = 0L
    private fun wake(why: String = "input"): Boolean { val now = android.os.SystemClock.uptimeMillis(); if (now - wakeLogged > 1000) { wakeLogged = now; Perf.note("now playing wake: $why") }; val was = !controlsShown; setControls(true, why); return was }
    private var lastSong: Song? = null
    private var lastX = -1f; private var lastY = -1f
    private var sel = 1
    private var style = "battery"; private var presets: List<String> = listOf("Random"); private var preset = 0

    init {
        visibility = View.GONE; isClickable = true; setBackgroundColor(Color.BLACK)
        addView(bg, -1, -1)
        // the visualizer goes in at index 1 (behind everything else) when it is created
        // the darkening only behind the bar (a full-screen layer over the visualizer cost a blend every frame)
        shade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xCC000000.toInt()))
        addView(shade, LayoutParams(-1, Kit.dpi(ctx, 320f), Gravity.BOTTOM))
        bar.orientation = LinearLayout.HORIZONTAL; bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Kit.dpi(ctx, 20f), Kit.dpi(ctx, 20f), Kit.dpi(ctx, 28f), Kit.dpi(ctx, 20f))
        bar.background = GlassDrawable(bar, Kit.dp(ctx, Kit.R_L), Kit.GLASS_STRONG)
        cover.background = GradientDrawable().apply { cornerRadius = Kit.dp(ctx, Kit.inner(Kit.R_L, 20f)) }; cover.clipToOutline = true
        bar.addView(cover, LinearLayout.LayoutParams(Kit.dpi(ctx, 150f), Kit.dpi(ctx, 150f)).apply { rightMargin = Kit.dpi(ctx, 26f) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title, LinearLayout.LayoutParams(-1, -2))
        col.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 4f) })
        track.background = GradientDrawable().apply { cornerRadius = Kit.dp(ctx, 2f); setColor(0x33FFFFFF) }
        fill.background = GradientDrawable().apply { cornerRadius = Kit.dp(ctx, 2f); setColor(Kit.ACCENT) }; fill.pivotX = 0f
        track.addView(fill, -1, -1)
        col.addView(track, LinearLayout.LayoutParams(-1, Kit.dpi(ctx, 4f)).apply { topMargin = Kit.dpi(ctx, 14f) })
        col.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 6f) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun ctl(label: String, w: Float, run: () -> Unit, side: ((Int) -> Unit)? = null): TextView {
            val tv = Kit.text(ctx, 20f, true).apply { text = label; gravity = Gravity.CENTER }
            val i = ctls.size
            tv.setOnClickListener { sel = i; run(); paint() }
            tv.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { sel = i; paint() }; false }
            row.addView(tv, LinearLayout.LayoutParams(Kit.dpi(ctx, w), Kit.dpi(ctx, 48f)).apply { rightMargin = Kit.dpi(ctx, 10f) })
            ctls.add(Ctl(tv, run, side)); return tv
        }
        ctl("⏮", 64f, { music.previous() }); ctl("⏯", 64f, { music.toggle() }); ctl("⏭", 64f, { music.next() })
        ctl("Shuffle", 120f, { music.shuffle = !music.shuffle })
        ctl("Repeat", 120f, { music.repeat = (music.repeat + 1) % 3 })
        ctl("Plasma", 130f, { setStyle(if (style == "battery") "barswaves" else "battery") })
        ctl("Random", 190f, { setPreset(preset + 1) }, { d -> setPreset(preset + d) })
        col.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(ctx, 14f) })
        bar.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        addView(bar, LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); rightMargin = Kit.dpi(ctx, Kit.SAFE_X); bottomMargin = Kit.dpi(ctx, Kit.SAFE_Y) })
        styleText.text = "Visualizer"
    }

    private val tick = object : Runnable { override fun run() { if (showing) { progress(); ui.postDelayed(this, 500) } } }

    fun show() {
        Backdrop.blurredDim?.let { bg.setImageBitmap(it) }
        visibility = View.VISIBLE; alpha = 0f; bringToFront()
        animate().alpha(1f).setDuration(Kit.t(250)).start()
        startVisualizer()
        refresh(); ui.removeCallbacks(tick); ui.post(tick)
        setControls(true)
    }

    fun hide() {
        ui.removeCallbacks(tick); ui.removeCallbacks(autoHide); stopVisualizer()
        animate().alpha(0f).setDuration(Kit.t(200)).withEndAction { visibility = View.GONE }.start()
    }

    fun refresh() {
        val s = music.current ?: return
        if (s !== lastSong) { lastSong = s; if (showing) setControls(true, "new song") }
        title.text = s.title; artist.text = if (s.album.isNotEmpty() && s.album != s.artist) "${s.artist}  ·  ${s.album}" else s.artist
        cover.setImageBitmap(coverFor(s, Kit.dpi(context, 150f)))
        Thread { val b = MusicRepo.cover(s, Kit.dpi(context, 150f)); if (b != null) ui.post { if (music.current === s) cover.setImageBitmap(b) } }.start()
        ctls[1].view.text = if (music.playing) "⏸" else "▶"
        ctls[3].view.text = if (music.shuffle) "Shuffle on" else "Shuffle"
        ctls[4].view.text = when (music.repeat) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat" }
        ctls[5].view.text = if (style == "battery") "Plasma" else "Spectrum"
        ctls[6].view.text = "◀ " + (presets.getOrNull(preset) ?: "Random") + " ▶"
        progress(); paint()
        // the visualizer listens to whatever session is playing now
        if (showing) { stopTap(); startTap() }
    }

    private fun progress() {
        val d = music.duration; val p = music.position
        fill.scaleX = if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
        fun f(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
        times.text = if (d > 0) "${f(p)}  /  ${f(d)}" + if (music.playing) "" else "   ·   Paused" else ""
        ctls.getOrNull(1)?.view?.text = if (music.playing) "⏸" else "▶"
    }

    private fun paint() {
        for ((i, c) in ctls.withIndex()) {
            val on = i == sel
            c.view.background = GradientDrawable().apply { cornerRadius = Kit.dp(context, Kit.pill(48f)); setColor(if (on) Kit.FOCUS_FILL else 0x24FFFFFF) }
            c.view.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val media = e.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        if (wake() && !media && e.keyCode != KeyEvent.KEYCODE_BACK && e.keyCode != KeyEvent.KEYCODE_BUTTON_B) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { sel = (sel - 1).coerceAtLeast(0); paint() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { sel = (sel + 1).coerceAtMost(ctls.size - 1); paint() }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> ctls[sel].side?.invoke(if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (e.repeatCount == 0) { ctls[sel].run(); refresh() }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { music.toggle(); refresh() }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_BUTTON_R1 -> { music.next(); refresh() }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_BUTTON_L1 -> { music.previous(); refresh() }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> music.seekBy(10_000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> music.seekBy(-10_000)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) { hide(); onClose?.invoke() }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        // only real movement counts: something on this box reports a pointer that never moves
        if (e.action == MotionEvent.ACTION_SCROLL) { wake("wheel"); return true }
        if (e.action == MotionEvent.ACTION_HOVER_MOVE) {
            if (Math.abs(e.x - lastX) + Math.abs(e.y - lastY) > 6) { if (lastX >= 0) wake("mouse"); lastX = e.x; lastY = e.y }
            return true
        }
        return super.onGenericMotionEvent(e)
    }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean { if (!controlsShown) { wake(); return true }; return false }

    // ------------------------------------------------------------------ the visualizer (Anthony's engine, bundled; no internet)
    private fun startVisualizer() {
        if (web == null) try {
            web = WebView(context).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                settings.allowFileAccess = true
                @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true
                setLayerType(View.LAYER_TYPE_HARDWARE, null); setBackgroundColor(Color.BLACK)
                isFocusable = false; isFocusableInTouchMode = false
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        bg.visibility = View.INVISIBLE   // the visualizer covers it: don't draw the wallpaper under it every frame
                        view.evaluateJavascript("setSize(1280,720);setVisualizer('$style');setIdle('demo');listPresets()") { json ->
                            presets = try { org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { listOf("Random") }
                            preset = 0; refresh()
                        }
                    }
                }
                loadUrl("file:///android_asset/visualizer/nowplaying.html")
            }
            addView(web, 1, LayoutParams(-1, -1))
        } catch (e: Throwable) { Perf.note("visualizer can't start: $e"); web = null }
        web?.onResume(); web?.evaluateJavascript("resume()", null)
        startTap()
    }

    private fun stopVisualizer() {
        stopTap()
        bg.visibility = View.VISIBLE
        web?.let { w -> w.evaluateJavascript("pause()", null); removeView(w); w.destroy() }
        web = null
    }

    private fun setStyle(s: String) { style = s; web?.evaluateJavascript("setVisualizer('$s');listPresets()") { json ->
        presets = try { org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { listOf("Random") }
        preset = 0; refresh() } }
    private fun setPreset(i: Int) { if (presets.isEmpty()) return; preset = ((i % presets.size) + presets.size) % presets.size; web?.evaluateJavascript("setPreset($preset)", null); refresh() }

    /** Android's audio visualiser on the music player's session: waveform and spectrum bytes, ~15 times a second. */
    private fun startTap() {
        val id = music.sessionId; if (id == 0 || vis != null) return
        try {
            vis = Visualizer(id).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    var wave: ByteArray? = null
                    override fun onWaveFormDataCapture(v: Visualizer, w: ByteArray, rate: Int) { wave = w }
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) { feed(fft, wave) }
                }, (Visualizer.getMaxCaptureRate() / 2).coerceAtMost(10000), true, true)
                enabled = true
            }
        } catch (e: Throwable) { Perf.note("visualizer tap unavailable: $e"); vis = null }
    }
    private fun stopTap() { try { vis?.enabled = false; vis?.release() } catch (_: Exception) {}; vis = null }

    private val sb = StringBuilder(12000)
    /** Spectrum magnitudes and waveform bytes, 1024 each, in the visualizer's feedLevel() form. */
    private fun feed(fft: ByteArray, wave: ByteArray?) {
        val w = web ?: return
        sb.setLength(0); sb.append("feedLevel({freq:[")
        val bins = fft.size / 2
        for (i in 0 until 1024) {
            val b = (i * bins / 1024).coerceAtMost(bins - 1)
            val re = fft[2 * b].toInt(); val im = fft[2 * b + 1].toInt()
            val m = (Math.sqrt((re * re + im * im).toDouble()) * 2.2).toInt().coerceIn(0, 255)
            if (i > 0) sb.append(','); sb.append(m)
        }
        sb.append("],wave:[")
        for (i in 0 until 1024) { val v = wave?.let { it[(i * it.size / 1024).coerceAtMost(it.size - 1)].toInt() and 0xFF } ?: 128; if (i > 0) sb.append(','); sb.append(v) }
        sb.append("]})")
        val js = sb.toString()
        ui.post { w.evaluateJavascript(js, null) }
    }

    companion object {
        /** A drawn cover for songs without one: the title on a colour of its own. */
        fun coverFor(s: Song, px: Int): Bitmap {
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val hue = ((s.title.hashCode() and 0x7fffffff) % 360).toFloat()
            c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), Color.HSVToColor(floatArrayOf(hue, .6f, .55f)), Color.HSVToColor(floatArrayOf((hue + 40) % 360, .7f, .2f)), Shader.TileMode.CLAMP) })
            // the title wraps word by word to fit the cover, and gets smaller if it still wouldn't fit: never cut off
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
            val maxW = px * .84f
            var size = px * .14f; var lines: List<String>
            while (true) {
                p.textSize = size
                val out = ArrayList<String>(); var line = ""
                for (w in s.title.split(' ')) { val tryLine = if (line.isEmpty()) w else "$line $w"; if (p.measureText(tryLine) <= maxW || line.isEmpty()) line = tryLine else { out.add(line); line = w } }
                if (line.isNotEmpty()) out.add(line)
                lines = out
                if ((lines.size <= 3 && lines.all { p.measureText(it) <= maxW }) || size < px * .07f) break
                size *= .88f
            }
            var y = px * .9f - (lines.size - 1) * size * 1.15f
            for (l in lines) { c.drawText(l, px * .08f, y, p); y += size * 1.15f }
            p.alpha = 190; p.textSize = px * .2f; c.drawText("♪", px * .08f, px * .28f, p)
            return b
        }
    }
}
