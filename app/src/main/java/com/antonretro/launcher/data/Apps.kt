package com.antonretro.launcher.data

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Anything that can sit on a card. */
sealed class Item(val key: String, val title: String) {
    class App(val component: ComponentName, title: String, val isGame: Boolean) : Item("app:" + component.flattenToShortString(), title)
    class Ch(val ch: Channel) : Item(ch.key, ch.name)
    class Yt(val v: Video) : Item("yt:" + v.id, v.title)
    class Vid(val v: LocalVideo) : Item(v.key, v.title)
    class Game(val rom: Rom) : Item(rom.key, rom.title)
    class AddGames : Item("games:add", "Add games")
    class YtSearch : Item("yt:search", "Search YouTube")
    class YtAdd : Item("yt:add", "Add a channel")
    /** "All 1,341 channels": opens the whole group. */
    class More(key: String, title: String, val all: List<Channel>) : Item(key, title)
    class Action(key: String, title: String, val subtitle: String, val icon: String, val run: (Context) -> Unit) : Item(key, title)
    class Track(val s: Song) : Item(s.key, s.title)
}

/** Loads every launchable app (TV and phone style together) and keeps the list fresh. */
class AppRepo(private val ctx: Context) {
    private val pm = ctx.packageManager
    @Volatile var apps: List<Item.App> = emptyList(); private set

    fun load(): List<Item.App> {
        val seen = HashMap<String, Item.App>()
        fun add(list: List<ResolveInfo>) {
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName) continue
                if (seen.containsKey(ai.packageName)) continue   // one card per app, TV entry wins
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName }
                val game = (ai.applicationInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                    (Build.VERSION.SDK_INT >= 26 && ai.applicationInfo.category == ApplicationInfo.CATEGORY_GAME) ||
                    ai.packageName in KNOWN_GAMES
                seen[ai.packageName] = Item.App(ComponentName(ai.packageName, ai.name), label, game)
            }
        }
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0))
        add(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
        apps = seen.values.sortedBy { it.title.lowercase() }
        return apps
    }

    /** Sharpest available picture for an app: its TV banner, else its icon at high density. */
    fun banner(app: Item.App): Drawable? = try {
        pm.getActivityInfo(app.component, 0).loadBanner(pm) ?: pm.getApplicationInfo(app.component.packageName, 0).loadBanner(pm)
    } catch (e: Exception) { null }

    fun icon(app: Item.App): Drawable? = try {
        val ai = pm.getActivityInfo(app.component, 0)
        val res = pm.getResourcesForApplication(ai.applicationInfo)
        val id = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
        (if (id != 0) res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXHIGH, null) else null) ?: ai.loadIcon(pm)
    } catch (e: Exception) { null }

    companion object {
        val KNOWN_GAMES = setOf("com.antonretro.subwaysurfersde", "com.antonretro.themepark", "com.antonretro.multiverse", "com.eamobile.themepark_na",
            "com.kiloo.subwaysurf", "air.au.com.metro.DumbWaysToDie", "air.com.hypah.io.slither", "com.rovio.angrybirdsstarwars.ads.iap")
        val YOUTUBE_APPS = setOf("org.smarttube.stable", "org.smarttube.beta", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")
        /** Shown first in the dock on a fresh box, if installed. */
        val DOCK_DEFAULTS = listOf("org.smarttube.stable", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "org.xbmc.kodi",
            "com.antonretro.subwaysurfersde", "com.antonretro.multiverse", "com.android.vending", "org.fdroid.fdroid", "com.droidlogic.FileBrower")
        /** Hidden from Apps by default (RetroOS replaces them). */
        val HIDDEN_DEFAULTS = setOf("com.android.settings", "com.droidlogic.mboxsettings", "com.spocky.projengmenu",
            "com.droidlogic.mboxlauncher", "com.android.launcher", "com.android.tv.settings",
            "com.retroarch.ra32", "com.retroarch", "com.retroarch.aarch64")   // RetroArch is the hidden game engine
    }
}

