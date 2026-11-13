package com.antonretro.launcher.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color

/**
 * Orchard Swap: a fruit match-three in an orchard at golden hour (original game, art and sounds).
 * Swap two neighbouring fruits to line up three or more of a kind in a row or column: they pop, the fruit above
 * falls in, and a match made by falling fruit is a cascade (worth more). Four in a line also clears its whole row;
 * five clears every fruit of that kind. Each round gives 25 moves to reach the target; reaching it starts the next
 * round with a higher target. A board with no possible move reshuffles.
 *
 * Controls: arrows move the cursor; OK picks a fruit, then an arrow swaps it with that neighbour (OK again lets go).
 * Mouse: click a fruit, then a neighbour. Everything is drawn from rectangles in the shared pixel palette.
 */
class OrchardSwap(ctx: Context) : PixelGame(ctx, "orchard", "Orchard Swap") {
    private val n = 8; private val ts = 19
    private val bx = 160 - n * ts / 2 + 30; private val by = 14
    private val kinds = 6
    private val skin = intArrayOf(Color.rgb(222, 58, 52), Color.rgb(244, 146, 40), Color.rgb(246, 214, 64), Color.rgb(122, 196, 70), Color.rgb(146, 84, 196), Color.rgb(72, 120, 226))
    private val names = listOf("APPLE", "ORANGE", "LEMON", "PEAR", "GRAPES", "BERRY")

    private val g = Array(n) { IntArray(n) }
    private val drop = Array(n) { FloatArray(n) }          // pixels a fruit is still above its place (falling in)
    private val pop = Array(n) { IntArray(n) }             // frames left of a popping fruit
    private var cx = 3; private var cy = 3; private var picked = false
    private var swapA = -1 to -1; private var swapB = -1 to -1; private var swapT = 0; private var swapBack = false
    private var score = 0; private var round = 1; private var moves = 25; private var target = 1500
    private var cascade = 0; private var busy = false; private var roundT = 0

    init {
        for (s in listOf("pick", "swap", "nope", "pop", "cascade", "big", "round", "over")) loadSound(s)
        restart()
        showTitle()
    }

    override val levelLabel = "Start round"
    override val levelMax: Int get() = 9
    override fun howToLines() = listOf(
        "SWAP TWO FRUITS SIDE BY SIDE",
        "TO LINE UP 3 OR MORE OF A KIND.",
        "OK PICKS A FRUIT, AN ARROW SWAPS.",
        "FALLING FRUIT MAKING A MATCH:",
        "A CASCADE, WORTH MORE.",
        "4 IN A LINE CLEARS THE ROW.",
        "5 CLEARS EVERY FRUIT OF THAT KIND.",
        "REACH THE TARGET IN 25 MOVES!")

    // ------------------------------------------------------------------ set-up
    override fun restart() {
        score = 0; round = startLevel; startRound(); fill()
    }
    private fun startRound() { moves = 25; target = 1200 + round * 600 + (round - 1) * (round - 1) * 150; roundT = 90 }
    /** A fresh board with no ready-made matches and at least one move. */
    private fun fill() {
        do {
            for (y in 0 until n) for (x in 0 until n) {
                var k: Int
                do { k = rnd.nextInt(kinds) } while ((x >= 2 && g[y][x - 1] == k && g[y][x - 2] == k) || (y >= 2 && g[y - 1][x] == k && g[y - 2][x] == k))
                g[y][x] = k; drop[y][x] = (n - y) * ts + 20f; pop[y][x] = 0
            }
        } while (!anyMove())
    }

    // ------------------------------------------------------------------ rules
    /** Cells in matches of 3+ (and the special clears for 4 and 5). Returns (cells, biggest run, kind of the 5). */
    private fun matches(): Triple<Set<Pair<Int, Int>>, Int, Int> {
        val out = HashSet<Pair<Int, Int>>(); var biggest = 0; var five = -1
        fun run(cells: List<Pair<Int, Int>>) {
            if (cells.size < 3) return
            out.addAll(cells); biggest = maxOf(biggest, cells.size)
            val (x0, y0) = cells[0]; val k = g[y0][x0]
            if (cells.size >= 5) five = k
            else if (cells.size == 4) {
                // four in a line: the whole row (or column) goes
                if (cells.all { it.second == y0 }) for (x in 0 until n) out.add(x to y0) else for (y in 0 until n) out.add(x0 to y)
            }
        }
        for (y in 0 until n) { var s = 0; for (x in 1..n) if (x == n || g[y][x] != g[y][s] || g[y][x] < 0) { run((s until x).map { it to y }); s = x } }
        for (x in 0 until n) { var s = 0; for (y in 1..n) if (y == n || g[y][x] != g[s][x] || g[y][x] < 0) { run((s until y).map { x to it }); s = y } }
        if (five >= 0) for (y in 0 until n) for (x in 0 until n) if (g[y][x] == five) out.add(x to y)
        return Triple(out, biggest, five)
    }
    private fun swapCells(a: Pair<Int, Int>, b: Pair<Int, Int>) { val t = g[a.second][a.first]; g[a.second][a.first] = g[b.second][b.first]; g[b.second][b.first] = t }
    private fun anyMove(): Boolean {
        for (y in 0 until n) for (x in 0 until n) for ((dx, dy) in listOf(1 to 0, 0 to 1)) {
            val a = x to y; val b = (x + dx) to (y + dy); if (b.first >= n || b.second >= n) continue
            swapCells(a, b); val ok = matches().first.isNotEmpty(); swapCells(a, b)
            if (ok) return true
        }
        return false
    }

