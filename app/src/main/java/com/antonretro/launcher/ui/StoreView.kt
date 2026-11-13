package com.antonretro.launcher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.antonretro.launcher.data.StoreApp
import com.antonretro.launcher.data.StoreRepo
import com.antonretro.launcher.kit.Images
import com.antonretro.launcher.kit.Kit

/**
 * The Store (PLAN.md "Our own Files and Store"): F-Droid's open-source catalogue in AntonOS's style. One list: Search,
 * Featured for TV, Updates, then every app that runs on this box, A to Z. OK opens an app's page (Install / Update /
 * Open / Remove), handled by the launcher through [onApp].
 */
class StoreView(ctx: Context) : FrameLayout(ctx) {
    sealed class Row { class Head(val text: String) : Row(); class App(val a: StoreApp) : Row(); object Search : Row() }

    var onApp: ((StoreApp) -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    var onRefresh: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    /** installed version code of a package, or -1 */
    var installed: (String) -> Long = { -1L }
    val showing get() = visibility == View.VISIBLE

    private val title = Kit.text(ctx, 30f, true).apply { text = "Store" }
    private val sub = Kit.text(ctx, 16f, false, Kit.TEXT_DIM)
    private val list = ListView(ctx)
    private var rows: List<Row> = emptyList()
    private var sel = 0
    private var query = ""

    init {
        visibility = View.GONE; isClickable = true
        setBackgroundColor(0xFF0A0C12.toInt())
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title); col.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 6f) })
        list.divider = null; list.dividerHeight = Kit.dpi(ctx, 6f); list.selector = android.graphics.drawable.ColorDrawable(0)
        list.isFocusable = false; list.itemsCanFocus = false; list.isVerticalScrollBarEnabled = false
        post { list.adapter = adapter }   // the adapter is declared below
        list.setOnItemClickListener { _, _, pos, _ -> sel = pos; ok() }
        col.addView(list, LinearLayout.LayoutParams(-1, -1).apply { topMargin = Kit.dpi(ctx, 16f) })
        addView(col, LayoutParams(-1, -1).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); rightMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, Kit.SAFE_Y); bottomMargin = Kit.dpi(ctx, 44f) })
    }

    fun show() { visibility = View.VISIBLE; alpha = 0f; bringToFront(); animate().alpha(1f).setDuration(Kit.t(200)).start(); rebuild() }
    fun hide() { animate().alpha(0f).setDuration(Kit.t(160)).withEndAction { visibility = View.GONE }.start(); onClose?.invoke() }
    fun setStatus(s: String) { sub.text = s }
    fun search(q: String) { query = q.trim(); sel = 0; rebuild() }

    /** Fill the list from the catalogue (call again when it changes or after an install). */
    fun rebuild() {
        val all = StoreRepo.apps
        val out = ArrayList<Row>()
        out.add(Row.Search)
        if (query.isNotEmpty()) {
            val q = query.lowercase()
            val hits = all.filter { it.name.lowercase().contains(q) || it.summary.lowercase().contains(q) }
            out.add(Row.Head(if (hits.isEmpty()) "Nothing called \"$query\"" else "Found ${hits.size}"))
            hits.take(200).forEach { out.add(Row.App(it)) }
        } else {
            val byPkg = all.associateBy { it.pkg }
            val feat = StoreRepo.FEATURED.mapNotNull { byPkg[it] }
            if (feat.isNotEmpty()) { out.add(Row.Head("Featured for TV")); feat.forEach { out.add(Row.App(it)) } }
            val upd = all.filter { installed(it.pkg).let { v -> v in 0 until it.versionCode } }
            if (upd.isNotEmpty()) { out.add(Row.Head("Updates (${upd.size})")); upd.forEach { out.add(Row.App(it)) } }
            if (all.isNotEmpty()) { out.add(Row.Head("All apps that run on this box (${all.size})")); all.forEach { out.add(Row.App(it)) } }
        }
        rows = out
        sel = sel.coerceIn(0, rows.size - 1).let { if (rows[it] is Row.Head) (it + 1).coerceAtMost(rows.size - 1) else it }
        adapter.notifyDataSetChanged(); list.setSelectionFromTop(sel, Kit.dpi(context, 120f))
        if (all.isEmpty() && sub.text.isNullOrEmpty()) sub.text = "Getting the catalogue from F-Droid…"
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Row.Head) 0 else 1
        override fun isEnabled(p: Int) = rows[p] !is Row.Head
        override fun getView(p: Int, old: View?, parent: ViewGroup): View {
            val r = rows[p]
            if (r is Row.Head) return ((old as? TextView) ?: Kit.text(context, 18f, true, Kit.TEXT_DIM).apply { setPadding(Kit.dpi(context, 6f), Kit.dpi(context, 14f), 0, Kit.dpi(context, 4f)) }).apply { text = r.text }
            val v = (old as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(Kit.dpi(context, 16f), 0, Kit.dpi(context, 20f), 0)
                minimumHeight = Kit.dpi(context, 64f)
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(Kit.dpi(context, 44f), Kit.dpi(context, 44f)).apply { rightMargin = Kit.dpi(context, 16f) })
                val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(Kit.text(context, 19f, true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
                texts.addView(Kit.text(context, 15f, false).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Kit.text(context, 15f, true))
            }
            v.layoutParams = android.widget.AbsListView.LayoutParams(-1, Kit.dpi(context, 64f))
            val on = p == sel
            v.background = GradientDrawable().apply { cornerRadius = Kit.dp(context, Kit.R_M); setColor(if (on) Kit.FOCUS_FILL else 0x14FFFFFF) }
            val icon = v.getChildAt(0) as ImageView
            val texts = v.getChildAt(1) as LinearLayout
            val name = texts.getChildAt(0) as TextView; val line = texts.getChildAt(1) as TextView
            val state = v.getChildAt(2) as TextView
            name.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT); line.setTextColor(if (on) 0xFF444444.toInt() else Kit.TEXT_DIM); state.setTextColor(if (on) 0xFF333333.toInt() else Kit.TEXT_DIM)
            if (r is Row.Search) {
                icon.setImageDrawable(null); name.text = if (query.isEmpty()) "⌕  Search" else "⌕  \"$query\"   (OK: new search)"; line.text = "Find an app by name"; state.text = ""
            } else if (r is Row.App) {
                val a = r.a
                name.text = a.name; line.text = a.summary
                val inst = installed(a.pkg)
                state.text = when { inst < 0 -> "%.1f MB".format(a.size / 1048576.0); inst < a.versionCode -> "Update"; else -> "Installed" }
                icon.tag = a.pkg; icon.setImageDrawable(null)
                StoreRepo.iconUrl(a)?.let { url -> Images.load(url, 96, 96) { b -> if (icon.tag == a.pkg && b != null) icon.setImageBitmap(b) } }
            }
            return v
        }
    }

    private fun ok() {
        when (val r = rows.getOrNull(sel)) { is Row.Search -> onSearch?.invoke(); is Row.App -> onApp?.invoke(r.a); else -> {} }
    }
    private fun move(d: Int) {
        var n = sel + d
        while (n in rows.indices && rows[n] is Row.Head) n += if (d > 0) 1 else -1
        if (n !in rows.indices) return
        sel = n; adapter.notifyDataSetChanged()
        val first = list.firstVisiblePosition; val last = list.lastVisiblePosition
        if (sel <= first + 1 || sel >= last - 1) list.setSelectionFromTop(sel, list.height / 2 - Kit.dpi(context, 32f))
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_BUTTON_L1 -> move(-8)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_BUTTON_R1 -> move(8)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (e.repeatCount == 0) ok()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> if (e.repeatCount == 0) onRefresh?.invoke()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) { if (query.isNotEmpty()) search("") else hide() }
        }
        return true
    }
}
