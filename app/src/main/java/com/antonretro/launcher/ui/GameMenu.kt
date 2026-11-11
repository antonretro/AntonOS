package com.antonretro.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.data.Rom
import com.antonretro.launcher.kit.Backdrop
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Images
import com.antonretro.launcher.kit.Kit
import java.io.File

/**
 * The game Home Menu: every way out of a game lands here. Box art and title on the left with the
 * controllers; big choices on the right (Resume first). Lives inside Retro Launcher, so it needs no overlay.
 */
@SuppressLint("ViewConstructor")
class GameMenu(ctx: Context) : FrameLayout(ctx) {
    var onResume: ((Rom) -> Unit)? = null
    var onLeave: ((Rom) -> Unit)? = null
    var onRestart: ((Rom) -> Unit)? = null          // relaunch the game (after options were written)
    var onAllSettings: (() -> Unit)? = null
    val showing get() = visibility == View.VISIBLE

    private class MRow(val label: () -> String, val value: (() -> String)? = null, val side: ((Int) -> Unit)? = null, val ok: (() -> Unit)? = null)

    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val artBox = FrameLayout(ctx)
    private val title = Kit.text(ctx, 30f, true).apply { isSingleLine = false; maxLines = 2 }
    private val system = Kit.text(ctx, 18f, true, Kit.TEXT_DIM)
    private val pads = Kit.text(ctx, 17f, false, Kit.TEXT_DIM).apply { isSingleLine = false; maxLines = 5 }
    private val heading = Kit.text(ctx, 26f, true)
    private val note = Kit.text(ctx, 17f, false, Kit.TEXT_DIM).apply { isSingleLine = false; maxLines = 3 }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val applying = Kit.text(ctx, 26f, true).apply { gravity = Gravity.CENTER; visibility = View.GONE }

    private var rom: Rom? = null
    private var rows: List<MRow> = emptyList()
    private val views = ArrayList<TextView>()
    private var sel = 0
    private var page = "main"

    // the game's options, read from and written to its RetroArch override
    private var smooth = false; private var fill = false; private var volume = 0; private var rewind = false
    private var changed = false

