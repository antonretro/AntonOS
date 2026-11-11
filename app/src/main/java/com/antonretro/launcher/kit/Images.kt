package com.antonretro.launcher.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A small image loader for channel logos and box art: downloads once, decodes at the size it is
 * shown (never full size), keeps a memory cache and a disk cache. No big image library needed.
 */
object Images {
    private val mem = object : LruCache<String, Bitmap>(4 * 1024 * 1024) { override fun sizeOf(k: String, b: Bitmap) = b.byteCount }
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val failed = HashSet<String>()
    private lateinit var dir: File

    fun init(ctx: Context) { dir = File(ctx.cacheDir, "img").apply { mkdirs() } }

    fun cached(url: String): Bitmap? = mem.get(url)

    /** Calls back on the UI thread with the bitmap (or null if it can't be had). */
    fun load(url: String, maxW: Int, maxH: Int, cb: (Bitmap?) -> Unit) {
        mem.get(url)?.let { cb(it); return }
        if (url in failed) { cb(null); return }
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val f = File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length)
                if (!f.exists() || f.length() == 0L) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 4000; c.readTimeout = 6000; c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "RetroLauncher")
                    if (c.responseCode in 200..299) c.inputStream.use { inp -> File(f.path + ".tmp").also { t -> t.outputStream().use { inp.copyTo(it) }; t.renameTo(f) } }
                    c.disconnect()
                }
                if (f.exists()) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    var s = 1; while (o.outWidth / (s * 2) >= maxW && o.outHeight / (s * 2) >= maxH) s *= 2
                    val raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                    // shrink to the size it is shown at (never keep more pixels than the card has)
                    bmp = raw?.let { r -> val k = minOf(maxW.toFloat() / r.width, maxH.toFloat() / r.height, 1f)
                        if (k < .95f) Bitmap.createScaledBitmap(r, maxOf(1, (r.width * k).toInt()), maxOf(1, (r.height * k).toInt()), true).also { if (it !== r) r.recycle() } else r }
                }
            } catch (_: Exception) {}
            val b = bmp
            ui.post { if (b != null) mem.put(url, b) else failed.add(url); cb(b) }
        }
    }
}
