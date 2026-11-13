package com.antonretro.launcher.kit

import android.content.Context
import android.view.InputDevice

/**
 * Game controllers and their player numbers (PLAN.md "Mouse, controllers and two players"): order of connection by
 * default, remembered per controller (its device descriptor) so the same pad gets the same number next time, and
 * changeable in Settings → Controllers & Remotes. The remote and a mouse never take a player slot.
 */
object Pads {
    private lateinit var prefs: android.content.SharedPreferences
    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("pads", Context.MODE_PRIVATE) }

    private var order: MutableList<String>
        get() = (prefs.getString("order", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()
        set(v) { prefs.edit().putString("order", v.joinToString("\n")).apply() }

    fun isPad(d: InputDevice?): Boolean {
        d ?: return false
        if (d.isVirtual) return false
        val s = d.sources
        val pad = (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        val n = d.name.lowercase()
        return pad && !n.contains("keypad") && !n.contains("cec") && !n.contains("remote")
    }

    /** Connected controllers in player order (new ones are added at the end and remembered). */
    fun connected(): List<InputDevice> {
        val pads = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val d: InputDevice = InputDevice.getDevice(id) ?: continue
            if (isPad(d) && pads.none { p -> p.getDescriptor() == d.getDescriptor() }) pads.add(d)
        }
        val o = order
        var changed = false
        for (p in pads) if (p.descriptor !in o) { o.add(p.descriptor); changed = true }
        if (changed) order = o
        return pads.sortedBy { d: InputDevice -> o.indexOf(d.descriptor) }
    }

    /** 1-based player number among the connected controllers (0: not a controller). */
    fun playerOf(d: InputDevice?): Int = if (d == null || !isPad(d)) 0 else connected().indexOfFirst { it.descriptor == d.descriptor } + 1

    fun makeFirst(descriptor: String) { val o = order; o.remove(descriptor); o.add(0, descriptor); order = o }

    /** A friendly name: what kind of controller it is, not the driver's string. */
    fun nameOf(d: InputDevice): String {
        val n = d.name.lowercase()
        return when {
            "x-box" in n || "xbox" in n || "microsoft" in n || d.vendorId == 0x045e || d.vendorId == 0x24c6 -> "Xbox controller"
            "sony" in n || "dualshock" in n || "dualsense" in n || "playstation" in n || d.vendorId == 0x054c -> "PlayStation controller"
            "8bitdo" in n || d.vendorId == 0x2dc8 -> "8BitDo controller"
            "nintendo" in n || "switch" in n || "joy-con" in n || d.vendorId == 0x057e -> "Nintendo controller"
            else -> d.name.trim()
        }
    }
}