    // ------------------------------------------------------------------ the game loop
    override fun update() {
        if (roundT > 0) roundT--
        var moving = false
        for (y in 0 until n) for (x in 0 until n) if (drop[y][x] > 0f) { drop[y][x] = maxOf(0f, drop[y][x] - 3.5f); moving = true }
        var popping = false
        for (y in 0 until n) for (x in 0 until n) if (pop[y][x] > 0) { if (--pop[y][x] == 0) g[y][x] = -1; popping = true }
        if (swapT > 0) { if (--swapT == 0) afterSwap(); busy = true; return }
        if (popping) { busy = true; return }
        if ((0 until n).any { y -> (0 until n).any { x -> g[y][x] < 0 } }) { settle(); busy = true; return }
        if (moving) { busy = true; return }
        // everything still: look for cascades
        val (m, big, five) = matches()
        if (m.isNotEmpty()) { clear(m, big, five); return }
        if (busy) {
            busy = false; cascade = 0
            if (score >= target) { round++; startRound(); play("round"); floatText("ROUND $round!", 160, 80, GOLD, 2); saveScore(score) }
            else if (moves <= 0) { play("over"); gameOver(score) }
            else if (!anyMove()) { floatText("NO MOVES: NEW FRUIT", bx + n * ts / 2, 80, BONE); fill() }
        }
    }

    private fun afterSwap() {
        val (m, big, five) = matches()
        if (m.isEmpty()) {
            if (!swapBack) { swapCells(swapA, swapB); swapBack = true; swapT = 6; play("nope", .5f) } else swapBack = false
            return
        }
        swapBack = false; moves--; cascade = 0
        clear(m, big, five)
    }

    private fun clear(m: Set<Pair<Int, Int>>, big: Int, five: Int) {
        cascade++
        val gain = m.size * 20 * cascade + (if (big >= 4) 200 else 0) + (if (five >= 0) 500 else 0)
        score += gain
        for ((x, y) in m) { if (pop[y][x] == 0) pop[y][x] = 12; burst(bx + x * ts + ts / 2, by + y * ts + ts / 2, skin[g[y][x].coerceIn(0, kinds - 1)], 3) }
        val (ax, ay) = m.first()
        when {
            five >= 0 -> { play("big"); shake(3, 16); floatText("ALL THE ${names[five]}S!", 160, 70, GOLD, 2) }
            big >= 4 -> { play("big", .7f); shake(2, 10); floatText("+$gain", bx + ax * ts, by + ay * ts, GOLD) }
            cascade > 1 -> { play("cascade"); floatText("CASCADE X$cascade", bx + ax * ts, by + ay * ts, GOLD) }
            else -> { play("pop", .6f); floatText("+$gain", bx + ax * ts, by + ay * ts, BONE) }
        }
    }

    /** Fruit falls into the gaps; new fruit drops in from above. */
    private fun settle() {
        for (x in 0 until n) {
            var w = n - 1
            for (y in n - 1 downTo 0) if (g[y][x] >= 0) { if (w != y) { g[w][x] = g[y][x]; drop[w][x] = drop[y][x] + (w - y) * ts; g[y][x] = -1 }; w-- }
            var fresh = 0
            for (y in w downTo 0) { g[y][x] = rnd.nextInt(kinds); fresh++; drop[y][x] = (w + 1) * ts.toFloat() + fresh * 2 }
        }
    }

