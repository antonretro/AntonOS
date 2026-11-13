package com.antonretro.launcher.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color

/**
 * Crate Cascade: a falling-block stacker with wooden cargo crates on a harbour dock at sunset (original game, art
 * and sounds; no other game's name, look or music). Crates come in seven four-block shapes; turn and slide them so
 * they fill whole rows, which clear. One to four rows at once score more; every 10 rows the cargo falls faster.
 * The game ends when a new crate has no room to come in.
 *
 * Controls: Left/Right slide, OK / A turn, Y turn the other way, Down drop faster, Up drop at once, RB / Ch+ hold.
 * Everything is drawn from rectangles in the shared pixel palette (no bitmaps to load).
 */
class CrateCascade(ctx: Context) : PixelGame(ctx, "crate", "Crate Cascade") {
    private val cols = 10; private val rows = 17; private val ts = 10
    private val bx = 110; private val by = 6                        // board position on the 320x180 canvas

    // the seven shapes, as cell lists in each of four turns (generated from the first by turning)
    private val base = listOf(
        listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1),   // long
        listOf(1 to 0, 2 to 0, 1 to 1, 2 to 1),   // square
        listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1),   // tee
        listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1),   // step right
        listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),   // step left
        listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1),   // hook
        listOf(2 to 0, 0 to 1, 1 to 1, 2 to 1))   // hook the other way
    private val size = intArrayOf(4, 4, 3, 3, 3, 3, 3)
    private fun cells(k: Int, r: Int): List<Pair<Int, Int>> {
        var c = base[k]; val n = size[k]
        repeat(((r % 4) + 4) % 4) { c = c.map { (x, y) -> (n - 1 - y) to x } }
        return c
    }
    // crate colours: painted cargo, not any other game's palette
    private val paint = intArrayOf(Color.rgb(64, 148, 186), Color.rgb(222, 178, 64), Color.rgb(156, 96, 186), Color.rgb(98, 168, 82),
        Color.rgb(214, 86, 64), Color.rgb(70, 110, 196), Color.rgb(226, 132, 54))

    private val grid = Array(rows) { IntArray(cols) { -1 } }
    private var k = 0; private var rot = 0; private var px = 3; private var py = 0
    private val bag = ArrayList<Int>(); private val next = ArrayList<Int>()
    private var held = -1; private var heldUsed = false
    private var fall = 0; private var lockT = 0; private var lockMoves = 0
    private var score = 0; private var lines = 0; private var level = 1
    private val clearing = ArrayList<Int>(); private var clearT = 0
    private var landFlash = 0

    init {
        for (s in listOf("move", "turn", "lock", "clear", "four", "drop", "over", "hold")) loadSound(s)
        restart()
        showTitle()
    }

    override val levelLabel = "Start speed"
    override fun howToLines() = listOf(
        "CRATES DROP ONTO THE DOCK.",
        "FILL A WHOLE ROW TO CLEAR IT.",
        "LEFT / RIGHT SLIDE THE CRATE.",
        "OK OR A TURNS IT, Y THE OTHER WAY.",
        "DOWN DROPS FASTER, UP DROPS AT ONCE.",
        "RB / CH+ HOLDS A CRATE FOR LATER.",
        "4 ROWS AT ONCE: A FULL CARGO!",
        "BACK PAUSES.")

    // ------------------------------------------------------------------ set-up
    override fun restart() {
        for (r in grid) r.fill(-1)
        bag.clear(); next.clear(); held = -1; heldUsed = false
        score = 0; lines = 0; level = startLevel; clearing.clear(); clearT = 0
        repeat(3) { next.add(draw()) }
        spawn()
    }
    private fun draw(): Int { if (bag.isEmpty()) { bag.addAll(0..6); bag.shuffle(rnd) }; return bag.removeAt(bag.size - 1) }
    private fun spawn(kind: Int = -1) {
        k = if (kind >= 0) kind else { next.add(draw()); next.removeAt(0) }
        rot = 0; px = (cols - size[k]) / 2; py = 0; fall = 0; lockT = 0; lockMoves = 0
        if (!fits(px, py, rot)) { play("over"); gameOver(score) }
    }
    private fun fits(x: Int, y: Int, r: Int): Boolean = cells(k, r).all { (cx, cy) ->
        val gx = x + cx; val gy = y + cy
        gx in 0 until cols && gy < rows && (gy < 0 || grid[gy][gx] < 0)
    }
    private fun gravity(): Int = maxOf(2, 46 - (level - 1) * 5)

    // ------------------------------------------------------------------ the game loop
    override fun update() {
        if (landFlash > 0) landFlash--
        if (clearT > 0) {
            if (--clearT == 0) {
                for (r in clearing.sortedDescending()) { for (y in r downTo 1) grid[y] = grid[y - 1].copyOf(); grid[0] = IntArray(cols) { -1 } }
                // the removal shifted rows: clear from the top so indexes stay right
                clearing.clear(); spawn()
            }
            return
        }
        if (++fall >= gravity()) {
            fall = 0
            if (fits(px, py + 1, rot)) py++ else lockStep()
        } else if (!fits(px, py + 1, rot)) lockStep()
    }
    private fun lockStep() { if (++lockT >= 30) lock() }

    private fun lock() {
        for ((cx, cy) in cells(k, rot)) { val gy = py + cy; if (gy >= 0) grid[gy][px + cx] = k }
        heldUsed = false; landFlash = 6
        play("lock", .5f)
        val full = (0 until rows).filter { r -> grid[r].all { it >= 0 } }
        if (full.isEmpty()) { spawn(); return }
        clearing.addAll(full); clearT = 18
        val gain = intArrayOf(0, 100, 300, 500, 800)[full.size] * level
        score += gain; lines += full.size
        if (lines / 10 + startLevel > level) { level = lines / 10 + startLevel; floatText("FASTER!", bx + cols * ts / 2, by + 60, GOLD) }
        if (full.size == 4) { play("four"); shake(3, 14); floatText("FULL CARGO!", bx + cols * ts / 2, by + full.first() * ts, GOLD, 2) }
        else { play("clear"); floatText("+$gain", bx + cols * ts / 2, by + full.first() * ts, BONE) }
        for (r in full) for (x in 0 until cols step 2) burst(bx + x * ts + ts / 2, by + r * ts + ts / 2, paint[grid[r][x].coerceAtLeast(0)], 2)
    }

    // ------------------------------------------------------------------ input
    override fun action(a: Act, repeat: Boolean) {
        if (clearT > 0) return
        when (a) {
            Act.LEFT -> move(-1)
            Act.RIGHT -> move(1)
            Act.DOWN -> if (fits(px, py + 1, rot)) { py++; fall = 0; score += 1 } else lockStep()
            Act.UP -> if (!repeat) { var d = 0; while (fits(px, py + 1, rot)) { py++; d++ }; score += d * 2; play("drop", .6f); shake(1, 5); lock() }
            Act.A, Act.X -> if (!repeat) turn(1)
            Act.Y, Act.B -> if (!repeat) turn(-1)
            Act.RB, Act.LB -> if (!repeat && !heldUsed) { val h = held; held = k; heldUsed = true; play("hold", .5f); spawn(h); heldUsed = true }
            else -> {}
        }
    }
    private fun move(d: Int) { if (fits(px + d, py, rot)) { px += d; play("move", .3f); if (!fits(px, py + 1, rot) && lockMoves++ < 15) lockT = 0 } }
    private fun turn(d: Int) {
        val nr = rot + d
        // try in place, then nudged sideways or up a little, so a turn against a wall or floor still works
        for ((dx, dy) in listOf(0 to 0, -1 to 0, 1 to 0, -2 to 0, 2 to 0, 0 to -1)) {
            if (fits(px + dx, py + dy, nr)) { px += dx; py += dy; rot = nr; play("turn", .4f); if (lockMoves++ < 15) lockT = 0; return }
        }
    }

    /** Mouse: click left or right of the crate to slide it, on it to turn it. */
    override fun tap(x: Int, y: Int) {
        if (x < bx || x >= bx + cols * ts) return
        val c = (x - bx) / ts
        when { c < px -> move(-1); c >= px + size[k] -> move(1); else -> turn(1) }
    }

    // ------------------------------------------------------------------ drawing
    /** One crate cell: painted boards, a darker frame, plank lines and corner nails. */
    private fun crate(c: Canvas, x: Int, y: Int, kind: Int, s: Int = ts, ghost: Boolean = false) {
        val col = paint[kind]
        if (ghost) { rect(c, x, y, x + s, y + 1, col); rect(c, x, y + s - 1, x + s, y + s, col); rect(c, x, y, x + 1, y + s, col); rect(c, x + s - 1, y, x + s, y + s, col); return }
        val dark = Color.rgb(Color.red(col) * 6 / 10, Color.green(col) * 6 / 10, Color.blue(col) * 6 / 10)
        val lite = Color.rgb(minOf(255, Color.red(col) + 40), minOf(255, Color.green(col) + 40), minOf(255, Color.blue(col) + 40))
        rect(c, x, y, x + s, y + s, INK)
        rect(c, x + 1, y + 1, x + s - 1, y + s - 1, dark)
        rect(c, x + 2, y + 2, x + s - 2, y + s - 2, col)
        rect(c, x + 2, y + 2, x + s - 2, y + 3, lite)                       // lit top board
        if (s >= 8) { rect(c, x + 2, y + s / 2, x + s - 2, y + s / 2 + 1, dark) }   // plank line
        rect(c, x + 2, y + 2, x + 3, y + 3, GOLD); rect(c, x + s - 3, y + s - 3, x + s - 2, y + s - 2, GOLD)   // nails
    }

    override fun drawScene(c: Canvas) {
        // a harbour at sunset: sky bands, the sun, sea with drifting glints, and the dock the crates land on
        val sky = intArrayOf(Color.rgb(54, 34, 72), Color.rgb(98, 48, 84), Color.rgb(168, 72, 78), Color.rgb(222, 120, 72), Color.rgb(246, 176, 92))
        for ((i, col) in sky.withIndex()) rect(c, 0, i * 22, W, i * 22 + 22, col)
        fill.color = Color.rgb(255, 214, 120); c.drawCircle(60f, 104f, 16f, fill)
        rect(c, 0, 110, W, H, Color.rgb(32, 64, 92))
        for (i in 0 until 14) { val gx = (i * 37 + t / (3 + i % 3)) % (W + 20) - 10; val gy = 116 + (i * 13) % 56; rect(c, gx, gy, gx + 6 + i % 4, gy + 1, Color.rgb(96, 150, 176)) }
        // cranes on the far quay
        rect(c, 262, 40, 265, 110, INK); rect(c, 236, 40, 300, 43, INK); rect(c, 288, 43, 289, 70, INK); rect(c, 284, 70, 293, 76, Color.rgb(226, 132, 54))
        rect(c, 18, 58, 21, 110, INK); rect(c, 8, 58, 48, 61, INK)
    }

    override fun drawTitle(c: Canvas) {
        // stacked crates spelling out the title on a wooden sign
        panel(c, 70, 16, 250, 70)
        text(c, "CRATE", 160, 24, GOLD, 3, align = 0)
        text(c, "CASCADE", 160, 48, BONE, 2, align = 0)
        for (i in 0 until 7) crate(c, 36 + i * 36, 150 - (i % 3) * 10, i, 12)
    }

    override fun drawPlay(c: Canvas) {
        // the well the crates fall into, with a dock floor
        rect(c, bx - 3, by - 2, bx + cols * ts + 3, by + rows * ts + 3, INK)
        rect(c, bx - 2, by - 1, bx + cols * ts + 2, by + rows * ts + 2, WOOD_D)
        rect(c, bx, by, bx + cols * ts, by + rows * ts, Color.argb(205, 16, 22, 32))
        for (x in 1 until cols) rect(c, bx + x * ts, by, bx + x * ts + 1, by + rows * ts, Color.argb(40, 255, 255, 255))
        // settled crates (rows being cleared flash white)
        for (r in 0 until rows) for (x in 0 until cols) {
            val v = grid[r][x]; if (v < 0) continue
            if (r in clearing && (clearT / 3) % 2 == 0) rect(c, bx + x * ts, by + r * ts, bx + x * ts + ts, by + r * ts + ts, BONE)
            else crate(c, bx + x * ts, by + r * ts, v)
        }
        if (clearT == 0 && inGame) {
            // where it will land (ghost), then the falling crate
            var gy = py; while (fits(px, gy + 1, rot)) gy++
            for ((cx, cy) in cells(k, rot)) if (gy + cy >= 0) crate(c, bx + (px + cx) * ts, by + (gy + cy) * ts, k, ghost = true)
            for ((cx, cy) in cells(k, rot)) if (py + cy >= 0) crate(c, bx + (px + cx) * ts, by + (py + cy) * ts, k)
        }
        // left: held crate, score, speed; right: the next three
        val lx = 12; val rx = bx + cols * ts + 12
        panel(c, lx, 6, lx + 86, 52); text(c, "HOLD", lx + 8, 11, GOLD); well(c, lx + 8, 21, lx + 78, 46)
        if (held >= 0) mini(c, held, lx + 43, 33)
        panel(c, lx, 56, lx + 86, 100); text(c, "SCORE", lx + 8, 61, GOLD); text(c, "$score", lx + 78, 74, BONE, 2, align = 1)
        text(c, "BEST ${maxOf(best, score)}", lx + 8, 90, DIM)
        panel(c, lx, 104, lx + 86, 136); text(c, "SPEED", lx + 8, 109, GOLD); text(c, "$level", lx + 78, 109, BONE, align = 1)
        text(c, "ROWS", lx + 8, 122, GOLD); text(c, "$lines", lx + 78, 122, BONE, align = 1)
        panel(c, rx, 6, rx + 86, 120); text(c, "NEXT", rx + 8, 11, GOLD); well(c, rx + 8, 21, rx + 78, 112)
        for ((i, n) in next.withIndex()) mini(c, n, rx + 43, 34 + i * 28)
        hint(c, lx, 144, "A", "OK", "TURN"); hint(c, lx, 158, "RB", "CH+", "HOLD")
        hint(c, rx, 128, "UP", "UP", "DROP"); hint(c, rx, 144, "DN", "DN", "FASTER")
    }
    /** A small crate shape centred on (cx, cy), for HOLD and NEXT. */
    private fun mini(c: Canvas, kind: Int, cx: Int, cy: Int) {
        val s = 6; val cl = cells(kind, 0)
        val w = (cl.maxOf { it.first } + 1) * s; val h = (cl.maxOf { it.second } - cl.minOf { it.second } + 1) * s
        val top = cl.minOf { it.second }
        for ((x, y) in cl) crate(c, cx - w / 2 + x * s, cy - h / 2 + (y - top) * s, kind, s)
    }
}
