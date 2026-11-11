package com.antonretro.launcher.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect

/**
 * Gear & Ghost: a clockwork pipe puzzle (Anthony's idea and concept picture; original rules and art).
 *
 * Rules
 * - Each level is a workshop floor. Energy leaves the Source on the left; light up the Portal on the right.
 * - Kiko the ghost floats over any square (moving is free). OK turns the pipe under Kiko a quarter clockwise,
 *   X turns it back. Each turn is one move; a level has a move limit and a par (3 stars at or under par).
 * - A gear carries energy every way. Pressing OK on a gear turns every pipe next to it (X: the other way).
 * - Bolted pipes (iron corners) can't be turned by hand, only by a gear next to them.
 * - Gaps need a spare gear: Y (Menu on the remote) puts one in, Y again takes it back.
 * - LB (Ch-) shows a hint, RB (Ch+) restarts the level. Running out of moves restarts it too.
 *
 * Levels are built from a fixed seed each (the same every time): a path from Source to Portal is laid, the rest
 * is filled with decoy pipes, then the board is scrambled only by moves the player can undo with OK (counter-
 * clockwise turns and counter-clockwise gear presses), so every level is solvable with OK alone (remote friendly)
 * in at most "par" moves.
 */
class GearGhost(ctx: Context) : PixelGame(ctx, "gear", "Gear & Ghost") {
    companion object {
        const val LEVELS = 32
        private const val FLOOR = 0; private const val STRAIGHT = 1; private const val CORNER = 2; private const val TEE = 3; private const val CROSS = 4
        private const val GEAR = 5; private const val SOURCE = 6; private const val PORTAL = 7
        private val BASE = intArrayOf(0, 1 or 4, 1 or 2, 1 or 2 or 4, 15, 15, 2, 8)
        private val DX = intArrayOf(0, 1, 0, -1); private val DY = intArrayOf(-1, 0, 1, 0)   // N E S W
        private val NAMES = listOf(
            "FIRST SPARK", "LOOSE PIPES", "CORNER SHOP", "MEET THE GEAR", "TWIN TURNS", "GEAR WORKS", "BOLTED DOWN", "WORKSHOP DOOR",
            "STEAM VENT", "SPARE PART", "PRESSURE", "COPPER COIL", "HOT PIPES", "BOILER HEART", "SAFETY VALVE", "FULL STEAM",
            "TICK", "TOCK", "CLOCKWORK PATH", "PENDULUM", "COGS AND CHAINS", "BELL TOWER", "MIDNIGHT", "QUARTER PAST",
            "UPDRAFT", "CLOUD PUMP", "SKY ANVIL", "STORM GLASS", "LIGHTNING ROD", "HIGH GEARS", "LAST FURNACE", "GHOST LIGHT")
        private val TIPS = mapOf(
            1 to listOf("LEAD THE SPARK TO THE PORTAL.", "MOVE KIKO ONTO A PIPE AND", "PRESS OK TO TURN IT."),
            4 to listOf("THIS IS A GEAR. IT CARRIES", "ENERGY EVERY WAY. OK ON A GEAR", "TURNS ALL THE PIPES AROUND IT."),
            7 to listOf("BOLTED PIPES CAN'T BE TURNED", "BY HAND. TURN A GEAR NEXT", "TO THEM INSTEAD."),
            10 to listOf("A GAP! PRESS Y (MENU ON THE", "REMOTE) TO PUT A SPARE GEAR", "IN IT. Y AGAIN TAKES IT BACK."))
        private fun rotMask(m: Int, r: Int): Int { var x = m; repeat(((r % 4) + 4) % 4) { x = ((x shl 1) or (x shr 3)) and 15 }; return x }
    }

    private val ts = 16
    private val scene = GearArt.scene()
    private val pipes = HashMap<Int, Bitmap>()
    private fun pipeB(mask: Int, on: Boolean, ph: Int) = pipes.getOrPut(mask * 4 + (if (on) 2 else 0) + ph) { GearArt.pipe(mask, on, ph) }
    private val gearOff = Array(4) { GearArt.gear(false, it) }; private val gearOn = Array(4) { GearArt.gear(true, it) }
    private val bigGear = Array(4) { GearArt.gear(false, it, stubs = false) }
    private val sourceB = Array(2) { GearArt.source(it) }
    private val portalOff = GearArt.portal(false, 0); private val portalOnB = Array(4) { GearArt.portal(true, it) }
    private val lockB = GearArt.lock(); private val kikoB = Array(2) { GearArt.kiko(it) }
    private val starOn = GearArt.star(true); private val starOff = GearArt.star(false)
    private val r0 = Rect()

