package com.antonretro.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.kit.Backdrop
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Talks to the hidden game engine over its local command port (network_cmd_enable in retroarch.cfg). */
object RetroCmd {
    const val PORT = 55355
    fun send(cmd: String) {
        Thread {
            try { DatagramSocket().use { s -> val b = cmd.toByteArray(); s.send(DatagramPacket(b, b.size, InetAddress.getByName("127.0.0.1"), PORT)) } } catch (_: Exception) {}
        }.start()
    }
}

/** Reads and writes single settings in Retro Launcher's tuned retroarch.cfg (Settings → Games). */
object RetroConfig {
    val file: File get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/retroarch.cfg")
    fun get(key: String): String? = try {
        file.readLines().firstOrNull { it.trimStart().startsWith("$key ") || it.trimStart().startsWith("$key=") }?.substringAfter('=')?.trim()?.trim('"')
    } catch (e: Exception) { null }
    fun set(key: String, value: String) {
        try {
            val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
            val i = lines.indexOfFirst { it.trimStart().startsWith("$key ") || it.trimStart().startsWith("$key=") }
            val line = "$key = \"$value\""
            if (i >= 0) lines[i] = line else lines.add(line)
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }
}

/**
 * The key helper (an accessibility service, switched on once over ADB): the controller's Guide/Xbox
 * button goes Home from anywhere, and inside a game the Guide button, Start+Select or the remote's
 * Back opens Retro Launcher's own pause menu, so RetroArch's menu never has to appear.
 */
class RetroKeys : AccessibilityService() {
    companion object { @Volatile var alive = false }
    override fun onDestroy() { alive = false; super.onDestroy() }
    private var foreground: String? = null
    private var lastStart = 0L; private var lastSelect = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) e.packageName?.toString()?.let { if (it != "com.android.systemui" && it != "android") foreground = it }
    }
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        // ask for key events explicitly as well as in the XML (some Android 7 builds ignore the XML flag)
        serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        if (Perf.file == null) Perf.file = getExternalFilesDir(null)?.let { File(it, "perf.log") }
        Perf.note("keys helper connected")
        alive = true
    }

    private fun inGame() = foreground?.startsWith("com.retroarch") == true

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN && File(Environment.getExternalStorageDirectory(), "RetroLauncher/.test-mute").exists()) Perf.note("keys helper saw $code in $foreground")
        // Start + Select: pressed within a second of each other (this pad reports each release straight away)
        if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BUTTON_START) lastStart = e.eventTime
        if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BUTTON_SELECT) lastSelect = e.eventTime
        val guide = code == KeyEvent.KEYCODE_BUTTON_MODE
        val combo = (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT) && e.action == KeyEvent.ACTION_DOWN && Math.abs(lastStart - lastSelect) < 1000
        val back = code == KeyEvent.KEYCODE_BACK
        if (inGame() && (guide || combo || back)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                lastStart = 0L; lastSelect = -5000L
                startActivity(Intent(this, PauseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
            return true   // RetroArch never sees it, so its own menu doesn't open
        }
        if (guide) { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) performGlobalAction(GLOBAL_ACTION_HOME); return true }
        return false
    }
}

/** The pause menu shown over a game: Resume, Save, Load, Restart, Quit to Home, in our glass style. */
class PauseActivity : Activity() {
    private val items = listOf("Resume", "Save my place", "Load my saved place", "Start again", "Quit to Home")
    private val views = ArrayList<TextView>()
    private var sel = 0
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Backdrop.blurred == null) Backdrop.build(0, 1280, 720)
        val root = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.setPadding(Kit.dpi(this, 34f), Kit.dpi(this, 30f), Kit.dpi(this, 34f), Kit.dpi(this, 26f))
        box.background = GlassDrawable(box, Kit.dp(this, 26f), Kit.GLASS_STRONG)
        box.clipChildren = false; box.clipToPadding = false   // a selected button grows a little; keep its round corners
        box.addView(Kit.text(this, 30f, true).apply { text = "Paused" })
        box.addView(Kit.text(this, 16f, false, Kit.TEXT_DIM).apply { text = "Your game waits right where you left it" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(this@PauseActivity, 6f); bottomMargin = Kit.dpi(this@PauseActivity, 20f) })
        for ((i, label) in items.withIndex()) {
            val tv = Kit.text(this, 21f, true).apply { text = label; gravity = Gravity.CENTER }
            tv.setOnHoverListener { _, ev -> if (ev.action == MotionEvent.ACTION_HOVER_ENTER) { sel = i; paint() }; false }
            tv.setOnClickListener { sel = i; choose() }
            box.addView(tv, LinearLayout.LayoutParams(Kit.dpi(this, 380f), Kit.dpi(this, 54f)).apply { bottomMargin = Kit.dpi(this@PauseActivity, 8f) })
            views.add(tv)
        }
        root.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        box.scaleX = .94f; box.scaleY = .94f; box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Kit.t(240)).setInterpolator(Kit.EASE).start()
        paint()
    }

    private fun paint() {
        for ((i, v) in views.withIndex()) {
            val on = i == sel
            v.background = GradientDrawable().apply { cornerRadius = Kit.dp(this@PauseActivity, 27f); setColor(if (on) Kit.FOCUS_FILL else 0x1FFFFFFF) }
            v.setTextColor(if (on) Kit.FOCUS_TEXT else Color.WHITE)
            v.animate().scaleX(if (on) 1.05f else 1f).scaleY(if (on) 1.05f else 1f).setDuration(Kit.t(150)).start()
        }
    }

    private fun backToGame(then: String?) { finish(); overridePendingTransition(0, 0); if (then != null) ui.postDelayed({ RetroCmd.send(then) }, 450) }

    private fun choose() {
        when (sel) {
            0 -> backToGame(null)
            1 -> backToGame("SAVE_STATE")
            2 -> backToGame("LOAD_STATE")
            3 -> backToGame("RESET")
            4 -> { RetroCmd.send("SAVE_STATE"); ui.postDelayed({ startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() }, 300) }
        }
    }

    /** A pad's D-pad and left stick arrive as movement, not keys: turn them into up/down. */
    private var stickY = 0
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) && e.action == MotionEvent.ACTION_MOVE) {
            // newest movement first, then anything batched in the same frame (pads report many small moves)
            var y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (it != 0f) it else e.getAxisValue(MotionEvent.AXIS_Y) }
            for (h in 0 until e.historySize) { val hy = e.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, h); if (Math.abs(hy) > .5f) y = hy }
            val d = if (y > .5f) 1 else if (y < -.5f) -1 else 0
            if (d != stickY) { stickY = d; if (d != 0) { sel = (sel + d).coerceIn(0, items.size - 1); paint() } }
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { sel = (sel - 1).coerceAtLeast(0); paint() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { sel = (sel + 1).coerceAtMost(items.size - 1); paint() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (e.repeatCount == 0) choose()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ESCAPE -> backToGame(null)
            KeyEvent.KEYCODE_HOME -> { sel = 4; choose() }
        }
        return true
    }
}