    init {
        visibility = View.GONE; isClickable = true
        addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; Backdrop.blurredDim?.let { setImageBitmap(it) } }, -1, -1)
        addView(View(ctx).apply { setBackgroundColor(Color.argb(140, 6, 8, 14)) }, -1, -1)
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        artBox.background = GlassDrawable(artBox, Kit.dp(ctx, 22f), Kit.GLASS_STRONG)
        artBox.setPadding(Kit.dpi(ctx, 14f), Kit.dpi(ctx, 14f), Kit.dpi(ctx, 14f), Kit.dpi(ctx, 14f))
        artBox.addView(art, -1, -1)
        left.addView(artBox, LinearLayout.LayoutParams(Kit.dpi(ctx, 340f), Kit.dpi(ctx, 300f)))
        left.addView(title, LinearLayout.LayoutParams(Kit.dpi(ctx, 340f), -2).apply { topMargin = Kit.dpi(ctx, 18f) })
        left.addView(system, LinearLayout.LayoutParams(Kit.dpi(ctx, 340f), -2).apply { topMargin = Kit.dpi(ctx, 6f) })
        left.addView(pads, LinearLayout.LayoutParams(Kit.dpi(ctx, 340f), -2).apply { topMargin = Kit.dpi(ctx, 18f) })
        addView(left, LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(ctx, 80f); topMargin = Kit.dpi(ctx, 70f) })

        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        right.addView(heading)
        right.addView(note, LinearLayout.LayoutParams(Kit.dpi(ctx, 600f), -2).apply { topMargin = Kit.dpi(ctx, 8f); bottomMargin = Kit.dpi(ctx, 20f) })
        right.addView(list)
        addView(right, LayoutParams(Kit.dpi(ctx, 620f), -2).apply { leftMargin = Kit.dpi(ctx, 520f); topMargin = Kit.dpi(ctx, 70f) })
        applying.background = GlassDrawable(applying, Kit.dp(ctx, 26f), Kit.GLASS_STRONG)
        addView(applying, LayoutParams(Kit.dpi(ctx, 420f), Kit.dpi(ctx, 120f), Gravity.CENTER))
    }

    // ------------------------------------------------------------------ open
    /** true: the game is paused behind us (Home again resumes it). false: it has closed (Home just stays Home). */
    var alive = true; private set

    fun show(r: Rom, gameAlive: Boolean = true) {
        alive = gameAlive
        rom = r; changed = false; applying.visibility = View.GONE
        title.text = r.title; system.text = r.system.shelf
        art.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(r.system.colour, Color.BLACK)).apply { cornerRadius = Kit.dp(context, 14f) })
        Images.load(r.boxArt, Kit.dpi(context, 312f), Kit.dpi(context, 272f)) { b -> if (b != null && rom === r) art.setImageBitmap(b) }
        pads.text = padsText()
        readOptions(r)
        mainPage()
        visibility = View.VISIBLE; alpha = 0f; bringToFront()
        animate().alpha(1f).setDuration(Kit.t(220)).start()
    }

    fun hide() { animate().alpha(0f).setDuration(Kit.t(180)).withEndAction { visibility = View.GONE }.start() }

    /** Home pressed again while the menu is up: same as Resume. */
    fun resume() { val r = rom ?: return; hide(); onResume?.invoke(r) }

    private fun padsText(): String {
        val found = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (!d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) found.add(d)
        }
        if (found.isEmpty()) return "No controller connected. The remote works too: OK is A, Back is B."
        return found.withIndex().joinToString("\n") { (i, d) -> "Player ${i + 1}   ${d.name.trim()}" }
    }

    // ------------------------------------------------------------------ pages
    private fun setPage(name: String, head: String, sub: String, r: List<MRow>, start: Int = 0) {
        page = name; heading.text = head; note.text = sub; rows = r; sel = start
        list.removeAllViews(); views.clear()
        for ((i, row) in r.withIndex()) {
            val tv = Kit.text(context, 21f, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(Kit.dpi(context, 28f), 0, Kit.dpi(context, 28f), 0) }
            tv.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { sel = i; paint() }; false }
            tv.setOnClickListener { sel = i; ok() }
            list.addView(tv, LinearLayout.LayoutParams(Kit.dpi(context, 560f), Kit.dpi(context, 56f)).apply { bottomMargin = Kit.dpi(context, 10f) })
            views.add(tv)
        }
        paint()
    }

    private fun mainPage() = setPage("main", if (alive) "Paused" else "Game closed",
        if (alive) "Your game waits right where you left it." else "Play again starts it from its last save.", listOf(
        MRow({ if (alive) "Resume" else "Play again" }, ok = { resume() }),
        MRow({ "Leave game" }, ok = { leavePage() }),
        MRow({ "Game options" }, ok = { optionsPage() }),
        MRow({ "Controls" }, ok = { controlsPage() }),
        MRow({ "All game settings" }, ok = { hide(); onAllSettings?.invoke() })))

    private fun leavePage() {
        val r = rom ?: return
        setPage("leave", "Leave ${r.title}?", "The game's own saves are kept (it saves every few seconds). This box can't keep your exact spot yet, so you'll go back to the game's last save.", listOf(
            MRow({ "Leave" }, ok = { hide(); onLeave?.invoke(r) }),
            MRow({ "Stay" }, ok = { mainPage() })))
    }

    private fun optionsPage(start: Int = 0) {
        val vols = listOf("Quieter", "Normal", "Louder")
        setPage("options", "Game options", "Just for ${rom?.title}. Changes restart the game from its last save.", listOf(
            MRow({ "Picture" }, { if (smooth) "Smooth" else "Sharp pixels" }, side = { smooth = !smooth; changed = true }),
            MRow({ "Screen" }, { if (fill) "Fill the screen" else "Whole pixels" }, side = { fill = !fill; changed = true }),
            MRow({ "Sound" }, { vols[volume + 1] }, side = { d -> volume = (volume + d).coerceIn(-1, 1); changed = true }),
            MRow({ "Rewind" }, { if (rewind) "On" else "Off" }, side = { rewind = !rewind; changed = true }),
            MRow({ if (changed) "Apply and restart the game" else "No changes yet" }, ok = { if (changed) apply() }),
            MRow({ "Start the game again" }, ok = { restartPage() }),
            MRow({ "Back" }, ok = { mainPage() })), start)
    }

    private fun restartPage() {
        val r = rom ?: return
        setPage("restart", "Start ${r.title} again?", "You go back to the game's last own save.", listOf(
            MRow({ "Start again" }, ok = { hide(); onRestart?.invoke(r) }),
            MRow({ "Cancel" }, ok = { optionsPage(5) })))
    }

    private fun controlsPage() {
        val r = rom ?: return
        val lines = when (r.system.id) {
            "nes", "gb", "gbc", "sms", "pce" -> listOf("A  ·  A button", "B  ·  B button", "Start  ·  Start", "Select  ·  Select", "D-pad or left stick  ·  move")
            "snes" -> listOf("A B X Y  ·  the same buttons", "LB / RB  ·  L / R", "Start  ·  Start", "Select  ·  Select", "D-pad or left stick  ·  move")
            "gba" -> listOf("A  ·  A button", "B  ·  B button", "LB / RB  ·  L / R", "Start / Select  ·  Start / Select", "D-pad or left stick  ·  move")
            "md", "gg" -> listOf("X / A / B  ·  A / B / C", "Start  ·  Start", "D-pad or left stick  ·  move")
            "ps1" -> listOf("A / B / X / Y  ·  Cross / Circle / Square / Triangle", "LB / RB, LT / RT  ·  L1 / R1, L2 / R2", "Start / Select  ·  Start / Select", "Sticks  ·  sticks")
            else -> listOf("A  ·  A button", "B  ·  B button", "Start  ·  Start")
        }
        setPage("controls", "Controls", "${r.system.shelf} on your controller. Home on the remote brings you to this menu.",
            lines.map { l -> MRow({ l }) } + MRow({ "Back" }, ok = { mainPage() }), lines.size)
    }

    private fun paint() {
        for ((i, v) in views.withIndex()) {
            val on = i == sel; val row = rows[i]
            val label = row.label(); val value = row.value?.invoke()
            v.text = if (value != null) "$label        ◀  $value  ▶" else label
            val passive = row.ok == null && row.side == null
            v.background = GradientDrawable().apply { cornerRadius = Kit.dp(context, 28f); setColor(if (on && !passive) Kit.FOCUS_FILL else if (passive) 0x00000000 else 0x1FFFFFFF) }
            v.setTextColor(if (on && !passive) Kit.FOCUS_TEXT else if (passive) 0xD9FFFFFF.toInt() else Color.WHITE)
            v.pivotX = 0f; v.pivotY = v.height / 2f   // grow to the right: the rounded left edge never gets cut off
            Kit.unclip(v); v.animate().scaleX(if (on && !passive) 1.04f else 1f).scaleY(if (on && !passive) 1.04f else 1f).setDuration(Kit.t(150)).start()
        }
    }

    private fun ok() { val r = rows.getOrNull(sel) ?: return; r.ok?.invoke() ?: r.side?.invoke(1); if (r.ok == null) paint() }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (applying.visibility == View.VISIBLE) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { var n = sel - 1; while (n >= 0 && rows[n].ok == null && rows[n].side == null) n--; if (n >= 0) { sel = n; paint() } }
            KeyEvent.KEYCODE_DPAD_DOWN -> { var n = sel + 1; while (n < rows.size && rows[n].ok == null && rows[n].side == null) n++; if (n < rows.size) { sel = n; paint() } }
            KeyEvent.KEYCODE_DPAD_LEFT -> rows.getOrNull(sel)?.side?.let { it(-1); paint() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> rows.getOrNull(sel)?.side?.let { it(1); paint() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (e.repeatCount == 0) ok()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (e.repeatCount == 0) { if (page == "main") resume() else mainPage() }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE -> if (e.repeatCount == 0) resume()
        }
        return true
    }

    // ------------------------------------------------------------------ per-game options (a RetroArch game override)
    private fun overrideFile(r: Rom): File {
        val core = when (r.system.core) {
            "fceumm_libretro_android.so" -> "FCEUmm"
            "snes9x2010_libretro_android.so" -> "Snes9x 2010"
            "gambatte_libretro_android.so" -> "Gambatte"
            "gpsp_libretro_android.so" -> "gpSP"
            "genesis_plus_gx_libretro_android.so" -> "Genesis Plus GX"
            "mednafen_pce_fast_libretro_android.so" -> "Beetle PCE Fast"
            "pcsx_rearmed_libretro_android.so" -> "PCSX-ReARMed"
            else -> "Other"
        }
        return File(File(android.os.Environment.getExternalStorageDirectory(), "RetroLauncher"), "$core/${r.file.nameWithoutExtension}.cfg")
    }

    private fun readOptions(r: Rom) {
        val kv = try { overrideFile(r).readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim().trim('"') } }.toMap() } catch (e: Exception) { emptyMap() }
        fun global(k: String) = com.antonretro.launcher.RetroConfig.get(k)
        smooth = (kv["video_smooth"] ?: global("video_smooth")) == "true"
        fill = (kv["video_scale_integer"] ?: global("video_scale_integer") ?: "true") == "false"
        rewind = (kv["rewind_enable"] ?: global("rewind_enable")) == "true"
        val v = kv["audio_volume"]?.toFloatOrNull() ?: 0f
        volume = if (v <= -1f) -1 else if (v >= 1f) 1 else 0
    }

    private fun apply() {
        val r = rom ?: return
        try {
            val f = overrideFile(r); f.parentFile?.mkdirs()
            f.writeText("""
                video_smooth = "$smooth"
                video_scale_integer = "${!fill}"
                rewind_enable = "$rewind"
                audio_volume = "${when (volume) { -1 -> "-8.000000"; 1 -> "4.000000"; else -> "0.000000" }}"
            """.trimIndent() + "\n")
        } catch (e: Exception) { note.text = "Couldn't save the options: ${e.message}"; return }
        applying.text = "Applying…"; applying.visibility = View.VISIBLE
        postDelayed({ applying.visibility = View.GONE; hide(); onRestart?.invoke(r) }, 900)
    }
}
