package com.antonretro.launcher.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect

/**
 * Totem Tide: a rising-stack swap puzzle with carved tiki masks (original game and art).
 * Swap two neighbouring masks sideways; three or more in a row or column clear; masks above fall, and a clear
 * made by falling masks is a chain (worth more). The stack rises steadily, a little faster every 30 seconds;
 * RB / Channel Up raises it at once. When the top row is full and the stack must rise, the game is over.
 */
class TotemTide(ctx: Context) : PixelGame(ctx, "tiki", "Totem Tide") {
    private val cols = 6; private val rows = 12; private val ts = 14
    private val bx = 118; private val by = 4                       // board position on the 320x180 canvas
    private val sheet: Bitmap = bitmap("tiles.png")
    private val scene: Bitmap = bitmap("scene.png")
    private val logo: Bitmap = bitmap("logo.png")
    private val src = Rect(); private val dstR = Rect()
    private val maskColour = intArrayOf(Color.rgb(214, 72, 52), Color.rgb(232, 170, 40), Color.rgb(88, 166, 72), Color.rgb(40, 156, 170), Color.rgb(130, 92, 196), Color.rgb(236, 112, 150))

    private val grid = Array(rows) { IntArray(cols) { -1 } }
    private val clearing = Array(rows) { IntArray(cols) }          // frames left before a mask vanishes
    private val chainy = Array(rows) { BooleanArray(cols) }        // fell because something below cleared
    private val land = Array(rows) { IntArray(cols) }              // frames of landing squash left
    private var incoming = IntArray(cols)
    private var rise = 0f; private var speed = 0f; private var level = 1
    private var cx = 2; private var cy = 8
    private var score = 0; private var chain = 1; private var frames = 0
    private var fallTick = 0; private var dangerTimer = 0
    private var swapR = -1; private var swapC = 0; private var swapF = 0
    private var shownChain = 1; private var shownChainT = 0
    private val clearFrames = 42; private val popFrames = 16

    init {
        for (s in listOf("swap", "clear", "chain", "raise", "land", "danger", "over")) loadSound(s)
        restart()
        showTitle()
    }

    override fun howToLines() = listOf(
        "SWAP TWO MASKS SIDE BY SIDE.",
        "LINE UP 3 OR MORE THE SAME",
        "IN A ROW OR COLUMN TO CLEAR.",
        "MASKS FALLING INTO A NEW MATCH",
        "MAKE A CHAIN: X2, X3... MORE POINTS.",
        "THE STACK RISES. RB / CH+ RAISES IT.",
        "DON'T LET IT REACH THE TOP!",
        "BACK PAUSES.")

