package com.antonretro.launcher.data

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the channels that don't work, so the Guide and the TV rows can leave them out ("Working only").
 * For each stream it goes as far as real video: the playlist, the first quality inside it, and the first piece of
 * video. Gentle on the sources: 4 at a time, short timeouts, a few bytes of each piece, once a day, and it stops
 * at once when something starts playing ([busy]).
 */
object ChannelCheck {
    private fun open(url: String): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 8_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RetroLauncher/1 (Android TV)")
        }
    } catch (e: Exception) { null }

    /** The first [max] bytes of a URL as text, and the final address after redirects; null if it failed. */
    private fun peek(url: String, max: Int): Pair<String, String>? {
        val c = open(url) ?: return null
        return try {
            if (c.responseCode !in 200..299) null
            else {
                val buf = ByteArray(max); var n = 0
                c.inputStream.use { inp -> while (n < max) { val r = inp.read(buf, n, max - n); if (r < 0) break; n += r } }
                if (n == 0) null else String(buf, 0, n, Charsets.ISO_8859_1) to c.url.toString()
            }
        } catch (e: Exception) { null } finally { c.disconnect() }
    }
    private fun resolve(base: String, ref: String): String = try { URL(URL(base), ref).toString() } catch (e: Exception) { ref }
    /** The first non-comment line after a tag that marks a URI. */
    private fun firstUri(text: String, after: String): String? {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val i = lines.indexOfFirst { it.startsWith(after) }; if (i < 0) return null
        return lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    /** true if the stream reaches real video (or sound, for radio). */
    fun works(url: String): Boolean {
        val (head, at) = peek(url, 64 * 1024) ?: return false
        return when {
            head.startsWith("#EXTM3U") -> {
                var media = head; var mediaAt = at
                if (head.contains("#EXT-X-STREAM-INF")) {
                    val v = firstUri(head, "#EXT-X-STREAM-INF") ?: return false
                    val got = peek(resolve(at, v), 64 * 1024) ?: return false
                    media = got.first; mediaAt = got.second
                }
                val seg = firstUri(media, "#EXTINF") ?: return false
                peek(resolve(mediaAt, seg), 2048) != null
            }
            head.contains("<MPD") -> true
            else -> true   // a plain stream (radio, .ts): bytes arrived
        }
    }

    /** Tests every URL, 4 at a time; [result] is called for each (on a worker thread). Returns how many failed. */
    fun run(urls: List<String>, busy: () -> Boolean, result: (String, Boolean) -> Unit, progress: (Int, Int) -> Unit): Int {
        val pool = Executors.newFixedThreadPool(4)
        val done = AtomicInteger(); val bad = AtomicInteger()
        for (u in urls) pool.execute {
            if (busy()) return@execute
            val ok = try { works(u) } catch (e: Throwable) { false }
            if (!ok) bad.incrementAndGet()
            if (!busy()) result(u, ok)
            val d = done.incrementAndGet(); if (d % 50 == 0) progress(d, urls.size)
        }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.HOURS)
        return bad.get()
    }
}
