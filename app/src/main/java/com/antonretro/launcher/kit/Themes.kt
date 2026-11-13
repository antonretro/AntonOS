package com.antonretro.launcher.kit

import android.content.Context
import android.graphics.Color
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * Themes (PLAN.md "Themes"): every colour the kit draws with comes from the active theme, so a theme is a data swap.
 * Six original built-in themes, plus theme files the user puts in /sdcard/RetroLauncher/Themes/ (see
 * docs/THEME-FORMAT.md). Focused text is checked for contrast and fixed automatically, whatever a file says.
 */
class Theme(
    val id: String, val name: String, val about: String,
    val accent: Int, val focusFill: Int, val focusText: Int,
    val glassTint: Int, val glassStrong: Int, val glassEdge: Int,
    val text: Int, val textDim: Int, val dimScrolled: Int,
    /** 1 = normal; the high-contrast theme uses bigger type */
    val textScale: Float = 1f)

object Themes {
    val builtIn = listOf(
        Theme("glass", "Glass", "Dark frosted glass over your wallpaper (the default)",
            Color.rgb(79, 140, 255), Color.argb(254, 255, 255, 255), Color.rgb(17, 17, 17),
            Color.argb(118, 28, 30, 38), Color.argb(170, 24, 26, 34), Color.argb(36, 255, 255, 255),
            Color.WHITE, Color.argb(158, 255, 255, 255), Color.argb(140, 6, 8, 14)),
        Theme("arcade", "Arcade", "Warm amber and wood, like an old arcade cabinet",
            Color.rgb(255, 140, 40), Color.argb(254, 255, 196, 64), Color.rgb(40, 20, 6),
            Color.argb(124, 48, 26, 12), Color.argb(178, 40, 22, 10), Color.argb(48, 255, 196, 120),
            Color.rgb(255, 244, 220), Color.argb(170, 255, 228, 190), Color.argb(150, 20, 10, 4)),
        Theme("neon", "Neon", "Electric cyan and magenta on deep purple",
            Color.rgb(255, 64, 200), Color.argb(254, 0, 229, 255), Color.rgb(10, 4, 30),
            Color.argb(128, 28, 10, 54), Color.argb(184, 22, 8, 46), Color.argb(70, 0, 229, 255),
            Color.WHITE, Color.argb(170, 210, 200, 255), Color.argb(160, 12, 4, 30)),
        Theme("pastel", "Pastel", "Soft pink and lavender, gentle on the eyes",
            Color.rgb(176, 140, 240), Color.argb(254, 255, 214, 228), Color.rgb(60, 30, 60),
            Color.argb(118, 70, 50, 84), Color.argb(170, 62, 44, 76), Color.argb(56, 255, 220, 240),
            Color.WHITE, Color.argb(176, 255, 236, 248), Color.argb(140, 30, 20, 40)),
        Theme("contrast", "High contrast", "Bold yellow focus, darker glass and bigger text, easy to read from the sofa",
            Color.rgb(255, 230, 0), Color.argb(254, 255, 230, 0), Color.BLACK,
            Color.argb(200, 0, 0, 0), Color.argb(230, 0, 0, 0), Color.argb(140, 255, 255, 255),
            Color.WHITE, Color.argb(230, 255, 255, 255), Color.argb(190, 0, 0, 0), textScale = 1.2f),
        Theme("classic", "Classic box", "Cool grey with a green light, a nod to old set-top boxes",
            Color.rgb(80, 200, 120), Color.argb(254, 220, 226, 232), Color.rgb(20, 28, 36),
            Color.argb(130, 44, 52, 62), Color.argb(184, 36, 44, 54), Color.argb(44, 200, 220, 240),
            Color.rgb(236, 242, 248), Color.argb(160, 220, 230, 240), Color.argb(150, 10, 14, 20)))

    val folder: File get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/Themes")

    /** Built-in themes, then any valid theme files (bad ones are skipped, never half-applied). */
    fun all(): List<Theme> = builtIn + (folder.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.length() < 16_384 }
        ?.sortedBy { it.name }?.mapNotNull { load(it) } ?: emptyList())

    private fun load(f: File): Theme? = try {
        val j = JSONObject(f.readText())
        val base = builtIn[0]
        fun col(k: String, d: Int): Int = j.optString(k).takeIf { it.isNotEmpty() }?.let { s ->
            require(Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(s)) { "bad colour $k" }; Color.parseColor(s) } ?: d
        Theme("file:" + f.name, j.optString("name").take(30).ifBlank { f.nameWithoutExtension }, j.optString("about").take(120),
            col("accent", base.accent), col("focusFill", base.focusFill), col("focusText", base.focusText),
            col("glassTint", base.glassTint), col("glassStrong", base.glassStrong), col("glassEdge", base.glassEdge),
            col("text", base.text), col("textDim", base.textDim), col("dimScrolled", base.dimScrolled),
            j.optDouble("textScale", 1.0).toFloat().coerceIn(.9f, 1.3f))
    } catch (e: Exception) { null }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= .03928) s / 12.92 else Math.pow((s + .055) / 1.055, 2.4) }
        return .2126 * ch(Color.red(c)) + .7152 * ch(Color.green(c)) + .0722 * ch(Color.blue(c))
    }
    /** WCAG contrast ratio between two opaque colours. */
    fun contrast(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + .05) / (minOf(x, y) + .05) }

    fun current(ctx: Context): Theme {
        val id = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).getString("id", "glass")
        return all().firstOrNull { it.id == id } ?: builtIn[0]
    }
    fun choose(ctx: Context, t: Theme) { ctx.getSharedPreferences("theme", Context.MODE_PRIVATE).edit().putString("id", t.id).apply() }

    /** Puts the theme's colours into the kit. Focused text always gets readable contrast against the focus fill. */
    fun apply(t: Theme) {
        Kit.ACCENT = t.accent; Kit.FOCUS_FILL = t.focusFill
        Kit.FOCUS_TEXT = if (contrast(t.focusText, t.focusFill or 0xFF000000.toInt()) >= 4.5) t.focusText
            else if (contrast(Color.BLACK, t.focusFill or 0xFF000000.toInt()) > contrast(Color.WHITE, t.focusFill or 0xFF000000.toInt())) Color.BLACK else Color.WHITE
        Kit.GLASS_TINT = t.glassTint; Kit.GLASS_STRONG = t.glassStrong; Kit.GLASS_EDGE = t.glassEdge
        Kit.TEXT = t.text; Kit.TEXT_DIM = t.textDim; Kit.DIM_SCROLLED = t.dimScrolled
        Kit.textScale = t.textScale
    }
}
