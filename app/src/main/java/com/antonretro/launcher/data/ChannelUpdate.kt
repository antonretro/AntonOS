package com.antonretro.launcher.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the channel list up to date on the box itself (no PC): the same free sources the setup skill uses, built the
 * same way, used as built (nothing hand-picked or promoted).
 *  - TV: iptv-org's playlist for each country already in the list (https://iptv-org.github.io/iptv/countries/xx.m3u),
 *    dropping [Geo-blocked] and [Not 24/7] channels, plus the Weather category.
 *  - iptv-org's channel database (api/channels.json, stream-read): closed channels and adult channels are left out.
 *  - Radio: Radio Browser's most-played working stations per country (the same 60 per country as the skill).
 * Playlists are fetched with their ETags, so an unchanged list costs almost nothing. The old list is kept for a day
 * as channels.m3u.prev, so an update can be undone.
 */
object ChannelUpdate {
    private const val IPTV = "https://iptv-org.github.io"
    private const val RADIO = "https://de1.api.radio-browser.info/json/stations/search"
    private const val RADIO_PER_COUNTRY = 60
    /** country names as the lists use them, and their codes */
    val COUNTRIES = linkedMapOf("CA" to "Canada", "US" to "United States", "GB" to "United Kingdom", "MT" to "Malta", "IT" to "Italy",
        "AU" to "Australia", "IE" to "Ireland", "NZ" to "New Zealand", "FR" to "France", "DE" to "Germany", "ES" to "Spain", "MX" to "Mexico",
        "IN" to "India", "PH" to "Philippines", "BR" to "Brazil", "PT" to "Portugal", "NL" to "Netherlands", "JP" to "Japan")

    /** iptv-org's playlist for a country code (it names the United Kingdom "uk", not "gb") */
    private fun listUrl(cc: String) = if (cc == "weather") "$IPTV/iptv/categories/weather.m3u" else "$IPTV/iptv/countries/${if (cc == "GB") "uk" else cc.lowercase()}.m3u"

    class Result(val changed: Boolean, val added: Int, val removed: Int, val total: Int, val note: String)

    val prevFile: File get() = File(ChannelRepo.dir, "channels.m3u.prev")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("channels", Context.MODE_PRIVATE)
    fun lastUpdated(ctx: Context): Long = prefs(ctx).getLong("updated", 0L)
    fun lastChecked(ctx: Context): Long = prefs(ctx).getLong("checked", 0L)
    fun canUndo(): Boolean = prevFile.isFile && System.currentTimeMillis() - prevFile.lastModified() < 24 * 3600_000L

    /** The countries in the list now (TV groups), as codes; Canada, US and UK when there's no list yet. */
    fun countries(): List<String> {
        val names = ChannelRepo.tv.map { it.country }.toSet()
        return COUNTRIES.filter { it.value in names }.keys.toList().ifEmpty { listOf("CA", "US", "GB") }
    }

