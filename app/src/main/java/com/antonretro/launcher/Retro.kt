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

/** Reads and writes single settings in AntonOS's tuned retroarch.cfg (Settings → Games). */
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

