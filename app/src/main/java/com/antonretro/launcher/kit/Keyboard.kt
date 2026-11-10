package com.antonretro.launcher.kit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The one on-screen keyboard (searches, Wi-Fi passwords, web addresses). Big keys, arrows move,
 * OK types, a real keyboard types directly, Back closes. Every screen uses this one.
 */
@SuppressLint("ViewConstructor")
class Keyboard(ctx: Context) : FrameLayout(ctx) {
    private val panel = LinearLayout(ctx)
    private val title = Kit.text(ctx, 20f, true)
    private val hint = Kit.text(ctx, 15f, false, Kit.TEXT_DIM)
    private val field = Kit.text(ctx, 26f, true)
    private val rowsBox = LinearLayout(ctx)
    private val keys = ArrayList<ArrayList<TextView>>()
    private var r = 1; private var c = 0
    private var text = StringBuilder()
    private var secret = false; private var shift = false; private var symbols = false
    private var done: ((String) -> Unit)? = null
    private var doneLabel = "Done"
    val showing get() = visibility == View.VISIBLE

    private val letters = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm-.@")
    private val syms = listOf("1234567890", "!#$%&*()_+", "/:;=?\"~^|\\", "<>[]{},.@-")

    init {
        visibility = View.GONE
        setBackgroundColor(0x66000000)
        isClickable = true
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(Kit.dpi(ctx, 26f), Kit.dpi(ctx, 22f), Kit.dpi(ctx, 26f), Kit.dpi(ctx, 24f))
        panel.background = GlassDrawable(panel, Kit.dp(ctx, 24f), Kit.GLASS_STRONG)
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(hint) }
        panel.addView(top)
        field.background = GradientDrawable().apply { cornerRadius = Kit.dp(ctx, 16f); setColor(0x59000000); setStroke(2, 0x4DFFFFFF) }
        field.setPadding(Kit.dpi(ctx, 20f), 0, Kit.dpi(ctx, 20f), 0); field.gravity = Gravity.CENTER_VERTICAL
        panel.addView(field, LinearLayout.LayoutParams(-1, Kit.dpi(ctx, 62f)).apply { topMargin = Kit.dpi(ctx, 10f); bottomMargin = Kit.dpi(ctx, 16f) })
        rowsBox.orientation = LinearLayout.VERTICAL
        panel.addView(rowsBox)
        addView(panel, LayoutParams(Kit.dpi(ctx, 1000f), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Kit.dpi(ctx, 34f) })
    }

    fun open(titleText: String, initial: String = "", password: Boolean = false, doneText: String = "Done", onDone: (String) -> Unit) {
        title.text = titleText; hint.text = "or type on a keyboard or your phone"
        text = StringBuilder(initial); secret = password; done = onDone; doneLabel = doneText
        shift = false; symbols = false; build(); r = 1; c = 0; paint(); show()
        visibility = View.VISIBLE; alpha = 0f; panel.translationY = Kit.dp(context, 40f)
        animate().alpha(1f).setDuration(Kit.t(200)).start()
        panel.animate().translationY(0f).setDuration(Kit.t(300)).setInterpolator(Kit.EASE).start()
    }

    fun close() { animate().alpha(0f).setDuration(Kit.t(180)).withEndAction { visibility = View.GONE }.start() }

    private fun build() {
        rowsBox.removeAllViews(); keys.clear()
        val base = if (symbols) syms else letters
        val all = base.map { row -> row.map { ch -> (if (shift) ch.uppercaseChar() else ch).toString() } } +
            listOf(listOf(if (symbols) "abc" else "#+=", "⇧", "space", "⌫", doneLabel))
        for ((ri, row) in all.withIndex()) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            val list = ArrayList<TextView>()
            for ((ki, label) in row.withIndex()) {
                val wide = ri == all.size - 1
                val w = when { !wide -> 80f; label == "space" -> 330f; label == doneLabel -> 190f; else -> 110f }
                val k = Kit.text(context, if (wide && label.length > 1) 18f else 24f, true).apply { text = label; gravity = Gravity.CENTER }
                k.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { r = ri; c = ki; paint() }; false }
                k.setOnClickListener { r = ri; c = ki; press() }
                line.addView(k, LinearLayout.LayoutParams(Kit.dpi(context, w), Kit.dpi(context, 54f)).apply { leftMargin = Kit.dpi(context, 4f); rightMargin = Kit.dpi(context, 4f) })
                list.add(k)
            }
            rowsBox.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Kit.dpi(context, 8f) })
            keys.add(list)
        }
    }

    private fun bg(on: Boolean, accent: Boolean) = GradientDrawable().apply { cornerRadius = Kit.dp(context, 12f); setColor(if (on) Kit.FOCUS_FILL else if (accent) Kit.ACCENT else 0x1FFFFFFF) }

    private fun paint() {
        for ((ri, row) in keys.withIndex()) for ((ki, k) in row.withIndex()) {
            val on = ri == r && ki == c; val accent = k.text == doneLabel
            k.background = bg(on, accent); k.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
            k.animate().scaleX(if (on) 1.1f else 1f).scaleY(if (on) 1.1f else 1f).setDuration(Kit.t(120)).start()
        }
    }

    private fun show() { field.text = (if (secret) "•".repeat(text.length) else text.toString()) + "▏" }

    private fun press() {
        when (val k = keys[r][c].text.toString()) {
            "⌫" -> if (text.isNotEmpty()) text.deleteCharAt(text.length - 1)
            "space" -> text.append(' ')
            "⇧" -> { shift = !shift; build() }
            "#+=", "abc" -> { symbols = !symbols; build(); r = r.coerceAtMost(keys.size - 1); c = c.coerceAtMost(keys[r].size - 1) }
            doneLabel -> { val t = text.toString(); close(); done?.invoke(t); return }
            else -> { text.append(k); if (shift) { shift = false; build() } }
        }
        paint(); show()
    }

    private fun moveV(d: Int) {
        val nr = (r + d).coerceIn(0, keys.size - 1); if (nr == r) return
        val x = keys[r][c].let { it.left + it.width / 2 }
        c = keys[nr].indices.minByOrNull { Math.abs(keys[nr][it].left + keys[nr][it].width / 2 - x) } ?: 0
        r = nr
    }

    /** Returns true when the key was used. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveV(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveV(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> c = (c - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> c = (c + 1).coerceAtMost(keys[r].size - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> { press(); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // a real keyboard's Enter submits; a remote whose OK sends Enter just presses the key
                if (e.device?.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) { val t = text.toString(); close(); done?.invoke(t) } else press()
                return true
            }
            KeyEvent.KEYCODE_DEL -> { if (text.isNotEmpty()) text.deleteCharAt(text.length - 1); show() }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> { close(); return true }
            else -> { val ch = e.unicodeChar; if (ch > 0) { text.append(ch.toChar()); show() }; return true }
        }
        paint(); return true
    }
}
