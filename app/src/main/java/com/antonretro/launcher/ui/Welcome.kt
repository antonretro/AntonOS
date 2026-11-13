package com.antonretro.launcher.ui

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit

/**
 * The first-time welcome (PLAN.md "First-time welcome", "Works with no PC"): one idea per screen, skippable, under a
 * minute. Every step that needs Android (home app, the key helper, Wi-Fi) opens the right Android screen and checks
 * by itself when it's done, so a plain APK install needs no PC. Shown once; again from Settings > About.
 */
class Welcome(ctx: Context) : FrameLayout(ctx) {
    /** One screen: a title, a few lines, the choices (label, action), and an optional "is it done?" check. */
    class Step(val title: String, val body: String, val choices: List<Pair<String, () -> Unit>>, val done: (() -> Boolean)? = null, val doneText: String = "")

    var steps: List<Step> = emptyList()
    var onFinish: (() -> Unit)? = null
    val showing get() = visibility == View.VISIBLE
    private var i = 0
    private var sel = 0
    private val box = LinearLayout(ctx)
    private val dots = Kit.text(ctx, 15f, true, Kit.TEXT_DIM)
    private val title = Kit.text(ctx, 38f, true)
    private val body = Kit.text(ctx, 21f, false, 0xE6FFFFFF.toInt())
    private val status = Kit.text(ctx, 18f, true)
    private val buttons = LinearLayout(ctx)
    private val btns = ArrayList<TextView>()

    init {
        visibility = View.GONE; isClickable = true
        setBackgroundColor(0xE60A0C12.toInt())
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Kit.dpi(ctx, 48f), Kit.dpi(ctx, 40f), Kit.dpi(ctx, 48f), Kit.dpi(ctx, 40f))
        box.background = GlassDrawable(box, Kit.dp(ctx, Kit.R_L), Kit.GLASS_STRONG)
        box.addView(dots)
        box.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 10f) })
        body.isSingleLine = false; body.maxLines = 6
        box.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 14f) })
        box.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 14f) })
        buttons.orientation = LinearLayout.HORIZONTAL; buttons.clipChildren = false; box.clipChildren = false; box.clipToPadding = false
        box.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(ctx, 30f) })
        addView(box, LayoutParams(Kit.dpi(ctx, 820f), -2, Gravity.CENTER))
    }

    fun start() { i = 0; visibility = View.VISIBLE; bringToFront(); show() }
    /** Back from an Android settings screen: refresh the "done" check. */
    fun recheck() { if (showing) show(keepSel = true) }
    fun next() { if (i < steps.size - 1) { i++; sel = 0; show() } else finish() }
    fun finish() { visibility = View.GONE; onFinish?.invoke() }

    private fun show(keepSel: Boolean = false) {
        val s = steps[i]
        dots.text = (0 until steps.size).joinToString("  ") { if (it == i) "●" else "○" } + "      Step ${i + 1} of ${steps.size}"
        title.text = s.title; body.text = s.body
        val ok = s.done?.invoke()
        status.visibility = if (ok == null) View.GONE else View.VISIBLE
        status.text = if (ok == true) "✓  " + s.doneText else "Not done yet"
        status.setTextColor(if (ok == true) 0xFF6BD48A.toInt() else Kit.TEXT_DIM)
        buttons.removeAllViews(); btns.clear()
        for ((k, c) in s.choices.withIndex()) {
            val t = Kit.text(context, 20f, true).apply { text = c.first; gravity = Gravity.CENTER; setPadding(Kit.dpi(context, 28f), 0, Kit.dpi(context, 28f), 0) }
            t.setOnClickListener { sel = k; c.second() }
            buttons.addView(t, LinearLayout.LayoutParams(-2, Kit.dpi(context, 54f)).apply { rightMargin = Kit.dpi(context, 14f) })
            btns.add(t)
        }
        // a step that's already done starts on its last choice (Next)
        if (!keepSel) sel = if (ok == true) s.choices.size - 1 else 0
        sel = sel.coerceIn(0, btns.size - 1); paint()
        box.alpha = 0f; box.translationY = Kit.dp(context, 10f)
        box.animate().alpha(1f).translationY(0f).setDuration(Kit.t(220)).setInterpolator(Kit.EASE).start()
    }
    private fun paint() {
        for ((k, b) in btns.withIndex()) {
            val on = k == sel
            b.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(context, Kit.pill(54f)); setColor(if (on) Kit.FOCUS_FILL else 0x24FFFFFF) }
            b.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (sel > 0) { sel--; paint() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (sel < btns.size - 1) { sel++; paint() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (e.repeatCount == 0) steps[i].choices.getOrNull(sel)?.second?.invoke()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> if (e.repeatCount == 0) { if (i > 0) { i--; sel = 0; show() } }
        }
        return true
    }
}
