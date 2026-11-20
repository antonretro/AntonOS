package com.antonretro.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit

/**
 * A small glass pill bottom right while the box works on something that takes a while (the guide, the channel
 * list, the channel check): what it's doing and a thin bar. The bar fills when the work knows how far it is;
 * otherwise a short light sweeps along it. Never takes focus; only transforms and alpha animate.
 */
class WorkPill(ctx: Context) : LinearLayout(ctx) {
    private val label: TextView = Kit.text(ctx, 15f, true)
    private val detail: TextView = Kit.text(ctx, 13f, false).apply { setTextColor(Kit.TEXT_DIM) }
    private val track = FrameLayout(ctx)
    private val fill = View(ctx)
    private val sweep = View(ctx)
    private var sweeping: ValueAnimator? = null
    private val trackW = Kit.dpi(ctx, 290f)
    var showing = false; private set

    init {
        orientation = VERTICAL
        isFocusable = false; alpha = 0f; visibility = GONE
        setPadding(Kit.dpi(ctx, 18f), Kit.dpi(ctx, 11f), Kit.dpi(ctx, 18f), Kit.dpi(ctx, 13f))
        background = GlassDrawable(this, Kit.dp(ctx, Kit.R_M), Kit.GLASS_STRONG)
        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
        top.addView(label, LayoutParams(0, -2, 1f))
        top.addView(detail, LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, 12f) })
        addView(top, LayoutParams(trackW, -2))
        val h = Kit.dpi(ctx, 4f)
        fun pill(c: Int) = GradientDrawable().apply { cornerRadius = h / 2f; setColor(c) }
        track.background = pill(0x33FFFFFF)
        track.clipToOutline = true
        track.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        fill.background = pill(Kit.ACCENT); fill.pivotX = 0f; fill.scaleX = 0f
        sweep.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, Kit.ACCENT, 0x00FFFFFF)).apply { cornerRadius = h / 2f }
        sweep.visibility = GONE
        track.addView(fill, FrameLayout.LayoutParams(-1, -1))
        track.addView(sweep, FrameLayout.LayoutParams(trackW / 3, -1))
        addView(track, LayoutParams(trackW, h).apply { topMargin = Kit.dpi(ctx, 9f) })
    }

    /** [what] null hides the pill. [fraction] 0..1 when known, else null for the sweep. */
    fun update(what: String?, info: String = "", fraction: Float? = null) {
        if (what == null) { hide(); return }
        label.text = what; detail.text = info
        if (!showing) {
            showing = true; visibility = VISIBLE; bringToFront()
            translationY = Kit.dp(context, 24f)
            animate().alpha(1f).translationY(0f).setDuration(Kit.t(380)).setInterpolator(Kit.EASE).start()
        }
        if (fraction == null) {
            fill.scaleX = 0f
            if (sweeping == null) {
                sweep.visibility = VISIBLE
                if (Kit.motion == 0f) { sweep.translationX = trackW / 3f; return }
                sweeping = ValueAnimator.ofFloat(-trackW / 3f, trackW.toFloat()).apply {
                    duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                    addUpdateListener { sweep.translationX = it.animatedValue as Float }
                    start()
                }
            }
        } else {
            stopSweep()
            fill.animate().scaleX(fraction.coerceIn(0.02f, 1f)).setDuration(Kit.t(450)).setInterpolator(Kit.EASE).start()
        }
    }

    private fun stopSweep() { sweeping?.cancel(); sweeping = null; sweep.visibility = GONE }

    private fun hide() {
        if (!showing) return
        showing = false; stopSweep()
        fill.animate().scaleX(1f).setDuration(Kit.t(200)).start()
        animate().alpha(0f).translationY(Kit.dp(context, 24f)).setStartDelay(Kit.t(250)).setDuration(Kit.t(320))
            .withEndAction { visibility = GONE; fill.scaleX = 0f }.start()
    }
}
