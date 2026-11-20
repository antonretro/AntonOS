package com.antonretro.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File

/** A console the S905W runs well. The user never sees "emulator" or "core": only the shelf name. */
class GameSystem(val id: String, val shelf: String, val badge: String, val ext: Set<String>, val core: String, val thumbs: String, val colour: Int,
                 /** width / height of the real box, so each card is the shape of its box art */ val boxAspect: Float = .72f)

class Rom(val file: File, val system: GameSystem) {
    val key get() = "rom:" + file.path
    val title: String get() = file.nameWithoutExtension.replace(Regex("""\s*[\[(][^\])]*[\])]"""), "").replace('_', ' ').trim().ifEmpty { file.nameWithoutExtension }
        // catalogue names put the article last ("Legend of Zelda, The - Link's Awakening"): say it the normal way
        .replace(Regex("""^(.+?), (The|A|An)\b(.*)$""")) { m -> m.groupValues[2] + " " + m.groupValues[1] + m.groupValues[3] }
    /** libretro-thumbnails box art (see [Covers]): the file's own name, or the catalogue name matched for it. */
    val boxArt: String get() = Covers.url(this)
}

/**
 * The launcher is the game library; RetroArch is a hidden engine. Games live in
 * /sdcard/RetroLauncher/Games/<system>/ (you supply your own game files; AntonOS downloads only game engines and box art, and asks first).
 */
object GameRepo {
    val SYSTEMS = listOf(
        GameSystem("nes", "Nintendo (NES)", "NES", setOf("nes", "fds", "unf", "unif"), "fceumm_libretro_android.so", "Nintendo - Nintendo Entertainment System", 0xFFE63946.toInt(), .72f),
        GameSystem("snes", "Super Nintendo", "SNES", setOf("sfc", "smc"), "snes9x2010_libretro_android.so", "Nintendo - Super Nintendo Entertainment System", 0xFF7B2CBF.toInt(), 1.38f),
        GameSystem("gb", "Game Boy", "GAME BOY", setOf("gb"), "gambatte_libretro_android.so", "Nintendo - Game Boy", 0xFF8BAC0F.toInt(), 1f),
        GameSystem("gbc", "Game Boy Color", "GAME BOY COLOR", setOf("gbc"), "gambatte_libretro_android.so", "Nintendo - Game Boy Color", 0xFF2A9D8F.toInt(), 1f),
        GameSystem("gba", "Game Boy Advance", "GAME BOY ADVANCE", setOf("gba"), "gpsp_libretro_android.so", "Nintendo - Game Boy Advance", 0xFF3A0CA3.toInt(), 1f),
        GameSystem("md", "Mega Drive", "MEGA DRIVE", setOf("md", "gen", "smd"), "genesis_plus_gx_libretro_android.so", "Sega - Mega Drive - Genesis", 0xFF1D3557.toInt(), .71f),
        GameSystem("sms", "Master System", "MASTER SYSTEM", setOf("sms"), "genesis_plus_gx_libretro_android.so", "Sega - Master System - Mark III", 0xFFC1121F.toInt(), .71f),
        GameSystem("gg", "Game Gear", "GAME GEAR", setOf("gg"), "genesis_plus_gx_libretro_android.so", "Sega - Game Gear", 0xFF264653.toInt(), .8f),
        GameSystem("pce", "PC Engine", "PC ENGINE", setOf("pce"), "mednafen_pce_fast_libretro_android.so", "NEC - PC Engine - TurboGrafx 16", 0xFFF77F00.toInt(), .72f),
        GameSystem("n64", "Nintendo 64", "N64", setOf("n64", "z64", "v64"), "mupen64plus_next_gles2_libretro_android.so", "Nintendo - Nintendo 64", 0xFF2D6A4F.toInt(), 1.38f),
        GameSystem("ps1", "PlayStation", "PLAYSTATION", setOf("cue", "chd", "pbp", "m3u"), "pcsx_rearmed_libretro_android.so", "Sony - PlayStation", 0xFF495057.toInt(), 1f),
    )
    private val byFolder = SYSTEMS.associateBy { it.id } + mapOf("genesis" to SYSTEMS[5], "megadrive" to SYSTEMS[5], "mastersystem" to SYSTEMS[6],
        "gamegear" to SYSTEMS[7], "psx" to SYSTEMS[10], "playstation" to SYSTEMS[10], "pcengine" to SYSTEMS[8], "tg16" to SYSTEMS[8], "nintendo64" to SYSTEMS[9])
    private val byExt = SYSTEMS.flatMap { s -> s.ext.map { it to s } }.toMap()
    val home get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/Games")
    val ENGINES = listOf("com.retroarch.ra32", "com.retroarch", "com.retroarch.aarch64")

    fun systemFor(f: File, folderHint: String?): GameSystem? {
        byExt[f.extension.lowercase()]?.let { return it }
        if (f.extension.lowercase() == "zip") return folderHint?.lowercase()?.let { byFolder[it] }
        return null
    }

    fun scan(root: File = home): List<Rom> {
        val out = ArrayList<Rom>()
        fun walk(d: File, hint: String?, depth: Int) {
            if (depth > 3) return
            for (f in d.listFiles() ?: return) {
                if (f.name.startsWith(".") || f.name == "Android") continue
                if (f.isDirectory) walk(f, if (byFolder.containsKey(f.name.lowercase())) f.name else hint, depth + 1)
                else systemFor(f, hint)?.let { s -> out.add(Rom(f, s)) }
            }
        }
        walk(root, null, 0)
        return out.sortedBy { it.title.lowercase().removePrefix("the ") }
    }

    fun engine(ctx: Context): String? = ENGINES.firstOrNull { try { ctx.packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false } }

    /** Starts the game in RetroArch with the right core and our tuned config: no RetroArch menus. */
    fun launchIntent(ctx: Context, rom: Rom): Intent? {
        val pkg = engine(ctx) ?: return null
        val sd = Environment.getExternalStorageDirectory().path
        val cfg = File("$sd/RetroLauncher/retroarch.cfg").takeIf { it.exists() }?.path ?: "$sd/Android/data/$pkg/files/retroarch.cfg"
        return Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture"))
            .putExtra("ROM", rom.file.path)
            .putExtra("LIBRETRO", Engines.path(ctx, pkg, rom.system.core))
            .putExtra("CONFIGFILE", cfg)
            .putExtra("IME", android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD))
            .putExtra("DATADIR", "/data/data/$pkg")
            .putExtra("SDCARD", sd)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /** Copies games from a USB drive into the library, sorted into system folders. */
    fun copyIn(roms: List<Rom>): Int {
        var n = 0
        for (r in roms) try {
            val dst = File(File(home, r.system.id), r.file.name)
            if (!dst.exists()) { dst.parentFile?.mkdirs(); r.file.copyTo(dst); n++ }
        } catch (_: Exception) {}
        return n
    }
}
