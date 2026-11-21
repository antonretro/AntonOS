package com.antonretro.launcher.data

import android.content.Context
import android.util.JsonReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Every country iptv-org has (name, flag, roughly how many channels), its regions (Europe, Latin America...), and
 * which countries are in the channel list. Settings > TV & Radio > Countries ticks them; the channel update
 * ([ChannelUpdate]) then fetches exactly those. The lists are small files kept in RetroLauncher and refreshed weekly;
 * the channel counts come from iptv-org's stream list (4 MB), read as a stream and kept as a short text file.
 * iptv-org writes the United Kingdom as "UK"; AntonOS has always used "GB" for it, so codes are mapped both ways.
 */
object Countries {
    class Country(val code: String, val name: String, val flag: String, val count: Int)
    class Region(val name: String, val codes: List<String>)

    private const val API = "https://iptv-org.github.io/api"
    private val dir get() = ChannelRepo.dir
    private val countriesFile get() = File(dir, "iptv-countries.json")
    private val regionsFile get() = File(dir, "iptv-regions.json")
    private val countsFile get() = File(dir, "iptv-counts.txt")
    private const val WEEK = 7 * 24 * 3600_000L

    private fun ours(code: String) = if (code.equals("UK", true)) "GB" else code.uppercase()

    @Volatile private var countries: List<Country> = emptyList()
    @Volatile private var regions: List<Region> = emptyList()
    fun all() = countries
    fun allRegions() = regions

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("channels", Context.MODE_PRIVATE)

    /** The ticked countries, as codes. The first time, whatever the list has now (Canada, US, UK on a new box). */
    fun chosen(ctx: Context): Set<String> {
        val p = prefs(ctx)
        p.getStringSet("countries", null)?.let { return it.toSet() }
        val now = ChannelUpdate.countries().toSet()
        p.edit().putStringSet("countries", now).apply()
        return now
    }
    fun setChosen(ctx: Context, codes: Set<String>) { prefs(ctx).edit().putStringSet("countries", codes.toSet()).apply() }

    /** The name the lists and the guide use for a country code. */
    fun name(code: String): String = countries.firstOrNull { it.code == code }?.name ?: ChannelUpdate.COUNTRIES[code] ?: code
    fun flag(code: String): String = countries.firstOrNull { it.code == code }?.flag ?: ""

    private fun download(url: String, to: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000; c.setRequestProperty("User-Agent", "AntonOS")
        try {
            if (c.responseCode != 200) false else {
                val tmp = File(to.path + ".part"); c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(to) || run { tmp.copyTo(to, true); tmp.delete(); true }
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { false }

    /** Streams per country: the channel id of each stream ends in its country ("CBCNews.ca@HD"). Read as a stream. */
    private fun fetchCounts(): Boolean = try {
        val c = URL("$API/streams.json").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000; c.setRequestProperty("User-Agent", "AntonOS")
        val n = HashMap<String, Int>()
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            JsonReader(c.inputStream.bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "channel" && r.peek() == android.util.JsonToken.STRING) {
                            val cc = ours(r.nextString().substringBefore('@').substringAfterLast('.', ""))
                            if (cc.isNotEmpty()) n[cc] = (n[cc] ?: 0) + 1
                        } else r.skipValue()
                    }
                    r.endObject()
                }
                r.endArray()
            }
        } finally { c.disconnect() }
        countsFile.writeText(n.entries.joinToString("") { "${it.key} ${it.value}\n" }); true
    } catch (e: Exception) { false }

    /** Reads the cached lists, downloading them when missing or older than a week. Background thread only. */
    fun load(refresh: Boolean = false) {
        fun stale(f: File) = refresh || !f.isFile || System.currentTimeMillis() - f.lastModified() > WEEK
        if (stale(countriesFile)) download("$API/countries.json", countriesFile)
        if (stale(regionsFile)) download("$API/regions.json", regionsFile)
        if (stale(countsFile)) fetchCounts()
        val counts = try { countsFile.readLines().mapNotNull { l -> l.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1].toInt() } }.toMap() } catch (e: Exception) { emptyMap() }
        try {
            val a = org.json.JSONArray(countriesFile.readText())
            countries = (0 until a.length()).map { i -> a.getJSONObject(i) }.map { o ->
                val code = ours(o.getString("code"))
                // the lists call it the United Kingdom; keep the names the channel list already uses
                Country(code, ChannelUpdate.COUNTRIES[code] ?: o.getString("name"), o.optString("flag"), counts[code] ?: 0)
            }.sortedBy { it.name }
        } catch (e: Exception) {}
        try {
            val a = org.json.JSONArray(regionsFile.readText())
            regions = (0 until a.length()).map { i -> a.getJSONObject(i) }.map { o ->
                val cs = o.getJSONArray("countries"); Region(o.getString("name"), (0 until cs.length()).map { ours(cs.getString(it)) })
            }.filter { it.codes.size in 2..60 }.sortedBy { it.name }
        } catch (e: Exception) {}
    }
}
