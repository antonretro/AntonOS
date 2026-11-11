package com.antonretro.launcher.kit

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * The design kit: every colour, size, font and timing used by any screen lives here.
 * Sizes are in dp for a 1280x720 @160 dpi screen (1 dp = 1 px on the MXQ Pro);
 * the 1920x1080 mockup numbers divided by 1.5.
 */
object Kit {
    // colours
    const val TEXT = Color.WHITE
    val TEXT_DIM = Color.argb(158, 255, 255, 255)
    val GLASS_TINT = Color.argb(118, 28, 30, 38)
    val GLASS_STRONG = Color.argb(170, 24, 26, 34)
    val GLASS_EDGE = Color.argb(36, 255, 255, 255)
    // 99.6% white: a fully solid fill makes Android 7 draw rounded backgrounds with square corners
    val FOCUS_FILL = Color.argb(254, 255, 255, 255)
    val FOCUS_TEXT = Color.rgb(17, 17, 17)
    val ACCENT = Color.rgb(79, 140, 255)
    val DIM_SCROLLED = Color.argb(140, 6, 8, 14)

    // layout (dp)
    const val SAFE_X = 53f          // 80 px at 1080p
    const val SAFE_Y = 40f
    const val CARD_W = 213f
    const val CARD_H = 120f
    // ---- the corner rule (Anthony: "make sure the white corner matches the corners"):
    // one radius scale, nothing else. A shape inside another is concentric: its radius is the outer radius minus
    // the gap between them (inner()), never less than XS; a highlight inside a panel keeps that gap on every side
    // and never grows into the panel's edge. Siblings share one radius. Pills (half the height) only for buttons,
    // chips and the tab bar. Cover art (game boxes, song covers, posters) all uses R_XS, the smallest step, and is shown
    // whole in its own proportions (Anthony: "all retro games rounded corners for simplicity ... and all music").
    const val R_XS = 6f
    /** box art corners, a setting: R_XS (rounded, the default) or 0 (square, like the real box) */
    @JvmStatic var coverRadius = R_XS
    const val R_S = 12f
    const val R_M = 16f
    const val R_L = 24f
    /** the radius of a shape inset by `gap` inside a shape of radius `outer` */
    fun inner(outer: Float, gap: Float) = maxOf(outer - gap, R_XS)
    /** the radius of a shape around a child of radius `inner` with `gap` between them */
    fun outer(inner: Float, gap: Float) = inner + gap
    /** a pill: fully round ends */
    fun pill(heightDp: Float) = heightDp / 2f
    const val CARD_RADIUS = R_S
    const val POSTER_W = 140f
    const val POSTER_H = 187f
    const val GAP = 27f
    const val ROW_TITLE_GAP = 37f
    const val TAB_TEXT = 20f        // sp
    const val TITLE_TEXT = 21f
    const val LABEL_TEXT = 17f
    const val BODY_TEXT = 19f
    const val SHELF_TITLE = 50f
    const val SHELF_SUB = 21f

    // motion
    const val T_FOCUS = 200L
    const val T_PRESS = 90L
    const val T_SCROLL = 320L
    const val T_FADE = 400L
    const val T_TAB = 250L
    const val FOCUS_SCALE = 1.12f
    val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    val DECEL = DecelerateInterpolator(1.6f)

    /** Reduce motion switch (Settings); every animation multiplies its duration by this. */
    @JvmStatic var motion = 1f
    /** Android's "animator duration scale" (the box tuning sets 0.5 to speed up other apps); our own
     *  timings divide it back out so Retro Launcher always moves exactly as designed. */
    @JvmStatic var systemScale = 1f
    fun init(ctx: Context) {
        systemScale = try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (e: Exception) { 1f }
        if (systemScale <= 0f) systemScale = 1f
    }
    fun t(ms: Long) = (ms * motion / systemScale).toLong()

    private var cachedFont: Typeface? = null
    fun font(ctx: Context, bold: Boolean = false): Typeface {
        // Inter is bundled later; until then the system sans keeps the same weights.
        return if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD)
        else cachedFont ?: Typeface.create("sans-serif-medium", Typeface.NORMAL).also { cachedFont = it }
    }

    /**
     * The no-cut-off rule: anything that grows when focused must never be clipped by the boxes around it.
     * Call before scaling a focused item: the few containers above it stop clipping their children.
     */
    fun unclip(v: android.view.View, levels: Int = 4) {
        var p = v.parent; var n = 0
        while (p is android.view.ViewGroup && n < levels) { p.clipChildren = false; p.clipToPadding = false; p = p.parent; n++ }
    }

    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun dpi(ctx: Context, v: Float) = dp(ctx, v).toInt()

    fun text(ctx: Context, sizeSp: Float, bold: Boolean = false, color: Int = TEXT): TextView = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = font(ctx, bold)
        setTextColor(color)
        includeFontPadding = false
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        // fading text must not need an off-screen buffer every frame
        if (android.os.Build.VERSION.SDK_INT >= 24) forceHasOverlappingRendering(false)
    }

    /** Soft text shadows are expensive to redraw while text fades or moves on the Mali-450, so they're only used
     *  when shadowsOn; the wallpapers' built-in darker bands keep white text readable without them. */
    @JvmStatic var shadowsOn = true   // measured: turning them off made no difference
    fun shadowText(tv: TextView) { if (shadowsOn) tv.setShadowLayer(12f, 0f, 2f, Color.argb(120, 0, 0, 0)) }
}