    // ------------------------------------------------------------------ level state
    private var cols = 6; private var rows = 4; private var bx = 0; private var by = 0
    private var kind = Array(1) { IntArray(1) }; private var rot = Array(1) { IntArray(1) }
    private var locked = Array(1) { BooleanArray(1) }; private var placed = Array(1) { BooleanArray(1) }
    private var powered = Array(1) { BooleanArray(1) }; private var spin = Array(1) { IntArray(1) }; private var kick = Array(1) { IntArray(1) }
    private var floorB: Bitmap? = null
    private val path = ArrayList<IntArray>(); private val need = ArrayList<Int>()
    private var spare = 0; private var spareMax = 0; private var moves = 0; private var par = 0; private var limit = 0
    private var level = 1; private var srcY = 0; private var portY = 0; private var portalOn = false
    private var kx = 1; private var ky = 0; private var gx = 1f; private var gy = 0f
    private enum class Phase { TIP, PLAY, WIN, CLEAR, FAIL }
    private var phase = Phase.PLAY; private var phaseT = 0
    private var hintCell: IntArray? = null; private var hintT = 0
    private var stars = 0; private var levelScore = 0

    private val store = ctx.getSharedPreferences("games", Context.MODE_PRIVATE)
    private var unlocked: Int get() = store.getInt("gear:unlocked", 1); set(v) { store.edit().putInt("gear:unlocked", v).apply() }
    private fun bestStars(l: Int) = store.getInt("gear:stars:$l", 0)
    override val levelLabel = "Level"
    override val levelMax: Int get() = unlocked.coerceIn(1, LEVELS)

    init {
        for (s in listOf("swap", "land", "turn", "gear", "place", "power", "portal", "fail", "bonk", "step")) loadSound(s)
        build(1); showTitle()
    }

    override fun wantMusic(): String? = when (screen) { Screen.TITLE, Screen.BEST, Screen.OVER -> "music_title"; else -> if (inGame) "music_play" else "music_title" }

    override fun howToLines() = listOf(
        "LIGHT THE PORTAL: LINK ITS PIPE",
        "TO THE SPARK ON THE LEFT.",
        "OK TURNS THE PIPE UNDER KIKO.",
        "OK ON A GEAR TURNS ITS NEIGHBOURS.",
        "BOLTED PIPES: USE A GEAR.",
        "Y / MENU: PUT IN A SPARE GEAR.",
        "LB / CH-: HINT. RB / CH+: RESTART.",
        "AT OR UNDER PAR = 3 STARS!")

    override fun restart() = build(startLevel.coerceIn(1, levelMax))

