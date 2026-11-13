package com.antonretro.launcher.ui

import android.content.Context
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

/** One big tile: a label, a short line under it, two colours for its gradient, an optional icon, what OK does. */
class Tile(val label: String, val sub: String, val from: Int, val to: Int, val icon: Drawable? = null, val run: () -> Unit)

/**
 * The Home tab in the Classic grid and Simple styles (PLAN.md "Home styles"): same content, another arrangement.
 * Classic grid: large colourful tiles for the main places, in two rows, with the favourite apps small along the
 * bottom (a nod to the box's original menu, drawn in RetroOS's own style). Simple: only the favourites, as a few
 * extra-large tiles with extra-large text; nothing scrolls.
 */
class TileScreen(ctx: Context, private val simple: Boolean, private val places: () -> List<Tile>, private val favourites: () -> List<Tile>) : Screen(ctx) {
    override val view = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val rows = ArrayList<List<View>>()
    private val acts = ArrayList<List<() -> Unit>>()
    private var r = 0; private var c = 0
    private var focused = false

    init {
        view.addView(col, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = Kit.dpi(ctx, Kit.SAFE_X); rightMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, 104f)
        })
        rebuild()
    }

    override fun scrolled() = true

    /** Build (or rebuild, when the dock changes) the tiles. */
    fun rebuild() {
        col.removeAllViews(); rows.clear(); acts.clear()
        if (simple) {
            val favs = favourites().take(6)
            if (favs.isEmpty()) addRow(listOf(Tile("Add favourites", "Hold OK on any app, channel or game and choose Add to Home", 0xFF3A4A6A.toInt(), 0xFF1A2236.toInt()) {}), 230f, 34f)
            else { addRow(favs.take(3), 230f, 34f); if (favs.size > 3) addRow(favs.drop(3), 230f, 34f) }
        } else {
            val p = places()
            addRow(p.take(4), 170f, 30f)
            addRow(p.drop(4).take(4), 170f, 30f)
            val favs = favourites().take(6)
            if (favs.isNotEmpty()) addRow(favs, 92f, 18f, small = true)
        }
        r = r.coerceIn(0, (rows.size - 1).coerceAtLeast(0)); c = c.coerceIn(0, (rows.getOrNull(r)?.size ?: 1) - 1)
        paint()
    }

    private fun addRow(tiles: List<Tile>, hDp: Float, textSp: Float, small: Boolean = false) {
        if (tiles.isEmpty()) return
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val views = ArrayList<View>(); val runs = ArrayList<() -> Unit>()
        for ((i, t) in tiles.withIndex()) {
            val box = FrameLayout(ctx)
            box.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.from, t.to)).apply { cornerRadius = Kit.dp(ctx, Kit.R_M) }
            val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = if (small) Gravity.CENTER else Gravity.BOTTOM or Gravity.START }
            t.icon?.let { d ->
                inner.addView(ImageView(ctx).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(Kit.dpi(ctx, if (small) 40f else 64f), Kit.dpi(ctx, if (small) 40f else 64f)).apply { bottomMargin = Kit.dpi(ctx, 6f); gravity = if (small) Gravity.CENTER_HORIZONTAL else Gravity.START })
            }
            inner.addView(Kit.text(ctx, textSp, true).apply { text = t.label; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; if (small) gravity = Gravity.CENTER })
            if (!small && t.sub.isNotEmpty()) inner.addView(Kit.text(ctx, textSp * .55f, false, 0xD9FFFFFF.toInt()).apply { text = t.sub; isSingleLine = !simple; maxLines = 2 })
            val pad = Kit.dpi(ctx, if (small) 8f else 20f)
            inner.setPadding(pad, pad, pad, pad)
            box.addView(inner, FrameLayout.LayoutParams(-1, -1))
            box.setOnClickListener { r = rows.indexOf(views).coerceAtLeast(rows.size); c = i; t.run() }
            box.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { r = rows.size.coerceAtMost(rows.indexOfFirst { it === views }.let { if (it < 0) rows.size else it }); c = i; focused = true; paint(); onHoverFocus?.invoke() }; false }
            line.addView(box, LinearLayout.LayoutParams(0, Kit.dpi(ctx, hDp), 1f).apply { if (i > 0) leftMargin = Kit.dpi(ctx, Kit.GAP * .7f) })
            views.add(box); runs.add(t.run)
        }
        col.addView(line, LinearLayout.LayoutParams(-1, -2).apply { if (rows.isNotEmpty()) topMargin = Kit.dpi(ctx, if (small) 30f else 22f) })
        rows.add(views); acts.add(runs)
    }

    private fun paint() {
        for ((ri, row) in rows.withIndex()) for ((ci, v) in row.withIndex()) {
            val on = focused && ri == r && ci == c
            v.foreground = if (on) GradientDrawable().apply { cornerRadius = Kit.dp(ctx, Kit.R_M); setStroke(Kit.dpi(ctx, 4f), Kit.FOCUS_FILL) } else null
            Kit.unclip(v)
            v.animate().scaleX(if (on) 1.05f else 1f).scaleY(if (on) 1.05f else 1f).setDuration(Kit.t(Kit.T_FOCUS)).setInterpolator(Kit.EASE).start()
        }
        onFocusChanged?.invoke()
    }

    override fun move(dRow: Int, dCol: Int): Boolean {
        if (rows.isEmpty()) return dRow >= 0
        if (dRow < 0 && r == 0) return false
        if (dRow != 0) {
            val nr = (r + dRow).coerceIn(0, rows.size - 1)
            if (nr == r) return true
            // keep roughly the same horizontal place between rows of different lengths
            val frac = (c + .5f) / rows[r].size
            r = nr; c = (frac * rows[r].size).toInt().coerceIn(0, rows[r].size - 1)
        } else c = (c + dCol).coerceIn(0, rows[r].size - 1)
        paint(); return true
    }
    override fun ok() { acts.getOrNull(r)?.getOrNull(c)?.invoke() }
    override fun enter() { focused = true; paint() }
    override fun leave() { focused = false; paint() }
    override fun reset() { r = 0; c = 0; paint() }
}
