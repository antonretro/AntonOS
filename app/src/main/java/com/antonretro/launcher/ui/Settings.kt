package com.antonretro.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit

/** One line in a settings page. value() is read every time the page is drawn, so pages stay live. */
class SRow(val title: String, val sub: () -> String? = { null }, val value: () -> String? = { null },
           val toggle: (() -> Boolean)? = null, val onClick: (() -> Unit)? = null)

class SPage(val icon: String, val title: String, val rows: () -> List<SRow>)

/**
 * The one Settings menu (replaces the box's three): categories on the left, the page on the right,
 * same glass, rounded rows and white focus as the rest of Retro Launcher. Left/Right moves between
 * the two sides; Back goes from the page to the categories, then to the tab bar.
 */
@SuppressLint("ViewConstructor")
class SettingsScreen(ctx: Context, private val pages: List<SPage>) : Screen(ctx) {
    private companion object { const val RAIL_GAP = 12f }
    private val root = FrameLayout(ctx)
    override val view: View get() = root
    private val catBox = LinearLayout(ctx)
    private val catViews = ArrayList<TextView>()
    private val pageTitle = Kit.text(ctx, 30f, true)
    private val pageClip = FrameLayout(ctx)
    private val pageList = LinearLayout(ctx)
    private val rowViews = ArrayList<RowView>()
    private var rows: List<SRow> = emptyList()
    private var cat = 0; private var row = 0
    private var inPage = false; private var active = false

