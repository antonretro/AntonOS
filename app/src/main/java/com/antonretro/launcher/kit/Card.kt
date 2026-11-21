package com.antonretro.launcher.kit

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** What a card shows. Built once (in the background) and cached. */
class Art(val bitmap: Bitmap?, val top: Int, val bottom: Int, val glyph: Bitmap? = null, val caption: String? = null,
          val bigText: String? = null, val badge: String? = null, val badgeColor: Int = 0xFFE5383B.toInt(), val star: Boolean = false,
          /** show the whole picture (box art) instead of cropping it to fill the card */ val fit: Boolean = false,
          /** cover art (game boxes, song covers, posters): shown whole in its own proportions, standing on the card's bottom line, with the small cover radius */ val cover: Boolean = false)

/**
 * The one card: 16:9 (or poster) rounded rectangle with art, a soft pre-rendered shadow,
 * and a name that fades in underneath. Focus = scale 1.12 + lift + light edge, 200 ms.
 * Only scale, position and alpha are animated; the card itself never redraws per frame.
 */
class CardCell(ctx: Context, val wDp: Float, val hDp: Float, val alwaysLabel: Boolean) : FrameLayout(ctx) {
    val card = CardFace(ctx)
    private val shadow = ImageView(ctx)
    /** Focus sheen + light edge as a separate, pre-drawn layer: focusing never re-renders the art. */
    val ring = View(ctx)
    val label: TextView = Kit.text(ctx, Kit.LABEL_TEXT, bold = true)
    private val cw = Kit.dpi(ctx, wDp); private val ch = Kit.dpi(ctx, hDp)
    var focused = false; private set

    init {
        clipChildren = false; clipToPadding = false
        val pad = Kit.dpi(ctx, 22f)
        shadow.setImageBitmap(Shadows.get(ctx, cw, ch))
        shadow.alpha = 0f
        addView(shadow, LayoutParams(cw + pad * 2, ch + pad * 2).apply { leftMargin = -pad; topMargin = -pad + Kit.dpi(ctx, 8f) })
        addView(card, LayoutParams(cw, ch))
        ring.background = FocusRing({ Kit.dp(ctx, if (card.art?.cover == true) Kit.coverRadius else Kit.CARD_RADIUS) }, { card.coverBox })
        ring.alpha = 0f
        addView(ring, LayoutParams(cw, ch))
        ring.pivotX = cw / 2f; ring.pivotY = ch / 2f
        label.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        label.alpha = if (alwaysLabel) .62f else 0f
        label.isSingleLine = true; label.marqueeRepeatLimit = -1; label.isHorizontalFadingEdgeEnabled = true; label.setFadingEdgeLength(Kit.dpi(ctx, 14f))   // a long name scrolls while its card is focused (as on Switch)
        Kit.shadowText(label)
        // fixed height: a wrap-content label would be squeezed to the card's own height and clipped
        addView(label, LayoutParams(cw + Kit.dpi(ctx, 24f), Kit.dpi(ctx, 28f)).apply { leftMargin = -Kit.dpi(ctx, 12f); topMargin = ch + Kit.dpi(ctx, 11f) })
        card.pivotX = cw / 2f; card.pivotY = ch / 2f
        shadow.pivotX = (cw + pad * 2) / 2f; shadow.pivotY = (ch + pad * 2) / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(cw, ch)   // the grid spaces by card size; label and shadow overflow
    }

    fun setFocus(on: Boolean, animate: Boolean = true) {
        if (on == focused) return
        focused = on
        val d = if (animate) Kit.t(Kit.T_FOCUS) else 0L
        val s = if (on) Kit.FOCUS_SCALE else 1f
        val lift = if (on) -Kit.dp(context, 4f) else 0f
        card.animate().scaleX(s).scaleY(s).translationY(lift).setDuration(d).setInterpolator(Kit.EASE).start()
        shadow.animate().scaleX(s).scaleY(s).translationY(lift).alpha(if (on && card.fillsCell) 1f else 0f).setDuration(d).setInterpolator(Kit.EASE).start()
        ring.animate().scaleX(s).scaleY(s).translationY(lift).alpha(if (on) 1f else 0f).setDuration(d).setInterpolator(Kit.EASE).start()
        label.ellipsize = if (on) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END; label.isSelected = on
        label.animate().alpha(if (on) 1f else if (alwaysLabel) .62f else 0f).translationY(if (on) Kit.dp(context, 4f) else 0f).setDuration(d).start()
        translationZ = if (on) 2f else 0f   // draw above neighbours without reordering the row
    }

