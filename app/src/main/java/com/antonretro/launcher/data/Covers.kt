package com.antonretro.launcher.data

import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Box art from the libretro-thumbnails catalogue (free; one folder of covers per console, named like the
 * No-Intro game lists). A game whose file isn't named exactly like the catalogue ("Super_Mario_Kart.smc") is
 * matched once by its title ("Super Mario Kart (USA)"), when you say yes to "Get box art"; the matches are kept in
 * RetroLauncher/covers.txt (one line per game: system/file, a tab, the catalogue name), so it's never looked up again.
 * Covers come from the catalogue's GitHub copy (thumbnails.libretro.com stopped serving them in November 2026).
 */
object Covers {
    private val file get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/covers.txt")
    @Volatile private var map: Map<String, String>? = null
    @Volatile var working = false; private set
    @Volatile var done = 0; private set
    @Volatile var total = 0; private set
    private const val BAD = "&*/:`<>?\\|\""

    private fun id(r: Rom) = r.system.id + "/" + r.file.name
    private fun names(): Map<String, String> = map ?: try {
        file.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    } catch (e: Exception) { emptyMap() }.also { map = it }

    /** the catalogue name for this game: the match found earlier, else the file's own name */
    fun nameFor(r: Rom): String = names()[id(r)] ?: r.file.nameWithoutExtension
    fun matched(r: Rom) = names().containsKey(id(r))

    fun url(r: Rom): String {
        val n = nameFor(r).map { if (it in BAD) '_' else it }.joinToString("")
        return "https://raw.githubusercontent.com/libretro-thumbnails/" + r.system.thumbs.replace(' ', '_') +
            "/master/Named_Boxarts/" + android.net.Uri.encode(n) + ".png"
    }

    /** "Super_Mario_Kart (U) [!]" -> "supermariokart" */
    private fun key(s: String) = s.replace(Regex("""\s*[\[(][^\])]*[\])]"""), "").replace('_', ' ').lowercase()
        .replace(Regex("""^(.+?), (the|a|an)\b"""), "$2 $1").replace(Regex("[^a-z0-9]"), "")
    private fun rank(n: String) = when { "(USA" in n -> 0; "(World" in n -> 1; "(Europe" in n -> 2; else -> 3 } * 10 +
        (if ("(Beta" in n || "(Proto" in n || "(Demo" in n) 5 else 0) + (if ("(Rev" in n) 1 else 0)

    private fun get(url: String, each: (String) -> Unit): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "AntonOS"); c.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (c.responseCode != 200) false else { c.inputStream.bufferedReader().useLines { it.forEach(each) }; true }
        } finally { c.disconnect() }
    }

    /** Every cover name in one console's folder of the catalogue: two small requests to GitHub per console (the
     *  folder list, then just the box-art folder; the whole repository's list is up to 20 MB). */
    private fun catalogue(sys: GameSystem): List<String> {
        val repo = "https://api.github.com/repos/libretro-thumbnails/" + sys.thumbs.replace(' ', '_') + "/git/trees/"
        var sha: String? = null
        val dirRe = Regex(""""path":\s*"Named_Boxarts",\s*"mode":\s*"040000",\s*"type":\s*"tree",\s*"sha":\s*"([0-9a-f]+)"""")
        val sb = StringBuilder()
        if (!get(repo + "master") { sb.append(it) }) return emptyList()
        sha = dirRe.find(sb)?.groupValues?.get(1) ?: return emptyList()
        val out = ArrayList<String>()
        val re = Regex(""""path":\s*"([^"]+)\.png"""")
        get(repo + sha) { l -> re.findAll(l).forEach { out.add(it.groupValues[1].replace("\\u0026", "&")) } }
        return out
    }

    /** Finds covers for these games and returns how many it found. Runs on the caller's (background) thread. */
    fun find(roms: List<Rom>): Int {
        if (working) return 0
        working = true; done = 0; total = roms.size
        var found = 0
        try {
            val m = HashMap(names())
            for ((sys, list) in roms.groupBy { it.system }) {
                val all = try { catalogue(sys) } catch (e: Exception) { emptyList() }
                val exact = all.toHashSet()
                val byKey = all.groupBy { key(it) }
                for (r in list) {
                    val own = r.file.nameWithoutExtension
                    val pick = if (own in exact) own else byKey[key(own)]?.minByOrNull { rank(it) }
                    if (pick != null) { m[id(r)] = pick; found++ }
                    done++
                }
            }
            map = m
            try { file.writeText(m.entries.joinToString("") { "${it.key}\t${it.value}\n" }) } catch (_: Exception) {}
        } finally { working = false }
        return found
    }
}
