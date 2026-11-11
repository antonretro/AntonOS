package com.antonretro.launcher.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** One programme in the guide. Times are epoch milliseconds. */
class Prog(val start: Long, val stop: Long, val title: String, val sub: String?, val desc: String?) {
    fun onAt(t: Long) = t in start until stop
}

/** A programme the viewer asked to be reminded about. */
class Reminder(val url: String, val channel: String, val start: Long, val title: String)

/**
 * The TV Guide's data. Free XMLTV feeds (one per service and country, listed in
 * /sdcard/RetroLauncher/guide-sources.txt) are downloaded gzipped and read as a stream, so a whole
 * file is never in memory (the box has 1 GB). Only channels in the playlist and only the next 48 hours
 * are kept, in a small SQLite file. Channels are matched by the Pluto TV id in their stream address,
 * otherwise by name within the same country.
 */
object Guide {
    private const val KEEP_BEFORE = 3 * 3600_000L
    private const val KEEP_AFTER = 48 * 3600_000L
    private const val DESC_MAX = 300

    /** Guide files from i.mjh.nz (free, updated several times a day). Country names match the playlist groups. */
    private val DEFAULT_SOURCES = """
        # One source per line: the playlist country it is for, then the address of an XMLTV file (.xml or .xml.gz).
        # "*" means any country. Earlier lines win when two sources have the same channel.
        Canada          https://i.mjh.nz/PlutoTV/ca.xml.gz
        Canada          https://i.mjh.nz/SamsungTVPlus/ca.xml.gz
        Canada          https://i.mjh.nz/Plex/ca.xml.gz
        United States   https://i.mjh.nz/PlutoTV/us.xml.gz
        United States   https://i.mjh.nz/SamsungTVPlus/us.xml.gz
        United States   https://i.mjh.nz/Plex/us.xml.gz
        United States   https://i.mjh.nz/Roku/all.xml.gz
        United Kingdom  https://i.mjh.nz/PlutoTV/gb.xml.gz
        United Kingdom  https://i.mjh.nz/SamsungTVPlus/gb.xml.gz
        United Kingdom  https://i.mjh.nz/Plex/gb.xml.gz
        Italy           https://i.mjh.nz/PlutoTV/it.xml.gz
        Italy           https://i.mjh.nz/SamsungTVPlus/it.xml.gz
        Australia       https://i.mjh.nz/Plex/au.xml.gz
    """.trimIndent() + "\n"

    private var db: SQLiteDatabase? = null
    private lateinit var filesDir: File
    /** channel url -> its id in the guide file; only channels that have programmes. */
    @Volatile private var ids: Map<String, Int> = emptyMap()
    @Volatile var refreshing = false; private set
    @Volatile var progress = ""; private set
    @Volatile var reminders: List<Reminder> = emptyList(); private set

    val sourcesFile: File get() = File(ChannelRepo.dir, "guide-sources.txt")

    fun open(ctx: Context) {
        if (db != null) return
        filesDir = ctx.filesDir
        val d = SQLiteDatabase.openOrCreateDatabase(File(ctx.filesDir, "guide.db"), null)
        d.execSQL("CREATE TABLE IF NOT EXISTS chans (id INTEGER PRIMARY KEY, url TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS prog (ch INTEGER, start INTEGER, stop INTEGER, title TEXT, sub TEXT, descr TEXT)")
        d.execSQL("CREATE INDEX IF NOT EXISTS prog_ch ON prog (ch, start)")
        d.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
        db = d
        loadIds()
        loadReminders()
    }

    private fun loadIds() {
        val m = HashMap<String, Int>()
        db?.rawQuery("SELECT id, url FROM chans", null)?.use { c -> while (c.moveToNext()) m[c.getString(1)] = c.getInt(0) }
        ids = m
    }

