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

/**
 * Anthony's Retro Visualizer engine in native code (the retro-visualizer AAR: C++ over JNI, its own SurfaceView,
 * 30 fps). Bit-identical to the web version and several times cheaper on the box.
 */
class NativeVisualizer(ctx: Context, private val ui: Handler, style: String) : VisualizerHost {
    override var onReady: ((List<String>) -> Unit)? = null
    private val vis = com.antonretro.visualizer.VisualizerView(ctx).apply {
        setFilter(com.antonretro.visualizer.VisualizerView.FILTER_LINEAR)
        isFocusable = false; isFocusableInTouchMode = false
    }
    private val engine get() = vis.engine
    /** the engine's own preset numbers, in the order shown in the menu */
    private var indexes: List<Int> = emptyList()
    override val view: View get() = vis

    init {
        engine.setVisualizer(kind(style))
        ui.post { onReady?.invoke(names()) }
    }
    private fun kind(style: String) = if (style == "battery") com.antonretro.visualizer.RetroVisualizer.PLASMA else com.antonretro.visualizer.RetroVisualizer.SPECTRUM
    private fun names(): List<String> {
        val p = try { engine.listPresets() } catch (e: Exception) { emptyList() }
        indexes = p.map { it.index }
        return p.map { it.title }.ifEmpty { listOf("Random") }
    }

    private var shorts = ShortArray(0)
    override fun feedPcm(pcm: ByteArray, channels: Int, rate: Int) {
        val n = pcm.size / 2; if (n == 0) return
        // the engine takes interleaved 16-bit stereo: mono is doubled, anything above stereo keeps its first two channels
        val frames = n / channels.coerceAtLeast(1)
        if (shorts.size < frames * 2) shorts = ShortArray(frames * 2)
        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (f in 0 until frames) {
            val l = bb.get(f * channels); val r = if (channels >= 2) bb.get(f * channels + 1) else l
            shorts[f * 2] = l; shorts[f * 2 + 1] = r
        }
        // in pieces of at most 1024 frames: a bigger block (the player hands one over when a song changes) once
        // overran a fixed buffer inside the native engine and crashed RetroOS ("stack corruption detected")
        var at = 0
        while (at < frames) {
            val n = minOf(1024, frames - at)
            val piece = if (at == 0 && n == frames) shorts else shorts.copyOfRange(at * 2, (at + n) * 2)
            try { engine.feedPcm(piece, n, rate) } catch (_: Exception) {}
            at += n
        }
    }
    override fun setVisualizer(style: String, then: (List<String>) -> Unit) { engine.setVisualizer(kind(style)); then(names()) }
    override fun setPreset(index: Int) { indexes.getOrNull(index)?.let { engine.setPreset(it) } }
    override fun resize(w: Int, h: Int) {}
    override fun pause() { engine.pause(); vis.stop() }
    override fun resume() { vis.start(); engine.resume() }
    override fun loadClassicPack(path: String) { try { engine.loadClassicPack(path) } catch (_: Exception) {} }
    override fun release() { vis.release() }
}