    /** The little dip when OK is pressed. */
    fun press(then: () -> Unit) {
        ring.animate().scaleX(1.04f).scaleY(1.04f).setDuration(Kit.t(Kit.T_PRESS)).start()
        card.animate().scaleX(1.04f).scaleY(1.04f).setDuration(Kit.t(Kit.T_PRESS)).withEndAction {
            card.animate().scaleX(Kit.FOCUS_SCALE).scaleY(Kit.FOCUS_SCALE).setDuration(Kit.t(Kit.T_PRESS)).start()
            ring.animate().scaleX(Kit.FOCUS_SCALE).scaleY(Kit.FOCUS_SCALE).setDuration(Kit.t(Kit.T_PRESS)).start()
            then()
        }.start()
    }
}

class CardFace(ctx: Context) : View(ctx) {
    var art: Art? = null; set(v) { field = v; trim = v?.takeIf { it.cover }?.bitmap?.let { Covers.trim(it) }; layoutCover(); invalidate(); invalidateOutline(); (parent as? CardCell)?.ring?.invalidate() }
    /** the part of a cover picture worth showing: dark scan borders around box art cut away */
    private var trim: Rect? = null
    /** where the cover is drawn inside the card, or null for ordinary (full-card) art */
    var coverBox: RectF? = null; private set
    val fillsCell: Boolean get() = coverBox.let { it == null || (it.width() >= width - 1 && it.height() >= height - 1) }
    private val coverSrc = Rect()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutCover(); invalidateOutline() }
    /**
     * Covers fill the whole card when their shape is near the console's box (squeezed a little, cropped a sliver), otherwise they keep their own
     * proportions standing on the bottom line, and the clip, focus ring and shadow follow the picture, never the empty
     * cell: no black or light lines around box art (Anthony: "correctly zoomed ... you don't see any black lines").
     */
    private fun layoutCover() {
        val a = art; val t = trim; val w = width.toFloat(); val h = height.toFloat()
        if (a == null || !a.cover || a.bitmap == null || t == null || w <= 0f || h <= 0f) { coverBox = null; return }
        val ar = t.width().toFloat() / t.height(); val cr = w / h
        val ratio = ar / cr
        if (ratio in .85f..1.18f) {
            // close to the console's usual box: every cover on a shelf gets the same shape (Pac-Man as tall as Mario).
            // Up to 6% is taken by gently squeezing the picture, which the eye doesn't notice; only the rest is
            // trimmed from the edges, so at most a sliver of the box is lost
            val srcAr = cr * ratio.coerceIn(1f / 1.06f, 1.06f)
            if (ar > srcAr) { val sw = (t.height() * srcAr).toInt(); coverSrc.set(t.centerX() - sw / 2, t.top, t.centerX() - sw / 2 + sw, t.bottom) }
            else { val sh = (t.width() / srcAr).toInt(); coverSrc.set(t.left, t.centerY() - sh / 2, t.right, t.centerY() - sh / 2 + sh) }
            coverBox = RectF(0f, 0f, w, h)
        } else {
            coverSrc.set(t); val fs = minOf(w / t.width(), h / t.height())
            val bw = Math.round(t.width() * fs).toFloat(); val bh = Math.round(t.height() * fs).toFloat()
            val left = Math.round((w - bw) / 2).toFloat()
            coverBox = RectF(left, h - bh, left + bw, h)
        }
    }
    var focusLook = false; set(v) { field = v; invalidate() }
    private val radius = Kit.dp(ctx, Kit.CARD_RADIUS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Kit.font(ctx, true); textSize = Kit.dp(ctx, 17f) }
    private val r = RectF(); private val src = Rect()
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Kit.font(ctx, true) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Kit.font(ctx, true) }

    /** Channel name as the picture when there's no logo: up to two centred lines. */
    private fun drawBig(c: Canvas, t: String, w: Float, h: Float) {
        big.textSize = Kit.dp(context, 22f)
        val words = t.split(' '); val lines = ArrayList<String>(); var cur = ""
        for (wd in words) { val n = if (cur.isEmpty()) wd else "$cur $wd"; if (big.measureText(n) > w - 28 && cur.isNotEmpty()) { lines.add(cur); cur = wd } else cur = n }
        if (cur.isNotEmpty()) lines.add(cur)
        val shown = lines.take(2).toMutableList()
        if (lines.size > 2) shown[1] = shown[1] + "…"
        for (i in shown.indices) while (big.measureText(shown[i]) > w - 20 && shown[i].length > 3) shown[i] = shown[i].dropLast(2) + "…"
        val lh = big.textSize * 1.12f; val y0 = h / 2 - (shown.size - 1) * lh / 2 + big.textSize * .36f
        for ((i, s) in shown.withIndex()) c.drawText(s, (w - big.measureText(s)) / 2, y0 + i * lh, big)
    }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val b = coverBox
                if (b != null) outline.setRoundRect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(), Kit.dp(context, Kit.coverRadius))
                else outline.setRoundRect(0, 0, view.width, view.height, if (art?.cover == true) Kit.dp(context, Kit.coverRadius) else radius)
            }
        }
        clipToOutline = true
        // no per-card graphics layer: on the Mali-450 drawing the card directly is cheaper than keeping
        // ~100 layers in graphics memory (36 MB) and compositing them
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); r.set(0f, 0f, w, h)
        val a = art
        // a game box: no card colour behind it, the whole box in its own proportions, standing on the bottom line
        val box = coverBox
        if (a != null && a.cover && a.bitmap != null && box != null) {
            c.drawBitmap(a.bitmap, coverSrc, box, paint)
            // the same faint glass edge as every other card, on the picture itself
            edge.strokeWidth = 1f; edge.color = Color.argb(28, 255, 255, 255)
            r.set(box); r.inset(.5f, .5f); val cr = Kit.dp(context, Kit.coverRadius); c.drawRoundRect(r, cr, cr, edge)
            return
        }
        paint.shader = LinearGradient(0f, 0f, w, h, a?.top ?: 0xFF2A2E38.toInt(), a?.bottom ?: 0xFF15171F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(r, paint); paint.shader = null
        a?.bitmap?.let { b ->   // banner: centre-crop to fill; box art: whole box, centred
            if (a.fit) {
                val fs = minOf((w - 8f) / b.width, (h - 8f) / b.height)
                val bw = b.width * fs; val bh = b.height * fs
                c.drawBitmap(b, null, RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), paint)
                return@let
            }
            val bs = maxOf(w / b.width, h / b.height)
            val sw = (w / bs).toInt(); val sh = (h / bs).toInt()
            src.set((b.width - sw) / 2, (b.height - sh) / 2, (b.width + sw) / 2, (b.height + sh) / 2)
            c.drawBitmap(b, src, r, paint)
        }
        a?.glyph?.let { g ->
            val gs = minOf(h * (if (a.caption != null) .46f else .56f) / g.height, w * .72f / g.width)
            val gw = g.width * gs; val gh = g.height * gs
            val top = if (a.caption != null) h * .2f else (h - gh) / 2
            c.drawBitmap(g, null, RectF((w - gw) / 2, top, (w + gw) / 2, top + gh), paint)
        }
        a?.caption?.let { cap ->
            var t = cap; while (text.measureText(t) > w - 24 && t.length > 4) t = t.dropLast(2) + "…"
            c.drawText(t, (w - text.measureText(t)) / 2, h - Kit.dp(context, 14f), text)
        }
        a?.bigText?.let { t -> drawBig(c, t, w, h) }
        a?.badge?.let { b ->
            small.textSize = Kit.dp(context, 11f)
            val bw = small.measureText(b) + Kit.dp(context, 12f); val bh = Kit.dp(context, 18f); val m = Kit.dp(context, 8f)
            paint.color = a.badgeColor; c.drawRoundRect(RectF(m, m, m + bw, m + bh), 5f, 5f, paint)
            c.drawText(b, m + Kit.dp(context, 6f), m + bh - Kit.dp(context, 5f), small)
        }
        if (a?.star == true) { small.textSize = Kit.dp(context, 17f); small.color = 0xFFFFD166.toInt(); c.drawText("★", w - Kit.dp(context, 24f), Kit.dp(context, 24f), small); small.color = Color.WHITE }
        edge.strokeWidth = 1f
        edge.color = Color.argb(28, 255, 255, 255)
        r.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
        c.drawRoundRect(r, radius, radius, edge)
    }
}