    // ------------------------------------------------------------------ input
    override fun action(a: Act, repeat: Boolean) {
        if (busy || swapT > 0) { if (a == Act.LEFT || a == Act.RIGHT || a == Act.UP || a == Act.DOWN) moveCursor(a); return }
        when (a) {
            Act.A, Act.X -> if (!repeat) { picked = !picked; play("pick", .4f) }
            Act.LEFT, Act.RIGHT, Act.UP, Act.DOWN -> if (picked) trySwap(a) else moveCursor(a)
            Act.B, Act.Y -> picked = false
            else -> {}
        }
    }
    private fun dir(a: Act) = when (a) { Act.LEFT -> -1 to 0; Act.RIGHT -> 1 to 0; Act.UP -> 0 to -1; else -> 0 to 1 }
    private fun moveCursor(a: Act) { val (dx, dy) = dir(a); cx = (cx + dx).coerceIn(0, n - 1); cy = (cy + dy).coerceIn(0, n - 1) }
    private fun trySwap(a: Act) {
        val (dx, dy) = dir(a); val nx = cx + dx; val ny = cy + dy
        picked = false
        if (nx !in 0 until n || ny !in 0 until n) return
        swapA = cx to cy; swapB = nx to ny
        swapCells(swapA, swapB); swapT = 6; play("swap", .5f)
        cx = nx; cy = ny
    }

    /** Mouse: click a fruit to pick it, then a neighbour to swap. */
    override fun tap(x: Int, y: Int) {
        val c = (x - bx) / ts; val r = (y - by) / ts
        if (x < bx || y < by || c !in 0 until n || r !in 0 until n || busy) return
        if (picked && kotlin.math.abs(c - cx) + kotlin.math.abs(r - cy) == 1) {
            trySwap(when { c < cx -> Act.LEFT; c > cx -> Act.RIGHT; r < cy -> Act.UP; else -> Act.DOWN })
        } else { cx = c; cy = r; picked = true; play("pick", .4f) }
    }

    // ------------------------------------------------------------------ drawing
    /** One fruit, drawn in pixels: a body, a highlight and a leaf or stem by kind. */
    private fun fruit(c: Canvas, k: Int, x: Int, y: Int, s: Int = ts, squash: Float = 1f) {
        val col = skin[k]
        val dark = Color.rgb(Color.red(col) * 6 / 10, Color.green(col) * 6 / 10, Color.blue(col) * 6 / 10)
        val r = (s / 2 - 2) * squash
        val ccx = x + s / 2f; val ccy = y + s / 2f + 1
        if (k == 4) {   // grapes: a little bunch
            fill.color = INK; for ((gx, gy) in listOf(-3 to -3, 3 to -3, 0 to 0, -3 to 3, 3 to 3, 0 to 6)) c.drawCircle(ccx + gx * squash, ccy - 2 + gy * squash, 3.6f * squash, fill)
            fill.color = col; for ((gx, gy) in listOf(-3 to -3, 3 to -3, 0 to 0, -3 to 3, 3 to 3, 0 to 6)) c.drawCircle(ccx + gx * squash, ccy - 2 + gy * squash, 2.8f * squash, fill)
        } else {
            fill.color = INK; c.drawCircle(ccx, ccy, r + 1, fill)
            fill.color = dark; c.drawCircle(ccx, ccy, r, fill)
            fill.color = col; c.drawCircle(ccx - .8f, ccy - .8f, r - 1.2f, fill)
            fill.color = Color.argb(170, 255, 255, 255); c.drawCircle(ccx - r / 2.5f, ccy - r / 2.5f, maxOf(1f, r / 4f), fill)
        }
        // stem and leaf
        rect(c, (ccx).toInt(), (ccy - r - 3).toInt(), (ccx + 1).toInt(), (ccy - r + 1).toInt(), Color.rgb(92, 56, 24))
        if (k != 5) rect(c, (ccx + 1).toInt(), (ccy - r - 3).toInt(), (ccx + 4).toInt(), (ccy - r - 1).toInt(), Color.rgb(84, 160, 60))
    }

    override fun drawScene(c: Canvas) {
        // golden-hour orchard: warm sky, rolling hills, rows of little fruit trees
        val sky = intArrayOf(Color.rgb(126, 176, 220), Color.rgb(170, 200, 214), Color.rgb(236, 210, 160), Color.rgb(248, 196, 120))
        for ((i, col) in sky.withIndex()) rect(c, 0, i * 25, W, i * 25 + 25, col)
        fill.color = Color.rgb(108, 156, 80); c.drawCircle(70f, 190f, 110f, fill)
        fill.color = Color.rgb(84, 136, 64); c.drawCircle(260f, 200f, 120f, fill)
        rect(c, 0, 140, W, H, Color.rgb(74, 120, 56))
        for (i in 0 until 9) {
            val tx = 8 + i * 38; val ty = 112 + (i % 2) * 10
            rect(c, tx + 5, ty + 10, tx + 8, ty + 22, Color.rgb(92, 56, 24))
            fill.color = Color.rgb(58, 112, 52); c.drawCircle(tx + 6.5f, ty + 6f, 9f, fill)
            fill.color = skin[i % kinds]; c.drawCircle(tx + 3f, ty + 6f, 1.6f, fill); c.drawCircle(tx + 10f, ty + 3f, 1.6f, fill)
        }
    }

