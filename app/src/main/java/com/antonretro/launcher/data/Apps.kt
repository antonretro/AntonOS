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
    class Action(key: String, title: String, val subtitle: String, val icon: String, val run: (Context) -> Unit) : Item(key, title)
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
        val KNOWN_GAMES = setOf("com.kiloo.subwaysurf", "com.antonretro.themepark", "com.eamobile.themepark_na")
        /** Shown first in the dock on a fresh box, if installed. */
        val DOCK_DEFAULTS = listOf("com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "org.xbmc.kodi",
            "com.kiloo.subwaysurf", "com.android.vending", "org.fdroid.fdroid", "com.droidlogic.FileBrower")
        /** Hidden from Apps by default (Retro Launcher replaces them). */
        val HIDDEN_DEFAULTS = setOf("com.android.settings", "com.droidlogic.mboxsettings", "com.spocky.projengmenu",
            "com.droidlogic.mboxlauncher", "com.android.launcher", "com.android.tv.settings")
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
            wallpaper = o.optInt("wallpaper", 0)
            firstRun = false
        } catch (e: Exception) { /* a broken file never stops the home screen */ }
    }

    fun save() {
        val o = JSONObject()
            .put("version", 1)
            .put("dock", JSONArray(dock)).put("hidden", JSONArray(hidden.toList())).put("recent", JSONArray(recent))
            .put("launches", JSONObject(launches as Map<*, *>)).put("wallpaper", wallpaper)
        Thread { try { val tmp = File(file.path + ".tmp"); tmp.writeText(o.toString(1)); tmp.renameTo(file) } catch (_: Exception) {} }.start()
    }

    fun noteLaunch(key: String) {
        launches[key] = (launches[key] ?: 0) + 1
        recent.remove(key); recent.add(0, key); while (recent.size > 10) recent.removeAt(recent.size - 1)
        save()
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