class FocusRing(private val radiusOf: () -> Float, private val boxOf: () -> RectF? = { null }) : android.graphics.drawable.Drawable() {
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG); private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.argb(150, 255, 255, 255) }
    private val r = RectF()
    override fun draw(c: Canvas) {
        r.set(bounds); boxOf()?.let { r.set(it) }; val radius = radiusOf()
        sheen.shader = LinearGradient(0f, 0f, r.width() * .6f, r.height() * .6f, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, sheen)
        r.inset(1.25f, 1.25f); c.drawRoundRect(r, radius, radius, edge)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Box art from scans often carries black (or near-black) borders: find the picture inside them. */
object Covers {
    private val cache = java.util.WeakHashMap<Bitmap, Rect>()
    fun trim(b: Bitmap): Rect = synchronized(cache) { cache[b] } ?: find(b).also { synchronized(cache) { cache[b] = it } }
    private fun dark(px: Int) = Color.red(px) < 28 && Color.green(px) < 28 && Color.blue(px) < 28 || Color.alpha(px) < 40
    /** an edge line counts as border only when it is all dark (a line or two of noise allowed): the thin tops of a
     *  title on a black cover (Micro Mages) are art, not border */
    private fun border(line: IntArray) = line.count { !dark(it) } <= line.size / 250
    private fun find(b: Bitmap): Rect {
        val w = b.width; val h = b.height; val maxX = w / 8; val maxY = h / 8
        val row = IntArray(w); val col = IntArray(h)
        var top = 0; while (top < maxY) { b.getPixels(row, 0, w, 0, top, w, 1); if (!border(row)) break; top++ }
        var bottom = h; while (h - bottom < maxY) { b.getPixels(row, 0, w, 0, bottom - 1, w, 1); if (!border(row)) break; bottom-- }
        var left = 0; while (left < maxX) { b.getPixels(col, 0, 1, left, 0, 1, h); if (!border(col)) break; left++ }
        var right = w; while (w - right < maxX) { b.getPixels(col, 0, 1, right - 1, 0, 1, h); if (!border(col)) break; right-- }
        // one more pixel in where anything was cut: the anti-aliased seam is often a dark grey line
        // a scan border goes round the picture; a dark band on one side only (the black top of an NES box) is art
        if ((top > 0) != (bottom < h)) { top = 0; bottom = h }
        if ((left > 0) != (right < w)) { left = 0; right = w }
        return if (right - left > w / 2 && bottom - top > h / 2) Rect(left, top, right, bottom) else Rect(0, 0, w, h)
    }
}

/** Soft shadows are rendered once per card size and reused (never drawn per frame). */
object Shadows {
    private val cache = HashMap<Long, Bitmap>()
    fun get(ctx: Context, w: Int, h: Int): Bitmap = cache.getOrPut(w.toLong() shl 32 or h.toLong()) {
        val pad = Kit.dpi(ctx, 22f)
        val b = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0); maskFilter = BlurMaskFilter(pad * .7f, BlurMaskFilter.Blur.NORMAL) }
        Canvas(b).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (w + pad).toFloat(), (h + pad).toFloat()), Kit.dp(ctx, Kit.CARD_RADIUS), Kit.dp(ctx, Kit.CARD_RADIUS), p)
        b
    }
}

