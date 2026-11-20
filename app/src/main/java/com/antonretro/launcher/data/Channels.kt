package com.antonretro.launcher.data

import android.os.Environment
import java.io.File

/** A TV channel or radio station from the channel list. */
class Channel(val name: String, val url: String, val logo: String?, val group: String, val radio: Boolean, val number: Int,
              /** iptv-org's id ("Name.ca@SD"): follows a channel when its stream address changes */ val tvgId: String = "",
              /** iptv-org's category ("News", "Kids"...) */ val category: String = "",
              /** picture height when the list says (1080, 720, 576, 480...), else 0 */ val height: Int = 0) {
    /** Feeds of the same channel share this: iptv-org's channel id ("CBCNews.ca" from "CBCNews.ca@HD"), or the name
     *  without HD/SD and the country when there's no id. */
    val feedKey: String get() = tvgId.substringBefore('@').ifEmpty { name.lowercase().replace(Regex("""\s+(hd|sd|fhd|uhd|4k)$"""), "").trim() + "|" + group }
    val hd get() = height >= 720
    val key get() = "ch:$url"
    /** "United States" for both "United States" and "Radio - United States". */
    val country get() = group.removePrefix("Radio - ")
}

/**
 * Reads the .m3u the skill builds (iptv-org TV by country, Weather, Radio) straight from
 * /sdcard/RetroLauncher/channels.m3u. On first run it copies Kodi's existing list there.
 * The list is used exactly as built: nothing is added, tested or promoted here.
 */
object ChannelRepo {
    val dir: File get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher")
    val file: File get() = File(dir, "channels.m3u")
    private val kodiList get() = File(Environment.getExternalStorageDirectory(), "Android/data/org.xbmc.kodi/files/.kodi/userdata/iptv")

    /** One entry per channel (the chosen feed of each); [allTv] / [allRadio] hold every feed in the list. */
    @Volatile var tv: List<Channel> = emptyList(); private set
    @Volatile var radio: List<Channel> = emptyList(); private set
    @Volatile var allTv: List<Channel> = emptyList(); private set
    @Volatile var allRadio: List<Channel> = emptyList(); private set
    fun feedsOf(c: Channel): List<Channel> = (if (c.radio) allRadio else allTv).filter { it.feedKey == c.feedKey }

    /** Chooses one feed per channel: the one picked by hand, else the best picture that isn't known to be broken
     *  (all broken: the best anyway, so "Working only" can hide the channel). Order stays the list's order. */
    fun choose(bad: (String) -> Boolean, picked: (String) -> String?) {
        fun pick(all: List<Channel>): List<Channel> {
            val groups = LinkedHashMap<String, MutableList<Channel>>()
            for (c in all) groups.getOrPut(c.feedKey) { ArrayList() }.add(c)
            return groups.values.map { feeds ->
                feeds.firstOrNull { it.url == picked(it.feedKey) && !bad(it.url) }
                    ?: feeds.filter { !bad(it.url) }.maxByOrNull { it.height }
                    ?: feeds.maxBy { it.height }
            }
        }
        tv = pick(allTv); radio = pick(allRadio)
    }
    @Volatile var loadedFrom: String = ""; private set

    /** Returns false when the list can't be read (no permission or no file). Runs off the UI thread. */
    fun load(): Boolean {
        try {
            if (!file.exists()) {
                val kodi = kodiList.listFiles { f -> f.name.endsWith(".m3u") }?.maxByOrNull { it.length() }
                if (kodi != null) { dir.mkdirs(); kodi.copyTo(file, overwrite = false) }
            }
            if (!file.exists()) return false
            val all = parse(file.readLines())
            allTv = all.filter { !it.radio }; allRadio = all.filter { it.radio }
            choose({ false }, { null })
            loadedFrom = file.path
            return true
        } catch (e: Exception) { return false }
    }

    fun parse(lines: List<String>): List<Channel> {
        val out = ArrayList<Channel>(lines.size / 2)
        var info: String? = null; var n = 0
        val seen = HashSet<String>()   // the same stream listed twice would get the same number twice: keep the first
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) info = line
            else if (line.isNotEmpty() && !line.startsWith("#") && info != null && !seen.add(line)) info = null
            else if (line.isNotEmpty() && !line.startsWith("#") && info != null) {
                val rawName = info.substringAfterLast(',').trim()
                val tvg = attr(info, "tvg-id") ?: ""
                val height = Regex("""\((\d{3,4})[pi]\)""").find(rawName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: when (tvg.substringAfter('@', "").uppercase()) { "UHD", "4K" -> 2160; "FHD" -> 1080; "HD" -> 720; "SD" -> 480; else -> 0 }
                val name = rawName
                    .replace(Regex("""\s*\((\d{3,4}p|SD|HD)\)"""), "")      // "(1080p)" is noise for a viewer
                    .replace(Regex("""\s*\[[^]]*]"""), "").trim()
                val group = attr(info, "group-title") ?: "Other"
                val radio = attr(info, "radio") == "true" || group.startsWith("Radio")
                out.add(Channel(name.ifEmpty { "Channel" }, line, attr(info, "tvg-logo")?.takeIf { it.startsWith("http") }, group, radio, ++n, tvg, attr(info, "category") ?: "", height))
                info = null
            }
        }
        return out
    }

    private fun attr(s: String, k: String): String? {
        val i = s.indexOf("$k=\""); if (i < 0) return null
        val j = s.indexOf('"', i + k.length + 2); return if (j < 0) null else s.substring(i + k.length + 2, j)
    }

    /** Countries in a friendly order: the user's own first, then by size. */
    fun groups(list: List<Channel>, first: List<String> = listOf("Canada", "United States", "United Kingdom", "Malta", "Italy", "Australia")): List<Pair<String, List<Channel>>> {
        val by = list.groupBy { it.country }
        val order = first.filter { by.containsKey(it) } + by.keys.filter { it !in first && it != "Weather" }.sortedByDescending { by[it]!!.size }
        return (order + listOfNotNull("Weather".takeIf { by.containsKey(it) })).map { it to by[it]!! }
    }
}
