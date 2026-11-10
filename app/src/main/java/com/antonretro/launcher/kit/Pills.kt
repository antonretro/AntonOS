package com.antonretro.launcher.kit

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.FrameLayout

/**
 * The tab bar's two pills (selected tab = soft glass, focused tab = white) drawn by one view
 * and glided from tab to tab, so the highlight visibly travels instead of jumping.
 */
class PillTrack(ctx: Context) : FrameLayout(ctx) {
    init { setWillNotDraw(false) }   // pills are drawn under the tab labels (children)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x29FFFFFF }
    private val focPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.FOCUS_FILL }
    private val sel = RectF(); private val foc = RectF()
    private var focAlpha = 0f
    private var selAnim: ValueAnimator? = null; private var focAnim: ValueAnimator? = null
    private val radius = Kit.dp(ctx, 21f)

    fun moveSelected(l: Float, r: Float, animate: Boolean) { selAnim?.cancel(); selAnim = glide(sel, l, r, animate) }
    fun moveFocus(l: Float, r: Float, show: Boolean, animate: Boolean) {
        focAnim?.cancel()
        if (focAlpha == 0f && show) { foc.set(l, sel.top, r, sel.bottom) }   // appear where it is, then grow in
        focAnim = glide(foc, l, r, animate)
        ValueAnimator.ofFloat(focAlpha, if (show) 1f else 0f).apply {
            duration = if (animate) Kit.t(Kit.T_FOCUS) else 0; interpolator = Kit.EASE
            addUpdateListener { focAlpha = it.animatedValue as Float; invalidate() }; start()
        }
    }
    fun setHeight(top: Float, bottom: Float) { sel.top = top; sel.bottom = bottom; foc.top = top; foc.bottom = bottom }

    private fun glide(r: RectF, l: Float, rt: Float, animate: Boolean): ValueAnimator? {
        if (!animate || r.width() == 0f) { r.left = l; r.right = rt; invalidate(); return null }
        val l0 = r.left; val r0 = r.right
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Kit.t(280); interpolator = Kit.EASE
            // the leading edge moves first and the trailing edge catches up: a soft stretch
            addUpdateListener {
                val f = it.animatedValue as Float
                val lead = Math.min(1f, f * 1.25f); val trail = Math.max(0f, f * 1.25f - .25f)
                if (l >= l0) { r.right = r0 + (rt - r0) * lead; r.left = l0 + (l - l0) * trail }
                else { r.left = l0 + (l - l0) * lead; r.right = r0 + (rt - r0) * trail }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(sel, radius, radius, selPaint)
        if (focAlpha > 0f) { focPaint.alpha = (focAlpha * 255).toInt(); c.drawRoundRect(foc, radius, radius, focPaint) }
    }
}
