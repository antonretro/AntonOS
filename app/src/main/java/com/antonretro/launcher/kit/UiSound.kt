package com.antonretro.launcher.kit

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.antonretro.launcher.R

/**
 * Interface sounds (tools/make_ui_sounds.py makes them: original, synthesised). Preloaded in a SoundPool so they play
 * with no delay; on by default (Settings → Sound → Interface sounds, with a level). Move ticks are rate-limited so a
 * held key never turns into noise, and everything stays silent while [quiet] says something else is playing.
 */
object UiSound {
    enum class Kind { MOVE, MOVE2, SELECT, BACK, TAB, BUMP, NOTICE }
    private var pool: SoundPool? = null
    private val ids = IntArray(Kind.values().size)
    private var lastMove = 0L
    private lateinit var prefs: android.content.SharedPreferences
    /** true while TV, a video, music or a game is playing: no interface sounds over it */
    var quiet: () -> Boolean = { false }

    var on: Boolean
        get() = prefs.getBoolean("on", true)
        set(v) { prefs.edit().putBoolean("on", v).apply() }
    /** 0 low, 1 medium, 2 high */
    var level: Int
        get() = prefs.getInt("level", 1)
        set(v) { prefs.edit().putInt("level", v.coerceIn(0, 2)).apply() }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("uisound", Context.MODE_PRIVATE)
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        val res = intArrayOf(R.raw.ui_move, R.raw.ui_move2, R.raw.ui_select, R.raw.ui_back, R.raw.ui_tab, R.raw.ui_bump, R.raw.ui_notice)
        for (i in res.indices) ids[i] = p.load(ctx, res[i], 1)
        pool = p
    }

    fun play(k: Kind) {
        val p = pool ?: return
        if (!::prefs.isInitialized || !on || quiet()) return
        if (k == Kind.MOVE || k == Kind.MOVE2) { val now = SystemClock.uptimeMillis(); if (now - lastMove < 70) return; lastMove = now }
        val v = floatArrayOf(.35f, .6f, .9f)[level]
        p.play(ids[k.ordinal], v, v, 1, 0, 1f)
    }
}
