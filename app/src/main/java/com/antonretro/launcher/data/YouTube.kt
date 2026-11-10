package com.antonretro.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Video(val id: String, val title: String, val channel: String) {
    val thumb get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val url get() = "https://www.youtube.com/watch?v=$id"
    fun json() = JSONObject().put("id", id).put("t", title).put("c", channel)
    companion object { fun of(o: JSONObject) = Video(o.getString("id"), o.optString("t"), o.optString("c")) }
}

/**
 * Pinned channels read from YouTube's own public channel feeds (official, no key, no sign-in,
 * newest 15 videos). Playing and searching are handed to SmartTube. No scraping, no own player.
 */
class YouTubeRepo(private val dir: File) {
    class Pinned(val id: String, val name: String)

    /** Starter channels (removable): calm, family-friendly, always posting. */
    val starters = listOf(Pinned("UCLA_DiR1FfKNvjuUpBHmylQ", "NASA"), Pinned("UCwmZiChSryoWQCZMIQezgTg", "BBC Earth"), Pinned("UCsXVk37bltHxD1rDPwtNM8Q", "Kurzgesagt"))

    private val pinFile = File(dir, "youtube.json")
    var pinned: MutableList<Pinned> = load(); private set
    var watched: MutableList<Video> = loadWatched(); private set

    private fun load(): MutableList<Pinned> = try {
        val a = JSONObject(pinFile.readText()).getJSONArray("pinned")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Pinned(o.getString("id"), o.getString("name")) } }.toMutableList()
    } catch (e: Exception) { starters.toMutableList() }

    private fun loadWatched(): MutableList<Video> = try {
        val a = JSONObject(pinFile.readText()).getJSONArray("watched"); (0 until a.length()).map { Video.of(a.getJSONObject(it)) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    fun save() {
        val o = JSONObject().put("pinned", JSONArray(pinned.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("watched", JSONArray(watched.map { it.json() }))
        try { pinFile.writeText(o.toString()) } catch (_: Exception) {}
    }

    fun noteWatched(v: Video) { watched.removeAll { it.id == v.id }; watched.add(0, v); while (watched.size > 12) watched.removeAt(watched.size - 1); save() }
    fun unpin(id: String) { pinned.removeAll { it.id == id }; save() }

    /** Accepts a channel link (youtube.com/channel/UC...) or a bare UC... id. Returns the channel's name or null. */
    fun pin(input: String): String? {
        val id = Regex("""(UC[\w-]{22})""").find(input)?.value ?: return null
        val vids = feed(id, force = true) ?: return null
        val name = cachedName(id) ?: vids.firstOrNull()?.channel ?: return null
        if (pinned.none { it.id == id }) { pinned.add(Pinned(id, name)); save() }
        return name
    }

    private fun cacheFile(id: String) = File(dir, "yt_$id.xml")
    private fun cachedName(id: String): String? = try { parse(cacheFile(id).readText()).second } catch (e: Exception) { null }

    /** Newest videos of a channel: from the cache when fresh (30 min), else from the feed. Runs off the UI thread. */
    fun feed(id: String, force: Boolean = false): List<Video>? {
        val f = cacheFile(id)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 30 * 60 * 1000
        if (!fresh || force) try {
            val c = URL("https://www.youtube.com/feeds/videos.xml?channel_id=$id").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            if (c.responseCode == 200) f.writeText(c.inputStream.bufferedReader().readText())
            c.disconnect()
        } catch (_: Exception) {}
        return try { parse(f.readText()).first } catch (e: Exception) { null }
    }

    /** Plain pattern reading of the Atom feed: sturdier than the box's XML parser with the yt: names. */
    private fun parse(xml: String): Pair<List<Video>, String?> {
        fun un(t: String) = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim()
        val head = xml.substringBefore("<entry>")
        val channel = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(head)?.groupValues?.get(1)?.let(::un)
        val out = ArrayList<Video>()
        for (m in Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val e = m.groupValues[1]
            val id = Regex("<yt:videoId>(.*?)</yt:videoId>").find(e)?.groupValues?.get(1)
                ?: Regex("""watch\?v=([\w-]{11})""").find(e)?.groupValues?.get(1) ?: continue
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1)?.let(::un) ?: ""
            val author = Regex("<name>(.*?)</name>").find(e)?.groupValues?.get(1)?.let(::un) ?: channel ?: ""
            out.add(Video(id, title, author))
        }
        return out to channel
    }
}
