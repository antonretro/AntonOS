package com.antonretro.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.data.Item
import com.antonretro.launcher.kit.*

/** One tab's content. Built once at start-up and kept in memory, so tab switches never load. */
abstract class Screen(val ctx: Context) {
    abstract val view: View
    /** Moves focus; returns false when the move leaves the top edge (focus goes to the tab bar). */
    abstract fun move(dRow: Int, dCol: Int): Boolean
    abstract fun ok()
    open fun longOk() {}
    /** Back inside the screen; returns false if there was nothing to go back to. */
    open fun back(): Boolean = false
    abstract fun enter()     // focus comes in from the tab bar
    abstract fun leave()     // focus goes up to the tab bar
    open fun reset() {}      // Home pressed: first row, first card
    open val usesShelf = false
    open fun shelfItem(): Item? = null
    /** true when the screen wants the dimmed, blurred backdrop (grid views, lists). */
    open fun scrolled(): Boolean = false
    var onFocusChanged: (() -> Unit)? = null
    var onHoverFocus: (() -> Unit)? = null
}

class Row(val title: String?, val items: List<Item>, val glass: Boolean = false, val alwaysLabel: Boolean = false, val poster: Boolean = false,
          /** card width / height for this row (box art shapes); null = the standard card */ val aspect: Float? = null)

/**
 * Rows of cards. Rules from the plan: rows are never faded or shrunk, scrolling moves one
 * row at a time and keeps the row above the focused one on screen, focus is remembered.
 */
