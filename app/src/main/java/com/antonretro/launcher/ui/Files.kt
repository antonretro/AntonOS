package com.antonretro.launcher.ui

import android.content.Context
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit
import java.io.File

/**
 * Files: AntonOS's own simple file browser (PLAN.md "Our own Files and Store"). The box's storage and any USB
 * drives, folders first, big readable rows. OK opens a folder or hands a file to [onOpen] (videos and music play in
 * AntonOS, games start, other files go to whatever app can open them). Hold OK: delete, after asking.
 */
class FilesView(ctx: Context) : FrameLayout(ctx) {
    var onOpen: ((File) -> Unit)? = null
    var onDelete: ((File, () -> Unit) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    val showing get() = visibility == View.VISIBLE

    private val title = Kit.text(ctx, 30f, true).apply { setBackgroundColor(0xFF0A0C12.toInt()) }   // solid: a shorter new title never shows the old one behind it
    private val path = Kit.text(ctx, 16f, false, Kit.TEXT_DIM)
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val rows = ArrayList<TextView>()
    private var entries: List<File?> = emptyList()   // null = "up one folder"
    private var dir: File? = null                     // null = the list of drives
    private var sel = 0
    private val roots = ArrayList<Pair<String, File>>()

    init {
        visibility = View.GONE; isClickable = true
        setBackgroundColor(0xF20A0C12.toInt())
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title, LinearLayout.LayoutParams(-1, -2)); col.addView(path, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 6f) })
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(-1, -1).apply { topMargin = Kit.dpi(ctx, 18f) })
        addView(col, LayoutParams(-1, -1).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); rightMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, Kit.SAFE_Y); bottomMargin = Kit.dpi(ctx, 44f) })
    }

    fun show() { visibility = View.VISIBLE; alpha = 0f; bringToFront(); animate().alpha(1f).setDuration(Kit.t(200)).start(); open(null) }
    fun hide() { animate().alpha(0f).setDuration(Kit.t(160)).withEndAction { visibility = View.GONE }.start(); onClose?.invoke() }

    private fun findRoots() {
        roots.clear()
        roots.add("This box" to Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }?.forEach { roots.add("USB drive  (${it.name})" to it) }
    }

    private fun open(d: File?) {
        dir = d; sel = 0
        if (d == null) {
            findRoots()
            title.text = "Files"; path.text = "Choose a drive"
            entries = roots.map { it.second }
        } else {
            title.text = roots.firstOrNull { it.second == d }?.first ?: d.name
            path.text = d.path
            val kids = (d.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            entries = listOf<File?>(null) + kids
        }
        build()
    }

    private fun sizeText(f: File): String {
        val b = f.length()
        return when { b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0); b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0); b >= 1024 -> "${b / 1024} KB"; else -> "$b bytes" }
    }
    private fun icon(f: File): String = when {
        f.isDirectory -> "📁"
        f.extension.lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v") -> "🎬"
        f.extension.lowercase() in setOf("mp3", "m4a", "flac", "ogg", "wav", "aac", "mid", "midi", "opus") -> "♪"
        f.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> "🖼"
        f.extension.lowercase() == "apk" -> "📦"
        f.extension.lowercase() in setOf("nes", "sfc", "smc", "gb", "gbc", "gba", "md", "gen", "sms", "gg", "pce") -> "🎮"
        else -> "📄"
    }

    private fun build() {
        list.removeAllViews(); rows.clear()
        for (f in entries) {
            val label = when {
                f == null -> "‹  Up one folder"
                dir == null -> "💾  " + (roots.firstOrNull { it.second == f }?.first ?: f.name)
                else -> icon(f) + "  " + f.name + (if (f.isDirectory) "" else "     " + sizeText(f))
            }
            val t = Kit.text(context, 20f, true).apply { text = label; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(context, 22f), 0, Kit.dpi(context, 22f), 0) }
            t.setOnClickListener { sel = rows.indexOf(t); ok() }
            list.addView(t, LinearLayout.LayoutParams(-1, Kit.dpi(context, 52f)).apply { bottomMargin = Kit.dpi(context, 6f) })
            rows.add(t)
        }
        if (entries.size == 1 && dir != null) {
            list.addView(Kit.text(context, 18f, false, Kit.TEXT_DIM).apply { text = "This folder is empty"; setPadding(Kit.dpi(context, 22f), Kit.dpi(context, 12f), 0, 0) })
        }
        paint()
    }

    private fun paint() {
        for ((i, t) in rows.withIndex()) {
            val on = i == sel
            t.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(context, Kit.R_M); setColor(if (on) Kit.FOCUS_FILL else 0x14FFFFFF) }
            t.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
        }
        rows.getOrNull(sel)?.let { v -> scroll.post { val top = v.top - Kit.dpi(context, 60f); val bottom = v.bottom - scroll.height + Kit.dpi(context, 60f); if (scroll.scrollY > top) scroll.smoothScrollTo(0, maxOf(0, top)) else if (scroll.scrollY < bottom) scroll.smoothScrollTo(0, bottom) } }
    }

    private fun ok() {
        val f = entries.getOrNull(sel)
        when {
            f == null && dir != null -> up()
            f == null -> {}
            f.isDirectory || dir == null -> open(f)
            else -> onOpen?.invoke(f)
        }
    }
    private fun up() {
        val d = dir ?: return
        open(if (roots.any { it.second == d }) null else d.parentFile)
    }

    private var downAt = 0L; private var held = false
    fun onKey(e: KeyEvent): Boolean {
        val okKey = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_BUTTON_A
        if (okKey) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { downAt = e.eventTime; held = false }
            if (e.action == KeyEvent.ACTION_DOWN && !held && e.eventTime - downAt > 600) {
                held = true
                val f = entries.getOrNull(sel); if (f != null && dir != null) onDelete?.invoke(f) { open(dir) }
            }
            if (e.action == KeyEvent.ACTION_UP && !held) ok()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (sel > 0) { sel--; paint() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (sel < rows.size - 1) { sel++; paint() }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_BUTTON_L1 -> { sel = (sel - 8).coerceAtLeast(0); paint() }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_BUTTON_R1 -> { sel = (sel + 8).coerceAtMost(rows.size - 1); paint() }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (dir != null) up()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) { if (dir != null) up() else hide() }
        }
        return true
    }
}