    init {
        val catPanel = FrameLayout(ctx)
        catPanel.background = GlassDrawable(catPanel, Kit.dp(ctx, Kit.R_L))
        catBox.orientation = LinearLayout.VERTICAL
        catBox.setPadding(Kit.dpi(ctx, RAIL_GAP), Kit.dpi(ctx, RAIL_GAP), Kit.dpi(ctx, RAIL_GAP), Kit.dpi(ctx, RAIL_GAP))
        catPanel.addView(catBox)
        root.addView(catPanel, FrameLayout.LayoutParams(Kit.dpi(ctx, 330f), -2).apply { leftMargin = Kit.dpi(ctx, 41f); topMargin = Kit.dpi(ctx, 112f) })
        for ((i, p) in pages.withIndex()) {
            val tv = Kit.text(ctx, 18f, true).apply { text = p.title; gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(ctx, 18f), 0, Kit.dpi(ctx, 12f), 0) }
            tv.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { active = true; inPage = false; cat = i; showPage(); paint(); onHoverFocus?.invoke() }; false }
            tv.setOnClickListener { active = true; cat = i; inPage = false; showPage(); paint() }
            catBox.addView(tv, LinearLayout.LayoutParams(-1, Kit.dpi(ctx, 44f)).apply { bottomMargin = Kit.dpi(ctx, 2f) })   // eleven pages fit on screen
            catViews.add(tv)
        }
        root.addView(pageTitle, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, 410f); topMargin = Kit.dpi(ctx, 118f) })
        pageList.orientation = LinearLayout.VERTICAL
        // room around the rows so a focused row can grow without its rounded corners being cut off
        pageList.setPadding(Kit.dpi(ctx, 14f), Kit.dpi(ctx, 6f), Kit.dpi(ctx, 14f), Kit.dpi(ctx, 6f))
        pageList.clipToPadding = false; pageList.clipChildren = false   // the padding is there to be drawn into
        pageClip.addView(pageList, FrameLayout.LayoutParams(-1, -2))
        root.addView(pageClip, FrameLayout.LayoutParams(Kit.dpi(ctx, 848f), Kit.dpi(ctx, 530f)).apply { leftMargin = Kit.dpi(ctx, 386f); topMargin = Kit.dpi(ctx, 164f) })
        pageClip.clipChildren = true
        showPage(); paint()
    }

    /** Re-reads the current page (after a value changed). */
    fun refresh() { val keep = row; showPage(); row = keep.coerceIn(0, (rows.size - 1).coerceAtLeast(0)); paint() }

    private fun showPage() {
        val p = pages[cat]
        pageTitle.text = p.title
        rows = p.rows(); row = 0
        pageList.removeAllViews(); rowViews.clear(); pageList.translationY = 0f
        for ((i, r) in rows.withIndex()) {
            val v = RowView(ctx, r)
            v.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { active = true; inPage = true; row = i; paint(); onHoverFocus?.invoke() }; false }
            v.setOnClickListener { active = true; inPage = true; row = i; paint(); ok() }
            pageList.addView(v, LinearLayout.LayoutParams(-1, Kit.dpi(ctx, if (r.sub() != null) 72f else 58f)).apply { bottomMargin = Kit.dpi(ctx, 7f) })
            rowViews.add(v)
        }
    }

    private fun paint() {
        for ((i, tv) in catViews.withIndex()) {
            val sel = i == cat; val foc = active && !inPage && sel
            // concentric with the rail panel and never larger than its inset: no growing into the panel's edge
            tv.background = if (foc || sel) GradientDrawable().apply { cornerRadius = Kit.dp(ctx, Kit.inner(Kit.R_L, RAIL_GAP)); setColor(if (foc) Kit.FOCUS_FILL else 0x24FFFFFF) } else null
            tv.setTextColor(if (foc) Kit.FOCUS_TEXT else if (sel) Kit.TEXT else 0xCCFFFFFF.toInt())
        }
        for ((i, v) in rowViews.withIndex()) v.setFocus(active && inPage && i == row)
        // keep the focused row on screen
        val v = rowViews.getOrNull(row)
        if (v != null && inPage) pageList.post {
            val clipH = pageClip.height.toFloat(); val bottom = v.bottom.toFloat(); val top = v.top.toFloat()
            val ty = when { bottom + pageList.translationY > clipH -> clipH - bottom; top + pageList.translationY < 0 -> -top; else -> pageList.translationY }
            pageList.animate().translationY(ty).setDuration(Kit.t(Kit.T_SCROLL)).setInterpolator(Kit.EASE).start()
        }
        onFocusChanged?.invoke()
    }

    override fun move(dRow: Int, dCol: Int): Boolean {
        if (!inPage) {
            if (dRow < 0 && cat == 0) return false
            if (dRow != 0) { cat = (cat + dRow).coerceIn(0, pages.size - 1); showPage() }
            if (dCol > 0 && rows.isNotEmpty()) { inPage = true; row = 0 }
        } else {
            if (dCol < 0) inPage = false
            if (dRow < 0 && row == 0) { inPage = false; paint(); return false }   // up from the top row: tab bar
            if (dRow != 0) row = (row + dRow).coerceIn(0, rows.size - 1)
        }
        paint(); return true
    }

    override fun ok() {
        if (!inPage) { if (rows.isNotEmpty()) { inPage = true; row = 0; paint() }; return }
        val r = rows.getOrNull(row) ?: return
        rowViews.getOrNull(row)?.let { v -> v.animate().scaleX(.98f).scaleY(.98f).setDuration(Kit.t(80)).withEndAction { v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(Kit.t(80)).start() }.start() }
        r.onClick?.invoke()
        refresh()
    }

    override fun back(): Boolean { if (inPage) { inPage = false; paint(); return true }; return false }
    override fun enter() { active = true; paint() }
    override fun leave() { active = false; inPage = false; paint() }
    override fun reset() { cat = 0; inPage = false; showPage(); paint() }
    override fun scrolled() = true
    fun open(title: String) { val i = pages.indexOfFirst { it.title == title }; if (i >= 0) { cat = i; inPage = false; showPage(); paint() } }

    /** A rounded row: title, optional small line under it, and a value or an On/Off switch on the right. */
    @SuppressLint("ViewConstructor")
    class RowView(ctx: Context, private val r: SRow) : LinearLayout(ctx) {
        private val title = Kit.text(ctx, 18f, true)
        private val sub = Kit.text(ctx, 14f, false, Kit.TEXT_DIM)
        private val value = Kit.text(ctx, 16f, false, Kit.TEXT_DIM)
        private val sw = Switch(ctx)
        private var on = false
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG); private val bgRect = RectF()
        private val radius = Kit.dp(ctx, Kit.R_M)   // every settings row shares one radius

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setWillNotDraw(false)   // the rounded row is drawn here (a background drawable came out square on this box)
            setPadding(Kit.dpi(ctx, 20f), 0, Kit.dpi(ctx, 18f), 0)
            val texts = LinearLayout(ctx).apply { orientation = VERTICAL; addView(title); addView(sub, LayoutParams(-2, -2).apply { topMargin = Kit.dpi(ctx, 3f) }) }
            addView(texts, LayoutParams(0, -2, 1f))
            addView(value, LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, 12f) })
            addView(sw, LayoutParams(Kit.dpi(ctx, 52f), Kit.dpi(ctx, 30f)).apply { leftMargin = Kit.dpi(ctx, 12f) })
            update()
        }

        fun update() {
            title.text = r.title
            val s = r.sub(); sub.text = s ?: ""; sub.visibility = if (s == null) View.GONE else View.VISIBLE
            val v = r.value(); value.text = v ?: (if (r.onClick != null && r.toggle == null) "›" else ""); value.visibility = if (r.toggle != null) View.GONE else View.VISIBLE
            sw.visibility = if (r.toggle != null) View.VISIBLE else View.GONE
            sw.on = r.toggle?.invoke() == true; sw.invalidate()
            paintBg()
        }

        override fun onDraw(c: Canvas) { bgRect.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(bgRect, radius, radius, bgPaint) }

        private var painted = false
        fun setFocus(f: Boolean) {
            if (f == on && painted) return
            painted = true
            on = f; update()
            Kit.unclip(this); animate().scaleX(if (f) 1.02f else 1f).scaleY(if (f) 1.02f else 1f).setDuration(Kit.t(Kit.T_FOCUS)).setInterpolator(Kit.EASE).start()
        }

        private fun paintBg() {
            bgPaint.color = if (on) Kit.FOCUS_FILL else 0x14FFFFFF; invalidate()
            title.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
            sub.setTextColor(if (on) 0xFF555555.toInt() else Kit.TEXT_DIM)
            value.setTextColor(if (on) 0xFF555555.toInt() else Kit.TEXT_DIM)
            sw.dark = on
        }
    }

    /** The On/Off switch: green when on. */
    class Switch(ctx: Context) : View(ctx) {
        var on = false; var dark = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val r = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); r.set(0f, 0f, w, h)
            p.color = if (on) 0xFF34C759.toInt() else if (dark) 0xFFBBBBBB.toInt() else 0x55FFFFFF
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.color = 0xFFFFFFFF.toInt()
            val k = h / 2 - 3; c.drawCircle(if (on) w - h / 2 else h / 2, h / 2, k, p)
        }
    }
}
