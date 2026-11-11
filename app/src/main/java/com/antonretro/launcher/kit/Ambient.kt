package com.antonretro.launcher.kit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.*

/**
 * Moving colour wallpapers ("Drift"): soft blobs of colour that wander slowly. Painted on the CPU into
 * a tiny 160x90 picture ten times a second and drawn stretched (smooth and blurry by nature), so the
 * GPU only ever draws one small texture: no cost to card animations on the Mali-450.
 */
object Drift {
    class Palette(val name: String, val base: Int, val blobs: IntArray)
    val PALETTES = listOf(
        Palette("Drift: Ocean", 0xFF03101F.toInt(), intArrayOf(0xFF0077B6.toInt(), 0xFF00B4D8.toInt(), 0xFF3A0CA3.toInt())),
        Palette("Drift: Sunset", 0xFF1A0B1E.toInt(), intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFA94D.toInt(), 0xFF8E2DE2.toInt())),
        Palette("Drift: Aurora", 0xFF020D12.toInt(), intArrayOf(0xFF2EC4B6.toInt(), 0xFF7CF5C9.toInt(), 0xFF5E60CE.toInt())),
        Palette("Drift: Berry", 0xFF14051A.toInt(), intArrayOf(0xFFE5383B.toInt(), 0xFFB5179E.toInt(), 0xFF4361EE.toInt())),
    )
    const val W = 160; const val H = 90

    fun paint(target: Bitmap, p: Palette, t: Float) {
        val c = Canvas(target); c.drawColor(p.base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((i, col) in p.blobs.withIndex()) {
            val ph = i * 2.1f
            val x = W * (.5f + .38f * Math.sin((t * .05f + ph).toDouble()).toFloat())
            val y = H * (.5f + .36f * Math.cos((t * .037f + ph * 1.3f).toDouble()).toFloat())
            val r = W * (.42f + .08f * Math.sin((t * .07f + ph).toDouble()).toFloat())
            paint.shader = RadialGradient(x, y, r, intArrayOf(col and 0x00FFFFFF or 0xD0000000.toInt(), col and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
        }
        // keep the bottom dark so white text always reads
        paint.shader = LinearGradient(0f, H * .5f, 0f, H.toFloat(), 0x00000000, 0x88000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
    }
}

/** A full-screen view that shows a Drift palette moving. start()/stop() so it never runs unseen. */
class DriftView(ctx: Context) : ImageView(ctx) {
    private val bmp = Bitmap.createBitmap(Drift.W, Drift.H, Bitmap.Config.ARGB_8888)
    private val ui = Handler(Looper.getMainLooper())
    var palette: Drift.Palette = Drift.PALETTES[0]
    private var t0 = System.currentTimeMillis()
    private var running = false
    private val tick = object : Runnable { override fun run() { if (!running) return; frame(); ui.postDelayed(this, 100) } }

    init { scaleType = ScaleType.FIT_XY; setImageBitmap(bmp) }
    private fun frame() { Drift.paint(bmp, palette, (System.currentTimeMillis() - t0) / 1000f); invalidate() }
    fun snapshot(): Bitmap { frame(); return bmp.copy(Bitmap.Config.ARGB_8888, false) }
    fun start() { if (running) return; running = true; ui.post(tick) }
    fun stop() { running = false; ui.removeCallbacks(tick) }
}

/**
 * The screensaver: either a slow pan and zoom through the wallpapers (shuffled), or moving colours.
 * The clock and date drift to a new place every minute so nothing burns into the TV. Any key returns.
 */
@SuppressLint("ViewConstructor")
class Screensaver(ctx: Context, private val sceneBitmap: (Int) -> Bitmap) : FrameLayout(ctx) {
    /** where the photo was taken and who took it, small in the corner */
    private val caption = Kit.text(ctx, 15f, false, 0xCCFFFFFF.toInt())
    private fun pictures() = (if (Backdrop.PHOTOS.isNotEmpty()) Backdrop.PHOTOS.indices else Backdrop.SCENES.indices).shuffled().toMutableList()
    private val ui = Handler(Looper.getMainLooper())
    private val a = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val b = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val drift = DriftView(ctx)
    private val clockBox = LinearLayout(ctx)
    private val time = Kit.text(ctx, 72f, true)
    private val date = Kit.text(ctx, 22f, false, 0xE6FFFFFF.toInt())
    private var front = a
    private var order: MutableList<Int> = mutableListOf()
    private var photos = true
    var clock24 = false
    val showing get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE; setBackgroundColor(Color.BLACK)
        addView(drift, -1, -1); addView(a, -1, -1); addView(b, -1, -1)
        clockBox.orientation = LinearLayout.VERTICAL
        Kit.shadowText(time); Kit.shadowText(date)
        clockBox.addView(time); clockBox.addView(date)
        addView(clockBox, LayoutParams(-2, -2))
        Kit.shadowText(caption)
        addView(caption, LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { rightMargin = Kit.dpi(ctx, 48f); bottomMargin = Kit.dpi(ctx, 32f) })
    }

    fun show(usePhotos: Boolean) {
        photos = usePhotos
        visibility = View.VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(1200).start()
        a.visibility = if (photos) View.VISIBLE else View.GONE; b.visibility = a.visibility
        drift.visibility = if (photos) View.GONE else View.VISIBLE
        caption.visibility = if (photos && Backdrop.PHOTOS.isNotEmpty()) View.VISIBLE else View.GONE
        if (photos) { order = pictures(); nextPhoto() } else { drift.palette = Drift.PALETTES.random(); drift.start() }
        tickClock(); moveClock()
    }

    fun hide() {
        ui.removeCallbacksAndMessages(null); drift.stop()
        animate().alpha(0f).setDuration(300).withEndAction { visibility = View.GONE; a.setImageDrawable(null); b.setImageDrawable(null) }.start()
    }

    private fun nextPhoto() {
        if (order.isEmpty()) order = pictures()
        val back = if (front === a) b else a
        val i = order.removeAt(0)
        back.setImageBitmap(sceneBitmap(i))
        Backdrop.PHOTOS.getOrNull(i)?.let { p -> caption.animate().alpha(0f).setDuration(600).withEndAction { caption.text = p.place + "  ·  Photo: " + p.by + " / Unsplash"; caption.animate().alpha(1f).setDuration(1200).start() }.start() }
        back.scaleX = 1.04f; back.scaleY = 1.04f; back.translationX = 0f
        back.animate().alpha(1f).setDuration(2500).start()
        // slow pan and zoom for the whole time it's showing
        back.animate().scaleX(1.18f).scaleY(1.18f).translationX(if (Math.random() < .5) -60f else 60f).setDuration(22000).setInterpolator(null).start()
        front.animate().alpha(0f).setDuration(2500).start()
        front = back
        ui.postDelayed({ nextPhoto() }, 20000)
    }

    private fun tickClock() {
        val now = Date()
        time.text = SimpleDateFormat(if (clock24) "H:mm" else "h:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
        ui.postDelayed({ tickClock() }, 10000)
    }

    private fun moveClock() {
        val w = width.coerceAtLeast(1280); val h = height.coerceAtLeast(720)
        val x = Kit.dp(context, 60f) + Math.random().toFloat() * (w - Kit.dp(context, 520f))
        val y = Kit.dp(context, 60f) + Math.random().toFloat() * (h - Kit.dp(context, 220f))
        clockBox.animate().alpha(0f).setDuration(800).withEndAction {
            clockBox.translationX = x; clockBox.translationY = y
            clockBox.animate().alpha(1f).setDuration(1200).start()
        }.start()
        ui.postDelayed({ moveClock() }, 60000)
    }
}
