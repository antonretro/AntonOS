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
 * A pass-through audio processor inside the music player: it hands a copy of the decoded sound to the visualizer
 * and changes nothing. (Android's audiofx Visualizer effect, used before, kept songs silent in Now Playing on this
 * box until the effect was released when leaving the screen: "I leave the app, and the music starts".)
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PcmTap : androidx.media3.common.audio.BaseAudioProcessor() {
    @Volatile var listener: ((ByteArray, Int, Int) -> Unit)? = null
    private var channels = 2; private var rate = 44100
    override fun onConfigure(f: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (f.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) throw androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException(f)
        channels = f.channelCount; rate = f.sampleRate
        return f   // same format out: nothing is altered
    }
    override fun queueInput(input: java.nio.ByteBuffer) {
        val n = input.remaining(); if (n == 0) return
        listener?.let { l -> val copy = ByteArray(n); input.duplicate().get(copy); l(copy, channels, rate) }
        replaceOutputBuffer(n).put(input).flip()
    }
}

/**
 * Plays music (one queue at a time). MP3, M4A, FLAC, OGG and WAV go through Media3 with the PcmTap above, so the
 * visualizer gets the real sound; MIDI (which Media3 can't play) uses Android's own player, without visualizer data.
 * Keeps playing when Now Playing is closed; the launcher shows a "Now playing" chip.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlayer(private val ctx: Context) {
    var queue: List<Song> = emptyList(); private set
    var index = 0; private set
    var shuffle = false; var repeat = 0          // 0 off, 1 all, 2 one
    val tap = PcmTap()
    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private var mp: MediaPlayer? = null
    var onChanged: (() -> Unit)? = null
    val current: Song? get() = queue.getOrNull(index)
    val playing: Boolean get() = try { exo?.isPlaying == true || exo?.playWhenReady == true && exo?.playbackState == androidx.media3.common.Player.STATE_BUFFERING || mp?.isPlaying == true } catch (e: Exception) { false }
    val active: Boolean get() = exo != null || mp != null
    val position: Int get() = try { (exo?.currentPosition ?: mp?.currentPosition?.toLong() ?: 0L).toInt() } catch (e: Exception) { 0 }
    val duration: Int get() = try { exo?.duration?.takeIf { it > 0 }?.toInt() ?: mp?.duration ?: 0 } catch (e: Exception) { 0 }

    private fun exoPlayer(): androidx.media3.exoplayer.ExoPlayer = exo ?: run {
        val rf = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(tap)).build()
        }
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx, rf).build().also { p ->
            p.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    Perf.note("music state $state on ${current?.title}")
                    if (state == androidx.media3.common.Player.STATE_ENDED) next(auto = true) else onChanged?.invoke()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { onChanged?.invoke() }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { Perf.note("music error ${error.errorCodeName} on ${current?.title}"); next(auto = true) }
            })
            exo = p
        }
    }

    fun play(list: List<Song>, i: Int) { queue = list; index = i.coerceIn(0, list.size - 1); start() }

    private fun start() {
        val s = current ?: return
        try { mp?.release() } catch (_: Exception) {}; mp = null
        val midi = (s.file?.extension ?: s.asset?.substringAfterLast('.') ?: "").lowercase() in setOf("mid", "midi")
        try {
            if (midi) {
                exo?.stop()
                mp = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    if (s.asset != null) ctx.assets.openFd(s.asset).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) } else setDataSource(s.file!!.path)
                    setOnCompletionListener { next(auto = true) }
                    prepare(); start()
                }
            } else {
                val uri = if (s.asset != null) android.net.Uri.parse("asset:///" + s.asset) else android.net.Uri.fromFile(s.file)
                exoPlayer().apply { setMediaItem(androidx.media3.common.MediaItem.fromUri(uri)); prepare(); playWhenReady = true }
            }
            Perf.note("music start ${s.title} (${if (midi) "midi" else "media3"})")
        } catch (e: Exception) { Perf.note("music can't play ${s.title}: $e") }
        onChanged?.invoke()
    }

    fun toggle() {
        exo?.let { if (it.mediaItemCount > 0) { it.playWhenReady = !it.playWhenReady; onChanged?.invoke(); return } }
        mp?.let { if (it.isPlaying) it.pause() else it.start(); onChanged?.invoke(); return }
        start()
    }
    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && repeat == 2) { start(); return }
        index = if (shuffle && queue.size > 1) { var n: Int; do { n = (queue.indices).random() } while (n == index); n }
                else if (index + 1 < queue.size) index + 1 else if (repeat == 1 || !auto) 0 else { stop(); return }
        start()
    }
    fun previous() { if (position > 3000) seekTo(0) else { index = if (index > 0) index - 1 else queue.size - 1; start() } }
    private fun seekTo(ms: Int) { exo?.seekTo(ms.toLong()) ?: mp?.seekTo(ms) }
    fun seekBy(ms: Int) { seekTo((position + ms).coerceIn(0, duration.coerceAtLeast(0))) }
    fun stop() {
        try { mp?.release() } catch (_: Exception) {}; mp = null
        try { exo?.release() } catch (_: Exception) {}; exo = null
        onChanged?.invoke()
    }
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
        if (showing) startTap()
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

    /** The player's own pass-through tap feeds the visualizer: about 6 batches a second, base64 PCM. */
    private val pending = java.io.ByteArrayOutputStream()
    private fun startTap() {
        music.tap.listener = { bytes, ch, rate ->
            var out: String? = null
            synchronized(pending) {
                pending.write(bytes, 0, bytes.size)
                if (pending.size() >= rate / 6 * ch * 2) { out = android.util.Base64.encodeToString(pending.toByteArray(), android.util.Base64.NO_WRAP); pending.reset() }
            }
            out?.let { b64 -> ui.post { web?.evaluateJavascript("feedPcmBase64('$b64',$ch,$rate)", null) } }
        }
    }
    private fun stopTap() { music.tap.listener = null; synchronized(pending) { pending.reset() } }

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
