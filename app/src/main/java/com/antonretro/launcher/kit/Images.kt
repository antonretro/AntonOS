package com.antonretro.launcher.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A small image loader for channel logos and box art: downloads once, decodes at the size it is
 * shown (never full size), keeps a memory cache and a disk cache. No big image library needed.
 *
 * Logos used to go missing for good: one slow download put them on a "failed" list until the launcher
 * restarted, the files sat in the cache folder Android empties first when storage is low (this box has
 * little free), and requests for cards already scrolled away held up the visible ones. Now: files live in
 * the app's own storage, failures are retried later, http-to-https redirects are followed, and the newest
 * request (what is on screen now) is fetched first.
 */
object Images {
    private val mem = object : LruCache<String, Bitmap>(6 * 1024 * 1024) { override fun sizeOf(k: String, b: Bitmap) = b.byteCount }
    /** newest first: a LIFO queue, so the cards on screen now beat the ones scrolled past */
    private val pool = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable) = offerFirst(e)
    })
    private val ui = Handler(Looper.getMainLooper())
    /** url -> when to try again (uptime ms) */
    private val retryAt = HashMap<String, Long>()
    private lateinit var dir: File
    private const val RETRY_NET = 10 * 60_000L          // a download that failed: try again in 10 minutes
    private const val RETRY_UNREADABLE = 24 * 3600_000L // a file Android can't decode (SVG and the like): once a day

    /** The logo and box-art store never grows past this (oldest files go first); the box has little free space. */
    private const val CAP = 40L * 1024 * 1024
    fun sizeOnDisk(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
    fun clear() { dir.listFiles()?.forEach { it.delete() }; mem.evictAll(); retryAt.clear() }
    private fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= CAP * 3 / 4) break; total -= f.length(); f.delete() }
    }

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "img").apply { mkdirs() }
        pool.execute { try { if (sizeOnDisk() > CAP) trim() } catch (_: Exception) {} }
        // move anything from the old place (the cache folder Android may empty) once
        File(ctx.cacheDir, "img").listFiles()?.forEach { f -> try { val t = File(dir, f.name); if (!t.exists()) f.renameTo(t) else f.delete() } catch (_: Exception) {} }
    }

    fun cached(url: String): Bitmap? = mem.get(url)

    private fun fileFor(url: String) = File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length)

    /** Downloads following redirects, including http to https (HttpURLConnection won't cross protocols itself). */
    private fun download(url: String, to: File): Boolean {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 10000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.1) RetroLauncher")
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: return false).toString(); return@repeat }
                if (code !in 200..299) return false
                val t = File(to.path + ".tmp")
                c.inputStream.use { inp -> t.outputStream().use { inp.copyTo(it) } }
                return t.length() > 0 && t.renameTo(to)
            } finally { c.disconnect() }
        }
        return false
    }

    /** Calls back on the UI thread with the bitmap (or null if it can't be had right now). */
    fun load(url: String, maxW: Int, maxH: Int, cb: (Bitmap?) -> Unit) {
        mem.get(url)?.let { cb(it); return }
        retryAt[url]?.let { if (SystemClock.uptimeMillis() < it) { cb(null); return } }
        pool.execute {
            var bmp: Bitmap? = null; var unreadable = false
            try {
                val f = fileFor(url)
                if (!f.exists() || f.length() == 0L) download(url, f)
                if (f.exists()) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    if (o.outWidth <= 0) unreadable = true
                    else {
                        var s = 1; while (o.outWidth / (s * 2) >= maxW && o.outHeight / (s * 2) >= maxH) s *= 2
                        val raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                        // shrink to the size it is shown at (never keep more pixels than the card has)
                        bmp = raw?.let { r -> val k = minOf(maxW.toFloat() / r.width, maxH.toFloat() / r.height, 1f)
                            if (k < .95f) Bitmap.createScaledBitmap(r, maxOf(1, (r.width * k).toInt()), maxOf(1, (r.height * k).toInt()), true).also { if (it !== r) r.recycle() } else r }
                    }
                }
            } catch (_: Exception) {}
            val b = bmp
            ui.post {
                if (b != null) { mem.put(url, b); retryAt.remove(url) }
                else retryAt[url] = SystemClock.uptimeMillis() + if (unreadable) RETRY_UNREADABLE else RETRY_NET
                cb(b)
            }
        }
    }
}
