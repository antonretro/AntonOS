package com.antonretro.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.antonretro.launcher.kit.GlassDrawable
import com.antonretro.launcher.kit.Kit

/**
 * Web: AntonOS's own browser for the TV, on the box's system web engine (WebView 119 here). The remote's arrows steer
 * a pointer (it speeds up when held, the page scrolls when it reaches an edge), OK clicks, Back goes back a page,
 * Menu (or Up at the very top) opens the address bar. A mouse just works. Pages ask nothing of the box: no file
 * access, no location, no camera; links to other apps (market://, intent://) are not followed.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebView2(ctx: Context) : FrameLayout(ctx) {
    var onClose: (() -> Unit)? = null
    /** opens the on-screen keyboard (or the phone's) for the address; calls back with what was typed */
    var askAddress: ((String, (String) -> Unit) -> Unit)? = null
    val showing get() = visibility == View.VISIBLE

    private val web = WebView(ctx)
    private val bar = LinearLayout(ctx)
    private val address = Kit.text(ctx, 18f, true)
    private val progress = View(ctx)
    private val pointer = Pointer(ctx)
    private val ui = Handler(Looper.getMainLooper())
    private var px = 640f; private var py = 360f
    private var barShown = false

    init {
        visibility = View.GONE; isClickable = true
        setBackgroundColor(0xFF0A0C12.toInt())
        with(web.settings) {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            setGeolocationEnabled(false)
            useWideViewPort = true; loadWithOverviewMode = true
            builtInZoomControls = false; displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            // the TV-sized desktop page reads better than the phone page
            userAgentString = userAgentString.replace("Mobile ", "").replace("; wv", "")
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val s = req.url.scheme ?: return true
                return s != "http" && s != "https"   // only web pages; never hand off to other apps
            }
            override fun onPageFinished(view: WebView, url: String) { address.text = pretty(url) }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.scaleX = p / 100f; progress.alpha = if (p >= 100) 0f else 1f
            }
        }
        addView(web, LayoutParams(-1, -1))
        progress.setBackgroundColor(Kit.ACCENT); progress.pivotX = 0f
        addView(progress, LayoutParams(-1, Kit.dpi(ctx, 3f), Gravity.TOP))
        // the address bar, hidden until Menu or Up at the top
        bar.orientation = LinearLayout.HORIZONTAL; bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Kit.dpi(ctx, 24f), 0, Kit.dpi(ctx, 24f), 0)
        bar.background = GlassDrawable(bar, Kit.dp(ctx, Kit.pill(56f)), Kit.GLASS_STRONG)
        address.isSingleLine = true; address.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(Kit.text(ctx, 18f, true, Kit.TEXT_DIM).apply { text = "🌐  " })
        bar.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Kit.text(ctx, 15f, true, Kit.TEXT_DIM).apply { text = "OK  type an address     Back  close" })
        bar.visibility = View.GONE
        addView(bar, LayoutParams(-1, Kit.dpi(ctx, 56f), Gravity.TOP).apply { leftMargin = Kit.dpi(ctx, Kit.SAFE_X); rightMargin = Kit.dpi(ctx, Kit.SAFE_X); topMargin = Kit.dpi(ctx, 20f) })
        addView(pointer, LayoutParams(-1, -1))
    }

    private fun pretty(u: String) = if (u.startsWith("about:") || u.contains("start.antonos")) "" else u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    fun show(url: String? = null) {
        visibility = View.VISIBLE; bringToFront(); web.onResume()
        post { px = width / 2f; py = height / 2f; pointer.invalidate() }   // the middle, once the size is known
        if (url != null) load(url) else if (web.url == null) web.loadDataWithBaseURL("https://start.antonos/", START, "text/html", "utf-8", null)
        pointer.invalidate()
    }
    fun hide() { web.onPause(); visibility = View.GONE; setBar(false); onClose?.invoke() }

    /** An address or search words: words search the web (DuckDuckGo, no account, no tracking). */
    fun load(input: String) {
        val s = input.trim(); if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> "https://duckduckgo.com/?q=" + android.net.Uri.encode(s)
        }
        web.loadUrl(url); address.text = pretty(url)
    }

    private fun setBar(on: Boolean) {
        barShown = on
        bar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { address.text = pretty(web.url ?: ""); bar.bringToFront() }
        pointer.visibility = if (on) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ the remote's pointer
    private var heldSince = 0L
    private fun step(dx: Int, dy: Int, repeat: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!repeat) heldSince = now
        // a tap moves a little; holding speeds up to cross the screen in about a second
        val held = (now - heldSince).coerceAtMost(900L)
        val speed = Kit.dp(context, 14f) + held / 900f * Kit.dp(context, 46f)
        px += dx * speed; py += dy * speed
        val edge = Kit.dp(context, 24f)
        if (py < edge && dy < 0) { if (web.scrollY <= 0) { py = edge } else web.scrollBy(0, -(speed * 2).toInt()) }
        if (py > height - edge && dy > 0) web.scrollBy(0, (speed * 2).toInt())
        if (px < edge && dx < 0) web.scrollBy(-(speed * 2).toInt(), 0)
        if (px > width - edge && dx > 0) web.scrollBy((speed * 2).toInt(), 0)
        px = px.coerceIn(edge, width - edge); py = py.coerceIn(edge, height - edge)
        pointer.invalidate()
    }
    private fun click() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, py, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, px, py, 0)
        web.dispatchTouchEvent(down); web.dispatchTouchEvent(up); down.recycle(); up.recycle()
        pointer.pulse()
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val rep = e.repeatCount > 0
        if (barShown) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (!rep) askAddress?.invoke(web.url ?: "") { load(it); setBar(false) }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> if (!rep) setBar(false)
            }
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (py <= Kit.dp(context, 30f) && web.scrollY <= 0 && !rep) setBar(true) else step(0, -1, rep)
            KeyEvent.KEYCODE_DPAD_DOWN -> step(0, 1, rep)
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1, 0, rep)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1, 0, rep)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> if (!rep) click()
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_BUTTON_R1 -> web.pageDown(false)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_BUTTON_L1 -> web.pageUp(false)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> if (!rep) setBar(true)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (!rep) { if (web.canGoBack()) web.goBack() else hide() }
            else -> return false
        }
        return true
    }

    /** The arrow pointer, drawn over the page. */
    private inner class Pointer(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var ring = 0f
        init { isClickable = false; isFocusable = false }
        fun pulse() { ring = 1f; postInvalidateOnAnimation() }
        override fun onDraw(c: Canvas) {
            val s = Kit.dp(context, 1f)
            path.reset(); path.moveTo(px, py); path.lineTo(px, py + 22 * s); path.lineTo(px + 6 * s, py + 17 * s)
            path.lineTo(px + 10 * s, py + 26 * s); path.lineTo(px + 14 * s, py + 24 * s); path.lineTo(px + 10 * s, py + 15 * s)
            path.lineTo(px + 17 * s, py + 15 * s); path.close()
            p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * s; p.color = 0xFF111111.toInt(); c.drawPath(path, p)
            if (ring > 0f) {
                p.color = Kit.ACCENT; p.alpha = (ring * 200).toInt(); p.strokeWidth = 3 * s
                c.drawCircle(px, py, (1f - ring) * 26 * s + 6 * s, p)
                ring -= .08f; postInvalidateOnAnimation()
            }
        }
        override fun onTouchEvent(e: MotionEvent) = false
    }

    companion object {
        /** The start page: a few big shortcuts, readable from the sofa. */
        private val START = """<!doctype html><html><head><meta name=viewport content="width=1280"><style>
            body{margin:0;background:#0a0c12;color:#fff;font-family:Roboto,sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh}
            h1{font-size:44px;margin:0 0 10px}p{color:#9aa0aa;font-size:20px;margin:0 0 40px}
            .g{display:grid;grid-template-columns:repeat(4,220px);gap:22px}
            a{display:flex;align-items:center;justify-content:center;height:110px;border-radius:18px;background:#1c2130;color:#fff;font-size:22px;font-weight:700;text-decoration:none}
            a:hover{background:#2c3550}</style></head><body>
            <h1>Web</h1><p>Arrows move the pointer · OK clicks · Menu or Up at the top: type an address · Back: back a page</p>
            <div class=g><a href="https://duckduckgo.com">Search</a><a href="https://en.wikipedia.org">Wikipedia</a><a href="https://www.bbc.com/weather">Weather</a><a href="https://news.google.com">News</a>
            <a href="https://archive.org">Internet Archive</a><a href="https://antonretro.com">AntonRetro</a><a href="https://github.com/antonretro/antonos">AntonOS</a><a href="https://maps.google.com">Maps</a></div></body></html>"""
    }
}