    private fun get(url: String, etag: String?): Pair<Int, Pair<String?, String>?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "AntonOS")
        if (etag != null) c.setRequestProperty("If-None-Match", etag)
        return try {
            val code = c.responseCode
            if (code == 304) 304 to null
            else if (code != 200) code to null
            else 200 to (c.getHeaderField("ETag") to c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { c.disconnect() }
    }

    private fun attr(s: String, k: String): String? { val i = s.indexOf("$k=\""); if (i < 0) return null; val j = s.indexOf('"', i + k.length + 2); return if (j < 0) null else s.substring(i + k.length + 2, j) }

    /** (EXTINF line, url) pairs from an iptv-org playlist that pass the skill's rules. */
    private fun entries(m3u: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var info: String? = null
        for (raw in m3u.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) info = l
            else if (l.isNotEmpty() && !l.startsWith("#") && info != null) {
                if (!info.contains("[Geo-blocked]") && !info.contains("[Not 24/7]")) out.add(info to l)
                info = null
            }
        }
        return out
    }

    /** tvg-id ("Name.ca@SD") -> channel id ("Name.ca"). */
    private fun cid(tvg: String?) = tvg?.substringBefore('@')?.takeIf { it.isNotEmpty() }

    /** Reads channels.json as a stream and returns the ids to leave out (closed or adult). */
    private fun excluded(ids: Set<String>): Set<String> {
        val out = HashSet<String>()
        val c = URL("$IPTV/api/channels.json").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000; c.setRequestProperty("User-Agent", "AntonOS")
        try {
            JsonReader(InputStreamReader(c.inputStream.buffered(64 * 1024), Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id: String? = null; var closed = false; var nsfw = false
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "id" -> id = r.nextString()
                        "is_nsfw" -> nsfw = r.nextBoolean()
                        "closed" -> if (r.peek() == JsonToken.NULL) r.nextNull() else { r.nextString(); closed = true }
                        else -> r.skipValue()
                    }
                    r.endObject()
                    if (id != null && id in ids && (closed || nsfw)) out.add(id)
                }
                r.endArray()
            }
        } finally { c.disconnect() }
        return out
    }

    /** Builds the new list and swaps it in if it changed. [force] skips the "nothing changed upstream" shortcut. */
    fun run(ctx: Context, force: Boolean, progress: (String) -> Unit): Result {
        val p = prefs(ctx)
        val codes = countries()
        progress("Checking the channel lists…")
        // 1. playlists, with their ETags
        var anyNew = force || !ChannelRepo.file.exists()
        val bodies = LinkedHashMap<String, String>()
        val failed = HashSet<String>()   // couldn't be fetched: these keep their current channels
        val tags = HashMap<String, String?>()
        for (cc in codes + "weather") {
            val url = listUrl(cc)
            val key = "etag:$cc"
            val (code, got) = get(url, if (force) null else p.getString(key, null))
            when {
                code == 304 -> {}
                got != null -> { anyNew = true; bodies[cc] = got.second; tags[key] = got.first }
                else -> failed.add(cc)
            }
        }
        // radio changes slowly: once a week
        val radioDue = force || System.currentTimeMillis() - p.getLong("radio", 0L) > 7 * 24 * 3600_000L
        if (!anyNew && !radioDue) { p.edit().putLong("checked", System.currentTimeMillis()).apply(); return Result(false, 0, 0, ChannelRepo.tv.size + ChannelRepo.radio.size, "Already up to date") }
        // anything not downloaded this time (304): fetch it plainly so the full list can be rebuilt
        for (cc in codes + "weather") if (cc !in bodies && cc !in failed) {
            val (_, got) = get(listUrl(cc), null)
            if (got == null) failed.add(cc) else { bodies[cc] = got.second; tags["etag:$cc"] = got.first }
        }
        if (bodies.isEmpty()) return Result(false, 0, 0, 0, "Couldn't reach the channel lists")
        // 2. TV entries, then the database's closed / adult flags
        val tv = ArrayList<Triple<String, String, String>>()   // (country or "Weather", EXTINF, url)
        for ((cc, body) in bodies) for ((info, url) in entries(body)) tv.add(Triple(if (cc == "weather") "Weather" else COUNTRIES[cc] ?: cc, info, url))
        // a country whose list couldn't be fetched keeps the channels it has now
        for (cc in failed) { val g = if (cc == "weather") "Weather" else COUNTRIES[cc] ?: cc
            for (c in ChannelRepo.tv.filter { it.group == g }) tv.add(Triple(g, "#EXTINF:-1 tvg-id=\"${c.tvgId}\" tvg-logo=\"${c.logo ?: ""}\" group-title=\"${c.category}\",${c.name}", c.url)) }
        progress("Checking for closed channels…")
        val drop = try { excluded(tv.mapNotNull { cid(attr(it.second, "tvg-id")) }.toSet()) } catch (e: Exception) { emptySet() }
        // 3. radio, kept from the current list unless it's due
        progress("Radio stations…")
        val radioLines = ArrayList<String>()
        if (radioDue) {
            for (cc in codes) try {
                val url = "$RADIO?countrycode=$cc&hidebroken=true&order=clickcount&reverse=true&limit=${RADIO_PER_COUNTRY * 2}"
                val (_, got) = get(url, null); val json = got?.second ?: continue
                val a = org.json.JSONArray(json); val seen = HashSet<String>(); var n = 0
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    val u = s.optString("url_resolved").ifEmpty { s.optString("url") }
                    val name = s.optString("name").trim().replace(",", " ")
                    if (u.isEmpty() || name.isEmpty() || !seen.add(name.lowercase())) continue
                    if (s.optString("codec").uppercase() !in setOf("MP3", "AAC", "AAC+", "OGG", "")) continue
                    radioLines.add("#EXTINF:-1 radio=\"true\" tvg-logo=\"${s.optString("favicon").replace("\"", "")}\" group-title=\"Radio - ${COUNTRIES[cc] ?: cc}\",$name"); radioLines.add(u)
                    if (++n >= RADIO_PER_COUNTRY) break
                }
            } catch (_: Exception) {}
        }
        if (radioLines.isEmpty()) for (c in ChannelRepo.radio) { radioLines.add("#EXTINF:-1 radio=\"true\" tvg-logo=\"${c.logo ?: ""}\" group-title=\"${c.group}\",${c.name}"); radioLines.add(c.url) }
        // 4. the new list: every channel keeps its tvg-id, its category, and its country as the group
        val sb = StringBuilder("#EXTM3U\n")
        for ((group, info, url) in tv) {
            val id = attr(info, "tvg-id"); if (cid(id) in drop) continue
            val name = info.substringAfterLast(',').trim()
            val cat = attr(info, "group-title") ?: ""
            if (cat.contains("XXX", true)) continue
            sb.append("#EXTINF:-1 tvg-id=\"${id ?: ""}\" tvg-logo=\"${attr(info, "tvg-logo") ?: ""}\" category=\"$cat\" group-title=\"$group\",$name\n").append(url).append('\n')
        }
        for (l in radioLines) sb.append(l).append('\n')
        val text = sb.toString()
        val old = if (ChannelRepo.file.exists()) ChannelRepo.file.readText() else ""
        val oldUrls = ChannelRepo.parse(old.lines()).map { it.url }.toSet()
        val newList = ChannelRepo.parse(text.lines())
        val newUrls = newList.map { it.url }.toSet()
        val e = p.edit().putLong("checked", System.currentTimeMillis())
        for ((k, v) in tags) if (v != null) e.putString(k, v)
        if (radioDue) e.putLong("radio", System.currentTimeMillis())
        if (text == old || newList.size < 20) { e.apply(); return Result(false, 0, 0, newList.size, if (newList.size < 20) "The new list looked broken, so the old one was kept" else "Already up to date") }
        // 5. swap it in, keeping the old one for a day
        if (ChannelRepo.file.exists()) ChannelRepo.file.copyTo(prevFile, overwrite = true)
        val tmp = File(ChannelRepo.dir, "channels.m3u.new"); tmp.writeText(text); tmp.renameTo(ChannelRepo.file) || run { ChannelRepo.file.writeText(text); true }
        e.putLong("updated", System.currentTimeMillis()).apply()
        return Result(true, (newUrls - oldUrls).size, (oldUrls - newUrls).size, newList.size, "")
    }

    /** Back to the list before the last update. */
    fun undo(): Boolean = try { if (!canUndo()) false else { prevFile.copyTo(ChannelRepo.file, overwrite = true); prevFile.delete(); true } } catch (e: Exception) { false }
}
