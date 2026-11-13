package com.antonretro.launcher.games

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Environment
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.random.Random

/**
 * The shared shell for RetroOS's built-in games. Every game gets the same finished presentation:
 * - a 320x180 pixel canvas drawn straight to the screen at a whole-number scale with sharp pixels;
 * - screens: title (with its own menu), play, pause, how to play, best scores, options, game over / new best,
 *   all drawn in the game's own pixel style (carved-wood panels, a 5x7 pixel font, pixel buttons), never the
 *   launcher's glass dialogs;
 * - a pixel button-hint panel whose glyphs follow the last input used (pad letters or remote keys);
 * - effects: screen shake, particles and floating text;
 * - fixed-step logic at 60 steps a second, best scores and options saved on the box;
 * - art and sounds from assets/games/<id>/ (or /sdcard/RetroLauncher/GameArt/<id>/ to swap them; see ASSETS.md).
 */
@SuppressLint("ViewConstructor")
abstract class PixelGame(ctx: Context, val id: String, val title: String) : View(ctx), Choreographer.FrameCallback {
    enum class Act { UP, DOWN, LEFT, RIGHT, A, B, X, Y, LB, RB, START }
    enum class Screen { TITLE, PLAY, PAUSE, HOWTO, BEST, OPTIONS, OVER }

    companion object {
        const val W = 320; const val H = 180; const val STEP_NS = 1_000_000_000L / 60
        // the one palette every built-in game uses
        val INK = Color.rgb(43, 20, 8); val BONE = Color.rgb(244, 234, 213)
        val WOOD = Color.rgb(120, 70, 34); val WOOD_L = Color.rgb(168, 108, 58); val WOOD_D = Color.rgb(72, 38, 16)
        val GOLD = Color.rgb(255, 204, 92); val TEAL = Color.rgb(40, 156, 170); val WELL = Color.rgb(18, 22, 30)
        val RED = Color.rgb(230, 72, 60); val DIM = Color.rgb(170, 160, 140)
    }

    /** speed experiments only: words in RetroLauncher/.perf-exp switch parts off for measuring */
    protected val exp: String = try { File(Environment.getExternalStorageDirectory(), "RetroLauncher/.perf-exp").readText() } catch (e: Exception) { "" }
    /** Use for every bitmap a game draws: sharp pixels when scaled up. */
    protected val sharp = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    protected val fill = Paint()
    private val dst = Rect()
    private var last = 0L; private var acc = 0L
    var running = false; private set
    /** frames since the game was opened: drives every little animation */
    protected var t = 0
    var screen = Screen.TITLE; protected set
    var onQuit: (() -> Unit)? = null
    protected val rnd = Random(System.nanoTime())

    // ------------------------------------------------------------------ assets
    private val override get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/GameArt/$id")
    fun bitmap(name: String, game: String = id): Bitmap {
        val f = File(File(Environment.getExternalStorageDirectory(), "RetroLauncher/GameArt/$game"), name)
        val opts = BitmapFactory.Options().apply { inScaled = false }
        if (f.isFile) BitmapFactory.decodeFile(f.path, opts)?.let { return it }
        return context.assets.open("games/$game/$name").use { BitmapFactory.decodeStream(it, null, opts)!! }
    }
    private val pool = SoundPool.Builder().setMaxStreams(5).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val sounds = HashMap<String, Int>()
    fun loadSound(name: String) {
        try {
            val f = File(override, "$name.wav")
            sounds[name] = if (f.isFile) pool.load(f.path, 1) else context.assets.openFd("games/$id/$name.wav").use { pool.load(it, 1) }
        } catch (_: Exception) {}
    }
    fun play(name: String, vol: Float = .8f) { val v = vol * sfxVol / 10f; if (v > 0f) sounds[name]?.let { pool.play(it, v, v, 1, 0, 1f) } }

