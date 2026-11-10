package com.antonretro.launcher.kit

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Wallpapers drawn in code at the exact screen size (always sharp), plus one small
 * pre-blurred copy used for every glass panel. No live blur anywhere.
 */
object Backdrop {
    class Scene(val name: String, val skyTop: Int, val skyBottom: Int, val sun: Int,
                val hill1: Int, val hill2: Int, val hill3: Int, val stars: Boolean, val sunX: Float)

    val SCENES = listOf(
        Scene("Dusk", 0xFF0E1A3A.toInt(), 0xFFC86B5A.toInt(), 0xFFFFB07A.toInt(), 0xFF5A3A5C.toInt(), 0xFF33264A.toInt(), 0xFF1A1530.toInt(), false, 0.70f),
        Scene("Night lake", 0xFF020617.toInt(), 0xFF0F3B57.toInt(), 0xFF9BE7FF.toInt(), 0xFF0F2C40.toInt(), 0xFF0A1F2E.toInt(), 0xFF061219.toInt(), true, 0.31f),
        Scene("Harvest", 0xFF1B2A1F.toInt(), 0xFFD9A35B.toInt(), 0xFFFFE1A0.toInt(), 0xFF5D6B3A.toInt(), 0xFF3B4A2A.toInt(), 0xFF1F2A18.toInt(), false, 0.78f),
    )

    var sharp: Bitmap? = null; private set
    var blurred: Bitmap? = null; private set
    var screenW = 1280; private set
    var screenH = 720; private set

    fun build(index: Int, w: Int, h: Int) {
        screenW = w; screenH = h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), SCENES[index % SCENES.size], w.toFloat(), h.toFloat())
        sharp = bmp
        // glass source: shrink 8x, blur a few passes, keep it small; drawn scaled with filtering
        val sw = w / 8; val sh = h / 8
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        blurred = boxBlur(boxBlur(boxBlur(small, 3), 3), 3)
    }

    private fun draw(c: Canvas, s: Scene, w: Float, h: Float) {
        val k = w / 1920f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(s.skyTop, s.skyBottom, s.skyBottom), floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        if (s.stars) {
            p.shader = null
            for (i in 0 until 90) {
                p.color = Color.argb((255 * (.25f + (i % 5) / 9f)).toInt(), 255, 255, 255)
                c.drawCircle(((i * 733) % 1920) * k, ((i * 397) % 520) * k, ((i % 3) * .7f + .6f) * k * 1.5f, p)
            }
        }
        val cx = s.sunX * w; val cy = 560 * k
        p.shader = RadialGradient(cx, cy, 520 * k, intArrayOf(withA(s.sun, 242), withA(s.sun, 140), withA(s.sun, 0)), floatArrayOf(0f, .25f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, 520 * k, p)
        p.shader = null; p.color = withA(s.sun, 230)
        c.drawCircle(cx, cy, 70 * k, p)
        fun hill(color: Int, pts: FloatArray) {
            val path = Path(); path.moveTo(0f, pts[0] * k)
            var i = 1
            while (i + 5 < pts.size) { path.cubicTo(pts[i] * k, pts[i + 1] * k, pts[i + 2] * k, pts[i + 3] * k, pts[i + 4] * k, pts[i + 5] * k); i += 6 }
            path.lineTo(w, h); path.lineTo(0f, h); path.close()
            p.color = color; c.drawPath(path, p)
        }
        hill(s.hill1, floatArrayOf(690f, 220f, 600f, 380f, 640f, 560f, 590f, 760f, 530f, 900f, 640f, 1100f, 600f, 1300f, 560f, 1500f, 520f, 1700f, 590f, 1800f, 620f, 1880f, 600f, 1920f, 610f))
        hill(s.hill2, floatArrayOf(800f, 260f, 720f, 460f, 780f, 700f, 730f, 940f, 680f, 1120f, 790f, 1380f, 740f, 1600f, 700f, 1760f, 760f, 1920f, 720f))
        hill(s.hill3, floatArrayOf(920f, 300f, 860f, 520f, 930f, 820f, 880f, 1100f, 840f, 1400f, 940f, 1700f, 880f, 1820f, 860f, 1880f, 870f, 1920f, 860f))
        // fixed bottom darkening so white text always reads
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, h * .45f, 0f, h, Color.argb(0, 0, 0, 0), Color.argb(140, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * .45f, w, h, p)
        p.shader = LinearGradient(0f, 0f, 0f, h * .22f, Color.argb(76, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * .22f, p)
    }

    private fun withA(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    /** Simple separable box blur on a small bitmap (runs once per wallpaper change). */
    private fun boxBlur(src: Bitmap, r: Int): Bitmap {
        val w = src.width; val h = src.height
        val a = IntArray(w * h); src.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h)
        fun pass(inp: IntArray, out: IntArray, horiz: Boolean) {
            val n = if (horiz) w else h; val m = if (horiz) h else w
            for (j in 0 until m) {
                for (i in 0 until n) {
                    var rr = 0; var gg = 0; var bb = 0; var cnt = 0
                    for (d in -r..r) {
                        val ii = (i + d).coerceIn(0, n - 1)
                        val px = if (horiz) inp[j * w + ii] else inp[ii * w + j]
                        rr += (px shr 16) and 255; gg += (px shr 8) and 255; bb += px and 255; cnt++
                    }
                    val idx = if (horiz) j * w + i else i * w + j
                    out[idx] = (0xFF shl 24) or ((rr / cnt) shl 16) or ((gg / cnt) shl 8) or (bb / cnt)
                }
            }
        }
        pass(a, b, true); pass(b, a, false)
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Frosted glass made from the pre-blurred wallpaper: the blurred copy is drawn under the
 * panel at the panel's own screen position, then a dark tint and a faint light edge.
 */
class GlassDrawable(private val host: View, private val radius: Float, private val tint: Int = Kit.GLASS_TINT) : Drawable() {
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Kit.GLASS_EDGE }
    private val m = Matrix(); private val loc = IntArray(2); private val rect = RectF()
    private var shaderFor: Bitmap? = null

    override fun draw(canvas: Canvas) {
        val bl = Backdrop.blurred
        rect.set(bounds)
        if (bl != null) {
            if (shaderFor !== bl) { shaderPaint.shader = BitmapShader(bl, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaderFor = bl }
            host.getLocationOnScreen(loc)
            val sx = Backdrop.screenW.toFloat() / bl.width; val sy = Backdrop.screenH.toFloat() / bl.height
            m.reset(); m.setScale(sx, sy); m.postTranslate(-loc[0].toFloat() + 0f, -loc[1].toFloat())
            shaderPaint.shader.setLocalMatrix(m)
            canvas.drawRoundRect(rect, radius, radius, shaderPaint)
        }
        canvas.drawRoundRect(rect, radius, radius, tintPaint)
        rect.inset(.5f, .5f)
        canvas.drawRoundRect(rect, radius, radius, edge)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
