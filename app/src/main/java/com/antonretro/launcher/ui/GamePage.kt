package com.antonretro.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.Perf
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit
import kotlin.math.*

/**
 * The game page between the shelf and the game (PLAN.md "The 3D game page", mockup/game-page.html, approved):
 * the game's box in 3D on the left (cover on the front, a spine and top in the system's colour, a slow bob and turn,
 * a soft shadow), its neighbours small and dim on either side, and on the right the title, system, what you can do
 * (Continue / Start from the beginning / Play, Options) over a blurred wash of the cover. Left/Right or LB/RB flip to
 * the next game (holding flips fast), Up/Down picks a button, OK chooses, Back returns to the shelf.
 *
 * Drawn natively (Canvas + a perspective Matrix per face) so it runs at the TV's full frame rate. The first seconds are
 * measured; if the box can't hold ~50 fps it switches to a flat, gently tilted cover (no side faces) for the session.
 */
class GamePage(ctx: Context) : FrameLayout(ctx) {

    /** What the page shows for one game. [cover] may arrive later (downloaded box art). */
    class Entry(val key: String, val title: String, val system: String, val colour: Int, val aspect: Float,
                val note: String, val hasSave: Boolean, var cover: Bitmap? = null,
                /** the short console name printed on the spine ("NES", "GAME BOY") */ val badge: String = system)

