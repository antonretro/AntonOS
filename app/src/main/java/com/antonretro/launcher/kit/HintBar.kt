package com.antonretro.launcher.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The button-hint bar along the bottom of every main screen: what OK, hold OK and Back do here, drawn with the
 * glyphs of whatever was used last (a pad's A/B in their colours, or the remote's OK/Back), so it always matches
 * the hand on the controls. Quiet: small, dim, never covering a focused card (the rows end above it).
 */
class HintBar(ctx: Context) : LinearLayout(ctx) {
    /** one hint: what to press and what it does */
    enum class Btn { OK, HOLD_OK, BACK, LR, UD, MENU }
    var pad = false
        set(v) { if (field != v) { field = v; draw() } }
    private var hints: List<Pair<Btn, String>> = emptyList()

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        setPadding(Kit.dpi(ctx, 18f), 0, Kit.dpi(ctx, 18f), 0)
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000))
    }

    fun set(list: List<Pair<Btn, String>>) { if (list == hints) return; hints = list; draw() }

    private fun glyph(b: Btn): Pair<String, Int> = if (pad) when (b) {
        Btn.OK -> "A" to 0xFF3E9B4F.toInt(); Btn.HOLD_OK -> "Hold A" to 0xFF3E9B4F.toInt(); Btn.BACK -> "B" to 0xFFC8443E.toInt()
        Btn.LR -> "◀ ▶" to 0x40FFFFFF; Btn.UD -> "▲ ▼" to 0x40FFFFFF; Btn.MENU -> "☰" to 0x40FFFFFF
    } else when (b) {
        Btn.OK -> "OK" to 0x40FFFFFF; Btn.HOLD_OK -> "Hold OK" to 0x40FFFFFF; Btn.BACK -> "Back" to 0x40FFFFFF
        Btn.LR -> "◀ ▶" to 0x40FFFFFF; Btn.UD -> "▲ ▼" to 0x40FFFFFF; Btn.MENU -> "Menu" to 0x40FFFFFF
    }

    private fun draw() {
        removeAllViews()
        for ((i, h) in hints.withIndex()) {
            val (g, col) = glyph(h.first)
            val chip = Kit.text(context, 13f, true).apply {
                text = g; gravity = Gravity.CENTER; setPadding(Kit.dpi(context, 8f), 0, Kit.dpi(context, 8f), 0)
                background = GradientDrawable().apply { cornerRadius = Kit.dp(context, Kit.pill(22f)); setColor(col) }
            }
            addView(chip, LayoutParams(-2, Kit.dpi(context, 22f)).apply { if (i > 0) leftMargin = Kit.dpi(context, 22f) })
            addView(Kit.text(context, 15f, true, 0xCCFFFFFF.toInt()).apply { text = h.second }, LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(context, 7f) })
        }
        visibility = if (hints.isEmpty()) View.GONE else View.VISIBLE
    }
}