    override fun drawTitle(c: Canvas) {
        panel(c, 70, 18, 250, 72)
        text(c, "ORCHARD", 160, 26, GOLD, 3, align = 0)
        text(c, "SWAP", 160, 51, BONE, 2, align = 0)
        for (k in 0 until kinds) fruit(c, k, 50 + k * 40, 150 + if ((t / 20 + k) % 2 == 0) 0 else -2, 18)
    }

    override fun drawPlay(c: Canvas) {
        // the board: a wooden fruit crate
        rect(c, bx - 4, by - 4, bx + n * ts + 4, by + n * ts + 4, INK)
        rect(c, bx - 3, by - 3, bx + n * ts + 3, by + n * ts + 3, WOOD_D)
        rect(c, bx - 1, by - 1, bx + n * ts + 1, by + n * ts + 1, Color.rgb(46, 30, 18))
        for (y in 0 until n) for (x in 0 until n) if ((x + y) % 2 == 0) rect(c, bx + x * ts, by + y * ts, bx + x * ts + ts, by + y * ts + ts, Color.rgb(56, 38, 22))
        // swap animation: the two fruits slide past each other
        val sp = if (swapT > 0) swapT / 6f else 0f
        for (y in 0 until n) for (x in 0 until n) {
            val k = g[y][x]; if (k < 0) continue
            var ox = 0f; var oy = 0f
            if (swapT > 0) {
                if (x to y == swapB) { ox = (swapA.first - x) * ts * sp; oy = (swapA.second - y) * ts * sp }
                if (x to y == swapA) { ox = (swapB.first - x) * ts * sp; oy = (swapB.second - y) * ts * sp }
            }
            val p = pop[y][x]
            val sq = if (p > 0) p / 12f else 1f
            val yy = by + y * ts - drop[y][x] + oy
            if (yy < by - ts) continue
            c.save(); c.clipRect(bx.toFloat(), by.toFloat(), (bx + n * ts).toFloat(), (by + n * ts).toFloat())
            fruit(c, k, (bx + x * ts + ox).toInt(), yy.toInt(), ts, sq)
            c.restore()
        }
        // cursor: a bright frame, filled gold when a fruit is picked up
        val l = bx + cx * ts; val tp = by + cy * ts
        val pulse = if ((t / 8) % 2 == 0) BONE else GOLD
        for (i in 0..1) { rect(c, l - i, tp - i, l + ts + i, tp + 1 - i, pulse); rect(c, l - i, tp + ts - 1 + i, l + ts + i, tp + ts + i, pulse); rect(c, l - i, tp - i, l + 1 - i, tp + ts + i, pulse); rect(c, l + ts - 1 + i, tp - i, l + ts + i, tp + ts + i, pulse) }
        if (picked) rect(c, l + 1, tp + 1, l + ts - 1, tp + 3, GOLD)
        // left: score, target, moves, round
        val lx = 10
        panel(c, lx, 8, lx + 86, 50); text(c, "SCORE", lx + 8, 13, GOLD); text(c, "$score", lx + 78, 27, BONE, 2, align = 1)
        panel(c, lx, 54, lx + 86, 80); text(c, "TARGET", lx + 8, 59, GOLD); text(c, "$target", lx + 78, 69, BONE, align = 1)
        well(c, lx + 8, 76, lx + 78, 79); rect(c, lx + 9, 77, lx + 9 + (68 * minOf(1f, score / target.toFloat())).toInt(), 78, GOLD)
        panel(c, lx, 84, lx + 86, 116); text(c, "MOVES", lx + 8, 89, GOLD); text(c, "$moves", lx + 78, 100, if (moves <= 5) RED else BONE, 2, align = 1)
        panel(c, lx, 120, lx + 86, 138); text(c, "ROUND $round", lx + 43, 126, BONE, align = 0)
        hint(c, lx, 146, "A", "OK", if (picked) "LET GO" else "PICK"); hint(c, lx, 160, "<>", "<>", if (picked) "SWAP" else "MOVE")
        if (roundT > 0) { panel(c, 110, 66, 210, 96); text(c, "ROUND $round", 160, 72, GOLD, align = 0); text(c, "REACH $target", 160, 84, BONE, align = 0) }
    }
}
