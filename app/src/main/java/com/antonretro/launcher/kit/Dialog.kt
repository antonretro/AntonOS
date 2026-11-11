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

/** The one dialog: title, plain sentence, one or two big buttons. Back = the last button. */
@SuppressLint("ViewConstructor")
class Dialog(ctx: Context) : FrameLayout(ctx) {
    private val box = LinearLayout(ctx)
    private val title = Kit.text(ctx, 30f, true)
    private val body = Kit.text(ctx, Kit.BODY_TEXT, false, 0xD9FFFFFF.toInt())
    private val buttons = LinearLayout(ctx)
    private val btns = ArrayList<TextView>()
    private var actions: List<() -> Unit> = emptyList()
    private var sel = 0
    private var vertical = false
    val showing get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE; isClickable = true
        setBackgroundColor(0x80000000.toInt())
        box.orientation = LinearLayout.VERTICAL; box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(Kit.dpi(ctx, 40f), Kit.dpi(ctx, 36f), Kit.dpi(ctx, 40f), Kit.dpi(ctx, 34f))
        box.background = GlassDrawable(box, Kit.dp(ctx, 26f), Kit.GLASS_STRONG)
        title.gravity = Gravity.CENTER; body.gravity = Gravity.CENTER; body.isSingleLine = false; body.maxLines = 4
        box.addView(title)
        box.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 12f); bottomMargin = Kit.dpi(ctx, 26f) })
        buttons.orientation = LinearLayout.HORIZONTAL; buttons.gravity = Gravity.CENTER
        buttons.clipChildren = false; box.clipChildren = false; box.clipToPadding = false   // keep a selected button's round corners
        box.addView(buttons)
        addView(box, LayoutParams(Kit.dpi(ctx, 620f), -2, Gravity.CENTER))
        box.isClickable = true
        setOnClickListener { sel = btns.size - 1; choose() }   // mouse: click outside = the last choice (Cancel / Not now)
    }

    fun show(t: String, b: String, vararg choices: Pair<String, () -> Unit>) {
        title.text = t; body.text = b
        buttons.removeAllViews(); btns.clear(); actions = choices.map { it.second }
        // side by side only when they fit the box (about 34 characters in all); otherwise one per line
        vertical = choices.size > 3 || choices.sumOf { it.first.length } > 34
        buttons.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for ((i, c) in choices.withIndex()) {
            val tv = Kit.text(context, 21f, true).apply { text = c.first; gravity = Gravity.CENTER; setPadding(Kit.dpi(context, 28f), 0, Kit.dpi(context, 28f), 0) }
            tv.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { sel = i; paint() }; false }
            tv.setOnClickListener { sel = i; choose() }
            buttons.addView(tv, (if (vertical) LinearLayout.LayoutParams(Kit.dpi(context, 380f), Kit.dpi(context, 52f)).apply { bottomMargin = Kit.dpi(context, 8f) }
                else LinearLayout.LayoutParams(-2, Kit.dpi(context, 56f)).apply { leftMargin = Kit.dpi(context, 8f); rightMargin = Kit.dpi(context, 8f) }))
            btns.add(tv)
        }
        sel = 0; paint()
        visibility = View.VISIBLE; alpha = 0f; box.scaleX = .96f; box.scaleY = .96f
        animate().alpha(1f).setDuration(Kit.t(200)).start()
        box.animate().scaleX(1f).scaleY(1f).setDuration(Kit.t(260)).setInterpolator(Kit.EASE).start()
        bringToFront()
    }

    private fun paint() {
        for ((i, b) in btns.withIndex()) {
            val on = i == sel
            b.background = GradientDrawable().apply { cornerRadius = Kit.dp(context, 28f); setColor(if (on) Kit.FOCUS_FILL else 0x24FFFFFF) }
            b.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
            Kit.unclip(b); b.animate().scaleX(if (on) 1.06f else 1f).scaleY(if (on) 1.06f else 1f).setDuration(Kit.t(150)).start()
        }
    }

    private fun choose() { val a = actions.getOrNull(sel); hide(); a?.invoke() }
    fun hide() { animate().alpha(0f).setDuration(Kit.t(160)).withEndAction { visibility = View.GONE }.start() }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (e.repeatCount > 0 && e.keyCode != KeyEvent.KEYCODE_DPAD_UP && e.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return true   // the held OK that opened this menu must not pick for you
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { sel = (sel - 1).coerceAtLeast(0); paint() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { sel = (sel + 1).coerceAtMost(btns.size - 1); paint() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> choose()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> { sel = btns.size - 1; choose() }
        }
        return true
    }
}