/** Order, dock, hidden apps and launch history in one small JSON file (copyable to another box). */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, "layout.json")
    var dock = mutableListOf<String>()
    var hidden = mutableSetOf<String>()
    var launches = mutableMapOf<String, Int>()
    var recent = mutableListOf<String>()
    var wallpaper = 0
    /** 2: wallpaper 0 is the rotating world photos (older saves are moved to it once) */
    var wallVersion = 0
    var worldIndex = 0
    var worldAt = 0L
    var favourites = mutableListOf<String>()      // channel urls, in the order starred
    var recentChannels = mutableListOf<String>()  // newest first
    var lastTv: String? = null
    var recentGames = mutableListOf<String>()
    var clock24 = false
    var hiddenTabs = mutableSetOf<String>()
    var startupSound = true
    var reduceMotion = false
    var squareCovers = false
    var saverMinutes = 5
    var hiddenGroups = mutableSetOf<String>()
    var numbers = mutableMapOf<String, Int>()   // channel url -> its channel number (kept, never shuffled)
    var hiddenChannels = mutableSetOf<String>()
    var playerSmooth = false
    var quality = 0   // 0 auto, 1 save data (720p), 2 best
    var saverPhotos = true
    var dailyWallpaper = false
    var wallpaperDay = 0
    var positions = mutableMapOf<String, Long>()   // video path -> where you stopped (ms); duration in durations
    var durations = mutableMapOf<String, Long>()
    var firstRun = true

    init { load() }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            dock = o.optJSONArray("dock").strings().toMutableList()
            hidden = o.optJSONArray("hidden").strings().toMutableSet()
            recent = o.optJSONArray("recent").strings().toMutableList()
            o.optJSONObject("launches")?.let { l -> l.keys().forEach { launches[it] = l.optInt(it) } }
            wallpaper = o.optInt("wallpaper", 0); wallVersion = o.optInt("wallVersion", 0); worldIndex = o.optInt("worldIndex", 0); worldAt = o.optLong("worldAt", 0L)
            favourites = o.optJSONArray("favourites").strings().toMutableList()
            recentChannels = o.optJSONArray("recentChannels").strings().toMutableList()
            lastTv = o.optString("lastTv").ifEmpty { null }
            recentGames = o.optJSONArray("recentGames").strings().toMutableList()
            hiddenTabs = o.optJSONArray("hiddenTabs").strings().toMutableSet()
            o.optJSONObject("numbers")?.let { l -> l.keys().forEach { numbers[it] = l.optInt(it) } }
            hiddenGroups = o.optJSONArray("hiddenGroups").strings().toMutableSet(); hiddenChannels = o.optJSONArray("hiddenChannels").strings().toMutableSet()
            playerSmooth = o.optBoolean("playerSmooth", false); quality = o.optInt("quality", 0)
            saverMinutes = o.optInt("saverMinutes", 5); saverPhotos = o.optBoolean("saverPhotos", true); dailyWallpaper = o.optBoolean("dailyWallpaper", false); wallpaperDay = o.optInt("wallpaperDay", 0)
            clock24 = o.optBoolean("clock24", false); startupSound = o.optBoolean("startupSound", true); reduceMotion = o.optBoolean("reduceMotion", false); squareCovers = o.optBoolean("squareCovers", false)
            o.optJSONObject("positions")?.let { l -> l.keys().forEach { positions[it] = l.optLong(it) } }
            o.optJSONObject("durations")?.let { l -> l.keys().forEach { durations[it] = l.optLong(it) } }
            firstRun = false
        } catch (e: Exception) { /* a broken file never stops the home screen */ }
    }

    fun save() {
        val o = JSONObject()
            .put("version", 1)
            .put("dock", JSONArray(dock)).put("hidden", JSONArray(hidden.toList())).put("recent", JSONArray(recent))
            .put("launches", JSONObject(launches as Map<*, *>)).put("wallpaper", wallpaper).put("wallVersion", wallVersion).put("worldIndex", worldIndex).put("worldAt", worldAt)
            .put("favourites", JSONArray(favourites)).put("recentChannels", JSONArray(recentChannels)).put("lastTv", lastTv ?: "").put("recentGames", JSONArray(recentGames))
            .put("hiddenTabs", JSONArray(hiddenTabs.toList())).put("clock24", clock24).put("startupSound", startupSound).put("reduceMotion", reduceMotion).put("squareCovers", squareCovers)
            .put("numbers", JSONObject(numbers as Map<*, *>)).put("hiddenGroups", JSONArray(hiddenGroups.toList())).put("hiddenChannels", JSONArray(hiddenChannels.toList()))
            .put("playerSmooth", playerSmooth).put("quality", quality)
            .put("saverMinutes", saverMinutes).put("saverPhotos", saverPhotos).put("dailyWallpaper", dailyWallpaper).put("wallpaperDay", wallpaperDay)
            .put("positions", JSONObject(positions as Map<*, *>)).put("durations", JSONObject(durations as Map<*, *>))
        Thread { try { val tmp = File(file.path + ".tmp"); tmp.writeText(o.toString(1)); tmp.renameTo(file) } catch (_: Exception) {} }.start()
    }

    fun noteLaunch(key: String) {
        launches[key] = (launches[key] ?: 0) + 1
        recent.remove(key); recent.add(0, key); while (recent.size > 10) recent.removeAt(recent.size - 1)
        save()
    }

    fun noteChannel(c: Channel) {
        recentChannels.remove(c.url); recentChannels.add(0, c.url); while (recentChannels.size > 12) recentChannels.removeAt(recentChannels.size - 1)
        if (!c.radio) lastTv = c.url
        save()
    }
    fun noteGame(key: String) { recentGames.remove(key); recentGames.add(0, key); while (recentGames.size > 10) recentGames.removeAt(recentGames.size - 1); save() }
    fun toggleFavourite(c: Channel): Boolean {
        val on = !favourites.remove(c.url); if (on) favourites.add(c.url); save(); return on
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
