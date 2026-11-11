package com.antonretro.launcher.games

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Random

/**
 * Gear & Ghost's pixel art, drawn in code once at start (no picture files), so it is sharp at any scale and
 * costs nothing per frame. Also used for the game's card on the Games shelf.
 */
object GearArt {
    const val DN = 1; const val DE = 2; const val DS = 4; const val DW = 8
    val INK = Color.rgb(28, 18, 12)
    val BRASS = Color.rgb(196, 140, 60); val BRASS_L = Color.rgb(240, 196, 108); val BRASS_D = Color.rgb(116, 72, 30)
    val IRON = Color.rgb(140, 146, 158); val IRON_L = Color.rgb(198, 204, 214); val IRON_D = Color.rgb(64, 68, 80)
    val CYAN = Color.rgb(120, 232, 255); val CYAN_D = Color.rgb(36, 140, 196); val GLOW = Color.rgb(224, 252, 255)
    val CORE_OFF = Color.rgb(58, 40, 26)
    val BOLT = Color.rgb(255, 214, 90)

    fun bmp(w: Int, h: Int, draw: (Canvas, Paint) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), Paint().apply { isAntiAlias = false; isFilterBitmap = false }); return b
    }
    private fun r(c: Canvas, p: Paint, l: Int, t: Int, rr: Int, b: Int, col: Int) { p.color = col; c.drawRect(l.toFloat(), t.toFloat(), rr.toFloat(), b.toFloat(), p) }

    /** One arm of a pipe, from the middle to an edge: ink outline, brass body, a light and a dark edge, a collar. */
    private fun arm(c: Canvas, p: Paint, d: Int, part: Int) {
        when (d) {
            DN -> when (part) { 0 -> r(c, p, 4, 0, 12, 8, INK); 1 -> { r(c, p, 5, 0, 11, 8, BRASS); r(c, p, 5, 0, 6, 8, BRASS_L); r(c, p, 10, 0, 11, 8, BRASS_D); r(c, p, 4, 1, 12, 3, BRASS_D) } }
            DS -> when (part) { 0 -> r(c, p, 4, 8, 12, 16, INK); 1 -> { r(c, p, 5, 8, 11, 16, BRASS); r(c, p, 5, 8, 6, 16, BRASS_L); r(c, p, 10, 8, 11, 16, BRASS_D); r(c, p, 4, 13, 12, 15, BRASS_D) } }
            DE -> when (part) { 0 -> r(c, p, 8, 4, 16, 12, INK); 1 -> { r(c, p, 8, 5, 16, 11, BRASS); r(c, p, 8, 5, 16, 6, BRASS_L); r(c, p, 8, 10, 16, 11, BRASS_D); r(c, p, 13, 4, 15, 12, BRASS_D) } }
            else -> when (part) { 0 -> r(c, p, 0, 4, 8, 12, INK); 1 -> { r(c, p, 0, 5, 8, 11, BRASS); r(c, p, 0, 5, 8, 6, BRASS_L); r(c, p, 0, 10, 8, 11, BRASS_D); r(c, p, 1, 4, 3, 12, BRASS_D) } }
        }
    }
    /** The energy line inside an arm: dark when off, cyan with moving bright dots when on. */
    private fun core(c: Canvas, p: Paint, d: Int, on: Boolean, phase: Int) {
        val col = if (on) CYAN else CORE_OFF
        when (d) { DN -> r(c, p, 7, 0, 9, 8, col); DS -> r(c, p, 7, 8, 9, 16, col); DE -> r(c, p, 8, 7, 16, 9, col); else -> r(c, p, 0, 7, 8, 9, col) }
        if (!on) return
        for (k in 0 until 2) {
            val o = (phase * 2 + k * 4) % 8
            when (d) { DN -> r(c, p, 7, 7 - o, 9, 8 - o, GLOW); DS -> r(c, p, 7, 8 + o, 9, 9 + o, GLOW); DE -> r(c, p, 8 + o, 7, 9 + o, 9, GLOW); else -> r(c, p, 7 - o, 7, 8 - o, 9, GLOW) }
        }
    }

    /** A pipe piece with openings in [mask] (N=1 E=2 S=4 W=8). */
    fun pipe(mask: Int, on: Boolean, phase: Int): Bitmap = bmp(16, 16) { c, p ->
        val dirs = listOf(DN, DE, DS, DW).filter { mask and it != 0 }
        for (d in dirs) arm(c, p, d, 0)
        r(c, p, 3, 3, 13, 13, INK)
        for (d in dirs) arm(c, p, d, 1)
        r(c, p, 4, 4, 12, 12, BRASS); r(c, p, 4, 4, 12, 5, BRASS_L); r(c, p, 4, 4, 5, 12, BRASS_L); r(c, p, 4, 11, 12, 12, BRASS_D); r(c, p, 11, 4, 12, 12, BRASS_D)
        for (d in dirs) core(c, p, d, on, phase)
        r(c, p, 6, 6, 10, 10, if (on) CYAN else CORE_OFF)
        if (on) r(c, p, 7, 7, 9, 9, GLOW)
    }

    /** A gear: it passes energy every way. [frame] 0..3 turns it by 11.25 degrees (an 8-tooth gear repeats every 45). */
    fun gear(on: Boolean, frame: Int, stubs: Boolean = true): Bitmap = bmp(16, 16) { c, p ->
        if (stubs) for (d in listOf(DN, DE, DS, DW)) {
            when (d) { DN -> { r(c, p, 5, 0, 11, 3, INK); r(c, p, 6, 0, 10, 2, BRASS_D) }; DS -> { r(c, p, 5, 13, 11, 16, INK); r(c, p, 6, 14, 10, 16, BRASS_D) }
                DE -> { r(c, p, 13, 5, 16, 11, INK); r(c, p, 14, 6, 16, 10, BRASS_D) }; else -> { r(c, p, 0, 5, 3, 11, INK); r(c, p, 0, 6, 2, 10, BRASS_D) } }
            if (on) when (d) { DN -> r(c, p, 7, 0, 9, 2, CYAN); DS -> r(c, p, 7, 14, 9, 16, CYAN); DE -> r(c, p, 14, 7, 16, 9, CYAN); else -> r(c, p, 0, 7, 2, 9, CYAN) }
        }
        val body = if (on) IRON else BRASS; val lite = if (on) IRON_L else BRASS_L; val dark = if (on) IRON_D else BRASS_D
        c.save(); c.rotate(frame * 11.25f, 8f, 8f)
        for (i in 0 until 8) { c.save(); c.rotate(i * 45f, 8f, 8f); p.color = INK; c.drawRect(6.5f, .5f, 9.5f, 4.5f, p); p.color = body; c.drawRect(7f, 1f, 9f, 4.5f, p); c.restore() }
        c.restore()
        p.color = INK; c.drawCircle(8f, 8f, 5.6f, p)
        p.color = body; c.drawCircle(8f, 8f, 4.6f, p)
        p.color = lite; c.drawRect(5f, 5f, 8f, 6f, p)
        p.color = dark; c.drawCircle(8f, 8f, 2.6f, p)
        p.color = if (on) CYAN else CORE_OFF; c.drawCircle(8f, 8f, 1.5f, p)
        // a spoke mark so turning is visible
        c.save(); c.rotate(frame * 11.25f, 8f, 8f); p.color = dark; c.drawRect(7.5f, 3.5f, 8.5f, 5.5f, p); c.restore()
    }

    /** The energy source: a brass box with a lightning bolt, pointing east. */
    fun source(frame: Int): Bitmap = bmp(16, 16) { c, p ->
        arm(c, p, DE, 0); arm(c, p, DE, 1); core(c, p, DE, true, frame)
        r(c, p, 1, 1, 13, 15, INK); r(c, p, 2, 2, 12, 14, BRASS_D); r(c, p, 2, 2, 12, 3, BRASS_L); r(c, p, 3, 4, 11, 13, Color.rgb(24, 16, 12))
        val col = if (frame % 2 == 0) BOLT else GLOW
        for ((x, y) in listOf(8 to 4, 7 to 5, 8 to 5, 6 to 6, 7 to 6, 5 to 7, 6 to 7, 7 to 7, 8 to 7, 9 to 7, 7 to 8, 8 to 8, 6 to 9, 7 to 9, 5 to 10, 6 to 10, 5 to 11))
            r(c, p, x, y, x + 1, y + 1, col)
    }

    /** The portal: a brass ring facing west; when awake it swirls. */
    fun portal(on: Boolean, frame: Int): Bitmap = bmp(16, 16) { c, p ->
        arm(c, p, DW, 0); arm(c, p, DW, 1); core(c, p, DW, on, frame)
        p.color = INK; c.drawOval(RectF(3f, 0f, 16f, 16f), p)
        p.color = BRASS; c.drawOval(RectF(4f, 1f, 15f, 15f), p)
        p.color = BRASS_L; c.drawRect(7f, 1f, 12f, 2f, p)
        p.color = if (on) CYAN_D else Color.rgb(16, 22, 30); c.drawOval(RectF(6f, 3f, 13f, 13f), p)
        if (on) {
            for (k in 0 until 3) {
                val a = Math.toRadians((frame * 30 + k * 120).toDouble())
                val x = 9.5f + Math.cos(a).toFloat() * 2.2f; val y = 8f + Math.sin(a).toFloat() * 3.2f
                p.color = GLOW; c.drawRect(x - .5f, y - .5f, x + 1f, y + 1f, p)
            }
            p.color = CYAN; c.drawRect(9f, 7f, 10f, 9f, p)
        } else { p.color = Color.rgb(40, 60, 76); c.drawRect(9f, 6f, 10f, 10f, p) }
    }

    /** Iron corner brackets: this pipe is bolted down and only a gear can turn it. */
    fun lock(): Bitmap = bmp(16, 16) { c, p ->
        for ((l, t) in listOf(0 to 0, 12 to 0, 0 to 12, 12 to 12)) {
            r(c, p, l, t, l + 4, t + 4, INK); r(c, p, l + 1, t + 1, l + 3, t + 3, IRON); r(c, p, l + 1, t + 1, l + 2, t + 2, IRON_L)
        }
    }

    private val KIKO = arrayOf(
        arrayOf("...oooooo...", "..owwwwwwo..", ".owwwwwwwwo.", "owwwwwwwwwwo", "owweewweewwo", "owweewweewwo", "owwwwwwwwwwo",
            "owpwwwwwwpwo", "owwwwwwwwwco", "owwwwwwwwcco", "owwwwwwwwcco", "owwoowwoowwo", ".oo..oo..oo."),
        arrayOf("...oooooo...", "..owwwwwwo..", ".owwwwwwwwo.", "owwwwwwwwwwo", "owweewweewwo", "owweewweewwo", "owwwwwwwwwwo",
            "owpwwwwwwpwo", "owwwwwwwwwco", "owwwwwwwwcco", "owwwwwwwwcco", "oowwoowwoowo", "o..oo..oo..o"))
    /** Kiko the ghost, 12x13, two frames of the wavy hem. */
    fun kiko(frame: Int): Bitmap = bmp(12, 13) { c, p ->
        for ((y, row) in KIKO[frame].withIndex()) for ((x, ch) in row.withIndex()) {
            val col = when (ch) { 'o' -> Color.rgb(40, 56, 84); 'w' -> Color.rgb(236, 248, 255); 'c' -> Color.rgb(160, 214, 244); 'e' -> Color.rgb(18, 22, 40); 'p' -> Color.rgb(250, 160, 190); else -> 0 }
            if (col != 0) r(c, p, x, y, x + 1, y + 1, col)
        }
    }

    private val STAR = arrayOf("....o....", "...oxo...", "...oxo...", "ooooxoooo", "oxxxxxxxo", ".oxxxxxo.", ".oxxoxxo.", "oxxo.oxxo", "ooo...ooo")
    fun star(lit: Boolean): Bitmap = bmp(9, 9) { c, p ->
        for ((y, row) in STAR.withIndex()) for ((x, ch) in row.withIndex()) {
            val col = when (ch) { 'o' -> INK; 'x' -> if (lit) BOLT else Color.rgb(70, 56, 44); else -> 0 }
            if (col != 0) r(c, p, x, y, x + 1, y + 1, col)
        }
    }

    /** The board floor for one level: worn stone tiles; the two edge columns are darker iron plates. */
    fun floor(cols: Int, rows: Int, seed: Int): Bitmap = bmp(cols * 16, rows * 16) { c, p ->
        val rnd = Random(seed.toLong())
        for (y in 0 until rows) for (x in 0 until cols) {
            val l = x * 16; val t = y * 16; val edge = x == 0 || x == cols - 1
            val base = when { edge -> Color.rgb(30, 30, 38); (x + y) % 2 == 0 -> Color.rgb(44, 38, 46); else -> Color.rgb(40, 35, 43) }
            r(c, p, l, t, l + 16, t + 16, base)
            r(c, p, l, t, l + 16, t + 1, if (edge) Color.rgb(44, 44, 54) else Color.rgb(56, 50, 60))
            r(c, p, l, t, l + 1, t + 16, if (edge) Color.rgb(40, 40, 50) else Color.rgb(52, 46, 56))
            r(c, p, l, t + 15, l + 16, t + 16, Color.rgb(22, 20, 26)); r(c, p, l + 15, t, l + 16, t + 16, Color.rgb(24, 22, 28))
            if (edge) for ((rx, ry) in listOf(2 to 2, 13 to 2, 2 to 13, 13 to 13)) r(c, p, l + rx, t + ry, l + rx + 1, t + ry + 1, Color.rgb(70, 72, 84))
            else repeat(3) { val sx = 2 + rnd.nextInt(12); val sy = 2 + rnd.nextInt(12); r(c, p, l + sx, t + sy, l + sx + 1, t + sy + 1, Color.rgb(34, 30, 36)) }
        }
    }

    /** The workshop behind everything: brick wall, shelves with bottles, a pipe along the top, a plank floor. */
    fun scene(): Bitmap = bmp(320, 180) { c, p ->
        val rnd = Random(7)
        r(c, p, 0, 0, 320, 180, Color.rgb(30, 20, 18))
        for (row in 0 until 20) {
            val y = row * 8; val off = if (row % 2 == 0) 0 else 8
            var x = -off
            while (x < 320) {
                val v = rnd.nextInt(10)
                r(c, p, x + 1, y + 1, x + 16, y + 8, Color.rgb(58 + v, 36 + v / 2, 30 + v / 2))
                r(c, p, x + 1, y + 1, x + 16, y + 2, Color.rgb(70 + v, 46 + v / 2, 38))
                x += 16
            }
        }
        // a warm light falloff: darker towards the edges
        for (i in 0 until 6) { p.color = Color.argb(26, 0, 0, 0); c.drawRect(0f, 0f, (6 - i) * 8f, 180f, p); c.drawRect(320f - (6 - i) * 8f, 0f, 320f, 180f, p) }
        // pipe along the top
        r(c, p, 0, 2, 320, 9, INK); r(c, p, 0, 3, 320, 8, IRON_D); r(c, p, 0, 3, 320, 4, IRON)
        for (x in 20 until 320 step 60) { r(c, p, x, 1, x + 5, 10, INK); r(c, p, x + 1, 2, x + 4, 9, BRASS_D) }
        // shelves with bottles (left) and big dark gears (right)
        for (sy in listOf(46, 92)) {
            r(c, p, 6, sy, 58, sy + 4, INK); r(c, p, 7, sy, 57, sy + 3, Color.rgb(110, 66, 32))
            var bx = 10
            while (bx < 52) {
                val h = 8 + rnd.nextInt(8); val col = listOf(Color.rgb(90, 200, 160), Color.rgb(180, 90, 200), Color.rgb(230, 170, 70), Color.rgb(90, 140, 230))[rnd.nextInt(4)]
                r(c, p, bx, sy - h, bx + 6, sy, INK); r(c, p, bx + 1, sy - h + 3, bx + 5, sy, col); r(c, p, bx + 2, sy - h, bx + 4, sy - h + 3, Color.rgb(150, 120, 90)); r(c, p, bx + 1, sy - h + 4, bx + 2, sy - 1, GLOW)
                bx += 8 + rnd.nextInt(3)
            }
        }
        p.isAntiAlias = false
        for ((gx, gy, rad) in listOf(Triple(282, 50, 26), Triple(250, 92, 16), Triple(296, 128, 20))) {
            p.color = Color.rgb(40, 28, 24)
            for (i in 0 until 10) { c.save(); c.rotate(i * 36f, gx.toFloat(), gy.toFloat()); c.drawRect(gx - 3f, gy - rad - 4f, gx + 3f, gy.toFloat(), p); c.restore() }
            c.drawCircle(gx.toFloat(), gy.toFloat(), rad.toFloat(), p)
            p.color = Color.rgb(30, 20, 18); c.drawCircle(gx.toFloat(), gy.toFloat(), rad * .4f, p)
        }
        // plank floor
        r(c, p, 0, 156, 320, 180, Color.rgb(64, 40, 22))
        for (y in 156 until 180 step 6) r(c, p, 0, y, 320, y + 1, Color.rgb(40, 24, 14))
        for (i in 0 until 14) { val x = rnd.nextInt(320); val y = 157 + rnd.nextInt(4) * 6; r(c, p, x, y, x + 1, y + 5, Color.rgb(40, 24, 14)) }
    }
}
