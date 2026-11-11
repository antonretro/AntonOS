package com.antonretro.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.data.ChannelRepo
import com.antonretro.launcher.data.Store
import com.antonretro.launcher.kit.Backdrop
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit
import java.io.File

/**
 * The key helper (an accessibility service, switched on once over ADB). It sees every key before any app does,
 * and makes sure you can never get stuck: from any app, the quick panel opens with
 *  - the pad's Guide/Xbox button, Select+Start together, or Start held about a second;
 *  - the remote's Back held about a second (a short Back still reaches the app as Back), or Esc held on a keyboard;
 *  - the mouse touching the top edge of the screen (a small Home pill appears).
 * Pressing a trigger again while the panel is open goes straight Home. Inside a game the Guide button,
 * Select+Start and a short Back open the panel at once (it's also the game's pause menu), so RetroArch's
 * own menu never has to appear.
 */
class RetroKeys : AccessibilityService() {
    companion object {
        @Volatile var alive = false
        @Volatile var instance: RetroKeys? = null
        /** The app that was in front just before Retro Launcher came back (so Home knows it came from a game). */
        @Volatile var beforeLauncher: String? = null
        const val HOLD_MS = 900L
    }
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var foreground: String? = null
    private var lastStart = 0L; private var lastSelect = -5000L
    private var startHeld = false; private var backHeld = false; private var backFired = false

