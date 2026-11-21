package com.antonretro.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.kit.Kit

/**
 * Home in the "Box classic" style: the layout people know from the box's original menu (a tall tile for what you used
 * last, a wide picture tile, colourful square tiles with their name on a strip along the bottom, and a small row of
 * your apps underneath), drawn in AntonOS's own look with its own pictures and icons. Nothing of the original
 * launcher's artwork is used.
 */
class BoxHome(ctx: Context, private val tiles: () -> List<BoxTile>, private val small: () -> List<BoxTile>) : Screen(ctx) {
    /** [span] 1 = one square, 2 = two squares wide (the picture tile); the first tile is the tall one */
    class BoxTile(val label: String, val color: Int, val icon: Drawable? = null, val picture: Bitmap? = null, val big: Boolean = false, val run: () -> Unit)

    override val view = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val cells = ArrayList<Pair<View, RectF>>()   // each tile and where it sits (in grid units), for moving
    private val runs = ArrayList<() -> Unit>()
    private var sel = 0
    private var focused = false

    // the grid: 4.5 columns wide (the tall tile is 1.25 wide), 2 rows, then the small row
    private val unit = Kit.dp(ctx, 184f)
    private val gap = Kit.dp(ctx, 6f)
    private val left = Kit.dp(ctx, 148f)
    private val top = Kit.dp(ctx, 116f)

    init { rebuild() }
    override fun scrolled() = true

    fun rebuild() {
        view.removeAllViews(); cells.clear(); runs.clear()
        val t = tiles()
        if (t.isEmpty()) return
        // the tall tile, then: one wide picture tile and two squares on top, four squares underneath
        val spots = listOf(RectF(0f, 0f, 1.25f, 2f), RectF(1.25f, 0f, 3.25f, 1f), RectF(3.25f, 0f, 4.25f, 1f), RectF(4.25f, 0f, 5.25f, 1f),
            RectF(1.25f, 1f, 2.25f, 2f), RectF(2.25f, 1f, 3.25f, 2f), RectF(3.25f, 1f, 4.25f, 2f), RectF(4.25f, 1f, 5.25f, 2f))
        for ((i, s) in spots.withIndex()) t.getOrNull(i)?.let { add(it, s, false) }
        // the small row: your apps, then "+" for all apps
        for ((i, s) in small().take(9).withIndex()) add(s, RectF(i * .5f, 2.12f, i * .5f + .5f, 2.62f), true)
        sel = sel.coerceIn(0, (cells.size - 1).coerceAtLeast(0))
        paint()
    }

    private fun add(t: BoxTile, at: RectF, mini: Boolean) {
        val box = FrameLayout(ctx)
        val corner = Kit.dp(ctx, 4f)
        box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.color, darker(t.color))).apply { cornerRadius = corner }
        box.clipToOutline = true
        if (t.picture != null) box.addView(ImageView(ctx).apply { setImageBitmap(t.picture); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        t.icon?.let { d ->
            val s = Kit.dpi(ctx, if (mini) 44f else if (t.big) 120f else 84f)
            box.addView(ImageView(ctx).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER },
                FrameLayout.LayoutParams(s, s, Gravity.CENTER).apply { if (!mini) bottomMargin = Kit.dpi(ctx, 26f) })
        }
        if (!mini) {
            // the name on a strip along the bottom, like the original menu
            val strip = TextView(ctx).apply {
                text = t.label; textSize = 19f; setTextColor(0xFFFFFFFF.toInt()); typeface = Kit.font(ctx, false)
                gravity = Gravity.CENTER_VERTICAL; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Kit.dpi(ctx, 12f), 0, Kit.dpi(ctx, 12f), 0); setBackgroundColor(0x66202A33)
            }
            box.addView(strip, FrameLayout.LayoutParams(-1, Kit.dpi(ctx, 40f), Gravity.BOTTOM))
        }
        val w = (at.width() * unit - gap).toInt(); val h = (at.height() * unit - gap).toInt()
        view.addView(box, FrameLayout.LayoutParams(w, h).apply { leftMargin = (left + at.left * unit).toInt(); topMargin = (top + at.top * unit).toInt() })
        val i = cells.size
        box.setOnClickListener { sel = i; t.run() }
        box.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { sel = i; focused = true; paint(); onHoverFocus?.invoke() }; false }
        cells.add(box to at); runs.add(t.run)
    }

    private fun darker(c: Int) = android.graphics.Color.rgb(android.graphics.Color.red(c) * 6 / 10, android.graphics.Color.green(c) * 6 / 10, android.graphics.Color.blue(c) * 6 / 10)

    private fun paint() {
        for ((i, p) in cells.withIndex()) {
            val v = p.first; val on = focused && i == sel
            // the original's look: a white frame and a lift on the focused tile
            v.foreground = if (on) GradientDrawable().apply { cornerRadius = Kit.dp(ctx, 4f); setStroke(Kit.dpi(ctx, 3f), 0xFFFFFFFF.toInt()) } else null
            v.elevation = if (on) Kit.dp(ctx, 12f) else 0f
            Kit.unclip(v)
            v.animate().scaleX(if (on) 1.06f else 1f).scaleY(if (on) 1.06f else 1f).setDuration(Kit.t(Kit.T_FOCUS)).setInterpolator(Kit.EASE).start()
        }
        onFocusChanged?.invoke()
    }

    /** Moves to the nearest tile in that direction (tiles have different sizes, so it's by position, not by index). */
    override fun move(dRow: Int, dCol: Int): Boolean {
        if (cells.isEmpty()) return dRow >= 0
        val from = cells[sel].second
        val cx = from.centerX(); val cy = from.centerY()
        var best = -1; var bestD = Float.MAX_VALUE
        for ((i, p) in cells.withIndex()) {
            if (i == sel) continue
            val r = p.second
            val ok = when {
                dCol > 0 -> r.left >= from.right - .01f
                dCol < 0 -> r.right <= from.left + .01f
                dRow > 0 -> r.top >= from.bottom - .01f
                else -> r.bottom <= from.top + .01f
            }
            if (!ok) continue
            val dx = r.centerX() - cx; val dy = r.centerY() - cy
            // prefer staying in line: sideways distance counts double
            val d = if (dCol != 0) Math.abs(dx) + 2 * Math.abs(dy) else Math.abs(dy) + 2 * Math.abs(dx)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return !(dRow < 0)   // up from the top row: the tab bar
        sel = best; paint(); return true
    }
    override fun ok() { runs.getOrNull(sel)?.invoke() }
    override fun enter() { focused = true; paint() }
    override fun leave() { focused = false; paint() }
    override fun reset() { sel = 0; paint() }
}