    var onPlay: ((Entry, fresh: Boolean) -> Unit)? = null
    var onOptions: ((Entry) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    /** asked for a game's cover when it comes into view; call [coverArrived] when it's ready */
    var wantCover: ((Entry) -> Unit)? = null

    private var list: List<Entry> = emptyList()
    private var cur = 0
    private var sel = 0
    val showing get() = visibility == View.VISIBLE

    private val wash = WashView(ctx)
    private val boxes = BoxView(ctx)
    private val info = LinearLayout(ctx)
    private val title = Kit.text(ctx, 40f, true)
    private val sub = Kit.text(ctx, 19f, true, Kit.TEXT_DIM)
    private val note = Kit.text(ctx, 16f, false, Kit.TEXT_DIM)
    private val btnBox = LinearLayout(ctx)
    private val btns = ArrayList<TextView>()
    private var actions: List<() -> Unit> = emptyList()
    private val hints = Kit.text(ctx, 16f, true, 0xFFC8CCD4.toInt())

    init {
        visibility = View.GONE; isClickable = true
        setBackgroundColor(0xFF0B0D12.toInt())
        addView(wash, LayoutParams(-1, -1))
        addView(boxes, LayoutParams(Kit.dpi(ctx, 640f), -1))
        info.orientation = LinearLayout.VERTICAL
        title.isSingleLine = false; title.maxLines = 3
        info.addView(title)
        info.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(ctx, 10f) })
        info.addView(note, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(ctx, 6f) })
        btnBox.orientation = LinearLayout.VERTICAL; btnBox.clipChildren = false; btnBox.clipToPadding = false
        info.clipChildren = false; info.clipToPadding = false
        info.addView(btnBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(ctx, 26f); rightMargin = Kit.dpi(ctx, 24f) })
        addView(info, LayoutParams(Kit.dpi(ctx, 520f), -2).apply { leftMargin = Kit.dpi(ctx, 680f); topMargin = Kit.dpi(ctx, 70f) })
        hints.gravity = Gravity.CENTER
        hints.text = "OK  Choose        ◀ ▶  Other games        Back  To the shelf"
        addView(hints, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = Kit.dpi(ctx, 26f) })
    }

    fun show(entries: List<Entry>, index: Int) {
        list = entries; cur = index.coerceIn(0, entries.size - 1); sel = 0
        visibility = View.VISIBLE; alpha = 0f; bringToFront()
        animate().alpha(1f).setDuration(Kit.t(300)).start()
        boxes.enter()
        refresh(); askCovers()
        boxes.startFrames()
    }

    fun hide() {
        boxes.stopFrames()
        animate().alpha(0f).setDuration(Kit.t(220)).withEndAction { visibility = View.GONE }.start()
        onClose?.invoke()
    }

    /** A cover finished downloading: use it if that game is on screen. */
    fun coverArrived(key: String, b: Bitmap?) {
        val e = list.firstOrNull { it.key == key } ?: return
        if (b == null) return
        e.cover = b; boxes.dropCache(key)
        if (list.getOrNull(cur)?.key == key) wash.set(b)
        boxes.invalidate()
    }

    private fun askCovers() { for (k in -1..1) list.getOrNull(cur + k)?.let { if (it.cover == null) wantCover?.invoke(it) } }

    private fun refresh() {
        val e = list[cur]
        title.text = e.title
        sub.text = e.system
        note.text = e.note
        note.visibility = if (e.note.isEmpty()) View.GONE else View.VISIBLE
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (e.hasSave) { items.add("Continue" to { onPlay?.invoke(e, false) }); items.add("Start from the beginning" to { onPlay?.invoke(e, true) }) }
        else items.add("Play" to { onPlay?.invoke(e, false) })
        items.add("Options" to { onOptions?.invoke(e) })
        btnBox.removeAllViews(); btns.clear(); actions = items.map { it.second }
        for ((i, it) in items.withIndex()) {
            val b = Kit.text(context, 20f, true).apply { text = it.first; gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(context, 26f), 0, Kit.dpi(context, 26f), 0) }
            b.setOnClickListener { sel = i; choose() }
            b.setOnHoverListener { _, ev -> if (ev.action == MotionEvent.ACTION_HOVER_ENTER) { sel = i; paint() }; false }
            btnBox.addView(b, LinearLayout.LayoutParams(-1, Kit.dpi(context, 50f)).apply { bottomMargin = Kit.dpi(context, 10f) })
            btns.add(b)
        }
        sel = sel.coerceIn(0, btns.size - 1); paint()
        e.cover?.let { wash.set(it) } ?: wash.set(null, e.colour)
    }

    private fun paint() {
        for ((i, b) in btns.withIndex()) {
            val on = i == sel
            b.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(context, Kit.pill(50f)); setColor(if (on) Kit.FOCUS_FILL else 0x14FFFFFF); setStroke(1, if (on) 0 else 0x1AFFFFFF) }
            b.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
            b.animate().scaleX(if (on) 1.03f else 1f).scaleY(if (on) 1.03f else 1f).setDuration(Kit.t(150)).start()
            b.pivotX = 0f
        }
    }

    private fun choose() { actions.getOrNull(sel)?.invoke() }

    private var lastFlip = 0L
    private fun flip(d: Int, held: Boolean) {
        val n = cur + d; if (n < 0 || n >= list.size) return
        cur = n; sel = 0
        // held: flip fast (no turn animation, just slide) so a long list is quick to get through
        boxes.slide(d, if (held) 160L else 480L)
        info.animate().cancel(); info.alpha = .0f; info.translationX = Kit.dp(context, 24f) * d
        info.animate().alpha(1f).translationX(0f).setDuration(Kit.t(if (held) 120L else 300L)).start()
        refresh(); askCovers()
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) Perf.note("game page key ${e.keyCode} cur=$cur of ${list.size}")
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val held = e.repeatCount > 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (sel > 0) { sel--; paint() } }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (sel < btns.size - 1) { sel++; paint() } }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> flip(-1, held)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> flip(1, held)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (!held) choose()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> if (!held) hide()
            else -> return false
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) { val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL); if (v != 0f) flip(if (v < 0) 1 else -1, false); return true }
        return super.onGenericMotionEvent(e)
    }

    // ------------------------------------------------------------------ the blurred colour wash behind everything
    private inner class WashView(ctx: Context) : View(ctx) {
        private var a: Bitmap? = null; private var b: Bitmap? = null
        private var fade = 1f
        private var colA = 0; private var colB = 0
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val veil = Paint()
        private val dst = RectF()
        fun set(cover: Bitmap?, colour: Int = 0) {
            // a tiny copy stretched up with filtering is a cheap, soft blur
            val small = cover?.let { Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(it, 6, (6f * it.height / it.width).toInt().coerceAtLeast(1), true), 48, (48f * it.height / it.width).toInt().coerceAtLeast(1), true) }
            b = a; colB = colA; a = small; colA = colour
            fade = 0f
            ValueAnimator.ofFloat(0f, 1f).apply { duration = Kit.t(450); addUpdateListener { fade = it.animatedValue as Float; invalidate() }; start() }
        }
        override fun onDraw(c: Canvas) {
            dst.set(-80f, -80f, width + 80f, height + 80f)
            fun layer(bm: Bitmap?, col: Int, al: Float) {
                if (al <= 0f) return
                p.alpha = (al * 255 * .5f).toInt()
                if (bm != null) c.drawBitmap(bm, null, dst, p)
                else if (col != 0) { p.color = col; p.alpha = (al * 255 * .35f).toInt(); c.drawRect(dst, p) }
            }
            layer(b, colB, 1f - fade); layer(a, colA, fade)
            // darker on the right so the text reads, lighter round the box
            veil.shader = RadialGradient(width * .28f, height * .55f, width * .8f, intArrayOf(0x33000000, 0xCC000000.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
        }
    }

    // ------------------------------------------------------------------ the boxes in 3D
    private inner class BoxView(ctx: Context) : View(ctx), Choreographer.FrameCallback {
        private var t0 = System.nanoTime()
        private var running = false
        private var shift = 0f           // -1..1 while sliding to a neighbour
        private var entering = 1f        // 0..1 open animation
        private val m = Matrix()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val face = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
        private val spines = HashMap<String, Bitmap>()
        private val stand = HashMap<String, Bitmap>()
        private val pts = FloatArray(8); private val src = FloatArray(8)
        private val path = Path()
        // frame-rate check over the first ~3 s on screen
        private var frames = 0; private var since = 0L; private var measured = false
        var flat = false; private set

        fun dropCache(key: String) { spines.remove(key); stand.remove(key) }

        fun enter() {
            entering = 0f
            ValueAnimator.ofFloat(0f, 1f).apply { duration = Kit.t(520); interpolator = Kit.EASE; addUpdateListener { entering = it.animatedValue as Float }; start() }
        }
        fun slide(d: Int, ms: Long) {
            shift = d.toFloat()
            ValueAnimator.ofFloat(d.toFloat(), 0f).apply { duration = Kit.t(ms); interpolator = Kit.EASE; addUpdateListener { shift = it.animatedValue as Float }; start() }
        }
        fun startFrames() { if (!running) { running = true; frames = 0; since = 0L; Choreographer.getInstance().postFrameCallback(this) } }
        fun stopFrames() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

        override fun doFrame(now: Long) {
            if (!running) return
            if (!measured) {
                if (since == 0L) since = now
                frames++
                val dt = (now - since) / 1e9
                if (dt > 3.0) {
                    val fps = frames / dt; measured = true
                    Perf.note("game page fps=${"%.1f".format(fps)}" + if (fps < 50) " -> flat covers" else "")
                    if (fps < 50) flat = true
                }
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }

        /** A stand-in cover while the real one loads: the system colour, the title, the system name. */
        private fun standIn(e: Entry): Bitmap = stand.getOrPut(e.key) {
            val w = 300; val h = (300 / e.aspect).toInt()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), e.colour, 0xFF111111.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null; p.color = Color.WHITE; p.textSize = 30f; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
            var y = h * .42f; var line = ""
            for (word in e.title.split(' ')) { val n = if (line.isEmpty()) word else "$line $word"; if (p.measureText(n) > 260 && line.isNotEmpty()) { c.drawText(line, 150f, y, p); line = word; y += 34f } else line = n }
            c.drawText(line, 150f, y, p)
            p.textSize = 16f; p.typeface = Typeface.DEFAULT; p.color = 0xB3FFFFFF.toInt(); c.drawText(e.system, 150f, h - 22f, p)
            b
        }

        /** The colours a printed box takes from its cover: the left edge strip (what the spine shows) and the
         *  overall colour (the top). Worked out once per cover from a tiny copy. */
        private val tones = HashMap<String, IntArray>()
        private fun tonesOf(e: Entry): IntArray = tones.getOrPut(e.key + (if (e.cover != null) "+" else "")) {
            val src = e.cover ?: standIn(e)
            val small = Bitmap.createScaledBitmap(src, 24, 32, true)
            fun avg(x0: Int, x1: Int): Int {
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (y in 0 until small.height) for (x in x0 until x1) { val c = small.getPixel(x, y); r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++ }
                return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            }
            val edge = avg(0, 2); val main = avg(0, small.width)
            if (small !== src) small.recycle()
            intArrayOf(edge, main)
        }
        private fun contrast(c: Int): Int { val l = .299 * Color.red(c) + .587 * Color.green(c) + .114 * Color.blue(c); return if (l > 150) 0xFF111111.toInt() else Color.WHITE }

        /** The spine as a printed box has it: one solid colour from the cover's edge, the console's name at the top and
         *  the title along it in a crisp contrasting colour, thin light edges. */
        private fun spine(e: Entry, wPx: Int, hPx: Int): Bitmap = spines.getOrPut(e.key) {
            val w = 60; val h = (60f * hPx / wPx.coerceAtLeast(1)).toInt().coerceIn(120, 1200)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val bg = tonesOf(e)[0]; val ink = contrast(bg)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawColor(bg)
            // thin edges: a light line where the spine meets the front and the back
            p.color = Color.argb(70, 255, 255, 255); c.drawRect(0f, 0f, 2f, h.toFloat(), p); c.drawRect(w - 2f, 0f, w.toFloat(), h.toFloat(), p)
            p.color = ink; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
            c.save(); c.rotate(90f, w / 2f, h / 2f)
            // after the turn, the spine's length runs left to right through the middle
            val left = w / 2f - h / 2f
            p.textSize = 15f; p.alpha = 200
            val badge = e.badge.uppercase()
            val badgeW = p.measureText(badge) + 22f
            c.drawText(badge, left + 14f + badgeW / 2f - 11f, h / 2f + 6f, p)
            p.alpha = 255; p.strokeWidth = 1.5f
            c.drawLine(left + 14f + badgeW, h / 2f - 12f, left + 14f + badgeW, h / 2f + 12f, p)
            p.textSize = 25f
            val room = h - badgeW - 44f
            val t = TextUtilsEllipsize(e.title, p, room)
            c.drawText(t, left + 14f + badgeW + 16f + room / 2f, h / 2f + 9f, p)
            c.restore()
            b
        }
        private fun TextUtilsEllipsize(s: String, p: Paint, max: Float): String { if (p.measureText(s) <= max) return s; var t = s; while (t.length > 1 && p.measureText("$t…") > max) t = t.dropLast(1); return "$t…" }
        private fun darker(c: Int) = Color.rgb((Color.red(c) * .25f).toInt(), (Color.green(c) * .25f).toInt(), (Color.blue(c) * .25f).toInt())

        override fun onDraw(c: Canvas) {
            if (list.isEmpty()) return
            val sec = (System.nanoTime() - t0) / 1e9f
            val dp = resources.displayMetrics.density
            // neighbours first (behind), then the main box
            for (k in intArrayOf(-2, 2, -1, 1, 0)) {
                val i = cur + k; val e = list.getOrNull(i) ?: continue
                val pos = k + shift                      // where it sits now: 0 = centre, ±1 = side
                if (abs(pos) > 1.6f) continue
                drawBox(c, e, pos, sec, dp)
            }
        }

        private fun drawBox(c: Canvas, e: Entry, pos: Float, sec: Float, dp: Float) {
            val a = abs(pos).coerceAtMost(1f)
            val main = 1f - a                              // 1 at the centre
            val H = (150f + 180f * main) * dp * (.82f + .18f * entering)
            val W = H * e.aspect; val D = H * .17f
            val cx = width * .5f + pos * width * .33f
            val cy = (height * .47f) + (1f - main) * 95f * dp + (1f - entering) * 60f * dp
            val bob = sin(sec * 2f * PI.toFloat() / 4.2f) * 7f * dp * main
            // a slow turn on the centre box; side boxes face inward
            val yawDeg = if (flat) (6f + 4f * sin(sec * 2f * PI.toFloat() / 10f)) * main - pos * 40f
                         else (26f + 8f * sin(sec * 2f * PI.toFloat() / 10f)) * main - pos * 38f
            val pitchDeg = 6f * main
            val alpha = (255 * (1f - .6f * a) * entering).toInt().coerceIn(0, 255)
            val yaw = Math.toRadians(yawDeg.toDouble()).toFloat(); val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
            val persp = 900f * dp

            // shadow on the floor under the centre box
            if (main > .05f) {
                val sw = W * .9f * (1f - .14f * (bob / (7f * dp) + 1f) / 2f)
                shadow.shader = RadialGradient(cx, cy + H / 2f + 34f * dp, sw / 2f, intArrayOf(((0x8C * main * entering).toInt() shl 24), 0), null, Shader.TileMode.CLAMP)
                c.save(); c.scale(1f, .16f, cx, cy + H / 2f + 34f * dp); c.drawCircle(cx, cy + H / 2f + 34f * dp, sw / 2f, shadow); c.restore()
            }

            // the box's 8 corners, centred on the origin, rotated, then projected
            fun proj(x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
                // yaw about Y, then pitch about X
                val x1 = x * cos(yaw) + z * sin(yaw); val z1 = -x * sin(yaw) + z * cos(yaw)
                val y2 = y * cos(pitch) - z1 * sin(pitch); val z2 = y * sin(pitch) + z1 * cos(pitch)
                val s = persp / (persp - z2)
                out[at] = cx + x1 * s; out[at + 1] = cy + bob + y2 * s
            }
            fun facing(nx: Float, ny: Float, nz: Float): Boolean {
                val z1 = -nx * sin(yaw) + nz * cos(yaw); val z2 = ny * sin(pitch) + z1 * cos(pitch)
                return z2 > 0.02f
            }
            val hw = W / 2f; val hh = H / 2f; val hd = D / 2f
            fun quad(bm: Bitmap?, colour: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float, shade: Float) {
                proj(x0, y0, z0, pts, 0); proj(x1, y1, z1, pts, 2); proj(x2, y2, z2, pts, 4); proj(x3, y3, z3, pts, 6)
                if (bm != null) {
                    src[0] = 0f; src[1] = 0f; src[2] = bm.width.toFloat(); src[3] = 0f; src[4] = bm.width.toFloat(); src[5] = bm.height.toFloat(); src[6] = 0f; src[7] = bm.height.toFloat()
                    m.setPolyToPoly(src, 0, pts, 0, 4)
                    c.save(); c.concat(m); paint.alpha = alpha; c.drawBitmap(bm, 0f, 0f, paint); c.restore()
                    if (shade > 0f) { path.reset(); path.moveTo(pts[0], pts[1]); path.lineTo(pts[2], pts[3]); path.lineTo(pts[4], pts[5]); path.lineTo(pts[6], pts[7]); path.close(); face.shader = null; face.color = Color.argb((shade * alpha).toInt(), 0, 0, 0); c.drawPath(path, face) }
                } else {
                    path.reset(); path.moveTo(pts[0], pts[1]); path.lineTo(pts[2], pts[3]); path.lineTo(pts[4], pts[5]); path.lineTo(pts[6], pts[7]); path.close()
                    face.shader = null; face.color = colour; face.alpha = alpha; c.drawPath(path, face)
                }
            }
            val cover = e.cover ?: standIn(e)
            if (!flat) {
                // sides that face the viewer (a box is convex, so visible faces never overlap: no sorting needed)
                val sp = spine(e, D.toInt(), H.toInt())
                if (facing(-1f, 0f, 0f)) quad(sp, 0, -hw, -hh, -hd, -hw, -hh, hd, -hw, hh, hd, -hw, hh, -hd, .28f)   // left spine
                if (facing(1f, 0f, 0f)) quad(sp, 0, hw, -hh, hd, hw, -hh, -hd, hw, hh, -hd, hw, hh, hd, .28f)        // right spine
                if (facing(0f, -1f, 0f)) quad(null, tonesOf(e)[1], -hw, -hh, -hd, hw, -hh, -hd, hw, -hh, hd, -hw, -hh, hd, 0f)  // top
                if (facing(0f, 1f, 0f)) quad(null, tonesOf(e)[1], -hw, hh, hd, hw, hh, hd, hw, hh, -hd, -hw, hh, -hd, 0f)      // bottom
            }
            if (facing(0f, 0f, 1f)) {
                quad(cover, 0, -hw, -hh, hd, hw, -hh, hd, hw, hh, hd, -hw, hh, hd, .5f * a)
                // a soft light sweeping across the front of the centre box every few seconds
                if (main > .5f) {
                    val ph = ((sec % 6f) / 6f); if (ph > .55f) {
                        val x = -1.2f + (ph - .55f) / .45f * 2.6f
                        m.setPolyToPoly(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 0, pts, 0, 4)
                        shine.shader = LinearGradient(x - .25f, 0f, x + .25f, .3f, intArrayOf(0, 0x38FFFFFF, 0), null, Shader.TileMode.CLAMP)
                        c.save(); c.concat(m); c.drawRect(0f, 0f, 1f, 1f, shine); c.restore()
                    }
                }
            }
        }
        private fun lighter(c: Int) = Color.rgb(min(255, Color.red(c) + 30), min(255, Color.green(c) + 30), min(255, Color.blue(c) + 30))
    }
}
