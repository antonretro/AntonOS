package com.antonretro.launcher.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.antonretro.launcher.data.Channel
import com.antonretro.launcher.data.Guide
import com.antonretro.launcher.data.Prog
import com.antonretro.launcher.kit.Backdrop
import com.antonretro.launcher.kit.Kit
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The TV Guide: channels down the side, time across, programmes as rounded blocks, the current time as a
 * line. One custom view draws only what is on screen, so scrolling 2,000 channels costs the same as 10.
 * Programme data is read from Guide (SQLite) a few rows at a time, off the UI thread.
 */
@SuppressLint("ViewConstructor")
class GuideView(ctx: Context, private val numberOf: (Channel) -> Int, private val clock24: () -> Boolean) : FrameLayout(ctx) {
    var onWatch: ((List<Channel>, Int) -> Unit)? = null
    var onLater: ((Channel, Prog) -> Unit)? = null
    /** true once the guide fully covers the screen: Home hides what is underneath so it isn't drawn every frame. */
    var onCover: ((Boolean) -> Unit)? = null
    val showing get() = visibility == View.VISIBLE

    /** One chip in the filter bar: a label and which channels it keeps. */
    class Filter(val label: String, val keep: (Channel) -> Boolean)
    /** the chips after Search: All, Favourites, Recent, countries… (set by the launcher before [show]) */
    var filters: List<Filter> = emptyList()
    /** a channel that failed to play recently ("Working only" hides those) */
    var isBroken: (Channel) -> Boolean = { false }
    var workingOnly = true
    var onWorkingOnly: ((Boolean) -> Unit)? = null
    /** Search chip: the launcher opens its keyboard and calls [search] with the words */
    var onSearch: (() -> Unit)? = null
    /** the "+ Add a country" chip at the end of the filter bar (Settings > TV & Radio > Countries) */
    var onAddCountry: (() -> Unit)? = null
    /** "Cards" chip: back to the card view of the TV tab (remembered by the launcher) */
    var onCards: (() -> Unit)? = null
    /** the Guide closed (any way) */
    var onHidden: (() -> Unit)? = null
    /** The channel you were watching plays small in the top-right corner: the hint there steps aside. */
    /** "Watching now" in the top-right corner: the channel still playing behind the Guide (Back returns to it).
     *  Live video there was tried: on this box the video layer can't show through the Guide. */
    private val watchCard = Kit.text(ctx, 17f, true).apply {
        isSingleLine = false; maxLines = 3
        setPadding(Kit.dpi(ctx, 18f), Kit.dpi(ctx, 12f), Kit.dpi(ctx, 18f), Kit.dpi(ctx, 12f))
        visibility = View.GONE
    }
    fun preview(c: Channel?, number: Int, now: String?) {
        hint.visibility = if (c != null) View.GONE else View.VISIBLE
        if (c == null) { watchCard.visibility = View.GONE; return }
        if (watchCard.parent == null) {
            watchCard.background = com.antonretro.launcher.kit.GlassDrawable(watchCard, Kit.dp(context, Kit.R_M), Kit.GLASS_STRONG)
            addView(watchCard, LayoutParams(Kit.dpi(context, 330f), -2, android.view.Gravity.END).apply { rightMargin = Kit.dpi(context, Kit.SAFE_X); topMargin = Kit.dpi(context, 28f) })
        }
        val nl = "\n"
        watchCard.text = android.text.SpannableStringBuilder("▶  Watching now" + nl).apply {
            setSpan(android.text.style.ForegroundColorSpan(Kit.TEXT_DIM), 0, length, 0); setSpan(android.text.style.RelativeSizeSpan(.8f), 0, length, 0)
            append("$number  ${c.name}")
            if (!now.isNullOrBlank()) { append(nl); val st = length; append(now); setSpan(android.text.style.ForegroundColorSpan(Kit.TEXT_DIM), st, length, 0); setSpan(android.text.style.RelativeSizeSpan(.85f), st, length, 0) }
        }
        watchCard.visibility = View.VISIBLE; watchCard.bringToFront()
    }
    /** Up from the filter bar: back to the tab bar */
    var onTop: (() -> Unit)? = null