    // ------------------------------------------------------------------ music: one looping track at a time
    private var player: android.media.MediaPlayer? = null
    private var track: String? = null
    private var hostPaused = false
    /** Which track should be playing now (null for none). Games decide; the shell switches tracks. */
    open fun wantMusic(): String? = null
    private fun musicVolume(): Float = musicVol / 10f * (if (screen == Screen.PLAY || screen == Screen.TITLE) .55f else .25f)
    private fun updateMusic() {
        val want = if (hostPaused || !running || musicVol == 0) null else wantMusic()
        if (want != track) {
            player?.release(); player = null; track = want
            if (want != null) try {
                val f = File(override, "$want.wav")
                player = android.media.MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    if (f.isFile) setDataSource(f.path) else context.assets.openFd("games/$id/$want.wav").use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    isLooping = true; prepare(); start()
                }
            } catch (e: Exception) { player = null }
        }
        player?.let { val v = musicVolume(); it.setVolume(v, v) }
    }
    /** The launcher went to the background (another app, the screensaver): music stops; it comes back on return. */
    fun hostPause(paused: Boolean) { hostPaused = paused; updateMusic() }

    // ------------------------------------------------------------------ saved things
    private val prefs get() = context.getSharedPreferences("games", Context.MODE_PRIVATE)
    var musicVol: Int get() = prefs.getInt("music:$id", 7); set(v) { prefs.edit().putInt("music:$id", v).apply(); updateMusic() }
    var sfxVol: Int get() = prefs.getInt("sfx:$id", 8); set(v) { prefs.edit().putInt("sfx:$id", v).apply() }
    var startLevel: Int get() = prefs.getInt("level:$id", 1); set(v) { prefs.edit().putInt("level:$id", v).apply() }
    val scores: List<Int> get() = prefs.getString("top:$id", "")!!.split(',').mapNotNull { it.toIntOrNull() }
    val best: Int get() = scores.maxOrNull() ?: 0
    /** Keeps the five best. Returns true when this is a new best. */
    private fun record(score: Int): Boolean {
        val wasBest = best
        val top = (scores + score).sortedDescending().take(5)
        prefs.edit().putString("top:$id", top.joinToString(",")).apply()
        return score > wasBest && score > 0
    }

    // ------------------------------------------------------------------ pixel text
    private val font = bitmap("font.png", "shared")
    private val chars = context.assets.open("games/shared/font.txt").use { it.readBytes().toString(Charsets.UTF_8) }
    private val icons = bitmap("icons.png", "shared")
    private val tint = Paint().apply { isFilterBitmap = false }
    private val s0 = Rect(); private val d0 = Rect()
    fun textWidth(s: String, scale: Int = 1) = s.length * 6 * scale - scale
    /** Pixel text. align: -1 left, 0 centre, 1 right. outline: a 1-px ink border (always readable over art). */
    fun text(c: Canvas, s: String, x: Int, y: Int, color: Int = BONE, scale: Int = 1, align: Int = -1, outline: Boolean = true) {
        val str = s.uppercase()
        val x0 = when (align) { 0 -> x - textWidth(str, scale) / 2; 1 -> x - textWidth(str, scale); else -> x }
        fun pass(dx: Int, dy: Int, col: Int) {
            tint.colorFilter = PorterDuffColorFilter(col, PorterDuff.Mode.SRC_IN)
            var cx = x0 + dx
            for (ch in str) {
                val i = chars.indexOf(ch).let { if (it < 0) 0 else it }
                if (i > 0) { s0.set(i * 6, 0, i * 6 + 5, 7); d0.set(cx, y + dy, cx + 5 * scale, y + dy + 7 * scale); c.drawBitmap(font, s0, d0, tint) }
                cx += 6 * scale
            }
        }
        if (outline) for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, 1 to 1)) pass(dx, dy, INK)
        pass(0, 0, color)
    }
    fun icon(c: Canvas, i: Int, x: Int, y: Int) { s0.set(i * 8, 0, i * 8 + 8, 8); d0.set(x, y, x + 8, y + 8); c.drawBitmap(icons, s0, d0, sharp) }

    // ------------------------------------------------------------------ panels and buttons (carved wood)
    fun rect(c: Canvas, l: Int, tp: Int, r: Int, b: Int, col: Int) { fill.color = col; c.drawRect(l.toFloat(), tp.toFloat(), r.toFloat(), b.toFloat(), fill) }
    /** A carved wooden panel with a lit top edge, a shaded bottom edge and four pegs. */
    fun panel(c: Canvas, l: Int, tp: Int, r: Int, b: Int) {
        rect(c, l, tp, r, b, INK); rect(c, l + 1, tp + 1, r - 1, b - 1, WOOD_D); rect(c, l + 1, tp + 1, r - 2, b - 2, WOOD_L); rect(c, l + 2, tp + 2, r - 2, b - 2, WOOD)
        for (yy in tp + 6 until b - 3 step 5) rect(c, l + 3, yy, r - 3, yy + 1, Color.rgb(110, 63, 30))     // grain
        for ((px, py) in listOf(l + 3 to tp + 3, r - 4 to tp + 3, l + 3 to b - 4, r - 4 to b - 4)) rect(c, px, py, px + 1, py + 1, GOLD)
    }
    /** A dark recessed well for numbers and lists. */
    fun well(c: Canvas, l: Int, tp: Int, r: Int, b: Int) { rect(c, l, tp, r, b, INK); rect(c, l + 1, tp + 1, r - 1, b - 1, WELL); rect(c, l + 1, b - 2, r - 1, b - 1, Color.rgb(40, 46, 58)) }
    /** A pixel button; the selected one is bone-white with ink text and a bobbing arrow. */
    fun button(c: Canvas, l: Int, tp: Int, r: Int, b: Int, label: String, selected: Boolean) {
        rect(c, l, tp, r, b, INK)
        rect(c, l + 1, tp + 1, r - 1, b - 1, if (selected) BONE else WOOD_D)
        rect(c, l + 1, b - 2, r - 1, b - 1, if (selected) DIM else Color.rgb(52, 26, 10))
        text(c, label, (l + r) / 2, tp + (b - tp - 7) / 2, if (selected) INK else BONE, align = 0, outline = false)
        if (selected) { val bob = if ((t / 10) % 2 == 0) 0 else 1; text(c, ">", l - 8 - bob, tp + (b - tp - 7) / 2, GOLD) }
    }

    // ------------------------------------------------------------------ menus (title, pause, options, game over)
    class Item(val label: () -> String, val side: ((Int) -> Unit)? = null, val ok: (() -> Unit)? = null)
    protected var items: List<Item> = emptyList()
    protected var sel = 0
    private var back: () -> Unit = {}
    private val itemRects = ArrayList<Rect>()
    protected fun menu(s: Screen, list: List<Item>, onBack: () -> Unit, start: Int = 0) { screen = s; items = list; sel = start; back = onBack }

    fun showTitle() { inGame = false; titleMenu() }
    private fun titleMenu() = menu(Screen.TITLE, listOf(
        Item({ "Play" }, ok = { startPlay() }),
        Item({ "How to play" }, ok = { howTo { showTitle() } }),
        Item({ "Best scores" }, ok = { menu(Screen.BEST, listOf(Item({ "Back" }, ok = { showTitle() })), { showTitle() }) }),
        Item({ "Options" }, ok = { options { showTitle() } }),
        Item({ "Quit" }, ok = { onQuit?.invoke() })), { onQuit?.invoke() })

    private fun startPlay() { restart(); inGame = true; screen = Screen.PLAY; items = emptyList() }
    fun pauseGame() { if (screen == Screen.PLAY) showPause() }
    private fun showPause() { inGame = true; pauseMenu() }
    private fun pauseMenu() = menu(Screen.PAUSE, listOf(
        Item({ "Resume" }, ok = { resume() }),
        Item({ "How to play" }, ok = { howTo { showPause() } }),
        Item({ "Options" }, ok = { options { showPause() } }),
        Item({ "Start again" }, ok = { startPlay() }),
        Item({ "Quit to title" }, ok = { showTitle() })), { resume() })
    private fun resume() { screen = Screen.PLAY; items = emptyList() }
    private fun howTo(then: () -> Unit) = menu(Screen.HOWTO, listOf(Item({ "OK" }, ok = then)), then)
    private fun options(then: () -> Unit) = menu(Screen.OPTIONS, listOf(
        Item({ "Music   < $musicVol >" }, side = { d -> musicVol = (musicVol + d).coerceIn(0, 10) }),
        Item({ "Effects   < $sfxVol >" }, side = { d -> sfxVol = (sfxVol + d).coerceIn(0, 10); play("swap") }),
        Item({ "$levelLabel   $startLevel" }, side = { d -> startLevel = (startLevel + d).coerceIn(1, levelMax) }, ok = { startLevel = startLevel % levelMax + 1 }),
        Item({ "Back" }, ok = then)), then)

    /** The options row that picks where play starts: a speed (Totem Tide) or a level (puzzles, up to the furthest reached). */
    open val levelLabel = "Start speed"
    open val levelMax: Int get() = 9
    /** Puzzle games record a score per finished level, without the game-over screen. */
    protected fun saveScore(score: Int) { record(score) }

    protected var lastScore = 0; protected var newBest = false
    /** Games call this when the game ends. */
    protected fun gameOver(score: Int) {
        lastScore = score; newBest = record(score); inGame = true
        shake(3, 20)
        menu(Screen.OVER, listOf(Item({ "Play again" }, ok = { startPlay() }), Item({ "Title" }, ok = { showTitle() })), { showTitle() })
    }

    // ------------------------------------------------------------------ effects
    private var shakeT = 0; private var shakeA = 0
    fun shake(amount: Int, frames: Int) { if (amount >= shakeA || shakeT == 0) { shakeA = amount; shakeT = frames } }
    private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Int, val col: Int)
    private val bits = ArrayList<Bit>()
    fun burst(x: Int, y: Int, col: Int, n: Int = 6) { repeat(n) { bits.add(Bit(x.toFloat(), y.toFloat(), rnd.nextFloat() * 2.4f - 1.2f, -rnd.nextFloat() * 2.2f - .4f, 22 + rnd.nextInt(14), col)) } }
    private class Pop(val s: String, val x: Int, var y: Float, var life: Int, val col: Int, val scale: Int)
    private val pops = ArrayList<Pop>()
    fun floatText(s: String, x: Int, y: Int, col: Int = GOLD, scale: Int = 1) { pops.add(Pop(s, x, y.toFloat(), 60, col, scale)) }

    // ------------------------------------------------------------------ hints (glyphs follow the last input)
    protected var padInput = false
    /** (pad glyph, remote glyph, what it does) */
    protected fun hint(c: Canvas, x: Int, y: Int, pad: String, remote: String, what: String) {
        val g = if (padInput) pad else remote
        val col = when (g) { "A" -> Color.rgb(96, 184, 72); "B" -> RED; "X" -> Color.rgb(64, 128, 230); "Y" -> GOLD; else -> DIM }
        val w = textWidth(g) + 6
        rect(c, x, y, x + w, y + 11, INK); rect(c, x + 1, y + 1, x + w - 1, y + 10, col)
        text(c, g, x + 3, y + 2, if (col == DIM || col == GOLD) INK else BONE, outline = false)
        text(c, what, x + w + 4, y + 2, BONE)
    }

    // ------------------------------------------------------------------ loop
    fun start() { if (running) return; running = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this); updateMusic() }
    fun release() { stop(); player?.release(); player = null; pool.release() }

    override fun doFrame(now: Long) {
        if (!running) return
        if (last == 0L) last = now
        acc += (now - last).coerceAtMost(STEP_NS * 6); last = now
        while (acc >= STEP_NS) {
            acc -= STEP_NS; t++
            if (screen == Screen.PLAY) update()
            if (shakeT > 0) shakeT--
            val it = bits.iterator(); while (it.hasNext()) { val b = it.next(); b.x += b.vx; b.y += b.vy; b.vy += .12f; if (--b.life <= 0) it.remove() }
            val ip = pops.iterator(); while (ip.hasNext()) { val p = ip.next(); p.y -= .35f; if (--p.life <= 0) ip.remove() }
        }
        if (t % 15 == 0) updateMusic()
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(cv: Canvas) {
        val s = maxOf(1, minOf(width / W, height / H))
        val w = W * s; val h = H * s
        dst.set((width - w) / 2, (height - h) / 2, (width - w) / 2 + w, (height - h) / 2 + h)
        if (dst.left > 0 || dst.top > 0) cv.drawColor(Color.BLACK)
        val sx = if (shakeT > 0) (rnd.nextInt(shakeA * 2 + 1) - shakeA) else 0
        val sy = if (shakeT > 0) (rnd.nextInt(shakeA * 2 + 1) - shakeA) else 0
        cv.save(); cv.clipRect(dst); cv.translate((dst.left + sx * s).toFloat(), (dst.top + sy * s).toFloat()); cv.scale(s.toFloat(), s.toFloat())
        drawScene(cv)
        if (screen == Screen.TITLE || (screen in listOf(Screen.HOWTO, Screen.BEST, Screen.OPTIONS) && !inGame)) drawTitle(cv) else drawPlay(cv)
        for (b in bits) rect(cv, b.x.toInt(), b.y.toInt(), b.x.toInt() + 1, b.y.toInt() + 1, b.col)
        for (p in pops) text(cv, p.s, p.x, p.y.toInt(), p.col, p.scale, align = 0)
        drawMenu(cv)
        cv.restore()
    }

    /** true while a game is in progress behind a menu (pause, its options or how-to). */
    protected var inGame = false

    private fun drawMenu(c: Canvas) {
        itemRects.clear()
        if (items.isEmpty()) return
        when (screen) {
            Screen.TITLE -> { buttons(c, 110, 98, 100, 12, 3); text(c, "BEST ${best}", W - 6, H - 11, DIM, align = 1) }
            Screen.PAUSE -> { dimAll(c); panel(c, 96, 30, 224, 160); text(c, "PAUSED", 160, 40, GOLD, 2, align = 0); buttons(c, 112, 66, 96, 13, 4) }
            Screen.OPTIONS -> { if (inGame) dimAll(c); panel(c, 80, 30, 240, 158); text(c, "OPTIONS", 160, 40, GOLD, 2, align = 0); buttons(c, 96, 62, 128, 13, 5); text(c, "LEFT / RIGHT TO CHANGE", 160, 140, DIM, align = 0) }
            Screen.HOWTO -> {
                if (inGame) dimAll(c); panel(c, 40, 16, 280, 166); text(c, "HOW TO PLAY", 160, 26, GOLD, 2, align = 0)
                well(c, 50, 46, 270, 136)
                howToLines().forEachIndexed { i, l -> text(c, l, 56, 51 + i * 10, BONE) }
                buttons(c, 136, 144, 48, 13, 0)
            }
            Screen.BEST -> {
                panel(c, 90, 24, 230, 166); text(c, "BEST SCORES", 160, 34, GOLD, 2, align = 0); well(c, 104, 54, 216, 130)
                val sc = scores
                for (i in 0 until 5) { val v = sc.getOrNull(i); text(c, "${i + 1}.", 112, 60 + i * 13, DIM); text(c, v?.toString() ?: "---", 208, 60 + i * 13, if (i == 0 && v != null) GOLD else BONE, align = 1) }
                buttons(c, 136, 142, 48, 13, 0)
            }
            Screen.OVER -> {
                dimAll(c); panel(c, 86, 36, 234, 150)
                text(c, "GAME OVER", 160, 46, if ((t / 20) % 2 == 0) RED else Color.rgb(255, 140, 120), 2, align = 0)
                well(c, 104, 66, 216, 98)
                text(c, "SCORE", 112, 71, DIM); text(c, "$lastScore", 208, 71, BONE, align = 1)
                text(c, "BEST", 112, 85, DIM); text(c, "$best", 208, 85, GOLD, align = 1)
                if (newBest) text(c, "NEW BEST!", 160, 104, if ((t / 8) % 2 == 0) GOLD else BONE, align = 0)
                buttons(c, 112, 116, 96, 13, 3)
            }
            else -> {}
        }
    }
    private fun dimAll(c: Canvas) { fill.color = Color.argb(150, 6, 8, 14); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), fill) }
    private fun buttons(c: Canvas, x: Int, y: Int, w: Int, h: Int, gap: Int) {
        for ((i, it) in items.withIndex()) {
            val tp = y + i * (h + gap); val r = Rect(x, tp, x + w, tp + h); itemRects.add(r)
            button(c, x, tp, x + w, tp + h, it.label() + if (it.side != null && i == sel) "" else "", i == sel)
        }
    }

    // ------------------------------------------------------------------ input
    /** Remote, pad and keyboard keys. Returns true if used. */
    fun key(e: KeyEvent): Boolean {
        val src = e.device?.sources ?: 0
        if (e.action == KeyEvent.ACTION_DOWN && e.deviceId >= 0) padInput = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        val a = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Act.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Act.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Act.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Act.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_NUMPAD_ENTER -> Act.A
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> Act.B
            KeyEvent.KEYCODE_BUTTON_X -> Act.X
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU -> Act.Y
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> Act.LB
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Act.RB
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Act.START
            else -> return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val repeat = e.repeatCount > 0
        if (items.isNotEmpty()) {
            when (a) {
                Act.UP -> { sel = (sel - 1 + items.size) % items.size; play("swap", .35f) }
                Act.DOWN -> { sel = (sel + 1) % items.size; play("swap", .35f) }
                Act.LEFT, Act.RIGHT -> items[sel].side?.let { it(if (a == Act.LEFT) -1 else 1); play("swap", .35f) }
                Act.A -> if (!repeat) { val it = items[sel]; (it.ok ?: { it.side?.invoke(1) })(); play("land", .5f) }
                Act.B -> if (!repeat) back()
                Act.START -> if (!repeat && screen == Screen.PAUSE) back()
                else -> {}
            }
            return true
        }
        if (screen == Screen.PLAY) {
            if ((a == Act.B || a == Act.START) && !repeat) { showPause(); return true }
            action(a, repeat)
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        padInput = false
        val (x, y) = toCanvas(e)
        if (items.isNotEmpty()) { itemRects.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { sel = it; val i = items[it]; (i.ok ?: { i.side?.invoke(1) })() }; return true }
        if (screen == Screen.PLAY) tap(x, y)
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val (x, y) = toCanvas(e)
        if (e.action == MotionEvent.ACTION_HOVER_MOVE && items.isNotEmpty()) { itemRects.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { sel = it }; return true }
        if (e.action == MotionEvent.ACTION_SCROLL && screen == Screen.PLAY) { if (e.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) action(Act.RB, false); return true }
        return super.onGenericMotionEvent(e)
    }
    private fun toCanvas(e: MotionEvent): Pair<Int, Int> { val s = maxOf(1, minOf(width / W, height / H)).toFloat(); return ((e.x - dst.left) / s).toInt() to ((e.y - dst.top) / s).toInt() }

    // ------------------------------------------------------------------ what each game provides
    abstract fun restart()
    abstract fun update()
    /** The world behind everything (sky, sea...), drawn on every screen. */
    abstract fun drawScene(c: Canvas)
    /** The title screen's art (logo and so on); the shell draws the menu over it. */
    abstract fun drawTitle(c: Canvas)
    /** The play screen (board, HUD, hints); also shown, dimmed, behind pause and game over. */
    abstract fun drawPlay(c: Canvas)
    abstract fun action(a: Act, repeat: Boolean)
    abstract fun howToLines(): List<String>
    open fun tap(x: Int, y: Int) {}
}
