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
    private val error = Kit.text(ctx, 26f, true)
    private val strip = RowsScreen(ctx, 0f, false, artFor, { item, _ -> (item as? Item.Ch)?.let { tune(list.indexOf(it.ch), 0) }; hideStrip() })
    private val stripBox = FrameLayout(ctx)

    var onClosed: ((Channel, Boolean) -> Unit)? = null     // channel, keepPlaying (radio)
    var onTuned: ((Channel) -> Unit)? = null
    var onStar: ((Channel) -> Unit)? = null

    private var player: ExoPlayer? = null
    /** Set while playing one of your own videos (not a channel). */
    private class VideoSession(val path: String, val title: String, val onExit: (Long, Long) -> Unit)
    private var video: VideoSession? = null
    private val tick = object : Runnable { override fun run() { val p = player ?: return; if (video != null) { bSub.text = progressText(p); ui.postDelayed(this, 1000) } } }
    private fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun progressText(p: ExoPlayer) = (if (p.playWhenReady) "" else "Paused  ·  ") + fmt(p.currentPosition) + " of " + fmt(p.duration.coerceAtLeast(0)) + "   ·   OK pause   ◀ back 10 s   ▶ ahead 30 s"

    fun playVideo(path: String, title: String, startMs: Long, onExit: (Long, Long) -> Unit) {
        prepare()
        video = VideoSession(path, title, onExit); list = emptyList()
        visibility = View.VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(Kit.t(220)).start()
        radioPanel.visibility = View.GONE; error.visibility = View.GONE
        cover.alpha = 0f; bNum.text = ""; bName.text = title; bSub.text = "Starting…"
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

        // channel banner (top left), fades after a few seconds
        banner.orientation = LinearLayout.HORIZONTAL; banner.gravity = Gravity.CENTER_VERTICAL
        banner.setPadding(Kit.dpi(ctx, 14f), Kit.dpi(ctx, 12f), Kit.dpi(ctx, 26f), Kit.dpi(ctx, 12f))
        banner.background = GlassDrawable(banner, Kit.dp(ctx, 20f), Kit.GLASS_STRONG)
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(bName); addView(bSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(ctx, 4f) }) }
        banner.addView(bNum, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Kit.dpi(ctx, 16f) })
        banner.addView(texts)
        addView(banner, LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, Kit.SAFE_Y) })

        error.gravity = Gravity.CENTER; error.text = "This channel isn't working right now"
        error.setPadding(Kit.dpi(ctx, 34f), Kit.dpi(ctx, 20f), Kit.dpi(ctx, 34f), Kit.dpi(ctx, 20f))
        error.background = GlassDrawable(error, Kit.dp(ctx, 20f), Kit.GLASS_STRONG)
        error.visibility = View.GONE
        addView(error, LayoutParams(-2, -2, Gravity.CENTER))

        // channel strip along the bottom
        stripBox.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x00000000))
        stripBox.clipChildren = false
        stripBox.addView(strip.view, LayoutParams(-1, -1).apply { topMargin = Kit.dpi(ctx, 62f) })
        stripBox.visibility = View.GONE
        addView(stripBox, LayoutParams(-1, Kit.dpi(ctx, 250f), Gravity.BOTTOM))
    }

    /** Testing over ADB: a file /sdcard/RetroLauncher/.test-mute silences the player. */
    private fun testMuted() = java.io.File(android.os.Environment.getExternalStorageDirectory(), "RetroLauncher/.test-mute").exists()

    /** Created once at start-up so the first channel doesn't pay for it. */
    fun prepare() {
        if (player != null) return
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3000, 20000, 900, 1800)      // start fast, then build a cushion
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val tracks = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSize(1920, 1080).setMaxVideoFrameRate(60).setExceedRendererCapabilitiesIfNecessary(false))
        }
        val http = DefaultHttpDataSource.Factory().setUserAgent("RetroLauncher/1 (Android TV)")
            .setConnectTimeoutMs(6000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(true)
        // start on a modest stream quality so the first picture comes fast, then step up
        val meter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(1_500_000).build()
        player = ExoPlayer.Builder(context).setLoadControl(load).setTrackSelector(tracks).setBandwidthMeter(meter)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build().apply {
                setVideoSurfaceView(surface)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = fail()
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
        bNum.text = c.number.toString(); bName.text = c.name; bSub.text = "${c.country}  ·  Tuning…"
        banner.animate().cancel(); banner.alpha = 1f
        ui.removeCallbacks(fadeBanner); ui.removeCallbacks(watchdog)
        ui.postDelayed(watchdog, 8000)
        cover.animate().cancel(); cover.alpha = 1f
        tunedAt = SystemClock.uptimeMillis()
        val p = player ?: return
        val item = MediaItem.Builder().setUri(Uri.parse(c.url)).apply { if (c.url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        p.volume = if (testMuted()) 0f else 1f
        p.setMediaItem(item); p.prepare(); p.playWhenReady = true
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
        bSub.text = "${c.country}  ·  Up/Down for other channels  ·  OK for the list"
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

    private fun showStrip() {
        stripOn = true
        strip.setRows(listOf(Row(null, list.map { Item.Ch(it) }, alwaysLabel = false)))
        strip.jumpTo(0, idx)
        stripBox.visibility = View.VISIBLE; stripBox.translationY = Kit.dp(context, 60f); stripBox.alpha = 0f
        stripBox.animate().translationY(0f).alpha(1f).setDuration(Kit.t(260)).setInterpolator(Kit.EASE).start()
        banner.animate().alpha(1f).setDuration(Kit.t(200)).start(); ui.removeCallbacks(fadeBanner)
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
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> current?.let { onStar?.invoke(it) }
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