/** Simple outline icons drawn in code (AntonOS's own): tv, radio, play, music, games, apps, settings, plus. */
class GlyphIcon(private val kind: String) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    override fun draw(c: Canvas) {
        val b = bounds; val s = minOf(b.width(), b.height()).toFloat(); val x0 = b.exactCenterX() - s / 2; val y0 = b.exactCenterY() - s / 2
        fun X(v: Float) = x0 + v * s
        fun Y(v: Float) = y0 + v * s
        p.strokeWidth = s * .075f
        when (kind) {
            "tv" -> { c.drawRoundRect(RectF(X(.1f), Y(.22f), X(.9f), Y(.76f)), s * .06f, s * .06f, p); c.drawLine(X(.35f), Y(.9f), X(.65f), Y(.9f), p); c.drawLine(X(.38f), Y(.06f), X(.5f), Y(.2f), p); c.drawLine(X(.62f), Y(.06f), X(.5f), Y(.2f), p) }
            "radio" -> { c.drawRoundRect(RectF(X(.08f), Y(.32f), X(.92f), Y(.86f)), s * .08f, s * .08f, p); c.drawCircle(X(.33f), Y(.59f), s * .14f, p); c.drawLine(X(.58f), Y(.5f), X(.8f), Y(.5f), p); c.drawLine(X(.58f), Y(.68f), X(.8f), Y(.68f), p); c.drawLine(X(.2f), Y(.3f), X(.7f), Y(.1f), p) }
            "play" -> { c.drawRoundRect(RectF(X(.06f), Y(.2f), X(.94f), Y(.8f)), s * .16f, s * .16f, p); val t = Path(); t.moveTo(X(.42f), Y(.36f)); t.lineTo(X(.66f), Y(.5f)); t.lineTo(X(.42f), Y(.64f)); t.close(); c.drawPath(t, f) }
            "music" -> { c.drawCircle(X(.3f), Y(.76f), s * .12f, f); c.drawCircle(X(.74f), Y(.66f), s * .12f, f); c.drawLine(X(.42f), Y(.76f), X(.42f), Y(.18f), p); c.drawLine(X(.86f), Y(.66f), X(.86f), Y(.1f), p); c.drawLine(X(.42f), Y(.2f), X(.86f), Y(.1f), p) }
            "games" -> { c.drawRoundRect(RectF(X(.06f), Y(.3f), X(.94f), Y(.74f)), s * .2f, s * .2f, p); c.drawLine(X(.24f), Y(.52f), X(.42f), Y(.52f), p); c.drawLine(X(.33f), Y(.43f), X(.33f), Y(.61f), p); c.drawCircle(X(.66f), Y(.47f), s * .045f, f); c.drawCircle(X(.76f), Y(.57f), s * .045f, f) }
            "apps" -> for (i in 0..2) for (j in 0..2) c.drawRoundRect(RectF(X(.12f + i * .28f), Y(.12f + j * .28f), X(.32f + i * .28f), Y(.32f + j * .28f)), s * .04f, s * .04f, f)
            "settings" -> {
                c.drawCircle(X(.5f), Y(.5f), s * .2f, p)
                for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); c.drawLine(X(.5f + .3f * Math.cos(a).toFloat()), Y(.5f + .3f * Math.sin(a).toFloat()), X(.5f + .42f * Math.cos(a).toFloat()), Y(.5f + .42f * Math.sin(a).toFloat()), p) }
            }
            "plus" -> { p.strokeWidth = s * .12f; c.drawLine(X(.5f), Y(.2f), X(.5f), Y(.8f), p); c.drawLine(X(.2f), Y(.5f), X(.8f), Y(.5f), p) }
        }
    }
    override fun setAlpha(a: Int) { p.alpha = a; f.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf; f.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