    override fun onDestroy() { alive = false; instance = null; removeEdge(); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) e.packageName?.toString()?.let {
            val cls = e.className?.toString() ?: ""
            // our own overlays (hint, corner button, pill) are windows of this package too: they must not count as "the launcher is in front"
            val ownOverlay = it == packageName && !cls.startsWith("com.antonretro")
            if (it != "com.android.systemui" && it != "android" && cls != QuickPanel::class.java.name && !ownOverlay) {
                val was = foreground; foreground = it
                if (it == packageName && was != null && was != packageName) beforeLauncher = was
                if (it != was) entered(was)
            }
        }
    }

    // ------------------------------------------------------------------ showing the way out
    private val prefs get() = getSharedPreferences("keys", Context.MODE_PRIVATE)
    private fun hasPad() = android.view.InputDevice.getDeviceIds().any { id -> android.view.InputDevice.getDevice(id)?.let { !it.isVirtual && (it.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD } == true }
    private fun hasMouse() = android.view.InputDevice.getDeviceIds().any { id -> android.view.InputDevice.getDevice(id)?.let { !it.isVirtual && (it.sources and android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE } == true }

    /** A new app came to the front: in a game, always a short hint; in any other app, a hint the first time only. */
    private fun entered(was: String?) {
        updateCorner()
        if (inLauncher()) return
        val way = if (hasPad()) "Xbox button or hold Start: menu and Home" else "Hold Back: menu and Home"
        // in games the route that works on every pad, helper or not: RetroArch's own Start + Select quit (it saves first)
        if (inGame()) { if (was?.startsWith("com.retroarch") != true) ui.postDelayed({ if (inGame()) hint("Home or Start + Select: menu") }, 1500) }
        else { val k = "hinted:" + foreground; if (!prefs.getBoolean(k, false)) { prefs.edit().putBoolean(k, true).apply(); ui.postDelayed({ hint(way) }, 1500) } }
    }

    private var hintView: TextView? = null
    private val hideHint = Runnable { hintView?.animate()?.alpha(0f)?.setDuration(Kit.t(400))?.withEndAction { hintView?.visibility = View.GONE }?.start() }
    private fun hint(text: String) {
        val h = hintView ?: Kit.text(this, 18f, true).also { t ->
            t.gravity = Gravity.CENTER; t.setPadding(Kit.dpi(this, 24f), 0, Kit.dpi(this, 24f), 0)
            t.background = GradientDrawable().apply { cornerRadius = Kit.dp(this@RetroKeys, 22f); setColor(Color.argb(215, 24, 26, 34)); setStroke(1, Kit.GLASS_EDGE) }
            try {
                wm.addView(t, WindowManager.LayoutParams(-2, Kit.dpi(this, 44f), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = Kit.dpi(this@RetroKeys, 40f) })
            } catch (e: Exception) { Perf.note("hint overlay failed: $e"); return }
            hintView = t
        }
        h.text = text; h.visibility = View.VISIBLE; h.alpha = 0f
        h.animate().alpha(1f).setDuration(Kit.t(300)).start()
        ui.removeCallbacks(hideHint); ui.postDelayed(hideHint, 4500)
    }

    /** With a mouse plugged in, a small Home button sits in the top-right corner of every app and game. */
    private var corner: TextView? = null
    private fun updateCorner() {
        val want = hasMouse() && !inLauncher() && foreground != null && !QuickPanel.showing
        if (!want) { corner?.visibility = View.GONE; return }
        val c = corner ?: Kit.text(this, 22f, true).also { t ->
            t.text = "⌂"; t.gravity = Gravity.CENTER; t.alpha = .55f
            t.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(200, 24, 26, 34)); setStroke(1, Kit.GLASS_EDGE) }
            t.setOnClickListener { trigger("corner button") }
            t.setOnHoverListener { v, ev -> if (ev.action == MotionEvent.ACTION_HOVER_ENTER) v.alpha = 1f else if (ev.action == MotionEvent.ACTION_HOVER_EXIT) v.alpha = .55f; false }
            try {
                wm.addView(t, WindowManager.LayoutParams(Kit.dpi(this, 44f), Kit.dpi(this, 44f), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
                    .apply { gravity = Gravity.TOP or Gravity.END; x = Kit.dpi(this@RetroKeys, 14f); y = Kit.dpi(this@RetroKeys, 14f) })
            } catch (e: Exception) { Perf.note("corner overlay failed: $e"); return }
            corner = t
        }
        c.visibility = View.VISIBLE
    }

    private val devices = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = updateCorner()
        override fun onInputDeviceRemoved(id: Int) = updateCorner()
        override fun onInputDeviceChanged(id: Int) {}
    }

    override fun onServiceConnected() {
        // ask for key events explicitly as well as in the XML (some Android 7 builds ignore the XML flag)
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        if (Perf.file == null) Perf.file = getExternalFilesDir(null)?.let { File(it, "perf.log") }
        Perf.note("keys helper connected")
        alive = true; instance = this
        addEdge()
        try { (getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager).registerInputDeviceListener(devices, ui) } catch (_: Exception) {}
    }

    private val testMode get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/.test-mute").exists()
    private fun inGame() = foreground?.startsWith("com.retroarch") == true
    private fun inLauncher() = foreground == packageName

    /** Opens the quick panel over the current app; if it's already open, goes straight Home. */
    fun trigger(why: String) {
        if (testMode) Perf.note("quick panel trigger $why in $foreground")
        if (QuickPanel.showing) { QuickPanel.current?.goHome(); return }
        corner?.visibility = View.GONE; hintView?.visibility = View.GONE
        // in a game every trigger leads to the one game Home Menu, which lives in Retro Launcher: just go Home
        if (inGame()) { goHome(); return }
        val app = foreground
        if (app == null || app == packageName) { goHome(); return }
        try {
            startActivity(Intent(this, QuickPanel::class.java).putExtra("pkg", app).putExtra("game", inGame())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) { goHome() }   // if the panel can't open over this app, Home is the safe answer
    }

    fun panelClosed() { ui.postDelayed({ updateCorner() }, 300) }

    fun goHome() {
        if (!performGlobalAction(GLOBAL_ACTION_HOME))
            try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private val startHold = Runnable { if (startHeld) { startHeld = false; trigger("hold Start") } }
    private val backHold = Runnable { if (backHeld) { backFired = true; trigger("hold Back") } }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode; val down = e.action == KeyEvent.ACTION_DOWN
        if (down && testMode) Perf.note("keys helper saw $code in $foreground")
        if (QuickPanel.showing) {
            // the panel handles its own keys; a trigger pressed again means "just go Home"
            if (code == KeyEvent.KEYCODE_BUTTON_MODE) { if (down && e.repeatCount == 0) trigger("Guide again"); return true }
            return false
        }
        if (inLauncher()) {
            // Retro Launcher handles everything itself; only the Guide button needs help (it goes Home there too)
            if (code == KeyEvent.KEYCODE_BUTTON_MODE) { if (down && e.repeatCount == 0) goHome(); return true }
            return false
        }

        // Guide / Xbox button
        if (code == KeyEvent.KEYCODE_BUTTON_MODE) { if (down && e.repeatCount == 0) trigger("Guide"); return true }

        // Select + Start pressed within a second of each other
        if (down && code == KeyEvent.KEYCODE_BUTTON_START) lastStart = e.eventTime
        if (down && code == KeyEvent.KEYCODE_BUTTON_SELECT) lastSelect = e.eventTime
        if (down && e.repeatCount == 0 && (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT) && Math.abs(lastStart - lastSelect) < 1000) {
            lastStart = 0L; lastSelect = -5000L; startHeld = false; ui.removeCallbacks(startHold)
            trigger("Select+Start"); return true
        }

        // Start held: Start still reaches the app (games pause on it); after about a second the panel opens
        if (code == KeyEvent.KEYCODE_BUTTON_START) {
            if (down && e.repeatCount == 0) { startHeld = true; ui.removeCallbacks(startHold); ui.postDelayed(startHold, HOLD_MS) }
            if (!down) { startHeld = false; ui.removeCallbacks(startHold) }
            return false
        }

        val isBack = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        if (isBack) {
            if (inGame()) { if (down && e.repeatCount == 0) trigger("Back in game"); return true }   // RetroArch never sees it
            // everywhere else: a short press is Back as normal, a hold opens the panel
            if (down) {
                if (e.repeatCount == 0) { backHeld = true; backFired = false; ui.removeCallbacks(backHold); ui.postDelayed(backHold, HOLD_MS) }
                return true
            }
            ui.removeCallbacks(backHold)
            val wasShort = backHeld && !backFired
            backHeld = false
            if (wasShort) performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ mouse: the top edge
    private var edge: View? = null
    private var pill: TextView? = null
    private val hidePill = Runnable { pill?.animate()?.alpha(0f)?.translationY(-Kit.dp(this, 20f))?.setDuration(Kit.t(250))?.withEndAction { pill?.visibility = View.GONE }?.start() }
    private val wm get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** A strip two pixels high along the top of the screen; when the mouse pointer touches it, a Home pill drops in. */
    private fun addEdge() {
        if (edge != null) return
        try {
            val strip = View(this)
            strip.setOnHoverListener { _, ev -> if (ev.action == MotionEvent.ACTION_HOVER_ENTER && !inLauncher() && !QuickPanel.showing) showPill(); false }
            wm.addView(strip, WindowManager.LayoutParams(-1, 2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP })
            edge = strip
        } catch (e: Exception) { Perf.note("top edge overlay failed: $e") }
    }

    private fun showPill() {
        val p = pill ?: Kit.text(this, 18f, true).also { t ->
            t.text = "⌂   Home and menu"; t.gravity = Gravity.CENTER
            t.setPadding(Kit.dpi(this, 26f), 0, Kit.dpi(this, 26f), 0)
            t.background = GradientDrawable().apply { cornerRadius = Kit.dp(this@RetroKeys, 24f); setColor(Color.argb(235, 24, 26, 34)); setStroke(1, Kit.GLASS_EDGE) }
            t.setOnClickListener { ui.removeCallbacks(hidePill); t.visibility = View.GONE; trigger("mouse") }
            t.setOnHoverListener { _, ev -> if (ev.action == MotionEvent.ACTION_HOVER_ENTER) ui.removeCallbacks(hidePill) else if (ev.action == MotionEvent.ACTION_HOVER_EXIT) ui.postDelayed(hidePill, 1500); false }
            try {
                wm.addView(t, WindowManager.LayoutParams(-2, Kit.dpi(this, 48f), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = Kit.dpi(this@RetroKeys, 14f) })
            } catch (e: Exception) { Perf.note("pill overlay failed: $e"); return }
            pill = t
        }
        p.visibility = View.VISIBLE; p.alpha = 0f; p.translationY = -Kit.dp(this, 20f)
        p.animate().alpha(1f).translationY(0f).setDuration(Kit.t(250)).setInterpolator(Kit.EASE).start()
        ui.removeCallbacks(hidePill); ui.postDelayed(hidePill, 3500)
    }

    private fun removeEdge() {
        for (v in listOf(edge, pill, hintView, corner)) try { v?.let { wm.removeView(it) } } catch (_: Exception) {}
        edge = null; pill = null; hintView = null; corner = null
    }
}

/**
 * The quick panel over any app (and the pause menu over a game): Go Home first, so trigger then OK always
 * gets you home; then back to the app, close it, the game's Save/Load/Restart, recent things and volume.
 */
class QuickPanel : Activity() {
    companion object {
        @Volatile var showing = false
        @Volatile var current: QuickPanel? = null
    }
    private class Choice(val label: String, val run: () -> Unit)

    private val ui = Handler(Looper.getMainLooper())
    private var pkg = ""; private var game = false; private var appName = "the app"
    private val rows = ArrayList<List<TextView>>()      // each row: one big button, or the recent cards side by side
    private val actions = ArrayList<List<() -> Unit>>()
    private var r = 0; private var c = 0
    private lateinit var volText: TextView
    private val audio get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true; current = this
        pkg = intent.getStringExtra("pkg") ?: ""; game = intent.getBooleanExtra("game", false)
        appName = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "the app" }
        if (Backdrop.blurred == null) Backdrop.build(0, 1280, 720)
        val root = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        root.setOnClickListener { backToApp(null) }   // mouse: click outside = back to the app
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true }
        box.setPadding(Kit.dpi(this, 34f), Kit.dpi(this, 26f), Kit.dpi(this, 34f), Kit.dpi(this, 24f))
        box.background = GlassDrawable(box, Kit.dp(this, Kit.R_L), Kit.GLASS_STRONG)
        box.clipChildren = false; box.clipToPadding = false

        val head = FrameLayout(this)
        head.addView(Kit.text(this, 28f, true).apply { text = if (game) "Paused" else appName }, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        val fmt = if (android.text.format.DateFormat.is24HourFormat(this)) "H:mm" else "h:mm a"
        head.addView(Kit.text(this, 24f, true, Kit.TEXT_DIM).apply { text = android.text.format.DateFormat.format(fmt, System.currentTimeMillis()) }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        box.addView(head, LinearLayout.LayoutParams(Kit.dpi(this, 520f), -2).apply { bottomMargin = Kit.dpi(this@QuickPanel, 18f) })

        val choices = ArrayList<Choice>()
        choices.add(Choice("Go Home") { goHome() })
        choices.add(Choice(if (game) "Back to the game" else "Back to $appName") { backToApp(null) })
        // Save/Load/Restart need RetroArch's command port, which doesn't open on this box: not offered until it does
        choices.add(Choice(if (game) "Close the game" else "Close $appName") { closeApp() })
        for (ch in choices) addRow(box, listOf(button(ch.label, 520f, 52f)), listOf(ch.run))

        // recent things (not the app that's open now)
        val recent = recentItems()
        if (recent.isNotEmpty()) {
            box.addView(Kit.text(this, 16f, true, Kit.TEXT_DIM).apply { text = "Recent" }, LinearLayout.LayoutParams(Kit.dpi(this, 520f), -2).apply { topMargin = Kit.dpi(this@QuickPanel, 10f); bottomMargin = Kit.dpi(this@QuickPanel, 8f) })
            val w = (520f - 10f * (recent.size - 1)) / recent.size
            addRow(box, recent.map { button(it.first, w, 46f, 17f) }, recent.map { (_, key) -> { openRecent(key) } }, horizontal = true)
        }

        // volume: Left/Right changes it
        volText = button("", 520f, 46f, 18f)
        addRow(box, listOf(volText), listOf { -> }, topGap = 10f)
        showVolume()

        box.addView(Kit.text(this, 14f, false, Kit.TEXT_DIM).apply { text = "Press the button again to go straight Home"; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(this@QuickPanel, 12f) })

        root.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        box.scaleX = .94f; box.scaleY = .94f; box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Kit.t(220)).setInterpolator(Kit.EASE).start()
        paint()
    }

    override fun onDestroy() { if (current === this) { showing = false; current = null }; super.onDestroy(); RetroKeys.instance?.panelClosed() }
    override fun onStop() { super.onStop(); if (!isFinishing) finish() }   // never left behind under an app

    private fun button(label: String, wDp: Float, hDp: Float, size: Float = 21f) = Kit.text(this, size, true).apply { text = label; gravity = Gravity.CENTER; setPadding(Kit.dpi(this@QuickPanel, 8f), 0, Kit.dpi(this@QuickPanel, 8f), 0); tag = floatArrayOf(wDp, hDp) }

    private fun addRow(box: LinearLayout, views: List<TextView>, runs: List<() -> Unit>, horizontal: Boolean = false, topGap: Float = 0f) {
        val ri = rows.size
        val holder = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        for ((ci, v) in views.withIndex()) {
            val wh = v.tag as FloatArray
            v.setOnHoverListener { _, ev -> if (ev.action == MotionEvent.ACTION_HOVER_ENTER) { r = ri; c = ci; paint() }; false }
            v.setOnClickListener { r = ri; c = ci; runs[ci]() }
            holder.addView(v, LinearLayout.LayoutParams(Kit.dpi(this, wh[0]), Kit.dpi(this, wh[1])).apply { if (ci > 0) leftMargin = Kit.dpi(this@QuickPanel, 10f) })
        }
        box.addView(holder, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Kit.dpi(this@QuickPanel, 8f); topMargin = Kit.dpi(this@QuickPanel, topGap) })
        rows.add(views); actions.add(runs)
    }

    private fun paint() {
        for ((ri, row) in rows.withIndex()) for ((ci, v) in row.withIndex()) {
            val on = ri == r && ci == c
            v.background = GradientDrawable().apply { cornerRadius = Kit.dp(this@QuickPanel, 26f); setColor(if (on) Kit.FOCUS_FILL else 0x1FFFFFFF) }
            v.setTextColor(if (on) Kit.FOCUS_TEXT else Color.WHITE)
            Kit.unclip(v); v.animate().scaleX(if (on) 1.04f else 1f).scaleY(if (on) 1.04f else 1f).setDuration(Kit.t(150)).start()
        }
    }

    private fun showVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1); val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val n = Math.round(v * 10f / max)
        volText.text = "Volume   ◀  " + "●".repeat(n) + "○".repeat(10 - n) + "  ▶"
    }
    private fun volume(d: Int) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (d > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0); showVolume() }

    /** Up to four: recent apps, games and the last channel, without the app that's open now. */
    private fun recentItems(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val st = Store(this)
            st.lastTv?.let { u -> (ChannelRepo.tv + ChannelRepo.radio).firstOrNull { it.url == u }?.let { out.add(it.name to "ch:$u") } }
            st.recentGames.firstOrNull()?.let { k -> if (!game) out.add(k.substringAfterLast('/').substringBeforeLast('.').take(28) to k) }
            for (k in st.recent) {
                if (out.size >= 4) break
                val p = k.removePrefix("app:").substringBefore('/')
                if (p == pkg || p == packageName) continue
                val name = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { continue }
                out.add(name to k)
            }
        } catch (_: Exception) {}
        return out.take(4)
    }

    private fun home(extra: Intent.() -> Unit = {}) =
        startActivity(Intent(this, HomeActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply(extra))

    fun goHome() {
        if (game) RetroCmd.send("SAVE_STATE")
        ui.postDelayed({ try { home() } catch (e: Exception) { RetroKeys.instance?.goHome() }; finish(); overridePendingTransition(0, 0) }, if (game) 300 else 0)
    }

    private fun backToApp(then: String?) { finish(); overridePendingTransition(0, 0); if (then != null) ui.postDelayed({ RetroCmd.send(then) }, 450) }

    /** Goes Home, then ends the app's process once it's in the background (Android lets a launcher do that). */
    private fun closeApp() {
        val p = pkg
        if (game) RetroCmd.send("SAVE_STATE")
        ui.postDelayed({
            try { home() } catch (_: Exception) {}
            finish(); overridePendingTransition(0, 0)
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            Handler(Looper.getMainLooper()).postDelayed({ try { am.killBackgroundProcesses(p) } catch (_: Exception) {} }, 700)
        }, if (game) 300 else 0)
    }

    private fun openRecent(key: String) { home { putExtra("rl_open", key) }; finish(); overridePendingTransition(0, 0) }

    /** A pad's D-pad and left stick arrive as movement, not keys: turn them into moves. */
    private var stickX = 0; private var stickY = 0
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) && e.action == MotionEvent.ACTION_MOVE) {
            fun axis(hat: Int, stick: Int): Float { var v = e.getAxisValue(hat).let { if (it != 0f) it else e.getAxisValue(stick) }; for (h in 0 until e.historySize) { val hv = e.getHistoricalAxisValue(hat, h); if (Math.abs(hv) > .5f) v = hv }; return v }
            val y = axis(MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y); val x = axis(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X)
            val dy = if (y > .5f) 1 else if (y < -.5f) -1 else 0; val dx = if (x > .5f) 1 else if (x < -.5f) -1 else 0
            if (dy != stickY) { stickY = dy; if (dy != 0) move(dy, 0) }
            if (dx != stickX) { stickX = dx; if (dx != 0) move(0, dx) }
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    private fun move(dr: Int, dc: Int) {
        if (dr != 0) { r = (r + dr).coerceIn(0, rows.size - 1); c = c.coerceAtMost(rows[r].size - 1) }
        if (dc != 0) { if (rows[r].first() === volText) volume(dc) else c = (c + dc).coerceIn(0, rows[r].size - 1) }
        paint()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1, 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(0, 1)
            KeyEvent.KEYCODE_VOLUME_UP -> volume(1)
            KeyEvent.KEYCODE_VOLUME_DOWN -> volume(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (e.repeatCount == 0) actions[r][c]()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) backToApp(null)
            // a trigger pressed again: straight Home
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_HOME -> if (e.repeatCount == 0) goHome()
        }
        return true
    }
}
