package com.antonretro.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import com.antonretro.launcher.data.GameRepo
import com.antonretro.launcher.data.Rom
import com.antonretro.launcher.data.Store
import com.antonretro.launcher.ui.GameMenu
import java.io.File

/**
 * The game Home Menu shown ON TOP of a running RetroArch game (see-through, so the paused game stays visible,
 * dimmed, behind it), instead of leaving the game first. Opened by the key helper when Home, the Xbox button,
 * Back or Select+Start is pressed in a game. Resume just closes this screen; everything that needs the
 * launcher (Leave, restart after options, all settings) is handed to HomeActivity.
 */
class GameOverlay : Activity() {
    companion object {
        @Volatile var showing = false
        @Volatile var current: GameOverlay? = null
        /** The game being played: the newest entry in "Keep playing". */
        fun lastRom(ctx: android.content.Context): Rom? = try {
            val key = Store(ctx).recentGames.firstOrNull { it.startsWith("rom:") } ?: return null
            val f = File(key.removePrefix("rom:"))
            GameRepo.systemFor(f, f.parentFile?.name)?.let { Rom(f, it) }
        } catch (e: Exception) { null }
    }

    private lateinit var menu: GameMenu
    private var openedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rom = lastRom(this)
        if (rom == null) { launcher("leave"); finish(); return }
        showing = true; current = this; openedAt = SystemClock.uptimeMillis()
        menu = GameMenu(this, overGame = true).apply {
            onResume = { _ -> finish(); overridePendingTransition(0, 0) }
            onLeave = { r -> launcher("leave", r.key) }
            onRestart = { r -> launcher("restart", r.key) }
            onAllSettings = { launcher("settings") }
        }
        setContentView(menu)
        menu.show(rom, gameAlive = true)
    }

    /** Home or the Xbox button again while the menu is up: back to the game, like a console. */
    fun resumeGame() { if (::menu.isInitialized) menu.resume() else finish() }

    private fun launcher(action: String, key: String? = null) {
        try {
            startActivity(Intent(this, HomeActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("rl_game", action).putExtra("rl_rom", key))
        } catch (_: Exception) {}
        finish(); overridePendingTransition(0, 0)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // the press that opened this menu must not also choose something in it
        if (SystemClock.uptimeMillis() - openedAt < 300) return true
        return menu.onKey(e)
    }

    override fun onDestroy() { if (current === this) { showing = false; current = null }; super.onDestroy() }
    override fun onStop() { super.onStop(); if (!isFinishing) finish() }   // never left behind under the game
}
