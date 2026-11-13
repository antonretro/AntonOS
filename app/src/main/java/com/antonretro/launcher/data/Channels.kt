package com.antonretro.launcher.data

import android.os.Environment
import java.io.File

/** A TV channel or radio station from the channel list. */
class Channel(val name: String, val url: String, val logo: String?, val group: String, val radio: Boolean, val number: Int) {
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

    @Volatile var tv: List<Channel> = emptyList(); private set
    @Volatile var radio: List<Channel> = emptyList(); private set
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
            tv = all.filter { !it.radio }; radio = all.filter { it.radio }
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
                val name = info.substringAfterLast(',').trim()
                    .replace(Regex("""\s*\((\d{3,4}p|SD|HD)\)"""), "")      // "(1080p)" is noise for a viewer
                    .replace(Regex("""\s*\[[^]]*]"""), "").trim()
                val group = attr(info, "group-title") ?: "Other"
                val radio = attr(info, "radio") == "true" || group.startsWith("Radio")
                out.add(Channel(name.ifEmpty { "Channel" }, line, attr(info, "tvg-logo")?.takeIf { it.startsWith("http") }, group, radio, ++n))
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
