package com.antonretro.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.antonretro.launcher.Perf
import com.antonretro.launcher.data.Channel
import com.antonretro.launcher.data.Item
import com.antonretro.launcher.data.Prog
import com.antonretro.launcher.kit.*

/**
 * The one built-in player for TV and radio. Up/Down changes channel straight away, OK shows the
 * channel strip, Back goes back. A channel that doesn't start says so for two seconds and moves on:
 * never a spinner forever, never a freeze.
 */
@SuppressLint("ViewConstructor")
@UnstableApi
class PlayerOverlay(ctx: Context, private val artFor: (Item, CardCell) -> Unit) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val surface = SurfaceView(ctx)
    /** Black cover while a channel tunes, so the last channel's picture never sits frozen on screen. */
    private val cover = View(ctx).apply { setBackgroundColor(Color.BLACK) }
    private val radioPanel = FrameLayout(ctx)
    private val radioCard = CardCell(ctx, 300f, 169f, false)
    private val radioName = Kit.text(ctx, 30f, true)
    private val radioSub = Kit.text(ctx, Kit.BODY_TEXT, false, Kit.TEXT_DIM)
    private val banner = LinearLayout(ctx)
    private val bNum = Kit.text(ctx, 20f, true, Kit.TEXT_DIM)
    private val bName = Kit.text(ctx, 30f, true)
    private val bSub = Kit.text(ctx, 17f, false, Kit.TEXT_DIM)
    private val bLogo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val bNow = Kit.text(ctx, 20f, true)
    private val bTrack = FrameLayout(ctx)
    private val bFill = View(ctx)
    private val bClock = Kit.text(ctx, 24f, true)
    /** Now and next from the guide (either can be null). */
    var nowNext: ((Channel) -> Pair<Prog?, Prog?>)? = null
    private val error = Kit.text(ctx, 26f, true)
    private val strip = RowsScreen(ctx, 0f, false, artFor, { item, _ -> (item as? Item.Ch)?.let { tune(list.indexOf(it.ch), 0) }; hideStrip() })
    private val stripBox = FrameLayout(ctx)
    /** Mouse: a Back button appears top right whenever the mouse moves over the picture. */
    private val mouseBack = Kit.text(ctx, 18f, true).apply { text = "‹  Back"; gravity = Gravity.CENTER; alpha = 0f }
    private val hideMouseBack = Runnable { mouseBack.animate().alpha(0f).setDuration(Kit.t(300)).start() }

    var onClosed: ((Channel, Boolean) -> Unit)? = null
    var numberOf: (Channel) -> Int = { it.number }     // channel, keepPlaying (radio)
    var onTuned: ((Channel) -> Unit)? = null
    var onStar: ((Channel) -> Unit)? = null
    var onGuide: ((Channel) -> Unit)? = null
    /** "Now: News at Nine, until 9:30 PM" from the guide, or null. */
    var nowLine: ((Channel) -> String?)? = null

    private var player: ExoPlayer? = null
    /** Set while playing one of your own videos (not a channel). */
    private class VideoSession(val path: String, val title: String, val onExit: (Long, Long) -> Unit)
    private var video: VideoSession? = null
    private val tick = object : Runnable { override fun run() { val p = player ?: return; if (video != null) { bSub.text = progressText(p); ui.postDelayed(this, 1000) } } }
    private fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun progressText(p: ExoPlayer) = (if (p.playWhenReady) "" else "Paused  ·  ") + fmt(p.currentPosition) + " of " + fmt(p.duration.coerceAtLeast(0)) + "   ·   OK pause   ◀ back 10 s   ▶ ahead 30 s"

    fun playVideo(path: String, title: String, startMs: Long, onExit: (Long, Long) -> Unit) {
        Perf.log("playVideo", 0)
        prepare()
        video = VideoSession(path, title, onExit); list = emptyList()
        visibility = View.VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(Kit.t(220)).start()
        radioPanel.visibility = View.GONE; error.visibility = View.GONE
        cover.alpha = 0f; bNum.text = ""; bName.text = title; bSub.text = "Starting…"
        bLogo.visibility = View.GONE; bNow.visibility = View.GONE; bTrack.visibility = View.GONE; bClock.text = clock()
        banner.animate().cancel(); banner.alpha = 1f; ui.removeCallbacks(fadeBanner)
        val p = player ?: return
        p.volume = if (testMuted()) 0f else 1f
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(path)))); p.prepare()
        if (startMs > 5000) p.seekTo(startMs - 3000)
        p.playWhenReady = true
        ui.removeCallbacks(tick); ui.post(tick); ui.postDelayed(fadeBanner, 4000)
    }

    private fun showVideoBanner() { banner.animate().cancel(); banner.animate().alpha(1f).setDuration(Kit.t(150)).start(); ui.removeCallbacks(fadeBanner); ui.postDelayed(fadeBanner, 3500) }

    private fun endVideo(finished: Boolean) {
        val v = video ?: return; val p = player
        Perf.note("endVideo finished=$finished state=${p?.playbackState} vis=$visibility alpha=$alpha")
        val pos = if (finished) 0L else p?.currentPosition ?: 0L; val dur = p?.duration?.coerceAtLeast(0) ?: 0L
        video = null; ui.removeCallbacks(tick); ui.removeCallbacks(fadeBanner)
        p?.stop()
        animate().alpha(0f).setDuration(Kit.t(200)).withEndAction { visibility = View.GONE }.start()
        v.onExit(pos, dur)
    }
    private var list: List<Channel> = emptyList()
    private var idx = 0
    private var zapDir = 1
    private var stripOn = false
    private var tunedAt = 0L
    private var firstTune = true
    val showing get() = visibility == View.VISIBLE
    val current: Channel? get() = list.getOrNull(idx)
    val playing get() = player?.isPlaying == true || player?.playbackState == Player.STATE_BUFFERING

    init {
        visibility = View.GONE
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(-1, -1, Gravity.CENTER))
        addView(cover, LayoutParams(-1, -1))

        // radio: logo, name and clock over the blurred wallpaper
        radioPanel.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(Backdrop.blurredDim) }, -1, -1)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(FrameLayout(ctx).apply { clipChildren = false; addView(radioCard, LayoutParams(Kit.dpi(ctx, 300f), Kit.dpi(ctx, 169f))) }, LinearLayout.LayoutParams(Kit.dpi(ctx, 300f), Kit.dpi(ctx, 169f)))
        col.addView(radioName, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(ctx, 34f) })
        col.addView(radioSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(ctx, 8f) })
        radioCard.label.visibility = View.GONE
        radioPanel.addView(col, LayoutParams(-2, -2, Gravity.CENTER))
        radioPanel.visibility = View.GONE
        addView(radioPanel, -1, -1)

        // the watching banner: one wide glass bar along the bottom, always the full width (never a box sized to
        // whatever text it had first, which once cut programme names off). Fades after a few seconds.
        banner.orientation = LinearLayout.HORIZONTAL; banner.gravity = Gravity.CENTER_VERTICAL
        banner.setPadding(Kit.dpi(ctx, 20f), Kit.dpi(ctx, 16f), Kit.dpi(ctx, 28f), Kit.dpi(ctx, 16f))
        banner.background = GlassDrawable(banner, Kit.dp(ctx, 24f), Kit.GLASS_STRONG)
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        bLogo.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(ctx, Kit.inner(Kit.R_L, 16f)); setColor(0x22FFFFFF) }   // 16 dp inside the banner: concentric
        bLogo.setPadding(Kit.dpi(ctx, 6f), Kit.dpi(ctx, 6f), Kit.dpi(ctx, 6f), Kit.dpi(ctx, 6f))
        left.addView(bLogo, LinearLayout.LayoutParams(Kit.dpi(ctx, 96f), Kit.dpi(ctx, 60f)))
        bNum.gravity = Gravity.CENTER
        left.addView(bNum, LinearLayout.LayoutParams(Kit.dpi(ctx, 96f), -2).apply { topMargin = Kit.dpi(ctx, 6f) })
        banner.addView(left, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Kit.dpi(ctx, 22f) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(bName, LinearLayout.LayoutParams(-1, -2))
        texts.addView(bNow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 6f) })
        bTrack.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(ctx, 2f); setColor(0x33FFFFFF) }
        bFill.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(ctx, 2f); setColor(Kit.ACCENT) }
        bFill.pivotX = 0f
        bTrack.addView(bFill, -1, -1)
        texts.addView(bTrack, LinearLayout.LayoutParams(Kit.dpi(ctx, 460f), Kit.dpi(ctx, 4f)).apply { topMargin = Kit.dpi(ctx, 8f) })
        texts.addView(bSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 8f) })
        banner.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        banner.addView(bClock, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, 24f) })
        addView(banner, LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); rightMargin = Kit.dpi(ctx, Kit.SAFE_X); bottomMargin = Kit.dpi(ctx, Kit.SAFE_Y) })

        error.gravity = Gravity.CENTER; error.text = "This channel isn't working right now"
        error.setPadding(Kit.dpi(ctx, 34f), Kit.dpi(ctx, 20f), Kit.dpi(ctx, 34f), Kit.dpi(ctx, 20f))
        error.background = GlassDrawable(error, Kit.dp(ctx, Kit.R_L), Kit.GLASS_STRONG)
        error.visibility = View.GONE
        addView(error, LayoutParams(-2, -2, Gravity.CENTER))

        // channel strip along the bottom
        stripBox.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000))
        stripBox.clipChildren = false
        stripBox.addView(strip.view, LayoutParams(-1, -1).apply { topMargin = Kit.dpi(ctx, 62f) })
        stripBox.visibility = View.GONE
        addView(stripBox, LayoutParams(-1, Kit.dpi(ctx, 250f), Gravity.BOTTOM))
        mouseBack.background = GlassDrawable(mouseBack, Kit.dp(ctx, Kit.pill(44f)), Kit.GLASS_STRONG)
        mouseBack.setOnClickListener { if (mouseBack.alpha > .1f) close(keepRadio = true) }
        addView(mouseBack, LayoutParams(Kit.dpi(ctx, 120f), Kit.dpi(ctx, 46f), Gravity.END).apply { topMargin = Kit.dpi(ctx, Kit.SAFE_Y); rightMargin = Kit.dpi(ctx, Kit.SAFE_X) })
        setOnGenericMotionListener { _, e ->
            if (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                mouseBack.bringToFront(); mouseBack.animate().alpha(1f).setDuration(Kit.t(150)).start(); ui.removeCallbacks(hideMouseBack); ui.postDelayed(hideMouseBack, 3000)
                // the banner comes up with the mouse too (not only with keys)
                if (!stripOn) { list.getOrNull(idx)?.let { if (video == null) showGuideLines(it) }; banner.animate().cancel(); banner.animate().alpha(1f).setDuration(Kit.t(150)).start(); ui.removeCallbacks(fadeBanner); ui.postDelayed(fadeBanner, 3500) }
            }
            false
        }
    }

    /** Testing over ADB: a file /sdcard/RetroLauncher/.test-mute silences the player. */
    private fun testMuted() = java.io.File(android.os.Environment.getExternalStorageDirectory(), "RetroLauncher/.test-mute").exists()

    /** Created once at start-up so the first channel doesn't pay for it. */
    /** Settings → TV: start fast or smoother, and the picture quality limit. Applies from the next channel. */
    var smooth = false
    var maxHeight = 1080
    fun reconfigure(smooth: Boolean, maxHeight: Int) {
        if (smooth == this.smooth && maxHeight == this.maxHeight) return
        this.smooth = smooth; this.maxHeight = maxHeight
        if (!showing && !playing) { player?.release(); player = null }
    }

    fun prepare() {
        if (player != null) return
        val load = DefaultLoadControl.Builder()
            .apply { if (smooth) setBufferDurationsMs(8000, 30000, 2500, 5000) else setBufferDurationsMs(3000, 20000, 900, 1800) }   // start fast, or wait a little for fewer pauses
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val tracks = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSize(maxHeight * 16 / 9, maxHeight).setMaxVideoFrameRate(60).setExceedRendererCapabilitiesIfNecessary(false))
        }
        val http = DefaultHttpDataSource.Factory().setUserAgent("RetroLauncher/1 (Android TV)")
            .setConnectTimeoutMs(6000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(true)
        // start on a modest stream quality so the first picture comes fast, then step up
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(1_500_000).build()
        player = ExoPlayer.Builder(context).setLoadControl(load).setTrackSelector(tracks).setBandwidthMeter(meter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, http)))   // web streams and files on the box
            .build().apply {
                setVideoSurfaceView(surface)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { Perf.note("error " + error.errorCodeName + " " + (error.cause?.toString() ?: "")); fail() }
                    override fun onRenderedFirstFrame() = started()
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && video != null) endVideo(finished = true)
                        if (state == Player.STATE_READY && list.getOrNull(idx)?.radio == true) started()
                    }
                })
            }
    }

    fun play(channels: List<Channel>, index: Int) {
        prepare()
        list = channels; visibility = View.VISIBLE; alpha = 0f
        animate().alpha(1f).setDuration(Kit.t(220)).start()
        firstTune = true
        tune(index, 1)
    }

    private val watchdog = Runnable { fail() }
    private val fadeBanner = Runnable { banner.animate().alpha(0f).setDuration(Kit.t(400)).start() }

    private fun tune(i: Int, dir: Int) {
        if (list.isEmpty()) return
        idx = ((i % list.size) + list.size) % list.size; zapDir = if (dir == 0) 1 else dir
        val c = list[idx]
        error.visibility = View.GONE
        radioPanel.visibility = if (c.radio) View.VISIBLE else View.GONE
        if (c.radio) {
            radioName.text = c.name; radioSub.text = "${c.country}  ·  Back keeps it playing"
            radioCard.tag = null; artFor(Item.Ch(c), radioCard)
        }
        bNum.text = numberOf(c).toString(); bName.text = c.name; bSub.text = "${c.country}  ·  Tuning…"
        bNow.visibility = View.GONE; bTrack.visibility = View.GONE; bClock.text = clock()
        bLogo.setImageDrawable(null); bLogo.visibility = View.VISIBLE
        val logo = c.logo
        if (logo != null) Images.load(logo, Kit.dpi(context, 84f), Kit.dpi(context, 48f)) { b -> if (b != null && list.getOrNull(idx) === c) bLogo.setImageBitmap(b) }
        else bLogo.setImageDrawable(android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.HSVToColor(floatArrayOf(((c.name.hashCode() and 0x7fffffff) % 360).toFloat(), .55f, .48f)), 0xFF101218.toInt())).apply { cornerRadius = Kit.dp(context, 8f) })
        banner.animate().cancel(); banner.alpha = 1f
        ui.removeCallbacks(fadeBanner); ui.removeCallbacks(watchdog)
        ui.postDelayed(watchdog, 8000)
        cover.animate().cancel(); cover.alpha = 1f
        tunedAt = SystemClock.uptimeMillis()
        val p = player ?: return
        val item = MediaItem.Builder().setUri(Uri.parse(c.url)).apply { if (c.url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        p.volume = if (testMuted()) 0f else 1f
        try { p.setMediaItem(item); p.prepare(); p.playWhenReady = true }
        catch (e: Exception) { Perf.note("player can't open ${c.url}: $e"); ui.post { fail() }; return }   // a format we can't play counts as a dead channel, never a crash
        onTuned?.invoke(c)
    }

    private fun started() {
        ui.removeCallbacks(watchdog)
        if (video != null) return
        val c = list.getOrNull(idx) ?: return
        cover.animate().alpha(0f).setDuration(Kit.t(160)).start()
        Perf.log(if (firstTune) "channel" else "zap", SystemClock.uptimeMillis() - tunedAt)
        warmNeighbours()
        firstTune = false
        showGuideLines(c)
        ui.postDelayed(fadeBanner, 3500)
    }

    /** Fetch the neighbouring channels' playlists in the background so a channel change skips the DNS,
     *  TLS and playlist round trips (the slow part of starting an HLS stream on this box). */
    private fun warmNeighbours() {
        val near = listOfNotNull(list.getOrNull(idx + zapDir), list.getOrNull(idx - zapDir)).map { it.url }
        Thread {
            for (u in near) try {
                val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 3000; c.setRequestProperty("User-Agent", "RetroLauncher/1 (Android TV)")
                c.inputStream.use { it.readBytes() }; c.disconnect()
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    private fun fail() {
        ui.removeCallbacks(watchdog)
        if (video != null) {
            error.text = "This video can't be played on this box"; error.visibility = View.VISIBLE
            ui.postDelayed({ error.text = "This channel isn't working right now"; endVideo(false) }, 2500); return
        }
        if (!showing && !playing) return
        player?.stop()
        error.visibility = View.VISIBLE; error.alpha = 0f; error.animate().alpha(1f).setDuration(Kit.t(200)).start()
        val failedIdx = idx
        ui.postDelayed({ if (idx == failedIdx && showing && !stripOn) tune(idx + zapDir, zapDir) }, 2000)
    }

    /** Times as the launcher shows them (its own 12/24-hour setting, not Android's). */
    var timeText: (Long) -> String = { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)) }
    private fun clock(): String = timeText(System.currentTimeMillis())

    /** "Now: …" with its progress line and "Next: … at 9:30 PM" when the guide has them; otherwise the country and the keys. */
    private fun showGuideLines(c: Channel) {
        val (now, next) = try { nowNext?.invoke(c) ?: (null to null) } catch (e: Exception) { null to null }
        val t = System.currentTimeMillis()
        bClock.text = clock()
        if (now != null) {
            bNow.text = "Now: ${now.title}"; bNow.visibility = View.VISIBLE
            bTrack.visibility = View.VISIBLE
            bFill.scaleX = ((t - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
        } else { bNow.visibility = View.GONE; bTrack.visibility = View.GONE }
        bSub.text = (if (next != null) "Next: ${next.title} at ${timeText(next.start)}" else c.country) + "     Up/Down: other channels   ·   Menu: guide"
    }

    private fun showStrip() {
        stripOn = true
        strip.setRows(listOf(Row(null, list.map { Item.Ch(it) }, alwaysLabel = false)))
        strip.jumpTo(0, idx)
        stripBox.visibility = View.VISIBLE; stripBox.translationY = Kit.dp(context, 60f); stripBox.alpha = 0f
        stripBox.animate().translationY(0f).alpha(1f).setDuration(Kit.t(260)).setInterpolator(Kit.EASE).start()
        banner.animate().alpha(0f).setDuration(Kit.t(150)).start(); ui.removeCallbacks(fadeBanner)
    }
    private fun hideStrip() {
        stripOn = false
        stripBox.animate().translationY(Kit.dp(context, 60f)).alpha(0f).setDuration(Kit.t(220)).withEndAction { stripBox.visibility = View.GONE }.start()
        ui.postDelayed(fadeBanner, 2500)
    }

    /** Returns true when the key was used. */
    fun onKey(code: Int): Boolean {
        video?.let {
            val p = player ?: return true
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> p.playWhenReady = !p.playWhenReady
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p.seekTo(p.currentPosition + 30_000)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {}
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> { endVideo(false); return true }
                else -> return false
            }
            bSub.text = progressText(p); showVideoBanner(); return true
        }
        if (stripOn) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> strip.move(0, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> strip.move(0, 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> strip.ok()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP -> hideStrip()
                else -> return false
            }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> tune(idx - 1, -1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> tune(idx + 1, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (current?.radio == true) { player?.let { it.playWhenReady = !it.playWhenReady; radioSub.text = if (it.playWhenReady) "${current?.country}  ·  Back keeps it playing" else "Paused  ·  OK to play, Back to stop" } }
                else showStrip()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> showStrip()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> current?.let { if (it.radio) onStar?.invoke(it) else onGuide?.invoke(it) }
            KeyEvent.KEYCODE_BUTTON_Y -> current?.let { onStar?.invoke(it) }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> close(keepRadio = true)
            else -> return false
        }
        return true
    }

    /** Back: TV stops; radio keeps playing in the background. */
    fun close(keepRadio: Boolean) {
        if (video != null) { endVideo(false); return }
        ui.removeCallbacks(watchdog); ui.removeCallbacks(fadeBanner)
        val c = current
        val keep = keepRadio && c?.radio == true && error.visibility != View.VISIBLE && player?.playWhenReady == true
        if (!keep) player?.stop()
        if (stripOn) { stripOn = false; stripBox.visibility = View.GONE }
        animate().alpha(0f).setDuration(Kit.t(200)).withEndAction { visibility = View.GONE }.start()
        if (c != null) onClosed?.invoke(c, keep)
    }

    fun stopBackground() { player?.stop() }
    fun resumeView() { if (current != null) { visibility = View.VISIBLE; alpha = 1f } }
    fun release() { player?.release(); player = null }
}