    fun meta(k: String): String? = db?.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k))?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    private fun setMeta(k: String, v: String) { db?.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)", arrayOf(k, v)) }

    fun lastUpdated(): Long = meta("updated")?.toLongOrNull() ?: 0L
    fun hasData(url: String) = ids.containsKey(url)
    val channelCount get() = ids.size
    fun totalChannels(): Int = meta("total")?.toIntOrNull() ?: 0

    fun sources(): List<Pair<String, String>> {
        val f = sourcesFile
        val text = try { if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(DEFAULT_SOURCES) }; f.readText() } catch (e: Exception) { DEFAULT_SOURCES }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { line ->
            val i = line.indexOf("http"); if (i <= 0) null else line.substring(0, i).trim() to line.substring(i).trim()
        }
    }

    // ------------------------------------------------------------------ reading
    /** Programmes for these channels between from and to, by channel url. Fast enough for the UI thread for a few rows. */
    fun window(urls: Collection<String>, from: Long, to: Long): Map<String, List<Prog>> {
        val d = db ?: return emptyMap()
        val byId = HashMap<Int, String>()
        for (u in urls) ids[u]?.let { byId[it] = u }
        if (byId.isEmpty()) return emptyMap()
        val out = HashMap<String, ArrayList<Prog>>()
        d.rawQuery("SELECT ch, start, stop, title, sub, descr FROM prog WHERE ch IN (${byId.keys.joinToString(",")}) AND stop > ? AND start < ? ORDER BY ch, start",
            arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) {
                val u = byId[c.getInt(0)] ?: continue
                out.getOrPut(u) { ArrayList() }.add(Prog(c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5)))
            }
        }
        return out
    }

    /** What's on now and what's next on one channel. */
    fun nowNext(url: String, now: Long = System.currentTimeMillis()): Pair<Prog?, Prog?> {
        val l = window(listOf(url), now, now + 6 * 3600_000L)[url] ?: return null to null
        val cur = l.firstOrNull { it.onAt(now) }
        return cur to l.firstOrNull { it.start >= (cur?.stop ?: now) }
    }

    // ------------------------------------------------------------------ reminders
    private val remFile get() = File(filesDir, "reminders.txt")
    private fun loadReminders() {
        reminders = try { remFile.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 4 }?.let { Reminder(it[0], it[1], it[2].toLong(), it[3]) } } } catch (e: Exception) { emptyList() }
    }
    private fun saveReminders() { try { remFile.writeText(reminders.joinToString("") { "${it.url}\t${it.channel}\t${it.start}\t${it.title.replace('\t', ' ')}\n" }) } catch (_: Exception) {} }
    fun hasReminder(url: String, start: Long) = reminders.any { it.url == url && it.start == start }
    fun addReminder(r: Reminder) { reminders = reminders.filter { !(it.url == r.url && it.start == r.start) } + r; saveReminders() }
    fun removeReminder(r: Reminder) { reminders = reminders.filter { it !== r }; saveReminders() }
    /** Reminders due within the next minute; they are removed. Ones more than 5 minutes late are dropped quietly. */
    fun dueReminders(now: Long = System.currentTimeMillis()): List<Reminder> {
        val due = reminders.filter { it.start - 60_000 <= now }
        if (due.isEmpty()) return due
        reminders = reminders - due.toSet(); saveReminders()
        return due.filter { now < it.start + 5 * 60_000 }
    }

    // ------------------------------------------------------------------ refreshing
    private val PLUTO = Regex("""plu-([0-9a-f]{24})""")
    private val NOISE = Regex("""\b(hd|sd|tv|channel|the|us|uk|ca|au|it|en)\b""")
    private val NON_ALNUM = Regex("[^a-z0-9]")
    private val BRACKETS = Regex("""\(.*?\)|\[.*?]""")
    private fun norm(s: String) = NON_ALNUM.replace(NOISE.replace(BRACKETS.replace(s, "").lowercase(), ""), "")

    /**
     * Downloads every source and replaces the guide in one step at the end, so the old guide stays usable
     * until the new one is complete. Runs on its own low-priority thread; onDone runs on that thread.
     */
    fun refresh(tv: List<Channel>, onDone: (String) -> Unit) {
        val d = db ?: return
        if (refreshing) return
        refreshing = true; progress = "Starting…"
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            var result: String
            try { result = doRefresh(d, tv) }
            catch (t: Throwable) { result = "Guide update failed: ${t.javaClass.simpleName}" }
            refreshing = false; progress = ""
            onDone(result)
        }.apply { name = "guide"; isDaemon = true }.start()
    }

    private fun doRefresh(d: SQLiteDatabase, tv: List<Channel>): String {
        val now = System.currentTimeMillis()
        val from = now - KEEP_BEFORE; val to = now + KEEP_AFTER
        d.execSQL("DROP TABLE IF EXISTS prog_new"); d.execSQL("DROP TABLE IF EXISTS chans_new")
        d.execSQL("CREATE TABLE prog_new (ch INTEGER, start INTEGER, stop INTEGER, title TEXT, sub TEXT, descr TEXT)")
        d.execSQL("CREATE TABLE chans_new (id INTEGER PRIMARY KEY, url TEXT)")
        val chanIndex = HashMap<String, Int>(); tv.forEachIndexed { i, c -> chanIndex.putIfAbsent(c.url, i) }
        val done = HashSet<Int>()        // our channels that already have programmes from an earlier source
        val srcs = sources()
        var failed = 0
        for ((n, src) in srcs.withIndex()) {
            progress = "Source ${n + 1} of ${srcs.size}"
            val (country, url) = src
            val cands = tv.filter { country == "*" || it.country == country || it.country == "Weather" || PLUTO.containsMatchIn(it.url) }
                .filter { chanIndex[it.url]!! !in done }
            if (cands.isEmpty()) continue
            try {
                val got = readSource(d, url, cands, chanIndex, from, to)
                done.addAll(got)
            } catch (e: Exception) { failed++ }
        }
        d.beginTransaction()
        try {
            val ins = d.compileStatement("INSERT INTO chans_new (id, url) VALUES (?, ?)")
            for (c in tv) { val i = chanIndex[c.url]!!; if (i in done) { ins.bindLong(1, i.toLong()); ins.bindString(2, c.url); ins.executeInsert(); done.remove(i) } }
            d.execSQL("DROP TABLE IF EXISTS prog"); d.execSQL("DROP TABLE IF EXISTS chans")
            d.execSQL("ALTER TABLE prog_new RENAME TO prog"); d.execSQL("ALTER TABLE chans_new RENAME TO chans")
            d.execSQL("CREATE INDEX IF NOT EXISTS prog_ch ON prog (ch, start)")
            setMeta("updated", now.toString()); setMeta("total", tv.size.toString())
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
        loadIds()
        return "${ids.size} of ${tv.size} channels have guide information" + if (failed > 0) " ($failed source${if (failed > 1) "s" else ""} didn't load)" else ""
    }

    /** Reads one XMLTV file as a stream. Returns our channel indexes that got at least one programme. */
    private fun readSource(d: SQLiteDatabase, url: String, cands: List<Channel>, chanIndex: Map<String, Int>, from: Long, to: Long): Set<Int> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "RetroLauncher/1 (Android TV)")
        val got = HashSet<Int>()
        conn.inputStream.use { raw ->
            val input: InputStream = BufferedInputStream(raw, 64 * 1024).let { if (url.endsWith(".gz")) GZIPInputStream(it, 64 * 1024) else it }
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            val feedIds = HashSet<String>(); val feedNames = HashMap<String, String>()
            var targets: Map<String, IntArray>? = null   // feed channel id -> our channel indexes
            val ins = d.compileStatement("INSERT INTO prog_new (ch, start, stop, title, sub, descr) VALUES (?, ?, ?, ?, ?, ?)")
            d.beginTransaction()
            try {
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) when (p.name) {
                        "channel" -> {
                            val id = p.getAttributeValue(null, "id")
                            if (id != null && targets == null) {
                                feedIds.add(id)
                                ev = p.next()
                                while (!(ev == XmlPullParser.END_TAG && p.name == "channel") && ev != XmlPullParser.END_DOCUMENT) {
                                    if (ev == XmlPullParser.START_TAG && p.name == "display-name") { val n = norm(p.nextText()); if (n.length > 2) feedNames.putIfAbsent(n, id) }
                                    ev = p.next()
                                }
                            } else skip(p)
                        }
                        "programme" -> {
                            val t = targets ?: match(cands, chanIndex, feedIds, feedNames).also { targets = it; feedNames.clear(); feedIds.clear() }
                            val ours = t[p.getAttributeValue(null, "channel")]
                            val start = time(p.getAttributeValue(null, "start")); val stop = time(p.getAttributeValue(null, "stop"))
                            if (ours == null || stop <= from || start >= to || stop <= start) skip(p)
                            else {
                                var title: String? = null; var sub: String? = null; var desc: String? = null
                                ev = p.next()
                                while (!(ev == XmlPullParser.END_TAG && p.name == "programme") && ev != XmlPullParser.END_DOCUMENT) {
                                    if (ev == XmlPullParser.START_TAG) when (p.name) {
                                        "title" -> { val s = p.nextText(); if (title == null) title = s }
                                        "sub-title" -> { val s = p.nextText(); if (sub == null) sub = s }
                                        "desc" -> { val s = p.nextText(); if (desc == null) desc = if (s.length > DESC_MAX) s.substring(0, DESC_MAX).substringBeforeLast(' ') + "…" else s }
                                        else -> skip(p)
                                    }
                                    ev = p.next()
                                }
                                if (!title.isNullOrBlank()) for (o in ours) {
                                    ins.clearBindings()
                                    ins.bindLong(1, o.toLong()); ins.bindLong(2, start); ins.bindLong(3, stop); ins.bindString(4, title.trim())
                                    if (!sub.isNullOrBlank()) ins.bindString(5, sub.trim())
                                    if (!desc.isNullOrBlank()) ins.bindString(6, desc.trim())
                                    ins.executeInsert(); got.add(o)
                                }
                            }
                        }
                    }
                    ev = p.next()
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
        conn.disconnect()
        return got
    }

    private fun match(cands: List<Channel>, chanIndex: Map<String, Int>, feedIds: Set<String>, feedNames: Map<String, String>): Map<String, IntArray> {
        val m = HashMap<String, ArrayList<Int>>()
        for (c in cands) {
            val pid = PLUTO.find(c.url)?.groupValues?.get(1)
            val feed = if (pid != null && pid in feedIds) pid else feedNames[norm(c.name)]
            if (feed != null) m.getOrPut(feed) { ArrayList() }.add(chanIndex[c.url]!!)
        }
        return m.mapValues { it.value.toIntArray() }
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) when (p.next()) { XmlPullParser.START_TAG -> depth++; XmlPullParser.END_TAG -> depth--; XmlPullParser.END_DOCUMENT -> return }
    }

    /** "20261111003600 +0000" to epoch ms, without allocating a date parser per programme. */
    fun time(s: String?): Long {
        if (s == null || s.length < 14) return 0
        fun n(a: Int, len: Int): Int { var v = 0; for (i in a until a + len) v = v * 10 + (s[i] - '0'); return v }
        var y = n(0, 4); val mo = n(4, 2); val dd = n(6, 2)
        if (mo <= 2) y--
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + dd - 1
        val days = era * 146097L + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719468
        var t = ((days * 24 + n(8, 2)) * 60 + n(10, 2)) * 60 + n(12, 2)
        val sp = s.indexOfAny(charArrayOf('+', '-'), 14)
        if (sp > 0 && s.length >= sp + 5) { val off = n(sp + 1, 2) * 3600 + n(sp + 3, 2) * 60; t -= if (s[sp] == '+') off else -off }
        return t * 1000
    }
}