@SuppressLint("ViewConstructor")
class RowsScreen(ctx: Context, private val startTop: Float, override val usesShelf: Boolean,
                 private val artFor: (Item, CardCell) -> Unit, private val open: (Item, CardCell) -> Unit,
                 private val emptyText: String? = null) : Screen(ctx) {

    private val root = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val content = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    override val view: View get() = root
    private var rows: List<Row> = emptyList()
    private val rowViews = ArrayList<FrameLayout>()
    private val tracks = ArrayList<LinearLayout>()
    private val cells = ArrayList<List<CardCell>>()     // a small pool per row, re-bound as you scroll
    private val windows = ArrayList<Int>()              // first item shown by each row's pool
    private val steps = ArrayList<Float>()
    private val tops = ArrayList<Float>()
    private val glassViews = ArrayList<View>()
    private var fr = 0; private var fc = 0; private var active = false
    private var scrollY = 0f; private var scrollAnim: android.animation.ValueAnimator? = null
    private val empty: TextView? = emptyText?.let { Kit.text(ctx, Kit.TITLE_TEXT, false, Kit.TEXT_DIM).apply { isSingleLine = false; text = it } }

    init {
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        // rows scroll out of sight under the tab bar instead of sliding behind it
        root.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> v.clipBounds = android.graphics.Rect(0, Kit.dpi(ctx, 92f), r - l, b - t) }
        empty?.let { root.addView(it, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, startTop) }) }
    }

    fun setRows(newRows: List<Row>) {
        val keepKey = rows.getOrNull(fr)?.items?.getOrNull(fc)?.key
        rows = newRows.filter { it.items.isNotEmpty() }
        content.removeAllViews(); glassViews.clear(); rowViews.clear(); tracks.clear(); cells.clear(); tops.clear(); windows.clear(); steps.clear()
        var top = Kit.dp(ctx, startTop)
        for ((ri, row) in rows.withIndex()) {
            val h = if (row.poster) Kit.POSTER_H else Kit.CARD_H
            val w = row.aspect?.let { h * it } ?: if (row.poster) Kit.POSTER_W else Kit.CARD_W   // box art keeps its real shape
            val rv = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            if (row.glass) {
                val g = View(ctx); g.background = GlassDrawable(g, Kit.dp(ctx, 27f)); glassViews.add(g)
                rv.addView(g, FrameLayout.LayoutParams(-1, Kit.dpi(ctx, h + 74f)).apply { leftMargin = Kit.dpi(ctx, 29f); rightMargin = Kit.dpi(ctx, 29f); topMargin = -Kit.dpi(ctx, 23f) })
            }
            row.title?.let {
                val t = Kit.text(ctx, Kit.TITLE_TEXT, true).apply { text = it; alpha = .9f }
                Kit.shadowText(t)
                rv.addView(t, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = -Kit.dpi(ctx, 37f) })
            }
            val track = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
            val list = ArrayList<CardCell>()
            val step = Kit.dp(ctx, w + Kit.GAP)
            val visible = ((Kit.dp(ctx, 1280f - 2 * Kit.SAFE_X) + Kit.dp(ctx, Kit.GAP)) / step).toInt().coerceAtLeast(1)
            val poolSize = minOf(row.items.size, visible + 1)
            for (pi in 0 until poolSize) {
                val cell = CardCell(ctx, w, h, row.alwaysLabel)
                if (layers) cell.card.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                cell.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { focusAt(ri, windows[ri] + pi, mouse = true); onHoverFocus?.invoke() }; false }
                cell.setOnClickListener { focusAt(ri, windows[ri] + pi); ok() }
                cell.setOnLongClickListener { focusAt(ri, windows[ri] + pi); longOk(); true }
                track.addView(cell, LinearLayout.LayoutParams(Kit.dpi(ctx, w), Kit.dpi(ctx, h)).apply { if (pi > 0) leftMargin = Kit.dpi(ctx, Kit.GAP) })
                list.add(cell)
            }
            windows.add(0); steps.add(step)
            rv.addView(track, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X) })
            content.addView(rv, FrameLayout.LayoutParams(-1, -2).apply { topMargin = top.toInt() })
            rowViews.add(rv); tracks.add(track); cells.add(list); tops.add(top)
            bind(ri)
            val labelSpace = if (row.alwaysLabel) 70f else 50f
            top += Kit.dp(ctx, h + labelSpace + (if (rows.getOrNull(ri + 1)?.title != null) 37f else 20f) + if (row.glass) 18f else 0f)
        }
        empty?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        // keep focus on the same item if it still exists
        var found = false
        if (keepKey != null) for ((ri, r) in rows.withIndex()) { val ci = r.items.indexOfFirst { it.key == keepKey }; if (ci >= 0) { fr = ri; fc = ci; found = true; break } }
        if (!found) { fr = fr.coerceIn(0, (rows.size - 1).coerceAtLeast(0)); fc = fc.coerceIn(0, ((rows.getOrNull(fr)?.items?.size ?: 1) - 1).coerceAtLeast(0)) }
        applyFocus(false)
    }

    private fun bind(ri: Int) {
        val items = rows[ri].items; val ws = windows[ri]
        for ((pi, cell) in cells[ri].withIndex()) {
            val item = items.getOrNull(ws + pi)
            cell.visibility = if (item == null) View.INVISIBLE else View.VISIBLE
            if (item == null || cell.tag == item.key) continue
            cell.tag = item.key; cell.label.text = item.title; artFor(item, cell)
        }
    }

    /** Re-labels every card in place (e.g. a star was added). */
    fun refreshLabels() { for (ri in rows.indices) { cells[ri].forEach { it.tag = null }; bind(ri) } }

    fun jumpTo(r: Int, c: Int) { active = true; fr = r.coerceIn(0, (rows.size - 1).coerceAtLeast(0)); fc = c; applyFocus(false) }

    /** Mouse: hovering only highlights the card; the page never moves under the pointer (the wheel scrolls). */
    private fun focusAt(r: Int, c: Int, mouse: Boolean = false) { if (!active) { active = true }; fr = r; fc = c; applyFocus(true, mouse) }

    private fun applyFocus(animate: Boolean, mouse: Boolean = false) {
        // horizontal: the pool slides one card at a time and is re-bound, so a row can hold thousands
        for ((ri, tr) in tracks.withIndex()) {
            val list = cells[ri]; if (list.isEmpty()) continue
            val visible = (list.size - 1).coerceAtLeast(1).let { if (list.size >= rows[ri].items.size) list.size else it }
            val c = if (ri == fr) fc else 0
            val want = (c - (visible - 1)).coerceIn(0, (rows[ri].items.size - visible).coerceAtLeast(0))
            val ws = windows[ri]
            if (want != ws && !mouse) {
                windows[ri] = want; bind(ri)
                for (cell in list) cell.setFocus(false, false)
                if (animate && Math.abs(want - ws) <= 2) { tr.translationX = (want - ws) * steps[ri]; tr.animate().translationX(0f).setDuration(Kit.t(260)).setInterpolator(Kit.EASE).start() }
                else tr.translationX = 0f
            }
        }
        for ((ri, list) in cells.withIndex()) for ((pi, cell) in list.withIndex()) cell.setFocus(active && ri == fr && windows[ri] + pi == fc, animate)
        for ((ri, rv) in rowViews.withIndex()) rv.translationZ = if (ri == fr) 1f else 0f
        // vertical: keep the row above the focused one visible
        val target = if (fr <= 0 || !active) 0f else {
            val firstVisible = tops[0]
            if (usesShelf) tops[fr - 1] - Kit.dp(ctx, if (rows[fr - 1].title != null) 193f else 167f)
            else if (fr >= 2) tops[fr - 1] - firstVisible else 0f
        }
        if (target != scrollY && !mouse) {
            scrollAnim?.cancel()
            val from = content.translationY
            scrollY = target
            if (animate) scrollAnim = animateFloat(from, -target, Kit.T_SCROLL) { content.translationY = it; glassViews.forEach(View::invalidate) }
            else content.translationY = -target
        }
        onFocusChanged?.invoke()
    }

    override fun move(dRow: Int, dCol: Int): Boolean {
        if (rows.isEmpty()) return dRow >= 0
        if (dRow != 0) {
            val nr = fr + dRow
            if (nr < 0) return false
            if (nr < rows.size) { fr = nr; fc = fc.coerceAtMost(rows[nr].items.size - 1) }
        }
        if (dCol != 0) { val n = fc + dCol; if (n >= 0 && n < rows[fr].items.size) fc = n }
        applyFocus(true); return true
    }

    fun currentCell(): CardCell? = cells.getOrNull(fr)?.getOrNull(fc - (windows.getOrNull(fr) ?: 0))
    fun currentItem(): Item? = rows.getOrNull(fr)?.items?.getOrNull(fc)
    override fun shelfItem(): Item? = if (!active || fr == 0) rows.getOrNull(0)?.items?.getOrNull(fc) else null
    override fun scrolled() = !usesShelf || (active && fr > 0)
    override fun ok() { val c = currentCell() ?: return; val item = rows[fr].items[fc]; c.press { open(item, c) } }
    override fun back(): Boolean { if (fr > 0) { fr = 0; applyFocus(true); return true }; return false }
    override fun enter() { active = true; if (rows.isNotEmpty()) applyFocus(true) }
    override fun leave() { active = false; applyFocus(true) }
    override fun reset() { fr = 0; fc = 0; applyFocus(true) }
    var onLong: ((Item, CardCell) -> Unit)? = null
    override fun longOk() { val c = currentCell() ?: return; val i = currentItem() ?: return; onLong?.invoke(i, c) }
    fun hasItems() = rows.isNotEmpty()
    fun currentRowItems(): List<Item>? = rows.getOrNull(fr)?.items

    /** Cards on the tab being shown keep a graphics layer (drawn once, then only moved); the other tabs
     *  give theirs back, so graphics memory stays small and moving stays cheap. */
    var layers = false
        set(on) { field = on; for (list in cells) for (c in list) c.card.setLayerType(if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null) }
    fun setEmptyText(t: String) { empty?.text = t }
    /** For the start-up intro: the views that rise into place, in order. */
    fun introViews(): List<View> = (cells.getOrNull(0) ?: emptyList<View>()) + rowViews.drop(1)
    /** Wheel / page scrolling for mice. */
    fun scrollBy(dRow: Int) = move(dRow, 0)
}