    // ------------------------------------------------------------------ building a level
    private fun build(l: Int) {
        level = l
        val rnd = java.util.Random(l * 7919L + 13)
        cols = (6 + l / 3).coerceAtMost(11); rows = (4 + l / 4).coerceAtMost(8)
        bx = 4 + (194 - cols * ts) / 2; by = 24 + (152 - rows * ts) / 2
        kind = Array(rows) { IntArray(cols) }; rot = Array(rows) { IntArray(cols) }
        locked = Array(rows) { BooleanArray(cols) }; placed = Array(rows) { BooleanArray(cols) }
        powered = Array(rows) { BooleanArray(cols) }; spin = Array(rows) { IntArray(cols) }; kick = Array(rows) { IntArray(cols) }
        var tries = 0
        while (!makePath(rnd) && tries++ < 400) {}
        kind[srcY][0] = SOURCE; kind[portY][cols - 1] = PORTAL
        // the route: each square gets a piece that can link its two neighbours on the path
        need.clear()
        val onPath = HashSet<Int>()
        for ((i, p) in path.withIndex()) {
            val prev = if (i == 0) 3 else dirTo(p, path[i - 1]); val next = if (i == path.size - 1) 1 else dirTo(p, path[i + 1])
            val m = (1 shl prev) or (1 shl next); need.add(m); onPath.add(p[1] * cols + p[0])
            var k = if (m == 5 || m == 10) STRAIGHT else CORNER
            if (l > 2 && rnd.nextInt(100) < 16) k = if (rnd.nextInt(3) == 0) CROSS else TEE
            kind[p[1]][p[0]] = k; rot[p[1]][p[0]] = (0..3).first { rotMask(BASE[k], it) and m == m }
        }
        // decoys everywhere else on the inner columns
        for (y in 0 until rows) for (x in 1 until cols - 1) if ((y * cols + x) !in onPath) {
            if (rnd.nextInt(100) < 62) { kind[y][x] = listOf(STRAIGHT, STRAIGHT, CORNER, CORNER, CORNER, TEE)[rnd.nextInt(6)]; rot[y][x] = rnd.nextInt(4) }
        }
        // gears next to the route
        val gearCount = if (l >= 4) (1 + (l - 4) / 5).coerceAtMost(4) else 0
        val cand = ArrayList<IntArray>()
        for (y in 0 until rows) for (x in 1 until cols - 1) if ((y * cols + x) !in onPath) {
            val n = (0..3).count { d -> val nx = x + DX[d]; val ny = y + DY[d]; nx in 0 until cols && ny in 0 until rows && (ny * cols + nx) in onPath }
            if (n > 0) cand.add(intArrayOf(x, y, n * 10 + rnd.nextInt(10)))
        }
        cand.sortByDescending { it[2] }
        val gears = ArrayList<IntArray>()
        for (c in cand) { if (gears.size >= gearCount) break; if (gears.any { Math.abs(it[0] - c[0]) + Math.abs(it[1] - c[1]) <= 1 }) continue; kind[c[1]][c[0]] = GEAR; rot[c[1]][c[0]] = 0; gears.add(c) }
        // bolts: route pieces next to a gear
        if (l >= 7) for (p in path) if (kind[p[1]][p[0]] in STRAIGHT..TEE && nextToGear(p[0], p[1]) && rnd.nextInt(100) < 55) locked[p[1]][p[0]] = true
        // gaps that need a spare gear
        val gapCount = if (l >= 10) (if (l >= 20) 2 else 1) else 0
        spareMax = 0
        var gapTries = 0
        while (path.size > 4 && spareMax < gapCount && gapTries++ < 60) {
            val i = 1 + rnd.nextInt(path.size - 2); val p = path[i]
            if (kind[p[1]][p[0]] != FLOOR && !locked[p[1]][p[0]] && (gapTries > 30 || !nextToGear(p[0], p[1]))) { kind[p[1]][p[0]] = FLOOR; locked[p[1]][p[0]] = false; spareMax++ }
        }
        spare = spareMax
        // scramble with moves OK can undo: counter-clockwise turns and counter-clockwise gear presses
        val ops = 3 + l / 2 + gearCount
        var done = 0; var guard = 0
        while ((done < ops || power()) && guard++ < 600) {
            if (gears.isNotEmpty() && rnd.nextInt(100) < 30) { val g = gears[rnd.nextInt(gears.size)]; pressGear(g[0], g[1], -1, animate = false); done++; continue }
            val p = if (rnd.nextInt(100) < 85) path[rnd.nextInt(path.size)] else intArrayOf(1 + rnd.nextInt(cols - 2), rnd.nextInt(rows))
            val x = p[0]; val y = p[1]
            if (kind[y][x] !in STRAIGHT..TEE || locked[y][x]) continue
            rot[y][x] = (rot[y][x] + 3) % 4; done++
        }
        par = done + spareMax; limit = par * 2 + 6
        moves = 0; portalOn = power()
        kx = 1; ky = srcY; gx = kx.toFloat(); gy = ky.toFloat()
        hintCell = null; hintT = 0
        floorB = GearArt.floor(cols, rows, l)
        phase = if (TIPS.containsKey(l) && !store.getBoolean("gear:tip:$l", false)) Phase.TIP else Phase.PLAY; phaseT = 0
    }

    private fun dirTo(a: IntArray, b: IntArray): Int = (0..3).first { a[0] + DX[it] == b[0] && a[1] + DY[it] == b[1] }
    private fun nextToGear(x: Int, y: Int) = (0..3).any { d -> val nx = x + DX[d]; val ny = y + DY[d]; nx in 0 until cols && ny in 0 until rows && kind[ny][nx] == GEAR }