    // ------------------------------------------------------------------ set-up
    override fun restart() {
        for (r in 0 until rows) { grid[r].fill(-1); clearing[r].fill(0); chainy[r].fill(false); land[r].fill(0) }
        for (r in rows - 6 until rows) for (c in 0 until cols) grid[r][c] = pick(r, c)
        incoming = newRow()
        level = startLevel
        rise = 0f; speed = 14f / (60 * 9f) * Math.pow(1.15, (level - 1).toDouble()).toFloat(); cx = 2; cy = rows - 4
        score = 0; chain = 1; frames = 0; dangerTimer = 0; swapF = 0; shownChainT = 0
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
            do { v = rnd.nextInt(6) } while ((c >= 2 && row[c - 1] == v && row[c - 2] == v) || (grid[rows - 1][c] == v && grid[rows - 2][c] == v))
            row[c] = v
        }
        return row
    }

    // ------------------------------------------------------------------ rules
    override fun update() {
        frames++
        if (swapF > 0) swapF--
        if (shownChainT > 0) shownChainT--
        for (r in 0 until rows) for (c in 0 until cols) if (land[r][c] > 0) land[r][c]--
        var anyClearing = false
        for (r in 0 until rows) for (c in 0 until cols) if (clearing[r][c] > 0) {
            anyClearing = true
            if (--clearing[r][c] == 0) {
                burst(bx + c * ts + ts / 2, by + r * ts - rise.toInt() + ts / 2, maskColour[grid[r][c].coerceIn(0, 5)], 7)
                grid[r][c] = -1
                for (u in 0 until r) if (grid[u][c] >= 0) chainy[u][c] = true
            }
        }
        // gravity: a mask with an empty space under it drops one row every 3 steps, and squashes a little on landing
        var fell = false
        if (++fallTick >= 3) {
            fallTick = 0
            for (r in rows - 2 downTo 0) for (c in 0 until cols)
                if (grid[r][c] >= 0 && clearing[r][c] == 0 && grid[r + 1][c] < 0) {
                    grid[r + 1][c] = grid[r][c]; chainy[r + 1][c] = chainy[r][c]; grid[r][c] = -1; chainy[r][c] = false; fell = true
                    if (r + 2 >= rows || grid[r + 2][c] >= 0) land[r + 1][c] = 5
                }
            if (fell) play("land", .25f)
        }
        val matched = findMatches()
        if (!anyClearing && !fell && !matched && !falling()) { chain = 1; for (r in 0 until rows) chainy[r].fill(false) }

        if (frames % (60 * 30) == 0) { speed *= 1.15f; level++; floatText("SPEED UP!", bx + cols * ts / 2, by + 40, Color.rgb(110, 214, 214)) }
        if (!anyClearing) rise += speed
        if (rise >= ts) pushRow()
        if (danger() && frames % 90 == 0) play("danger", .35f)
    }

    private fun danger() = (0 until cols).any { grid[1][it] >= 0 }
    private fun falling(): Boolean { for (r in 0 until rows - 1) for (c in 0 until cols) if (grid[r][c] >= 0 && grid[r + 1][c] < 0) return true; return false }
    private fun stackHeight(): Int { for (r in 0 until rows) if ((0 until cols).any { grid[r][it] >= 0 }) return rows - r; return 0 }

    /** Marks runs of three or more resting masks to clear. Returns true if any were found. */
    private fun findMatches(): Boolean {
        val mark = Array(rows) { BooleanArray(cols) }
        fun still(r: Int, c: Int) = grid[r][c] >= 0 && clearing[r][c] == 0 && (r == rows - 1 || grid[r + 1][c] >= 0)
        for (r in 0 until rows) { var c = 0; while (c < cols) { var e = c; if (still(r, c)) { while (e + 1 < cols && still(r, e + 1) && grid[r][e + 1] == grid[r][c]) e++; if (e - c >= 2) for (k in c..e) mark[r][k] = true }; c = e + 1 } }
        for (c in 0 until cols) { var r = 0; while (r < rows) { var e = r; if (still(r, c)) { while (e + 1 < rows && still(e + 1, c) && grid[e + 1][c] == grid[r][c]) e++; if (e - r >= 2) for (k in r..e) mark[k][c] = true }; r = e + 1 } }
        var n = 0; var fromChain = false; var sx = 0; var sy = 0
        for (r in 0 until rows) for (c in 0 until cols) if (mark[r][c]) { n++; clearing[r][c] = clearFrames; if (chainy[r][c]) fromChain = true; sx += c; sy += r }
        if (n == 0) return false
        if (fromChain) chain++
        score += (n * 10 + (n - 3) * 20) * chain
        val px = bx + (sx * ts / n) + ts / 2; val py = by + (sy * ts / n) - rise.toInt()
        if (chain > 1) { floatText("X$chain", px, py, GOLD, 2); shownChain = chain; shownChainT = 150 }
        else if (n > 3) floatText("$n!", px, py, Color.rgb(110, 214, 214), 2)
        if (chain >= 3 || n >= 5) shake(if (chain >= 4 || n >= 6) 3 else 2, 14)
        play(if (chain > 1) "chain" else "clear")
        return true
    }

    private fun pushRow() {
        if ((0 until cols).any { grid[0][it] >= 0 }) {
            rise = ts.toFloat()
            if (++dangerTimer > 90) { play("over"); gameOver(score) }
            return
        }
        dangerTimer = 0
        for (r in 0 until rows - 1) { grid[r] = grid[r + 1].copyOf(); clearing[r] = clearing[r + 1].copyOf(); chainy[r] = chainy[r + 1].copyOf(); land[r] = land[r + 1].copyOf() }
        grid[rows - 1] = incoming; clearing[rows - 1] = IntArray(cols); chainy[rows - 1] = BooleanArray(cols); land[rows - 1] = IntArray(cols)
        incoming = newRow()
        rise -= ts
        cy = (cy - 1).coerceAtLeast(0)
        if (swapF > 0) swapR--
        score += 1
    }

    // ------------------------------------------------------------------ input
    override fun action(a: Act, repeat: Boolean) {
        when (a) {
            Act.LEFT -> cx = (cx - 1).coerceAtLeast(0)
            Act.RIGHT -> cx = (cx + 1).coerceAtMost(cols - 2)
            Act.UP -> cy = (cy - 1).coerceAtLeast(0)
            Act.DOWN -> cy = (cy + 1).coerceAtMost(rows - 1)
            Act.A, Act.X -> if (!repeat) swap()
            Act.RB, Act.LB, Act.Y -> if ((0 until rows).none { r -> clearing[r].any { it > 0 } }) { rise = ts.toFloat(); play("raise", .5f) }
            else -> {}
        }
    }

    private fun swap() {
        val l = grid[cy][cx]; val r = grid[cy][cx + 1]
        if (clearing[cy][cx] > 0 || clearing[cy][cx + 1] > 0 || (l < 0 && r < 0)) return
        grid[cy][cx] = r; grid[cy][cx + 1] = l
        chainy[cy][cx] = false; chainy[cy][cx + 1] = false
        swapR = cy; swapC = cx; swapF = 4
        play("swap", .6f)
    }

    /** Mouse: click a mask to put the cursor on it and its neighbour; click the same spot again to swap. */
    override fun tap(x: Int, y: Int) {
        val c = (x - bx) / ts; val r = ((y - by + rise) / ts).toInt()
        if (x < bx || c !in 0 until cols || r !in 0 until rows) return
        val ncx = c.coerceAtMost(cols - 2)
        if (ncx == cx && r == cy) swap() else { cx = ncx; cy = r }
    }

    // ------------------------------------------------------------------ drawing
    override fun drawScene(c: Canvas) {
        c.drawBitmap(scene, 0f, 0f, sharp)
        // the tide: rows of wave crests drifting slowly
        for ((i, y) in listOf(136, 145, 155, 166).withIndex()) {
            val off = (t / (8 + i * 3) + i * 5) % 16
            val col = if (i % 2 == 0) Color.rgb(46, 104, 120) else Color.rgb(34, 82, 100)
            var x = -16 + off
            while (x < W) { rect(c, x, y, x + 5, y + 1, col); rect(c, x + 1, y - 1, x + 3, y, col); x += 16 }
        }
    }

    override fun drawTitle(c: Canvas) {
        val lx = (W - logo.width) / 2; val ly = 8 + if ((t / 30) % 2 == 0) 0 else 1
        c.drawBitmap(logo, lx.toFloat(), ly.toFloat(), sharp)
        // masks bobbing on the tide, left and right of the menu
        for (i in 0 until 6) {
            val x = if (i < 3) 22 + i * 22 else 236 + (i - 3) * 22
            val y = 146 + ((t / 12 + i * 3) % 6).let { if (it > 3) 6 - it else it }
            tile(c, i, 0, x, y)
        }
    }

    override fun drawPlay(c: Canvas) {
        frame(c)
        c.save(); c.clipRect(bx, by, bx + cols * ts, by + rows * ts)
        val off = rise.toInt()
        val danger = danger()
        for (r in 0 until rows) for (col in 0 until cols) {
            val v = grid[r][col]; if (v < 0) continue
            var x = bx + col * ts; val y = by + r * ts - off
            if (swapF > 0 && r == swapR) { if (col == swapC) x += ts * swapF / 4; if (col == swapC + 1) x -= ts * swapF / 4 }
            val cl = clearing[r][col]
            when {
                cl > popFrames -> tile(c, v, if ((cl / 4) % 2 == 0) 1 else 0, x, y)
                cl > 0 -> { val k = cl * ts / popFrames / 2; tileIn(c, v, 1, x + ts / 2 - k, y + ts / 2 - k, x + ts / 2 + k, y + ts / 2 + k) }
                land[r][col] > 0 -> tileIn(c, v, 0, x - 1, y + 1, x + ts + 1, y + ts)
                else -> tile(c, v, 0, x, y - if (danger && r <= 1 && (t / 4) % 2 == 0) 1 else 0)
            }
        }
        for (col in 0 until cols) tile(c, incoming[col], 2, bx + col * ts, by + rows * ts - off)
        if (danger) { fill.color = Color.argb(40 + ((t / 3) % 10) * 6, 230, 60, 50); c.drawRect(bx.toFloat(), by.toFloat(), (bx + cols * ts).toFloat(), (by + ts * 2).toFloat(), fill) }
        c.restore()
        if (screen == Screen.PLAY) {
            val x = bx + cx * ts - 1; val y = by + cy * ts - off - 1
            val col = if ((t / 15) % 2 == 0) BONE else GOLD
            val w = 2 * ts + 2; val h = ts + 2
            for ((dx, dy) in listOf(0 to 0, w - 4 to 0, 0 to h - 1, w - 4 to h - 1)) rect(c, x + dx, y + dy, x + dx + 4, y + dy + 1, col)
            for ((dx, dy) in listOf(0 to 0, w - 1 to 0, 0 to h - 4, w - 1 to h - 4)) rect(c, x + dx, y + dy, x + dx + 1, y + dy + 4, col)
        }
        hud(c, danger)
    }

    /** The board in a carved frame: two totem posts and a beam. */
    private fun frame(c: Canvas) {
        val l = bx - 9; val r = bx + cols * ts + 9; val top = by - 3; val bot = by + rows * ts + 3
        for (px in listOf(l, r - 8)) {
            rect(c, px, top, px + 8, bot, INK); rect(c, px + 1, top + 1, px + 7, bot - 1, WOOD); rect(c, px + 1, top + 1, px + 2, bot - 1, WOOD_L)
            var y = top + 6
            while (y < bot - 10) { rect(c, px + 2, y, px + 3, y + 1, INK); rect(c, px + 5, y, px + 6, y + 1, INK); rect(c, px + 2, y + 4, px + 6, y + 5, WOOD_D); y += 14 }
        }
        rect(c, l, top, r, top + 3, INK); rect(c, l + 1, top + 1, r - 1, top + 2, WOOD_L)
        rect(c, bx - 1, by - 1, bx + cols * ts + 1, by + rows * ts + 1, INK)
        rect(c, bx, by, bx + cols * ts, by + rows * ts, Color.rgb(16, 20, 26))
        // faint carved lanes so the columns read
        for (k in 1 until cols) rect(c, bx + k * ts, by, bx + k * ts + 1, by + rows * ts, Color.rgb(22, 27, 35))
    }

    private fun hud(c: Canvas, danger: Boolean) {
        // left: the logo and how the buttons work right now
        c.drawBitmap(logo, (8 + (100 - logo.width) / 2).toFloat(), 6f, sharp)
        panel(c, 6, 96, 108, 172)
        text(c, "CONTROLS", 57, 101, GOLD, align = 0)
        hint(c, 12, 113, "+", "ARROWS", "MOVE")
        hint(c, 12, 127, "A", "OK", "SWAP")
        hint(c, 12, 141, "RB", "CH+", "RAISE")
        hint(c, 12, 155, "B", "BACK", "PAUSE")
        // right: score, best, speed, stack height, chain
        val x0 = 212; val x1 = 314
        panel(c, x0, 4, x1, 42); icon(c, 0, x0 + 6, 9); text(c, "SCORE", x0 + 17, 10, GOLD)
        well(c, x0 + 6, 20, x1 - 6, 37); text(c, "$score", x1 - 9, 22, BONE, 2, align = 1)
        panel(c, x0, 46, x1, 70); icon(c, 1, x0 + 6, 52); text(c, "BEST", x0 + 17, 53, GOLD); text(c, "${maxOf(best, score)}", x1 - 8, 53, BONE, align = 1)
        panel(c, x0, 74, x1, 98); icon(c, 2, x0 + 6, 80); text(c, "SPEED", x0 + 17, 81, GOLD); text(c, "$level", x1 - 8, 81, BONE, align = 1)
        panel(c, x0, 102, x1, 134); icon(c, 4, x0 + 6, 108); text(c, "STACK", x0 + 17, 109, if (danger && (t / 10) % 2 == 0) RED else GOLD)
        well(c, x0 + 6, 120, x1 - 6, 129)
        val h = stackHeight(); val fillW = ((x1 - 7) - (x0 + 7)) * h / rows
        rect(c, x0 + 7, 121, x0 + 7 + fillW, 127, when { h >= rows - 1 -> RED; h >= rows - 4 -> GOLD; else -> Color.rgb(96, 184, 72) })
        panel(c, x0, 138, x1, 172); icon(c, 3, x0 + 6, 144); text(c, "CHAIN", x0 + 17, 145, GOLD)
        if (shownChainT > 0) text(c, "X$shownChain", (x0 + x1) / 2, 155, if ((t / 6) % 2 == 0) GOLD else BONE, 2, align = 0)
        else text(c, "-", (x0 + x1) / 2, 157, DIM, align = 0)
    }

    private fun tile(c: Canvas, v: Int, row: Int, x: Int, y: Int) = tileIn(c, v, row, x, y, x + ts, y + ts)
    private fun tileIn(c: Canvas, v: Int, row: Int, l: Int, tp: Int, r: Int, b: Int) {
        src.set(v * ts, row * ts, v * ts + ts, row * ts + ts); dstR.set(l, tp, r, b); c.drawBitmap(sheet, src, dstR, sharp)
    }
}