    // before the views below: Grid's paints are sized with dp() when it is created
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val title = Kit.text(ctx, 34f, true)
    private val meta = Kit.text(ctx, 19f, true, Kit.TEXT_DIM)
    private val desc = Kit.text(ctx, Kit.BODY_TEXT, false, 0xD9FFFFFF.toInt()).apply { isSingleLine = false; maxLines = 2 }
    private val hint = Kit.text(ctx, 16f, false, Kit.TEXT_DIM)
    private val grid = Grid(ctx)
    private val bg = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var bgFor: android.graphics.Bitmap? = null
    private var bgBitmap: android.graphics.Bitmap? = null

    private var chans: List<Channel> = emptyList()
    private val HINT = "LB RB  filters   ·   Y  search   ·   Back  close"
    private var all: List<Channel> = emptyList()
    private var filterIdx = 0
    private var query = ""
    // the filter bar: Search, the filters, Working only, Cards
    private val barScroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false }
    private val bar = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    private val chips = ArrayList<android.widget.TextView>()
    private val chipActs = ArrayList<() -> Unit>()
    private var inBar = false
    private var chipSel = 0
    private val cache = HashMap<String, List<Prog>>()
    private val loading = HashSet<String>()

    // focus and scroll
    private var fRow = 0
    private var fProg: Prog? = null
    private var anchor = 0L                  // the time Up/Down tries to stay on
    private var topRow = 0f                  // first visible row (animated)
    private var viewStart = 0L               // time at the left edge of the programme area (animated)
    private var targetTop = 0; private var targetStart = 0L
    private var anim: ValueAnimator? = null

    private val windowMs = 150 * 60_000L     // two and a half hours across
    private val half = 30 * 60_000L

    init {
        visibility = View.GONE; isClickable = true
        addView(bg, -1, -1)
        // fixed sizes everywhere: new text then only redraws itself instead of re-measuring the whole screen
        fun put(v: View, top: Float, h: Float, w: Float = 900f) = addView(v, LayoutParams(Kit.dpi(ctx, w), Kit.dpi(ctx, h)).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, top) })
        put(meta, 34f, 26f, 760f); put(title, 64f, 44f); put(desc, 112f, 52f)
        hint.text = HINT; hint.gravity = android.view.Gravity.END
        addView(hint, LayoutParams(Kit.dpi(ctx, 400f), Kit.dpi(ctx, 24f), android.view.Gravity.END).apply { rightMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, 40f) })
        barScroll.addView(bar)
        barScroll.setPadding(Kit.dpi(ctx, Kit.SAFE_X), 0, Kit.dpi(ctx, Kit.SAFE_X), 0)
        addView(barScroll, LayoutParams(-1, Kit.dpi(ctx, 52f)).apply { topMargin = Kit.dpi(ctx, 168f) })
        addView(grid, LayoutParams(-1, -1).apply { topMargin = Kit.dpi(ctx, 226f) })
    }

    // ------------------------------------------------------------------ open / close
    fun show(list: List<Channel>, startUrl: String?) {
        all = list; filterIdx = filterIdx.coerceIn(0, filters.size); query = ""; inBar = false
        chans = applyFilter(); if (startUrl != null && chans.none { it.url == startUrl } && filterIdx != 0) { filterIdx = 0; chans = applyFilter() }
        buildBar()
        cache.clear(); loading.clear()
        val now = System.currentTimeMillis()
        targetStart = floorHalf(now); viewStart = targetStart; anchor = now
        fRow = chans.indexOfFirst { it.url == startUrl }.coerceAtLeast(0)
        targetTop = (fRow - 2).coerceAtLeast(0); topRow = targetTop.toFloat()
        fProg = null
        // one ready-made background picture (blurred wallpaper with the dark tint baked in): one layer, not two
        val src = Backdrop.blurredDim
        if (src != null && src !== bgFor) {
            bgFor = src
            bgBitmap = src.copy(android.graphics.Bitmap.Config.RGB_565, true)?.also { Canvas(it).drawColor(Color.argb(150, 6, 8, 14)) }
            bg.setImageBitmap(bgBitmap)
        }
        if (bgBitmap == null) bg.setBackgroundColor(Color.rgb(12, 14, 20))
        visibility = View.VISIBLE; alpha = 0f; bringToFront()
        animate().alpha(1f).setDuration(Kit.t(220)).withEndAction { if (showing) onCover?.invoke(true) }.start()
        focusChanged(); ui.removeCallbacks(tick); ui.postDelayed(tick, 30_000)
    }

    fun hide() { ui.removeCallbacks(tick); onHidden?.invoke(); onCover?.invoke(false); animate().alpha(0f).setDuration(Kit.t(180)).withEndAction { visibility = View.GONE; cache.clear() }.start() }

    /** New guide data arrived: forget what was read and redraw. */
    fun reload() { if (!showing) return; cache.clear(); loading.clear(); fProg = null; focusChanged() }

    private val tick = object : Runnable { override fun run() { if (showing) { grid.invalidate(); updateInfo(); ui.postDelayed(this, 30_000) } } }

    // ------------------------------------------------------------------ data
    private fun rowsVisible(): Int = ((grid.height - dp(HEADER)) / dp(ROW_H + ROW_GAP)).toInt().coerceAtLeast(1)

    private fun progsFor(i: Int): List<Prog>? {
        val c = chans.getOrNull(i) ?: return null
        if (!Guide.hasData(c.url)) return emptyList()
        cache[c.url]?.let { return it }
        if (c.url !in loading) {
            // read this row and its neighbours in one query
            val want = (i - 8..i + rowsVisible() + 8).mapNotNull { chans.getOrNull(it)?.url }.filter { it !in cache && it !in loading && Guide.hasData(it) }
            loading.addAll(want)
            val now = System.currentTimeMillis()
            io.execute {
                val got = Guide.window(want, now - 3 * 3600_000L, now + 48 * 3600_000L)
                ui.post {
                    if (cache.size > 300) cache.clear()
                    for (u in want) { cache[u] = got[u] ?: emptyList(); loading.remove(u) }
                    if (fProg == null) pickProg()
                    grid.invalidate(); updateInfo()
                }
            }
        }
        return null
    }

    private fun pickProg() {
        val l = progsFor(fRow) ?: return
        fProg = l.firstOrNull { it.onAt(anchor) } ?: l.firstOrNull { it.start >= anchor } ?: l.lastOrNull()
    }

    // ------------------------------------------------------------------ filters
    /** chip 0 is Search; then [filters] (index 0 there = the first filter, usually All) */
    private fun applyFilter(): List<Channel> {
        val f = filters.getOrNull(filterIdx)
        val q = query.trim().lowercase()
        return all.filter { c -> (f == null || f.keep(c)) && (!workingOnly || !isBroken(c)) && (q.isEmpty() || c.name.lowercase().contains(q)) }
    }

    /** Coming down from the tab bar: the filter bar first, one more Down goes into the channels. */
    fun focusBar() { inBar = true; chipSel = (filterIdx + 1).coerceIn(0, chips.size - 1); paintBar(); grid.invalidate() }

    /** The words from the Search chip ("" clears the search). */
    fun search(words: String) {
        query = words.trim(); refilter(); buildBar(); chipSel = 0; paintBar()
        if (chans.isEmpty()) hint.text = "No channel called \"$query\""
    }

    private fun refilter() {
        val keep = chans.getOrNull(fRow)?.url
        chans = applyFilter(); cache.clear(); loading.clear(); hint.text = HINT
        fRow = chans.indexOfFirst { it.url == keep }.coerceAtLeast(0)
        targetTop = (fRow - 2).coerceAtLeast(0); topRow = targetTop.toFloat(); fProg = null
        pickProg(); grid.invalidate(); updateInfo()
    }

    private fun buildBar() {
        bar.removeAllViews(); chips.clear(); chipActs.clear()
        fun chip(label: String, on: Boolean, act: () -> Unit) {
            val t = Kit.text(context, 17f, true).apply { text = label; gravity = android.view.Gravity.CENTER; setPadding(Kit.dpi(context, 18f), 0, Kit.dpi(context, 18f), 0); tag = on }
            t.setOnClickListener { chipSel = chips.indexOf(t); act() }
            bar.addView(t, android.widget.LinearLayout.LayoutParams(-2, Kit.dpi(context, 40f)).apply { rightMargin = Kit.dpi(context, 8f) })
            chips.add(t); chipActs.add(act)
        }
        chip(if (query.isEmpty()) "⌕  Search" else "⌕  \"$query\"  ✕", query.isNotEmpty()) { if (query.isEmpty()) onSearch?.invoke() else search("") }
        for ((i, f) in filters.withIndex()) chip(f.label, i == filterIdx) { filterIdx = i; refilter(); buildBar(); paintBar() }
        chip(if (workingOnly) "✓  Working only" else "Working only", workingOnly) { workingOnly = !workingOnly; onWorkingOnly?.invoke(workingOnly); refilter(); buildBar(); paintBar() }
        chip("▦  Cards", false) { onCards?.invoke() }
        if (onAddCountry != null) chip("+  Add a country", false) { onAddCountry?.invoke() }
        chipSel = chipSel.coerceIn(0, chips.size - 1)
        paintBar()
    }

    private fun paintBar() {
        for ((i, t) in chips.withIndex()) {
            val focus = inBar && i == chipSel; val on = t.tag == true
            t.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(context, Kit.pill(40f)); setColor(when { focus -> Kit.FOCUS_FILL; on -> 0x40FFFFFF; else -> 0x14FFFFFF }) }
            t.setTextColor(if (focus) Kit.FOCUS_TEXT else if (on) Kit.TEXT else 0xCCFFFFFF.toInt())
        }
        chips.getOrNull(chipSel)?.let { c -> if (inBar) barScroll.post { barScroll.smoothScrollTo((c.left - Kit.dpi(context, 120f)).coerceAtLeast(0), 0) } }
    }

    // ------------------------------------------------------------------ moving
    /** Next or previous filter (All, Recent, the countries...) from anywhere in the guide. */
    private fun stepFilter(d: Int) {
        if (filters.isEmpty()) return
        filterIdx = ((filterIdx + d) % filters.size + filters.size) % filters.size
        refilter(); buildBar()
        if (inBar) chipSel = filterIdx + 1
        paintBar()
        barScroll.post { chips.getOrNull(filterIdx + 1)?.let { c -> barScroll.smoothScrollTo((c.left - Kit.dpi(context, 120f)).coerceAtLeast(0), 0) } }
    }

    fun onKey(e: KeyEvent): Boolean {
        // shortcuts that work anywhere in the guide: LB/RB, Ch+/Ch- and [ ] change the filter; LT/RT page the
        // channels; Y, Menu or Search open Search
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_LEFT_BRACKET -> { if (e.repeatCount == 0) stepFilter(-1); return true }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_RIGHT_BRACKET -> { if (e.repeatCount == 0) stepFilter(1); return true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { if (!inBar) moveRow(-rowsVisible()); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { if (!inBar) moveRow(rowsVisible()); return true }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH -> { if (e.repeatCount == 0) onSearch?.invoke(); return true }
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (inBar) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (chipSel > 0) { chipSel--; paintBar() }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (chipSel < chips.size - 1) { chipSel++; paintBar() }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (chans.isNotEmpty()) { inBar = false; paintBar(); focusChanged() }
                KeyEvent.KEYCODE_DPAD_UP -> if (e.repeatCount == 0) onTop?.invoke()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (e.repeatCount == 0) chipActs.getOrNull(chipSel)?.invoke()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) hide()
            }
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (fRow == 0) { if (e.repeatCount == 0) { inBar = true; chipSel = (filterIdx + 1).coerceIn(0, chips.size - 1); paintBar(); grid.invalidate() } } else moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_PAGE_UP -> moveRow(-rowsVisible())
            KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(rowsVisible())
            KeyEvent.KEYCODE_DPAD_LEFT -> moveTime(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTime(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (e.repeatCount == 0) ok()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_GUIDE -> if (e.repeatCount == 0) hide()
        }
        return true
    }

    private fun moveRow(dr: Int) {
        val n = (fRow + dr).coerceIn(0, (chans.size - 1).coerceAtLeast(0))
        if (n == fRow) return
        fRow = n; fProg = null; pickProg()
        val vis = rowsVisible()
        if (fRow < targetTop + 1) targetTop = (fRow - 1).coerceAtLeast(0)
        if (fRow > targetTop + vis - 2) targetTop = (fRow - vis + 2).coerceAtLeast(0)
        focusChanged()
    }

    private fun moveTime(dc: Int) {
        val now = System.currentTimeMillis()
        val l = progsFor(fRow)
        val cur = fProg
        if (l.isNullOrEmpty() || cur == null) {
            // no guide for this channel: just move the time
            targetStart = (targetStart + dc * half).coerceIn(floorHalf(now) - 2 * half, floorHalf(now) + 92 * half)
            anchor = targetStart; focusChanged(); return
        }
        val i = l.indexOf(cur) + dc
        val next = l.getOrNull(i) ?: return
        if (dc < 0 && next.stop <= now - 2 * half) return
        fProg = next
        anchor = maxOf(next.start, now.takeIf { next.onAt(it) } ?: next.start)
        if (next.start < targetStart || next.start >= targetStart + windowMs - half) targetStart = maxOf(floorHalf(next.start), floorHalf(now) - 2 * half)
        if (next.stop <= targetStart) targetStart = floorHalf(next.start)
        focusChanged()
    }

    private fun ok() {
        val c = chans.getOrNull(fRow) ?: return
        val p = fProg
        if (p != null && p.start > System.currentTimeMillis()) onLater?.invoke(c, p)
        else onWatch?.invoke(chans, fRow)
    }

    private fun focusChanged() {
        anim?.cancel()
        val fromTop = topRow; val fromStart = viewStart
        if (fromTop == targetTop.toFloat() && fromStart == targetStart) { grid.invalidate(); updateInfo(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Kit.t(220); interpolator = Kit.EASE
            addUpdateListener { a -> val f = a.animatedValue as Float
                topRow = fromTop + (targetTop - fromTop) * f
                viewStart = fromStart + ((targetStart - fromStart) * f).toLong()
                grid.invalidate() }
            start()
        }
        updateInfo()
    }

    // ------------------------------------------------------------------ the text above the grid
    private fun fmt(t: Long): String = timeFmt().format(Date(t))
    private fun timeFmt(): DateFormat = if (clock24()) java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault()) else java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())

    private fun updateInfo() {
        val c = chans.getOrNull(fRow)
        val now = System.currentTimeMillis()
        if (c == null) { title.text = "No channels"; meta.text = ""; desc.text = ""; return }
        val num = numberOf(c)
        val p = fProg
        val l = progsFor(fRow)
        when {
            !Guide.hasData(c.url) -> {
                title.text = c.name
                meta.text = "$num  ·  ${c.country}"
                desc.text = if (Guide.refreshing) "Getting the guide…  ${Guide.progress}" else if (Guide.channelCount == 0) "No guide yet. It downloads by itself when the box is online." else "No guide information for this channel. OK to watch it."
            }
            p == null -> { title.text = c.name; meta.text = "$num  ·  ${c.country}"; desc.text = if (l == null) "Loading…" else "Nothing listed. OK to watch it." }
            else -> {
                title.text = p.title + (p.sub?.let { "  ·  $it" } ?: "")
                val mins = ((p.stop - p.start) / 60_000).toInt()
                val whenTxt = when {
                    p.onAt(now) -> "On now, ${((p.stop - now) / 60_000).toInt().coerceAtLeast(1)} min left"
                    p.start > now -> { val m = ((p.start - now) / 60_000).toInt(); if (m < 60) "Starts in $m min" else if (sameDay(p.start, now)) "Later today" else "Tomorrow" }
                    else -> "Earlier"
                }
                val bell = if (Guide.hasReminder(c.url, p.start)) "  ·  Reminder set" else ""
                meta.text = "$num  ${c.name}   ·   ${fmt(p.start)} – ${fmt(p.stop)}  ($mins min)   ·   $whenTxt$bell"
                desc.text = p.desc ?: ""
            }
        }
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }; val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun floorHalf(t: Long): Long { val off = java.util.TimeZone.getDefault().getOffset(t); return ((t + off) / half) * half - off }

    // ------------------------------------------------------------------ drawing
    private inner class Grid(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.font(ctx, true); textSize = dp(18f) }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.font(ctx); textSize = dp(15f) }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.font(ctx, true); textSize = dp(16f); color = Kit.TEXT_DIM }
        private val r = RectF()
        private val labelCache = HashMap<Long, String>()

        override fun onDraw(cv: Canvas) {
            val left = dp(Kit.SAFE_X); val right = width - dp(Kit.SAFE_X)
            val chanW = dp(CHAN_W); val pLeft = left + chanW + dp(8f)
            val perMs = (right - pLeft) / windowMs
            val rowsTop = dp(HEADER); val rowH = dp(ROW_H); val step = dp(ROW_H + ROW_GAP)
            val now = System.currentTimeMillis()
            fun x(t: Long) = pLeft + (t - viewStart) * perMs

            // time labels every half hour
            var t = floorHalf(viewStart)
            while (t < viewStart + windowMs + half) {
                val lx = x(t)
                if (lx >= pLeft - 2 && lx < right - dp(40f)) {
                    val s = labelCache.getOrPut(t) { fmt(t) }
                    cv.drawText(s, lx + dp(6f), dp(22f), small.apply { color = Kit.TEXT_DIM })
                    fill.color = Color.argb(40, 255, 255, 255); cv.drawRect(lx, dp(30f), lx + 1, rowsTop - dp(6f), fill)
                }
                t += half
            }
            if (labelCache.size > 400) labelCache.clear()

            val first = topRow.toInt(); val frac = topRow - first
            val vis = ((height - rowsTop) / step).toInt() + 2
            cv.save(); cv.clipRect(0f, rowsTop - dp(4f), width.toFloat(), height.toFloat())
            for (k in 0 until vis) {
                val i = first + k
                val c = chans.getOrNull(i) ?: break
                val top = rowsTop + (k - frac) * step
                if (top > height) break
                val focusedRow = i == fRow && !inBar
                // channel cell
                r.set(left, top, left + chanW, top + rowH)
                fill.color = if (focusedRow) Color.argb(70, 255, 255, 255) else Color.argb(34, 255, 255, 255)
                cv.drawRoundRect(r, dp(12f), dp(12f), fill)
                val ns = numberOf(c).toString()
                cv.drawText(ns, left + dp(14f), top + rowH / 2 + dp(6f), num)
                val nameX = left + dp(14f) + maxOf(dp(44f), num.measureText(ns) + dp(10f))
                text.color = Kit.TEXT
                drawFit(cv, c.name, nameX, top + rowH / 2 + dp(6f), left + chanW - dp(10f) - nameX, text)

                // programmes
                cv.save(); cv.clipRect(pLeft, top - dp(4f), right + dp(4f), top + rowH + dp(4f))
                val l = progsFor(i)
                if (l == null || l.isEmpty()) {
                    r.set(pLeft, top, right, top + rowH)
                    val focused = focusedRow
                    fill.color = if (focused) Kit.FOCUS_FILL else Color.argb(18, 255, 255, 255)
                    cv.drawRoundRect(r, dp(12f), dp(12f), fill)
                    small.color = if (focused) Kit.FOCUS_TEXT else Kit.TEXT_DIM
                    cv.drawText(if (l == null) "…" else if (Guide.hasData(c.url)) "Nothing listed" else "No guide information", pLeft + dp(16f), top + rowH / 2 + dp(5f), small)
                } else for (p in l) {
                    if (p.stop <= viewStart || p.start >= viewStart + windowMs) continue
                    val x0 = maxOf(x(p.start), pLeft); val x1 = minOf(x(p.stop), right) - dp(4f)
                    if (x1 - x0 < dp(3f)) continue
                    r.set(x0, top, x1, top + rowH)
                    val focused = focusedRow && p === fProg
                    val live = p.onAt(now)
                    fill.color = when { focused -> Kit.FOCUS_FILL; live -> Color.argb(62, 255, 255, 255); p.stop <= now -> Color.argb(16, 255, 255, 255); else -> Color.argb(34, 255, 255, 255) }
                    cv.drawRoundRect(r, dp(12f), dp(12f), fill)
                    if (x1 - x0 > dp(28f)) {
                        text.color = if (focused) Kit.FOCUS_TEXT else if (p.stop <= now) Kit.TEXT_DIM else Kit.TEXT
                        val tx = x0 + dp(14f)
                        val bell = if (Guide.hasReminder(c.url, p.start)) "⏰ " else ""
                        drawFit(cv, bell + p.title, tx, top + rowH / 2 + dp(6f), x1 - tx - dp(10f), text)
                    }
                }
                cv.restore()
            }
            cv.restore()

            // now line
            val nx = x(now)
            if (nx in pLeft..right) {
                fill.color = Kit.ACCENT
                cv.drawRect(nx - dp(1f), dp(30f), nx + dp(1f), height.toFloat(), fill)
                cv.drawCircle(nx, dp(30f), dp(4f), fill)
            }
        }

        /** Text cut to fit, with an ellipsis; no layout objects per frame. */
        private fun drawFit(cv: Canvas, s: String, x: Float, y: Float, w: Float, p: Paint) {
            if (w <= 0) return
            val n = p.breakText(s, true, w, null)
            if (n >= s.length) { cv.drawText(s, x, y, p); return }
            val m = p.breakText(s, true, w - p.measureText("…"), null).coerceAtLeast(0)
            cv.drawText(s.substring(0, m).trimEnd() + "…", x, y, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            val rowsTop = dp(HEADER); val step = dp(ROW_H + ROW_GAP)
            if (e.y < rowsTop) return true
            val i = (topRow + (e.y - rowsTop) / step).toInt()
            if (i !in chans.indices) return true
            val pLeft = dp(Kit.SAFE_X) + dp(CHAN_W) + dp(8f)
            val t = if (e.x > pLeft) viewStart + ((e.x - pLeft) / ((width - dp(Kit.SAFE_X) - pLeft) / windowMs)).toLong() else System.currentTimeMillis()
            val p = progsFor(i)?.firstOrNull { it.onAt(t) }
            if (i == fRow && (p == null || p === fProg)) { ok(); return true }
            fRow = i; anchor = t; fProg = p; if (p == null) pickProg(); focusChanged()
            return true
        }

        override fun onGenericMotionEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_SCROLL) { val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL); if (v != 0f) moveRow(if (v < 0) 1 else -1); return true }
            return super.onGenericMotionEvent(e)
        }
    }

    companion object {
        const val HEADER = 40f
        const val ROW_H = 52f
        const val ROW_GAP = 8f
        const val CHAN_W = 250f
    }
}
