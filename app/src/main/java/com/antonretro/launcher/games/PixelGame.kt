package com.antonretro.launcher.games

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Environment
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.io.File

/**
 * The shared shell for Retro Launcher's built-in games.
 * - Draws into a small 320x180 canvas, shown at a whole-number scale with sharp (nearest-neighbour) pixels.
 * - Fixed-step update at 60 steps a second, drawing once per screen frame.
 * - Input arrives as plain actions (up/down/left/right/A/B/X/Y, LB/RB, Start) from remote, pad or keyboard,
 *   plus taps/clicks in canvas coordinates; the launcher passes keys in.
 * - Art and sounds load from the APK's assets/games/<id>/, unless a file with the same name is in
 *   /sdcard/RetroLauncher/GameArt/<id>/ (so new art can be dropped in without changing code; see ASSETS.md).
 * - High scores are kept per game on the box.
 */
@SuppressLint("ViewConstructor")
abstract class PixelGame(ctx: Context, val id: String) : View(ctx), Choreographer.FrameCallback {
    enum class Act { UP, DOWN, LEFT, RIGHT, A, B, X, Y, LB, RB, START }

    companion object { const val W = 320; const val H = 180; const val STEP_NS = 1_000_000_000L / 60 }

    /** speed experiments only: words in RetroLauncher/.perf-exp switch parts off for measuring */
    protected val exp: String = try { File(Environment.getExternalStorageDirectory(), "RetroLauncher/.perf-exp").readText() } catch (e: Exception) { "" }
    /** Use for every bitmap a game draws: sharp pixels when scaled up. */
    protected val sharp = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val dst = Rect()
    private var last = 0L; private var acc = 0L
    var running = false; private set
    var paused = false
    /** Called on game over and when the player asks to leave. */
    var onQuit: (() -> Unit)? = null
    var onPauseAsk: (() -> Unit)? = null

    // ------------------------------------------------------------------ assets
    private val override get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/GameArt/$id")
    fun bitmap(name: String): Bitmap {
        val f = File(override, name)
        val opts = BitmapFactory.Options().apply { inScaled = false }
        if (f.isFile) BitmapFactory.decodeFile(f.path, opts)?.let { return it }
        return context.assets.open("games/$id/$name").use { BitmapFactory.decodeStream(it, null, opts)!! }
    }
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val sounds = HashMap<String, Int>()
    fun loadSound(name: String) {
        try {
            val f = File(override, "$name.wav")
            sounds[name] = if (f.isFile) pool.load(f.path, 1) else context.assets.openFd("games/$id/$name.wav").use { pool.load(it, 1) }
        } catch (_: Exception) {}
    }
    fun play(name: String, vol: Float = .8f) { sounds[name]?.let { pool.play(it, vol, vol, 1, 0, 1f) } }

    // ------------------------------------------------------------------ high score
    private val prefs get() = context.getSharedPreferences("games", Context.MODE_PRIVATE)
    var best: Int
        get() = prefs.getInt("best:$id", 0)
        set(v) { prefs.edit().putInt("best:$id", v).apply() }

    // ------------------------------------------------------------------ loop
    fun start() { if (running) return; running = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }
    fun release() { stop(); pool.release() }

    override fun doFrame(now: Long) {
        if (!running) return
        if (last == 0L) last = now
        acc += (now - last).coerceAtMost(STEP_NS * 6); last = now   // never try to catch up more than 6 steps
        if (!paused) while (acc >= STEP_NS) { update(); acc -= STEP_NS } else acc = 0
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(cv: Canvas) {
        val s = maxOf(1, minOf(width / W, height / H))      // whole-number scale: every pixel the same size
        val w = W * s; val h = H * s
        dst.set((width - w) / 2, (height - h) / 2, (width - w) / 2 + w, (height - h) / 2 + h)
        // only the letterbox needs black: at 1280x720 the game covers the whole screen (one full-screen fill less per frame)
        if (dst.left > 0 || dst.top > 0) cv.drawColor(0xFF000000.toInt())
        // the game draws straight onto the screen at a whole-number scale: its sprite sheet and background reach the
        // graphics chip once, instead of a freshly painted picture every frame (that cost ~30 ms a frame on the Mali-450)
        cv.save(); cv.clipRect(dst); cv.translate(dst.left.toFloat(), dst.top.toFloat()); cv.scale(s.toFloat(), s.toFloat())
        render(cv)
        cv.restore()
    }

    // ------------------------------------------------------------------ input
    /** Remote, pad and keyboard keys mapped to actions. Returns true if used. */
    fun key(e: KeyEvent): Boolean {
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
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (a == Act.B || a == Act.START) { if (e.repeatCount == 0) onPauseAsk?.invoke(); return true }
            if (!paused) action(a, e.repeatCount > 0)
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && !paused) {
            val s = maxOf(1, minOf(width / W, height / H)).toFloat()
            tap(((e.x - dst.left) / s).toInt(), ((e.y - dst.top) / s).toInt())
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL && !paused) { if (e.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) action(Act.RB, false); return true }
        if (e.action == MotionEvent.ACTION_HOVER_MOVE && !paused) {
            val s = maxOf(1, minOf(width / W, height / H)).toFloat()
            hover(((e.x - dst.left) / s).toInt(), ((e.y - dst.top) / s).toInt()); return true
        }
        return super.onGenericMotionEvent(e)
    }

    abstract fun update()
    abstract fun render(c: Canvas)
    abstract fun action(a: Act, repeat: Boolean)
    open fun tap(x: Int, y: Int) {}
    open fun hover(x: Int, y: Int) {}
    abstract fun restart()
}