/** A plain vertical list (Settings, for now): big rows, focus = white fill. */
class ListScreen(ctx: Context, private val title: String, private val entries: List<Item.Action>) : Screen(ctx) {
    private val root = FrameLayout(ctx)
    override val view: View get() = root
    private val rowViews = ArrayList<TextView>()
    private var idx = 0; private var active = false
    private val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val bgOn = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(ctx, 14f); setColor(Kit.FOCUS_FILL) }
    private val bgOff = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(ctx, 14f); setColor(0x0DFFFFFF) }

    init {
        val h = Kit.text(ctx, 32f, true).apply { text = title }
        root.addView(h, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, 118f) })
        val box = FrameLayout(ctx)
        box.background = GlassDrawable(box, Kit.dp(ctx, 24f))
        box.setPadding(Kit.dpi(ctx, 16f), Kit.dpi(ctx, 16f), Kit.dpi(ctx, 16f), Kit.dpi(ctx, 16f))
        box.addView(panel)
        root.addView(box, FrameLayout.LayoutParams(Kit.dpi(ctx, 760f), -2).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, 170f) })
        for ((i, e) in entries.withIndex()) {
            val tv = Kit.text(ctx, Kit.BODY_TEXT, true)
            tv.text = if (e.subtitle.isNotEmpty()) "${e.title}    ·    ${e.subtitle}" else e.title
            tv.setPadding(Kit.dpi(ctx, 20f), 0, Kit.dpi(ctx, 20f), 0)
            tv.gravity = android.view.Gravity.CENTER_VERTICAL
            tv.setOnHoverListener { _, ev -> if (ev.action == MotionEvent.ACTION_HOVER_ENTER) { idx = i; active = true; draw(); onHoverFocus?.invoke() }; false }
            tv.setOnClickListener { idx = i; ok() }
            panel.addView(tv, LinearLayout.LayoutParams(-1, Kit.dpi(ctx, 58f)).apply { bottomMargin = Kit.dpi(ctx, 5f) })
            rowViews.add(tv)
        }
        draw()
    }

    private fun draw() {
        for ((i, tv) in rowViews.withIndex()) {
            val on = active && i == idx
            val want = if (on) bgOn.constantState!!.newDrawable() else bgOff.constantState!!.newDrawable()
            if ((tv.tag as? Boolean) != on) { tv.background = want; tv.tag = on }
            tv.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
            tv.animate().scaleX(if (on) 1.03f else 1f).scaleY(if (on) 1.03f else 1f).setDuration(Kit.t(Kit.T_FOCUS)).setInterpolator(Kit.EASE).start()
        }
        onFocusChanged?.invoke()
    }
    override fun move(dRow: Int, dCol: Int): Boolean { val n = idx + dRow; if (n < 0) return false; if (n < entries.size) idx = n; draw(); return true }
    override fun ok() { entries[idx].run(ctx) }
    override fun enter() { active = true; draw() }
    override fun leave() { active = false; draw() }
    override fun reset() { idx = 0; draw() }
    override fun scrolled() = true
}