/** Turns an app's banner or icon into card art: banner if sharp enough, else icon on its own colours. */
object ArtMaker {
    fun fromBanner(d: Drawable, wPx: Int, hPx: Int): Art? {
        val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
        if (iw < wPx * 0.7f || ih <= 0) return null   // too small: would look blurry
        val b = toBitmap(d, wPx, hPx)   // exactly the size shown: a 2x copy costs four times the memory
        return Art(b, 0xFF222222.toInt(), 0xFF111111.toInt())
    }

    fun fromIcon(d: Drawable?, caption: String?, hPx: Int): Art {
        if (d == null) return Art(null, 0xFF3A3F55.toInt(), 0xFF15171F.toInt(), null, caption)
        val size = (hPx * 1.2f).toInt().coerceAtLeast(96)
        val g = toBitmap(d, size, size)
        val (top, bottom) = colours(g)
        return Art(null, top, bottom, g, caption)
    }

    private fun toBitmap(d: Drawable, w: Int, h: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null && d.bitmap.width == w && d.bitmap.height == h) return d.bitmap
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val old = d.copyBounds(); d.setBounds(0, 0, w, h); d.draw(c); d.bounds = old
        return b
    }

    /** Main colours of an icon: most common saturated hue, made deep enough for white text. */
    private fun colours(b: Bitmap): Pair<Int, Int> {
        val s = Bitmap.createScaledBitmap(b, 24, 24, true)
        val buckets = IntArray(12); val sat = FloatArray(12); val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val px = s.getPixel(x, y); if (Color.alpha(px) < 128) continue
            Color.colorToHSV(px, hsv); if (hsv[1] < .25f || hsv[2] < .2f) continue
            val i = (hsv[0] / 30f).toInt().coerceIn(0, 11); buckets[i]++; sat[i] += hsv[1]
        }
        val best = buckets.indices.maxByOrNull { buckets[it] } ?: 0
        if (buckets[best] < 6) return 0xFF4A5060.toInt() to 0xFF1C1F27.toInt()
        val hue = best * 30f + 15f
        val top = Color.HSVToColor(floatArrayOf(hue, (sat[best] / buckets[best]).coerceIn(.45f, .8f), .62f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 18f) % 360f, .75f, .26f))
        return top to bottom
    }
}

/** Animates a float with the kit's curve; used for scroll and crossfades. */
fun animateFloat(from: Float, to: Float, ms: Long, update: (Float) -> Unit): ValueAnimator =
    ValueAnimator.ofFloat(from, to).apply { duration = Kit.t(ms); interpolator = Kit.EASE; addUpdateListener { update(it.animatedValue as Float) }; start() }
