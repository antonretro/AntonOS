package com.antonretro.launcher.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.view.View
import android.webkit.WebView

/**
 * Everything Now Playing needs from a visualizer. Today it is Anthony's JS engine in a WebView ([WebVisualizer]);
 * it is planned to become his native C++ engine (an NDK library with its own View), which only has to implement
 * this interface: Now Playing doesn't change.
 */
interface VisualizerHost {
    /** the view to show full screen behind the controls */
    val view: View
    /** called once the engine is up, with the preset names of the current visualizer */
    var onReady: ((List<String>) -> Unit)?
    /** 16-bit little-endian PCM straight from the player's tap; may be called from the audio thread */
    fun feedPcm(pcm: ByteArray, channels: Int, rate: Int)
    /** switch visualizer; [then] gets its preset names */
    fun setVisualizer(style: String, then: (List<String>) -> Unit)
    fun setPreset(index: Int)
    fun resize(w: Int, h: Int)
    fun pause()
    fun resume()
    /** the optional classic pack the user put on the box themselves (never shipped) */
    fun loadClassicPack(path: String)
    fun release()
}

/** Anthony's visualizer engine (assets/visualizer) in a WebView, capped at 30 fps by nowplaying.html. */
class WebVisualizer(ctx: Context, private val ui: Handler, private val style: String) : VisualizerHost {
    override var onReady: ((List<String>) -> Unit)? = null
    private val web = WebView(ctx).apply {
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        settings.allowFileAccess = true
        @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true
        setLayerType(View.LAYER_TYPE_HARDWARE, null); setBackgroundColor(Color.BLACK)
        isFocusable = false; isFocusableInTouchMode = false
        webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("setSize(1280,720);setVisualizer('$style');setIdle('demo');listPresets()") { json -> onReady?.invoke(names(json)) }
            }
        }
        loadUrl("file:///android_asset/visualizer/nowplaying.html")
    }
    override val view: View get() = web

    private fun names(json: String?): List<String> = try { org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { listOf("Random") }

    /** PCM goes over as base64 in about 6 batches a second (fewer, larger calls are cheaper for the WebView). */
    private val pending = java.io.ByteArrayOutputStream()
    override fun feedPcm(pcm: ByteArray, channels: Int, rate: Int) {
        var out: String? = null
        synchronized(pending) {
            pending.write(pcm, 0, pcm.size)
            if (pending.size() >= rate / 6 * channels * 2) { out = android.util.Base64.encodeToString(pending.toByteArray(), android.util.Base64.NO_WRAP); pending.reset() }
        }
        out?.let { b64 -> ui.post { web.evaluateJavascript("feedPcmBase64('$b64',$channels,$rate)", null) } }
    }
    override fun setVisualizer(style: String, then: (List<String>) -> Unit) = web.evaluateJavascript("setVisualizer('$style');listPresets()") { then(names(it)) }
    override fun setPreset(index: Int) = web.evaluateJavascript("setPreset($index)", null)
    override fun resize(w: Int, h: Int) = web.evaluateJavascript("setSize($w,$h)", null)
    override fun pause() { web.evaluateJavascript("pause()", null); synchronized(pending) { pending.reset() } }
    override fun resume() { web.onResume(); web.evaluateJavascript("resume()", null) }
    override fun loadClassicPack(path: String) = web.evaluateJavascript("loadClassicPack(${org.json.JSONObject.quote(path)})", null)
    override fun release() { pause(); web.destroy() }
}
