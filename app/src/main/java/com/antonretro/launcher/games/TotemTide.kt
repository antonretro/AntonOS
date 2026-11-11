package com.antonretro.launcher.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.antonretro.launcher.kit.Kit
import kotlin.random.Random

/**
 * Totem Tide: a rising-stack swap puzzle with carved tiki masks (original game and art).
 * Swap two neighbouring masks sideways; three or more in a row or column clear; masks above fall, and a clear
 * made by falling masks is a chain (worth more). The stack rises steadily, a little faster every 30 seconds;
 * RB / Channel Up raises it at once. When the top row is full and the stack must rise, the game is over.
 */
class TotemTide(ctx: Context) : PixelGame(ctx, "tiki") {
    private val cols = 6; private val rows = 12; private val t = 14
    private val bx = 118; private val by = 4                       // board position on the 320x180 canvas
    private val sheet: Bitmap = bitmap("tiles.png")
    private val src = Rect(); private val dstR = Rect()
    private val p = Paint()

    private val grid = Array(rows) { IntArray(cols) { -1 } }
    private val clearing = Array(rows) { IntArray(cols) }          // frames left of the flash before a mask vanishes
    private val chainy = Array(rows) { BooleanArray(cols) }        // fell because something below cleared
    private var incoming = IntArray(cols)
    private var rise = 0f; private var speed = 0f
    private var cx = 2; private var cy = 8
    private var score = 0; private var chain = 1; private var frames = 0
    private var fallTick = 0; private var dangerTimer = 0; private var over = false
    private var popup = ""; private var popupT = 0
    private val rnd = Random(System.nanoTime())
    private val bg: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)

    init {
        for (s in listOf("swap", "clear", "chain", "raise", "land", "danger", "over")) loadSound(s)
        paintBackground()
        restart()
    }

    // ------------------------------------------------------------------ set-up
    override fun restart() {
        for (r in 0 until rows) { grid[r].fill(-1); clearing[r].fill(0); chainy[r].fill(false) }
        for (r in rows - 6 until rows) for (c in 0 until cols) grid[r][c] = pick(r, c)
        incoming = newRow()
        rise = 0f; speed = 14f / (60 * 9f); cx = 2; cy = rows - 4
        score = 0; chain = 1; frames = 0; over = false; dangerTimer = 0; popupT = 0
    }

    /** A mask that doesn't make three in a row with the ones left of it and above it. */
    private fun pick(r: Int, c: Int): Int {
        while (true) {
            val v = rnd.nextInt(6)
            if (c >= 2 && grid[r][c - 1] == v && grid[r][c - 2] == v) continue
            if (r >= 2 && grid[r - 1][c] == v && grid[r - 2][c] == v) continue
            return v
        }
    }
    private fun newRow(): IntArray {
        val row = IntArray(cols)
        for (c in 0 until cols) {
            var v: Int
            do { v = rnd.nextInt(6) } while ((c >= 2 && row[c - 1] == v && row[c - 2] == v) || grid[rows - 1][c] == v && grid[rows - 2][c] == v)
            row[c] = v
        }
        return row
    }

    private fun paintBackground() {
        val g = Canvas(bg)
        for (y in 0 until H) { val k = y / H.toFloat(); p.color = Color.rgb((12 + 10 * k).toInt(), (34 + 20 * k).toInt(), (40 + 10 * k).toInt()); g.drawRect(0f, y.toFloat(), W.toFloat(), y + 1f, p) }
        val r = Random(7)
        p.color = Color.rgb(200, 230, 220)
        repeat(40) { val x = r.nextInt(W).toFloat(); val y = r.nextInt(70).toFloat(); p.alpha = 80 + r.nextInt(150); g.drawRect(x, y, x + 1, y + 1, p) }
        p.alpha = 255
        // palm-leaf silhouettes along the sides
        p.color = Color.rgb(8, 22, 24)
        for (i in 0 until 9) { val y = 70 + i * 13; g.drawRect(0f, y.toFloat(), (30 + (i % 3) * 14).toFloat(), y + 6f, p); g.drawRect((W - 30 - (i % 2) * 18).toFloat(), y + 4f, W.toFloat(), y + 10f, p) }
        // carved wood frame around the board
        val l = bx - 4; val tp = by - 2; val rr = bx + cols * t + 4; val bt = by + rows * t + 2
        p.color = Color.rgb(92, 52, 26); g.drawRect(l.toFloat(), tp.toFloat(), rr.toFloat(), bt.toFloat(), p)
        p.color = Color.rgb(140, 86, 44); g.drawRect(l + 1f, tp + 1f, rr - 1f, tp + 2f, p)
        p.color = Color.rgb(48, 24, 10); for (y in tp + 6 until bt step 9) { g.drawRect(l + 1f, y.toFloat(), l + 3f, y + 1f, p); g.drawRect(rr - 3f, y.toFloat(), rr - 1f, y + 1f, p) }
        p.color = Color.rgb(16, 20, 22); g.drawRect(bx.toFloat(), by.toFloat(), (bx + cols * t).toFloat(), (by + rows * t).toFloat(), p)
    }

    // ------------------------------------------------------------------ rules
    override fun update() {
        if (over) return
        frames++
        var anyClearing = false
        for (r in 0 until rows) for (c in 0 until cols) if (clearing[r][c] > 0) {
            anyClearing = true
            if (--clearing[r][c] == 0) { grid[r][c] = -1; for (u in 0 until r) if (grid[u][c] >= 0) chainy[u][c] = true }
        }
        // gravity: a mask with an empty space under it drops one row every 3 steps
        var fell = false
        if (++fallTick >= 3) {
            fallTick = 0
            for (r in rows - 2 downTo 0) for (c in 0 until cols)
                if (grid[r][c] >= 0 && clearing[r][c] == 0 && grid[r + 1][c] < 0) {
                    grid[r + 1][c] = grid[r][c]; chainy[r + 1][c] = chainy[r][c]; grid[r][c] = -1; chainy[r][c] = false; fell = true
                }
            if (fell) play("land", .25f)
        }
        val matched = findMatches()
        if (!anyClearing && !fell && !matched && !falling()) { chain = 1; for (r in 0 until rows) chainy[r].fill(false) }

        // the stack rises, a little faster every 30 seconds; it waits while masks are clearing
        if (frames % (60 * 30) == 0) speed *= 1.15f
        if (!anyClearing) rise += speed
        if (rise >= t) pushRow()
        val danger = (0 until cols).any { grid[0][it] >= 0 }
        if (danger && frames % 90 == 0) play("danger", .35f)
        if (popupT > 0) popupT--
    }

    private fun falling(): Boolean { for (r in 0 until rows - 1) for (c in 0 until cols) if (grid[r][c] >= 0 && grid[r + 1][c] < 0) return true; return false }

    /** Marks runs of three or more resting masks to clear. Returns true if any were found. */
    private fun findMatches(): Boolean {
        val mark = Array(rows) { BooleanArray(cols) }
        fun still(r: Int, c: Int) = grid[r][c] >= 0 && clearing[r][c] == 0 && (r == rows - 1 || grid[r + 1][c] >= 0)
        for (r in 0 until rows) { var c = 0; while (c < cols) { var e = c; if (still(r, c)) { while (e + 1 < cols && still(r, e + 1) && grid[r][e + 1] == grid[r][c]) e++; if (e - c >= 2) for (k in c..e) mark[r][k] = true }; c = e + 1 } }
        for (c in 0 until cols) { var r = 0; while (r < rows) { var e = r; if (still(r, c)) { while (e + 1 < rows && still(e + 1, c) && grid[e + 1][c] == grid[r][c]) e++; if (e - r >= 2) for (k in r..e) mark[k][c] = true }; r = e + 1 } }
        var n = 0; var fromChain = false
        for (r in 0 until rows) for (c in 0 until cols) if (mark[r][c]) { n++; clearing[r][c] = 42; if (chainy[r][c]) fromChain = true }
        if (n == 0) return false
        if (fromChain) chain++
        val pts = (n * 10 + (n - 3) * 20) * chain
        score += pts
        popup = when { chain > 1 -> "Chain ×$chain!"; n > 3 -> "$n at once!"; else -> "" }; if (popup.isNotEmpty()) popupT = 70
        play(if (chain > 1) "chain" else "clear")
        return true
    }

    private fun pushRow() {
        if ((0 until cols).any { grid[0][it] >= 0 }) {
            // full to the top: a short grace, then it's over
            rise = t.toFloat()
            if (++dangerTimer > 90) gameOver()
            return
        }
        dangerTimer = 0
        for (r in 0 until rows - 1) { grid[r] = grid[r + 1].copyOf(); clearing[r] = clearing[r + 1].copyOf(); chainy[r] = chainy[r + 1].copyOf() }
        grid[rows - 1] = incoming; clearing[rows - 1] = IntArray(cols); chainy[rows - 1] = BooleanArray(cols)
        incoming = newRow()
        rise -= t
        cy = (cy - 1).coerceAtLeast(0)
        score += 1
    }

    private fun gameOver() {
        over = true; play("over")
        if (score > best) best = score
    }

    // ------------------------------------------------------------------ input
    override fun action(a: Act, repeat: Boolean) {
        if (over) { if (a == Act.A && !repeat) restart(); return }
        when (a) {
            Act.LEFT -> cx = (cx - 1).coerceAtLeast(0)
            Act.RIGHT -> cx = (cx + 1).coerceAtMost(cols - 2)
            Act.UP -> cy = (cy - 1).coerceAtLeast(0)
            Act.DOWN -> cy = (cy + 1).coerceAtMost(rows - 1)
            Act.A, Act.X -> if (!repeat) swap()
            Act.RB, Act.LB, Act.Y -> if ((0 until rows).none { r -> clearing[r].any { it > 0 } }) { rise = t.toFloat(); play("raise", .5f) }
            else -> {}
        }
    }

    private fun swap() {
        val l = grid[cy][cx]; val r = grid[cy][cx + 1]
        if (clearing[cy][cx] > 0 || clearing[cy][cx + 1] > 0 || (l < 0 && r < 0)) return
        grid[cy][cx] = r; grid[cy][cx + 1] = l
        chainy[cy][cx] = false; chainy[cy][cx + 1] = false
        play("swap", .6f)
    }

    /** Mouse: click a mask to put the cursor on it and its right-hand neighbour; click the same spot again to swap. */
    override fun tap(x: Int, y: Int) {
        if (over) { restart(); return }
        val c = (x - bx) / t; val r = ((y - by + rise) / t).toInt()
        if (x < bx || c !in 0 until cols || r !in 0 until rows) return
        val ncx = c.coerceAtMost(cols - 2)
        if (ncx == cx && r == cy) swap() else { cx = ncx; cy = r }
    }

    // ------------------------------------------------------------------ drawing
    override fun render(c: Canvas) {
        c.drawBitmap(bg, 0f, 0f, sharp)
        c.save(); c.clipRect(bx, by, bx + cols * t, by + rows * t)
        val off = rise.toInt()
        val danger = (0 until cols).any { grid[0][it] >= 0 }
        for (r in 0 until rows) for (col in 0 until cols) {
            val v = grid[r][col]; if (v < 0) continue
            val flash = clearing[r][col] > 0 && (clearing[r][col] / 4) % 2 == 0
            val shake = if (danger && r <= 1 && (frames / 4) % 2 == 0) 1 else 0
            tile(c, v, if (flash) 1 else 0, bx + col * t, by + r * t - off - shake)
        }
        for (col in 0 until cols) tile(c, incoming[col], 2, bx + col * t, by + rows * t - off)
        c.restore()
        if (!over) {
            // the cursor: two masks wide, a bright carved bracket that pulses gently
            val x = bx + cx * t - 1; val y = by + cy * t - off - 1
            p.color = if ((frames / 20) % 2 == 0) Color.WHITE else Color.rgb(255, 230, 150)
            val w = 2 * t + 2; val h = t + 2
            for ((dx, dy) in listOf(0 to 0, w - 4 to 0, 0 to h - 1, w - 4 to h - 1)) c.drawRect((x + dx).toFloat(), (y + dy).toFloat(), (x + dx + 4).toFloat(), (y + dy + 1).toFloat(), p)
            for ((dx, dy) in listOf(0 to 0, w - 1 to 0, 0 to h - 4, w - 1 to h - 4)) c.drawRect((x + dx).toFloat(), (y + dy).toFloat(), (x + dx + 1).toFloat(), (y + dy + 4).toFloat(), p)
        }
    }

    private fun tile(c: Canvas, v: Int, row: Int, x: Int, y: Int) {
        src.set(v * t, row * t, v * t + t, row * t + t); dstR.set(x, y, x + t, y + t); c.drawBitmap(sheet, src, dstR, sharp)
    }

    // sharp text at full resolution over the pixel art
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Kit.font(ctx, true) }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.TEXT_DIM; typeface = Kit.font(ctx, true) }

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        if (exp.contains("notext")) return
        val s = maxOf(1, minOf(width / W, height / H)).toFloat()
        val ox = (width - W * s) / 2; val oy = (height - H * s) / 2
        fun X(v: Int): Float = ox + v * s
        fun Y(v: Int): Float = oy + v * s
        txt.textSize = 34f * s / 4; dim.textSize = 17f * s / 4
        cv.drawText("Totem Tide", X(10), Y(22), txt)
        dim.textSize = 16f * s / 4
        cv.drawText("SCORE", X(214), Y(24), dim); txt.textSize = 30f * s / 4; cv.drawText("$score", X(214), Y(40), txt)
        cv.drawText("BEST", X(214), Y(60), dim); txt.textSize = 22f * s / 4; cv.drawText("${maxOf(best, score)}", X(214), Y(73), txt)
        cv.drawText("SPEED", X(214), Y(92), dim); cv.drawText("${1 + frames / (60 * 30)}", X(214), Y(105), txt)
        if (popupT > 0 && popup.isNotEmpty()) { txt.textSize = 26f * s / 4; txt.color = Color.rgb(255, 214, 102); cv.drawText(popup, X(214), Y(130), txt); txt.color = Color.WHITE }
        dim.textSize = 14f * s / 4
        val hints = listOf("Arrows  move", "OK / A  swap", "RB / Ch+  raise", "Back  pause")
        hints.forEachIndexed { i, h -> cv.drawText(h, X(10), Y(120 + i * 12), dim) }
        if (over) {
            p.color = Color.argb(200, 6, 8, 14); cv.drawRect(X(bx - 4), Y(60), X(bx + cols * t + 4), Y(122), p)
            txt.textSize = 30f * s / 4; val w1 = txt.measureText("Game over"); cv.drawText("Game over", X(bx + cols * t / 2) - w1 / 2, Y(82), txt)
            dim.textSize = 16f * s / 4
            val l2 = if (score >= best && score > 0) "New best: $score" else "Score $score · Best $best"
            cv.drawText(l2, X(bx + cols * t / 2) - dim.measureText(l2) / 2, Y(98), dim)
            val l3 = "OK: play again · Back: leave"
            cv.drawText(l3, X(bx + cols * t / 2) - dim.measureText(l3) / 2, Y(112), dim)
        }
    }
}
