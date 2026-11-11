package com.antonretro.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Environment
import java.io.File

/**
 * Silences the box for automated tests and gives the sound back afterwards. Only ADB can send this
 * (the receiver needs android.permission.DUMP). This firmware has no `media volume` command.
 *   am broadcast -a com.antonretro.launcher.TEST_AUDIO --ez mute true    (remembers the volumes, sets 0)
 *   am broadcast -a com.antonretro.launcher.TEST_AUDIO --ez mute false   (puts the remembered volumes back)
 * The remembered volumes live in RetroLauncher/.test-volume, so muting twice never loses the real level.
 */
class TestAudio : BroadcastReceiver() {
    // music = games, TV, radio, music, the start-up chime; system = navigation sounds. Not notification/ring:
    // changing those counts as Do Not Disturb and Android refuses (that crashed the launcher once).
    private val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)
    private inline fun safe(f: () -> Unit) { try { f() } catch (_: Exception) {} }

    override fun onReceive(c: Context, i: Intent) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = File(Environment.getExternalStorageDirectory(), "RetroLauncher")
        val saved = File(dir, ".test-volume"); val flag = File(dir, ".test-mute")
        if (i.getBooleanExtra("mute", true)) {
            if (!saved.exists()) saved.writeText(streams.joinToString(",") { "$it=${am.getStreamVolume(it)}" })
            // HDMI is a fixed-volume output on this box: setting a level is ignored there, muting the stream is not
            for (s in streams) safe { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) }
            flag.createNewFile()
            resultData = "muted (was ${saved.readText()})"
        } else {
            if (saved.exists()) {
                for (p in saved.readText().split(',')) safe { val (s, v) = p.split('='); am.adjustStreamVolume(s.toInt(), AudioManager.ADJUST_UNMUTE, 0); am.setStreamVolume(s.toInt(), v.toInt(), 0) }
                saved.delete()
            }
            for (s in streams) safe { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
            flag.delete()
            resultData = "sound back: music ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}"
        }
    }
}
