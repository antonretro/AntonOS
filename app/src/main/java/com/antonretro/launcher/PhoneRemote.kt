package com.antonretro.launcher

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The phone remote: a tiny web server inside RetroOS on the home network. Scanning the QR code on the TV
 * opens the remote page (assets/remote) in the phone's browser; no app, so iPhone and Android both work.
 *
 * Safety: off until switched on in Settings; every request must carry the secret from the QR code (so only a phone
 * that saw the TV screen can press anything); "Forget phones" makes a new secret. Plain http on the home network
 * only: the box never asks the router to open a port, so it can't be reached from the internet.
 *
 * What reaches the box goes through [Box] (implemented by HomeActivity): one small interface for every press,
 * so the transport can change later without touching the launcher.
 */
object PhoneRemote {
    const val PORT = 8642

    interface Box {
        /** a remote key: up down left right ok back home playpause next prev volup voldown mute menu; returns a note for the phone or null */
        fun key(k: String): String?
        /** text typed or dictated on the phone; [submit] = Enter / Send */
        fun text(t: String, submit: Boolean, target: String): String?
        /** open a tab of the launcher by name */
        fun tab(name: String): String?
        /** a channel number typed on the number pad */
        fun number(n: String): String?
        /** what the TV shows now, for the top of the phone page */
        fun status(): String
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile var handler: Box? = null
    private val main = android.os.Handler(Looper.getMainLooper())
    val running get() = server != null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("phone", Context.MODE_PRIVATE)
    /** the secret in the QR code */
    fun token(ctx: Context): String = prefs(ctx).getString("token", null) ?: newToken(ctx)
    fun newToken(ctx: Context): String {
        val b = ByteArray(9); SecureRandom().nextBytes(b)
        val t = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val code = "%04d".format(SecureRandom().nextInt(10000))
        prefs(ctx).edit().putString("token", t).putString("code", code).apply(); return t
    }
    /** the 4-digit code on the TV, for a phone that types the address instead of scanning */
    fun code(ctx: Context): String { token(ctx); return prefs(ctx).getString("code", null) ?: "%04d".format(SecureRandom().nextInt(10000)).also { prefs(ctx).edit().putString("code", it).apply() } }
    @Volatile private var wrongTries = 0
    @Volatile private var lockedUntil = 0L
    /** the plain address to type (no secret in it) */
    fun shortUrl(): String? = address()?.let { "http://$it:$PORT" }
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("on", on).apply(); if (on) start(ctx) else stop() }

    /** The box's address on the home network (Wi-Fi or Ethernet). */
    fun address(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    } catch (e: Exception) { null }
    fun url(ctx: Context): String? = address()?.let { "http://$it:$PORT/?t=" + token(ctx) }

    fun start(ctx: Context) {
        if (server != null) return
        val app = ctx.applicationContext
        val s = try { ServerSocket(PORT) } catch (e: Exception) { Perf.note("phone remote can't start: $e"); return }
        server = s
        thread(name = "phone-remote", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { try { serve(app, c) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} } }
            }
        }
        Perf.note("phone remote on at ${address()}:$PORT")
    }
    fun stop() { try { server?.close() } catch (_: Exception) {}; server = null }

    // ------------------------------------------------------------------ a very small http server
    private fun serve(ctx: Context, c: Socket) {
        c.soTimeout = 8000
        val inp = BufferedInputStream(c.getInputStream())
        val head = readHead(inp) ?: return
        val lines = head.split("\r\n")
        val parts = lines[0].split(" "); if (parts.size < 2) return
        val method = parts[0]; val target = parts[1]
        val len = lines.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = if (len in 1..65536) ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) break; o += n } }.toString(Charsets.UTF_8) else ""
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { android.net.Uri.decode(it[0]) to android.net.Uri.decode(it[1]) } }.toMap()
        val out = c.getOutputStream()
        // the page itself and its files (they hold nothing secret; the secret travels in the page's address)
        if (method == "GET") {
            val file = when (path) { "/", "/index.html" -> "index.html"; "/manifest.json" -> "manifest.json"; "/icon.png" -> "icon.png"; else -> null }
            if (file == null) return send(out, 404, "text/plain", "Not found".toByteArray())
            val bytes = try { ctx.assets.open("remote/$file").use { it.readBytes() } } catch (e: Exception) { return send(out, 404, "text/plain", "Not found".toByteArray()) }
            val type = when { file.endsWith(".html") -> "text/html; charset=utf-8"; file.endsWith(".json") -> "application/manifest+json"; else -> "image/png" }
            return send(out, 200, type, bytes)
        }
        // typing the 4-digit code from the TV swaps it for the secret; 5 wrong tries lock it for a minute
        if (path == "/api/pair") {
            val now = System.currentTimeMillis()
            if (now < lockedUntil) return send(out, 403, "application/json", """{"ok":false,"note":"Too many tries. Wait a minute"}""".toByteArray())
            val given = try { JSONObject(body).optString("code") } catch (e: Exception) { "" }
            if (given == code(ctx)) { wrongTries = 0; return send(out, 200, "application/json", JSONObject().put("ok", true).put("token", token(ctx)).toString().toByteArray()) }
            if (++wrongTries >= 5) { wrongTries = 0; lockedUntil = now + 60_000 }
            return send(out, 403, "application/json", """{"ok":false,"note":"That's not the code on the TV"}""".toByteArray())
        }
        // every press needs the secret from the QR code
        val tok = lines.firstOrNull { it.lowercase().startsWith("x-remote-token:") }?.substringAfter(':')?.trim() ?: query["t"]
        if (tok == null || tok != token(ctx)) return send(out, 403, "application/json", """{"ok":false,"note":"Scan the QR code on the TV again"}""".toByteArray())
        val j = try { JSONObject(body.ifEmpty { "{}" }) } catch (e: Exception) { JSONObject() }
        val h = handler ?: return send(out, 503, "application/json", """{"ok":false,"note":"RetroOS is starting"}""".toByteArray())
        val note = runOnMain {
            when (path) {
                "/api/key" -> h.key(j.optString("k"))
                "/api/text" -> h.text(j.optString("t"), j.optBoolean("submit"), j.optString("target"))
                "/api/tab" -> h.tab(j.optString("name"))
                "/api/number" -> h.number(j.optString("n"))
                "/api/status" -> null
                else -> "Unknown"
            }
        }
        val status = runOnMain { h.status() }
        send(out, 200, "application/json", JSONObject().put("ok", true).put("note", note ?: JSONObject.NULL).put("status", status ?: "").toString().toByteArray())
    }

    private fun <T> runOnMain(f: () -> T): T? {
        val lock = Object(); var r: T? = null; var done = false
        main.post { try { r = f() } catch (e: Exception) { Perf.note("phone remote: $e") } finally { synchronized(lock) { done = true; lock.notifyAll() } } }
        synchronized(lock) { val until = System.currentTimeMillis() + 3000; while (!done && System.currentTimeMillis() < until) lock.wait(200) }
        return r
    }

    private fun readHead(inp: BufferedInputStream): String? {
        val b = ByteArrayOutputStream(); var last4 = 0
        while (b.size() < 16384) {
            val x = inp.read(); if (x < 0) return null
            b.write(x); last4 = (last4 shl 8) or x
            if (last4 == 0x0D0A0D0A) return b.toString("UTF-8").trimEnd()
        }
        return null
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Unavailable" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    /** The QR code for [text] as a bitmap with a quiet border (zxing's encoder). */
    fun qr(text: String, size: Int): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }
}