    /** A wandering route from the Source's neighbour to the Portal's neighbour, inside the inner columns. */
    private fun makePath(rnd: java.util.Random): Boolean {
        srcY = rnd.nextInt(rows); portY = rnd.nextInt(rows)
        val tx = cols - 2; val seen = HashSet<Int>()
        path.clear(); var x = 1; var y = srcY; path.add(intArrayOf(x, y)); seen.add(y * cols + x)
        val maxLen = ((cols - 2) * rows * .6f).toInt().coerceAtLeast(cols)
        while (!(x == tx && y == portY)) {
            if (path.size > maxLen) return false
            val opts = ArrayList<Int>()
            for (d in 0..3) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (nx < 1 || nx > tx || ny < 0 || ny >= rows || (ny * cols + nx) in seen) continue
                val toward = (DX[d] != 0 && Integer.signum(tx - x) == DX[d]) || (DY[d] != 0 && Integer.signum(portY - y) == DY[d])
                repeat(if (toward) 3 else 2) { opts.add(d) }
            }
            if (opts.isEmpty()) return false
            val d = opts[rnd.nextInt(opts.size)]; x += DX[d]; y += DY[d]; path.add(intArrayOf(x, y)); seen.add(y * cols + x)
        }
        return path.size >= 3
    }

    // ------------------------------------------------------------------ the rules
    private fun maskAt(x: Int, y: Int): Int = when (val k = kind[y][x]) { FLOOR -> 0; GEAR -> 15; SOURCE -> 2; PORTAL -> 8; else -> rotMask(BASE[k], rot[y][x]) }
    private fun turnable(x: Int, y: Int) = kind[y][x] in STRAIGHT..CROSS

    /** Lights everything linked to the Source; true when the Portal is lit. */
    private fun power(): Boolean {
        for (row in powered) row.fill(false)
        val q = ArrayDeque<Int>(); powered[srcY][0] = true; q.add(srcY * cols)
        while (q.isNotEmpty()) {
            val v = q.removeFirst(); val x = v % cols; val y = v / cols; val m = maskAt(x, y)
            for (d in 0..3) {
                if ((m and (1 shl d)) == 0) continue
                val nx = x + DX[d]; val ny = y + DY[d]
                if (nx !in 0 until cols || ny !in 0 until rows || powered[ny][nx]) continue
                if ((maskAt(nx, ny) and (1 shl ((d + 2) % 4))) == 0) continue
                powered[ny][nx] = true; q.add(ny * cols + nx)
            }
        }
        return powered[portY][cols - 1]
    }

    private fun turn(x: Int, y: Int, dir: Int, animate: Boolean = true) { rot[y][x] = (rot[y][x] + dir + 4) % 4; if (animate) spin[y][x] = 6 * dir }
    private fun pressGear(x: Int, y: Int, dir: Int, animate: Boolean = true) {
        for (d in 0..3) { val nx = x + DX[d]; val ny = y + DY[d]; if (nx in 0 until cols && ny in 0 until rows && turnable(nx, ny)) turn(nx, ny, dir, animate) }
        if (animate) kick[y][x] = 16 * dir
    }

    private fun cx(x: Int) = bx + x * ts + ts / 2
    private fun cy(y: Int) = by + y * ts

    private fun use(dir: Int) {
        val k = kind[ky][kx]
        when {
            k == GEAR -> { pressGear(kx, ky, dir); play("gear"); spend() }
            turnable(kx, ky) && locked[ky][kx] -> { play("bonk", .6f); shake(1, 6); floatText("BOLTED: USE A GEAR", 100, cy(ky) - 4, GearArt.IRON_L) }
            turnable(kx, ky) -> { turn(kx, ky, dir); play("turn", .7f); spend() }
            k == FLOOR && spare > 0 && kx in 1 until cols - 1 -> spareGear()
            else -> play("bonk", .35f)
        }
    }

    private fun spareGear() {
        when {
            kind[ky][kx] == FLOOR && kx in 1 until cols - 1 && spare > 0 -> { kind[ky][kx] = GEAR; placed[ky][kx] = true; spare--; kick[ky][kx] = 16; play("place"); burst(cx(kx), cy(ky) + 8, GearArt.BRASS_L, 8); spend() }
            kind[ky][kx] == GEAR && placed[ky][kx] -> { kind[ky][kx] = FLOOR; placed[ky][kx] = false; spare++; play("place", .5f); spend() }
            spareMax == 0 -> { play("bonk", .35f); floatText("NO SPARE GEARS HERE", 100, cy(ky) - 4, GearArt.IRON_L) }
            spare == 0 -> { play("bonk", .35f); floatText("ALL GEARS USED", 100, cy(ky) - 4, GearArt.IRON_L) }
            else -> { play("bonk", .35f); floatText("GEARS GO IN GAPS", 100, cy(ky) - 4, GearArt.IRON_L) }
        }
    }

    private fun spend() {
        moves++; hintT = 0
        val wasLit = powered.sumOf { r -> r.count { it } }
        portalOn = power()
        if (portalOn) {
            phase = Phase.WIN; phaseT = 0; play("power"); shake(2, 14)
            stars = when { moves <= par -> 3; moves <= par + par / 2 + 2 -> 2; else -> 1 }
            levelScore = stars * 100 + (limit - moves).coerceAtLeast(0) * 10
            saveScore(levelScore)
            if (stars > bestStars(level)) store.edit().putInt("gear:stars:$level", stars).apply()
            if (level + 1 > unlocked) unlocked = (level + 1).coerceAtMost(LEVELS)
            startLevel = (level + 1).coerceAtMost(LEVELS)
            kx = cols - 1; ky = portY
        } else if (moves >= limit) { phase = Phase.FAIL; phaseT = 0; play("fail"); shake(3, 16) }
        else if (powered.sumOf { r -> r.count { it } } > wasLit) play("step", .5f)
    }

    private fun hint() {
        for ((i, p) in path.withIndex()) {
            val x = p[0]; val y = p[1]; val m = need[i]
            if (kind[y][x] == GEAR) continue
            if (kind[y][x] == FLOOR) { showHint(p, "PUT A SPARE GEAR HERE"); return }
            if ((maskAt(x, y) and m) != m) {
                if (locked[y][x]) {
                    val g = (0..3).map { intArrayOf(x + DX[it], y + DY[it]) }.firstOrNull { it[0] in 0 until cols && it[1] in 0 until rows && kind[it[1]][it[0]] == GEAR }
                    showHint(g ?: p, "TURN THE GEAR BY IT")
                } else showHint(p, "TURN THIS ONE")
                return
            }
        }
        floatText("TRY ANOTHER WAY ROUND", 100, 100, GearArt.CYAN)
    }
    private fun showHint(p: IntArray, msg: String) { hintCell = p; hintT = 180; play("swap", .5f); floatText(msg, 100, (cy(p[1]) - 6).coerceAtLeast(26), GearArt.CYAN) }

    private fun nextLevel() {
        if (level >= LEVELS) { floatText("ALL PORTALS AWAKE!", 160, 60, GOLD, 2); showTitle(); return }
        build(level + 1)
    }

    override fun update() {
        gx += (kx - gx) * (if (phase == Phase.WIN) .06f else .35f); gy += (ky - gy) * (if (phase == Phase.WIN) .06f else .35f)
        for (y in 0 until rows) for (x in 0 until cols) {
            if (spin[y][x] != 0) spin[y][x] -= Integer.signum(spin[y][x])
            if (kick[y][x] != 0) kick[y][x] -= Integer.signum(kick[y][x])
        }
        if (hintT > 0) hintT--
        phaseT++
        if (phase == Phase.WIN && phaseT == 80) { phase = Phase.CLEAR; phaseT = 0; play("portal"); burst(bx + (cols - 1) * ts + 8, by + portY * ts + 8, GearArt.CYAN, 14) }
    }

    // ------------------------------------------------------------------ input
    override fun action(a: Act, repeat: Boolean) {
        when (phase) {
            Phase.TIP -> { if (a == Act.A && !repeat && phaseT > 15) { phase = Phase.PLAY; store.edit().putBoolean("gear:tip:$level", true).apply(); play("land", .5f) }; return }
            Phase.CLEAR -> { if (a == Act.A && !repeat && phaseT > 20) nextLevel(); return }
            Phase.FAIL -> { if ((a == Act.A || a == Act.RB) && !repeat && phaseT > 20) build(level); return }
            Phase.WIN -> return
            Phase.PLAY -> {}
        }
        when (a) {
            Act.UP -> move(0, -1); Act.DOWN -> move(0, 1); Act.LEFT -> move(-1, 0); Act.RIGHT -> move(1, 0)
            Act.A -> if (!repeat) use(1)
            Act.X -> if (!repeat) use(-1)
            Act.Y -> if (!repeat) spareGear()
            Act.LB -> if (!repeat) hint()
            Act.RB -> if (!repeat) { build(level); if (phase == Phase.TIP) phase = Phase.PLAY; play("place", .5f); floatText("FRESH START", 100, 100, BONE) }
            else -> {}
        }
    }
    private fun move(dx: Int, dy: Int) {
        val nx = (kx + dx).coerceIn(0, cols - 1); val ny = (ky + dy).coerceIn(0, rows - 1)
        if (nx != kx || ny != ky) { kx = nx; ky = ny; play("step", .2f) }
    }
    private val rowActs = ArrayList<Pair<Int, Act>>()
    override fun tap(x: Int, y: Int) {
        if (phase != Phase.PLAY) { action(Act.A, false); return }
        if (x >= 202) { rowActs.firstOrNull { y in it.first until it.first + 11 }?.let { if (it.second == Act.B) pauseGame() else action(it.second, false) }; return }
        if (x < bx || y < by) return
        val c = (x - bx) / ts; val r = (y - by) / ts
        if (c !in 0 until cols || r !in 0 until rows) return
        kx = c; ky = r; gx = c.toFloat(); gy = r.toFloat()
        if (kind[r][c] == FLOOR && spare > 0) spareGear() else use(1)
    }

    // ------------------------------------------------------------------ drawing
    override fun drawScene(c: Canvas) { c.drawBitmap(scene, 0f, 0f, sharp) }

    private fun logo(c: Canvas, cxx: Int, y: Int, scale: Int) {
        val gw = 8 * scale; val wGear = textWidth("GEAR", scale); val wGhost = textWidth("GHOST", scale)
        val total = wGear + gw + wGhost + scale * 4; val x0 = cxx - total / 2
        text(c, "GEAR", x0, y, GOLD, scale)
        val gxp = x0 + wGear + scale * 2; r0.set(gxp, y - scale, gxp + gw, y - scale + gw); c.drawBitmap(bigGear[(t / 6) % 4], null, r0, sharp)
        text(c, "GHOST", gxp + gw + scale * 2, y, Color.rgb(200, 240, 255), scale)
    }

    override fun drawTitle(c: Canvas) {
        // big gears turning behind the name
        logo(c, 160, 24, 3)
        text(c, "ROUTE THE ENERGY. WAKE THE PORTAL.", 160, 56, BONE, align = 0)
        // Kiko and a sleeping portal either side of the menu
        val bob = if ((t / 24) % 2 == 0) 0 else 2
        r0.set(52, 104 + bob, 52 + 24, 104 + 26 + bob); c.drawBitmap(kikoB[(t / 14) % 2], null, r0, sharp)
        r0.set(236, 100, 268, 132); c.drawBitmap(portalOnB[(t / 8) % 4], null, r0, sharp)
    }

    override fun drawPlay(c: Canvas) {
        // the name plate over the board
        rect(c, 46, 4, 154, 19, GearArt.INK); rect(c, 47, 5, 153, 18, GearArt.BRASS_D); rect(c, 47, 5, 153, 6, GearArt.BRASS)
        logo(c, 100, 8, 1)
        // the board in a brass frame
        rect(c, bx - 4, by - 4, bx + cols * ts + 4, by + rows * ts + 4, GearArt.INK)
        rect(c, bx - 3, by - 3, bx + cols * ts + 3, by + rows * ts + 3, GearArt.BRASS_D)
        rect(c, bx - 3, by - 3, bx + cols * ts + 3, by - 2, GearArt.BRASS)
        rect(c, bx - 1, by - 1, bx + cols * ts + 1, by + rows * ts + 1, GearArt.INK)
        floorB?.let { c.drawBitmap(it, bx.toFloat(), by.toFloat(), sharp) }
        for (y in 0 until rows) for (x in 0 until cols) tile(c, x, y)
        // hint glow
        val h = hintCell
        if (hintT > 0 && h != null && (t / 8) % 2 == 0) { val l = bx + h[0] * ts; val tp = by + h[1] * ts; frameRect(c, l - 1, tp - 1, l + ts + 1, tp + ts + 1, GearArt.CYAN) }
        // the cursor under Kiko, then Kiko
        if (phase == Phase.PLAY || phase == Phase.TIP) { val l = bx + kx * ts; val tp = by + ky * ts; corners(c, l - 1, tp - 1, ts + 2, ts + 2, if ((t / 15) % 2 == 0) BONE else GOLD) }
        drawKiko(c)
        side(c)
        when (phase) {
            Phase.TIP -> {
                dim(c); panel(c, 50, 54, 270, 128)
                val lines = TIPS[level] ?: emptyList()
                text(c, if (level == 1) "HOW IT WORKS" else "SOMETHING NEW", 160, 60, GOLD, align = 0)
                lines.forEachIndexed { i, l -> text(c, l, 160, 74 + i * 11, BONE, align = 0) }
                hint(c, 136, 111, "A", "OK", "GOT IT")
            }
            Phase.CLEAR -> {
                dim(c); panel(c, 70, 40, 250, 140)
                text(c, "PORTAL AWAKE!", 160, 48, if ((t / 10) % 2 == 0) GOLD else BONE, 2, align = 0)
                for (i in 0 until 3) { r0.set(124 + i * 26, 68, 124 + i * 26 + 18, 86); c.drawBitmap(if (i < stars && phaseT > 10 + i * 12) starOn else starOff, null, r0, sharp) }
                well(c, 96, 92, 224, 116)
                text(c, "MOVES", 102, 95, DIM); text(c, "$moves  (PAR $par)", 218, 95, BONE, align = 1)
                text(c, "SCORE", 102, 106, DIM); text(c, "$levelScore", 218, 106, GOLD, align = 1)
                hint(c, 110, 123, "A", "OK", if (level >= LEVELS) "FINISH" else "NEXT LEVEL")
            }
            Phase.FAIL -> {
                dim(c); panel(c, 70, 52, 250, 128)
                text(c, "OUT OF STEAM", 160, 60, if ((t / 12) % 2 == 0) RED else Color.rgb(255, 150, 120), 2, align = 0)
                text(c, "TOO MANY MOVES THIS TIME.", 160, 82, BONE, align = 0)
                text(c, "LB / CH- SHOWS A HINT.", 160, 93, DIM, align = 0)
                hint(c, 120, 110, "A", "OK", "TRY AGAIN")
            }
            else -> {}
        }
    }

    private fun tile(c: Canvas, x: Int, y: Int) {
        val k = kind[y][x]; if (k == FLOOR) return
        val px = bx + x * ts; val py = by + y * ts; val on = powered[y][x]
        val b = when (k) {
            GEAR -> { val kk = kick[y][x]; val f = if (kk != 0) ((kk / 2) % 4 + 4) % 4 else if (on) (t / 5) % 4 else 0; if (on) gearOn[f] else gearOff[f] }
            SOURCE -> sourceB[(t / 10) % 2]
            PORTAL -> if (on) portalOnB[(t / 6) % 4] else portalOff
            else -> pipeB(maskAt(x, y), on, if (on) ((t / 6 + x + y) % 2) else 0)
        }
        val sp = spin[y][x]
        if (sp != 0) { c.save(); c.rotate(-sp * 15f, px + 8f, py + 8f); c.drawBitmap(b, px.toFloat(), py.toFloat(), sharp); c.restore() }
        else c.drawBitmap(b, px.toFloat(), py.toFloat(), sharp)
        if (locked[y][x]) c.drawBitmap(lockB, px.toFloat(), py.toFloat(), sharp)
        if (placed[y][x] && (t / 30) % 2 == 0) rect(c, px + 7, py + 7, px + 9, py + 9, GOLD)
    }

    private fun drawKiko(c: Canvas) {
        val bob = if ((t / 20) % 2 == 0) 0 else -1
        var size = 1f
        if (phase == Phase.WIN && phaseT > 50) size = (1f - (phaseT - 50) / 30f).coerceAtLeast(0f)
        if (phase == Phase.CLEAR) return
        val w = (12 * size).toInt(); val h = (13 * size).toInt(); if (w <= 0) return
        val x = (bx + gx * ts + 13).toInt() - w / 2; val y = (by + gy * ts + bob).toInt() - h / 2 - 1
        r0.set(x, y, x + w, y + h); c.drawBitmap(kikoB[(t / 12) % 2], null, r0, ghostPaint)
    }

    private fun side(c: Canvas) {
        val x0 = 202; val x1 = 316; val mid = (x0 + x1) / 2
        panel(c, x0, 4, x1, 176)
        text(c, "LEVEL ${(level - 1) / 8 + 1}-${(level - 1) % 8 + 1}", mid, 10, GOLD, align = 0)
        text(c, NAMES.getOrElse(level - 1) { "" }, mid, 20, BONE, align = 0)
        well(c, x0 + 6, 31, x1 - 6, 55)
        text(c, "MOVES", x0 + 10, 34, DIM); text(c, "$moves/$limit", x1 - 10, 34, if (limit - moves <= 3) RED else BONE, align = 1)
        text(c, "PAR", x0 + 10, 45, DIM); text(c, "$par", x1 - 10, 45, GOLD, align = 1)
        text(c, "GOAL", x0 + 8, 60, DIM); text(c, if (portalOn) "PORTAL AWAKE!" else "WAKE THE PORTAL", x0 + 8, 70, if (portalOn) GearArt.CYAN else BONE)
        text(c, "SPARES", x0 + 8, 82, DIM)
        if (spareMax == 0) text(c, "NONE", x0 + 8, 92, DIM)
        else for (i in 0 until spareMax) { r0.set(x0 + 8 + i * 14, 90, x0 + 20 + i * 14, 102); c.drawBitmap(if (i < spare) gearOff[0] else gearOn[0], null, r0, if (i < spare) sharp else dimPaint) }
        text(c, "BEST", x0 + 60, 82, DIM)
        val bs = bestStars(level)
        for (i in 0 until 3) { r0.set(x0 + 60 + i * 11, 91, x0 + 69 + i * 11, 100); c.drawBitmap(if (i < bs) starOn else starOff, null, r0, sharp) }
        rowActs.clear()
        val rows = ArrayList<Triple<String, String, Pair<String, Act>>>()
        rows.add(Triple("A", "OK", "TURN" to Act.A))
        if (padInput) rows.add(Triple("X", "X", "TURN BACK" to Act.X))
        rows.add(Triple("Y", "MENU", "SPARE GEAR" to Act.Y))
        rows.add(Triple("LB", "CH-", "HINT" to Act.LB))
        rows.add(Triple("RB", "CH+", "RESTART" to Act.RB))
        if (!padInput) rows.add(Triple("B", "BACK", "PAUSE" to Act.B))
        for ((i, rw) in rows.withIndex()) { val yy = 110 + i * 11; hint(c, x0 + 6, yy, rw.first, rw.second, rw.third.first); rowActs.add(yy to rw.third.second) }
    }

    private val ghostPaint = android.graphics.Paint().apply { isFilterBitmap = false; alpha = 215 }
    private val dimPaint = android.graphics.Paint().apply { isFilterBitmap = false; alpha = 70 }
    private fun dim(c: Canvas) { fill.color = Color.argb(150, 6, 8, 14); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), fill) }
    private fun frameRect(c: Canvas, l: Int, tp: Int, r: Int, b: Int, col: Int) { rect(c, l, tp, r, tp + 1, col); rect(c, l, b - 1, r, b, col); rect(c, l, tp, l + 1, b, col); rect(c, r - 1, tp, r, b, col) }
    private fun corners(c: Canvas, x: Int, y: Int, w: Int, h: Int, col: Int) {
        for ((dx, dy) in listOf(0 to 0, w - 4 to 0, 0 to h - 1, w - 4 to h - 1)) rect(c, x + dx, y + dy, x + dx + 4, y + dy + 1, col)
        for ((dx, dy) in listOf(0 to 0, w - 1 to 0, 0 to h - 4, w - 1 to h - 4)) rect(c, x + dx, y + dy, x + dx + 1, y + dy + 4, col)
    }
}
