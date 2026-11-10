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
    const val CARD_RADIUS = 13f
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

    fun shadowText(tv: TextView) = tv.setShadowLayer(12f, 0f, 2f, Color.argb(120, 0, 0, 0))
}
