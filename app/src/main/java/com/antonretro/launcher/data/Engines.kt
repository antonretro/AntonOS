package com.antonretro.launcher.data

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Game engines (libretro cores) that AntonOS downloads itself, so a new console works without opening RetroArch's
 * menus. They come from libretro's own build server (the same place RetroArch's Core Downloader uses), for the
 * box's processor, and are kept in AntonOS's private folder, readable by RetroArch, which loads them from there.
 * Asked for when a game needs one ("This game needs the Super Nintendo engine"), or in Settings, Games, Game engines.
 */
object Engines {
    /** cores RetroArch had on this box before AntonOS could download any (games started with these from day one) */
    private val IN_RETROARCH = setOf("fceumm_libretro_android.so", "gambatte_libretro_android.so")

    /** How much each console asks of the box: 0 any box, 1 a quad-core from about 1.2 GHz, 2 a fast, newer box. */
    private val DEMAND = mapOf("n64" to 2, "ps1" to 1)
    /** This box, on the same scale (measured once: processor cores and speed, memory). */
    private var power = -1
    fun power(ctx: Context): Int {
        if (power >= 0) return power
        val cores = Runtime.getRuntime().availableProcessors()
        val ghz = try { File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1_000_000f } catch (e: Exception) { 1.2f }
        val mem = android.app.ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }.totalMem / 1e9f
        power = when {
            cores >= 4 && ghz >= 1.8f && mem >= 1.8f && android.os.Process.is64Bit() -> 2
            cores >= 4 && ghz >= 1.15f -> 1
            else -> 0
        }
        return power
    }
    /** true when this box is probably too slow for the console's games */
    fun tooSlow(ctx: Context, sys: GameSystem) = (DEMAND[sys.id] ?: 0) > power(ctx)

    @Volatile var working: String? = null; private set
    @Volatile var fraction = 0f; private set

    private fun abi(pkg: String) = if (pkg.endsWith("aarch64")) "arm64-v8a" else "armeabi-v7a"
    private fun dir(ctx: Context) = File(ctx.filesDir, "cores")
    fun local(ctx: Context, core: String): File? = File(dir(ctx), core).takeIf { it.length() > 0 }
    /** true when this core can start games: ours, or one RetroArch already had */
    fun ready(ctx: Context, core: String) = local(ctx, core) != null || core in IN_RETROARCH
    /** where RetroArch should load the core from */
    fun path(ctx: Context, pkg: String, core: String): String = local(ctx, core)?.path ?: "/data/data/$pkg/cores/$core"
    fun name(core: String) = core.substringBefore("_libretro").replace('_', ' ')

    /** Downloads and unpacks one core. Runs on the caller's (background) thread. */
    fun install(ctx: Context, pkg: String, core: String): Boolean {
        if (working != null) return false
        working = core; fraction = 0f
        val d = dir(ctx); d.mkdirs()
        val tmp = File(d, "$core.part")
        try {
            val c = URL("https://buildbot.libretro.com/nightly/android/latest/${abi(pkg)}/$core.zip").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000; c.setRequestProperty("User-Agent", "AntonOS")
            try {
                if (c.responseCode != 200) return false
                val size = c.contentLength.toFloat()
                var got = 0L
                ZipInputStream(c.inputStream.buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null && !e.name.endsWith(".so")) e = z.nextEntry
                    if (e == null) return false
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = z.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            // the zip is about half the size of the core inside it
                            if (size > 0) fraction = (got / (size * 2.2f)).coerceAtMost(.98f)
                        }
                    }
                }
            } finally { c.disconnect() }
            val dst = File(d, core)
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, true); tmp.delete() }
            // RetroArch runs as another app: let it reach the folder and read the file (Android 7 checks only these)
            ctx.filesDir.parentFile?.setExecutable(true, false)
            ctx.filesDir.setExecutable(true, false)
            d.setExecutable(true, false)
            dst.setReadable(true, false)
            fraction = 1f
            return true
        } catch (e: Exception) {
            tmp.delete(); return false
        } finally { working = null }
    }
}
