package com.antonretro.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.*
import android.graphics.Color
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.antonretro.launcher.data.AppRepo
import com.antonretro.launcher.data.Item
import com.antonretro.launcher.data.Store
import com.antonretro.launcher.data.Channel
import com.antonretro.launcher.data.ChannelRepo
import com.antonretro.launcher.kit.*
import com.antonretro.launcher.ui.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class HomeActivity : Activity() {
    private lateinit var repo: AppRepo
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val artCache = HashMap<String, Art>()

    private lateinit var root: FrameLayout
    private lateinit var bgSharp: ImageView
    private lateinit var bgBlur: ImageView
    private lateinit var dim: View
    private lateinit var shelf: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var clock: TextView
    private val tabViews = ArrayList<TextView>()
    private lateinit var pills: PillTrack

    private val tabs = listOf("Home", "TV", "Radio", "YouTube", "Videos", "Games", "Apps", "Settings")
    private val screens = LinkedHashMap<String, Screen>()
    private var current = "Home"
    private var inTabBar = false
    private var tabIdx = 0
    private var scrolledShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        Perf.file = getExternalFilesDir(null)?.let { java.io.File(it, "perf.log") }
        repo = AppRepo(this); store = Store(this)
        val dm = resources.displayMetrics
        Backdrop.build(store.wallpaper, maxOf(dm.widthPixels, dm.heightPixels), minOf(dm.widthPixels, dm.heightPixels))
        buildViews()
        setContentView(root)
        window.setBackgroundDrawable(null)   // the wallpaper covers everything: skip one full-screen fill
        hideSystemBars()
        buildScreens()
        showTab("Home", animate = false)
        Images.init(this)
        loadApps { if (wantIntro()) playIntro() }
        loadChannels()
        ui.postDelayed({ player.prepare() }, 1500)   // the first channel doesn't pay for creating the player
        registerReceivers()
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean { root.viewTreeObserver.removeOnPreDrawListener(this); Perf.log("cold", SystemClock.uptimeMillis() - t0); return true }
        })
    }

    // ---------------------------------------------------------------- views
    private fun buildViews() {
        root = FrameLayout(this).apply { clipChildren = false }
        bgSharp = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(Backdrop.sharp) }
        bgBlur = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(Backdrop.blurredDim); alpha = 0f }
        dim = View(this).apply { setBackgroundColor(Kit.DIM_SCROLLED); alpha = 0f }
        shelf = FrameLayout(this)
        stage = FrameLayout(this).apply { clipChildren = false }
        root.addView(bgSharp, -1, -1); root.addView(bgBlur, -1, -1); root.addView(dim, -1, -1)
        root.addView(shelf, FrameLayout.LayoutParams(-1, Kit.dpi(this, 400f)))
        // content is clipped under the tab bar with a soft fade (pre-made gradient, no per-frame work)
        root.addView(stage, -1, -1)

        pills = PillTrack(this)
        val bar = pills
        bar.background = GlassDrawable(bar, Kit.dp(this, 27f))
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Kit.dpi(this@HomeActivity, 4f), Kit.dpi(this@HomeActivity, 4f), Kit.dpi(this@HomeActivity, 4f), Kit.dpi(this@HomeActivity, 4f)) }
        for ((i, name) in tabs.withIndex()) {
            val tv = Kit.text(this, Kit.TAB_TEXT, true).apply { text = name; gravity = Gravity.CENTER; setPadding(Kit.dpi(this@HomeActivity, 16f), 0, Kit.dpi(this@HomeActivity, 16f), 0) }
            tv.setOnClickListener { inTabBar = false; showTab(name) }
            tv.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) { tabIdx = i; drawTabs() }; false }
            tabBar.addView(tv, LinearLayout.LayoutParams(-2, Kit.dpi(this, 43f)))
            tabViews.add(tv)
        }
        bar.addView(tabBar)
        tabBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> drawTabs(animate = false) }
        val topFade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000)) }
        root.addView(topFade, FrameLayout.LayoutParams(-1, Kit.dpi(this, 110f)))
        root.addView(bar, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Kit.dpi(this@HomeActivity, 41f); topMargin = Kit.dpi(this@HomeActivity, 29f) })
        clock = Kit.text(this, 24f, true)
        Kit.shadowText(clock)
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { rightMargin = Kit.dpi(this@HomeActivity, Kit.SAFE_X); topMargin = Kit.dpi(this@HomeActivity, 38f) })
        tickClock()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    // ---------------------------------------------------------------- screens
    private fun buildScreens() {
        val art: (Item, CardCell) -> Unit = { item, cell -> bindArt(item, cell) }
        val open: (Item, CardCell) -> Unit = { item, cell -> open(item, cell) }
        screens["Home"] = RowsScreen(this, 410f, true, art, open)
        screens["TV"] = RowsScreen(this, 150f, false, art, open, "Loading your channels…")
        screens["Radio"] = RowsScreen(this, 150f, false, art, open, "Loading radio stations…")
        for (n in listOf("TV", "Radio", "Home")) (screens[n] as RowsScreen).onLong = { item, _ -> if (item is Item.Ch) star(item.ch) }
        screens["YouTube"] = RowsScreen(this, 150f, false, art, open)
        screens["Videos"] = RowsScreen(this, 150f, false, art, open, "Your videos and films will show up here.")
        screens["Games"] = RowsScreen(this, 150f, false, art, open)
        screens["Apps"] = RowsScreen(this, 150f, false, art, open, "Looking for apps…")
        screens["Settings"] = ListScreen(this, "Settings", settingsEntries())
        player = PlayerOverlay(this, art).apply {
            onTuned = { c -> store.noteChannel(c); if (!c.radio) nowPlaying(null) }
            onClosed = { c, keep -> nowPlaying(if (keep) c else null); rebuildChannelRows() }
            onStar = { c -> star(c) }
        }
        for ((name, s) in screens) {
            s.view.visibility = View.GONE
            stage.addView(s.view, -1, -1)
            s.onFocusChanged = { if (name == current) { updateShelf(); updateScrolled() } }
            s.onHoverFocus = { if (name == current) { inTabBar = false; drawTabs() } }
        }
    }

    private fun settingsEntries(): List<Item.Action> {
        fun intent(a: String): (Context) -> Unit = { _ -> safeStart(Intent(a)) }
        return listOf(
            Item.Action("set:wifi", "Wi-Fi & Internet", "", "wifi", intent(Settings.ACTION_WIFI_SETTINGS)),
            Item.Action("set:picture", "Picture", "", "image", intent(Settings.ACTION_DISPLAY_SETTINGS)),
            Item.Action("set:wall", "Wallpaper", Backdrop.SCENES[store.wallpaper % Backdrop.SCENES.size].name, "image") { _ -> nextWallpaper() },
            Item.Action("set:sound", "Sound", "", "sound", intent(Settings.ACTION_SOUND_SETTINGS)),
            Item.Action("set:apps", "Apps", "", "apps", intent(Settings.ACTION_APPLICATION_SETTINGS)),
            Item.Action("set:storage", "Storage", "", "usb", intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
            Item.Action("set:about", "About", "Retro Launcher " + BuildConfig.VERSION_NAME, "info", intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
            Item.Action("set:classic", "Classic launcher", "the box's original menu", "grid") { _ -> openClassic() },
            Item.Action("set:android", "Advanced Android settings", "", "gear", intent(Settings.ACTION_SETTINGS)),
        )
    }

    // ---------------------------------------------------------------- TV and radio
    private lateinit var player: PlayerOverlay
    private var channelsAsked = false

    private fun loadChannels() {
        bg.execute {
            val ok = ChannelRepo.load()
            ui.post {
                if (!ok) {
                    val denied = android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (denied && !channelsAsked) { channelsAsked = true; requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 7) }
                    val msg = if (denied) "Retro Launcher needs permission to read your channel list." else "No channel list yet. Put one at /sdcard/RetroLauncher/channels.m3u"
                    (screens["TV"] as RowsScreen).setEmptyText(msg); (screens["Radio"] as RowsScreen).setEmptyText(msg)
                }
                rebuildChannelRows()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 7 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) loadChannels()
    }

    private fun rebuildChannelRows() {
        val tv = ChannelRepo.tv; val radio = ChannelRepo.radio
        val byUrl = (tv + radio).associateBy { it.url }
        fun rows(list: List<Channel>, radioTab: Boolean): List<Row> {
            val favs = store.favourites.mapNotNull { byUrl[it] }.filter { it.radio == radioTab }
            val recent = store.recentChannels.mapNotNull { byUrl[it] }.filter { it.radio == radioTab }
            val out = ArrayList<Row>()
            if (!radioTab) store.lastTv?.let { byUrl[it] }?.let { out.add(Row("Keep watching", listOf(Item.Ch(it)), alwaysLabel = true)) }
            if (favs.isNotEmpty()) out.add(Row("Favourites", favs.map { Item.Ch(it) }, alwaysLabel = true))
            if (recent.isNotEmpty()) out.add(Row("Recent", recent.map { Item.Ch(it) }, alwaysLabel = true))
            for ((g, chans) in ChannelRepo.groups(list)) out.add(Row("$g  ·  ${chans.size}", chans.map { Item.Ch(it) }, alwaysLabel = true))
            return out
        }
        (screens["TV"] as RowsScreen).setRows(rows(tv, false))
        (screens["Radio"] as RowsScreen).setRows(rows(radio, true))
        homeExtra = store.recentChannels.mapNotNull { byUrl[it] }.filter { !it.radio }.take(6).map { Item.Ch(it) }
        rebuildHome()
    }

    private var homeExtra: List<Item> = emptyList()
    private var homeDock: List<Item> = emptyList()
    private var homeRecent: List<Item> = emptyList()
    private fun rebuildHome() {
        (screens["Home"] as RowsScreen).setRows(listOf(Row(null, homeDock, glass = true), Row("Keep watching", homeExtra, alwaysLabel = true), Row("Recently used", homeRecent, alwaysLabel = true)))
    }

    private fun star(c: Channel) {
        val on = store.toggleFavourite(c)
        notice(if (on) "Added to Favourites" else "Removed from Favourites", c.name)
        rebuildChannelRows()
    }

    /** The small "Now playing" chip next to the clock while radio plays in the background. */
    private var nowChip: TextView? = null
    private fun nowPlaying(c: Channel?) {
        if (c == null) { nowChip?.animate()?.alpha(0f)?.setDuration(Kit.t(200))?.start(); return }
        val chip = nowChip ?: Kit.text(this, 16f, true).also { t ->
            t.setPadding(Kit.dpi(this, 14f), Kit.dpi(this, 8f), Kit.dpi(this, 14f), Kit.dpi(this, 8f))
            t.background = GlassDrawable(t, Kit.dp(this, 16f))
            t.setOnClickListener { player.current?.let { openPlayer(listOf(it), 0) } }
            t.setOnLongClickListener { player.stopBackground(); nowPlaying(null); true }
            root.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { rightMargin = Kit.dpi(this@HomeActivity, 130f); topMargin = Kit.dpi(this@HomeActivity, 36f) })
            nowChip = t
        }
        chip.text = "♪  ${c.name}"; chip.alpha = 0f; chip.animate().alpha(1f).setDuration(Kit.t(250)).start()
    }

    private fun openPlayer(list: List<Channel>, index: Int) {
        if (player.parent == null) root.addView(player, -1, -1)
        player.bringToFront()
        player.play(list, index)
    }

    // ---------------------------------------------------------------- notices
    private var noticeView: TextView? = null
    private val hideNotice = Runnable { noticeView?.animate()?.translationX(Kit.dp(this, 420f))?.alpha(0f)?.setDuration(Kit.t(350))?.start() }
    /** A small glass pill top right for three seconds; never takes focus. */
    fun notice(title: String, sub: String) {
        val n = noticeView ?: Kit.text(this, 17f, true).also { t ->
            t.isSingleLine = false; t.maxLines = 2
            t.setPadding(Kit.dpi(this, 22f), Kit.dpi(this, 12f), Kit.dpi(this, 22f), Kit.dpi(this, 12f))
            t.background = GlassDrawable(t, Kit.dp(this, 22f), Kit.GLASS_STRONG)
            root.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { rightMargin = Kit.dpi(this@HomeActivity, Kit.SAFE_X); topMargin = Kit.dpi(this@HomeActivity, 88f) })
            noticeView = t
        }
        n.text = android.text.SpannableStringBuilder(title).apply {
            append("\n"); val st = length; append(sub)
            setSpan(android.text.style.ForegroundColorSpan(Kit.TEXT_DIM), st, length, 0)
            setSpan(android.text.style.RelativeSizeSpan(.85f), st, length, 0)
        }
        n.bringToFront()
        n.translationX = Kit.dp(this, 420f); n.alpha = 0f
        n.animate().translationX(0f).alpha(1f).setDuration(Kit.t(420)).setInterpolator(Kit.EASE).start()
        ui.removeCallbacks(hideNotice); ui.postDelayed(hideNotice, 3000)
    }

    private fun loadApps(then: (() -> Unit)? = null) {
        bg.execute {
            val all = repo.load()
            ui.post { rebuildRows(all); then?.invoke() }
        }
    }

    // ---------------------------------------------------------------- start-up intro (once per boot)
    private var introRunning = false
    private fun wantIntro(): Boolean {
        if (intent?.getBooleanExtra("intro", false) == true) return true
        val bootAt = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000   // minutes, stable for this boot
        val prefs = getSharedPreferences("intro", MODE_PRIVATE)
        if (prefs.getLong("boot", -1) == bootAt) return false
        prefs.edit().putLong("boot", bootAt).apply(); return true
    }

    /** About 1.6 s: mark fades and scales in over black, wallpaper rises behind it, then the
     *  tab bar, clock and dock cards slide up into place one after another. Any key skips. */
    private fun playIntro() {
        introRunning = true
        if (getSharedPreferences("intro", MODE_PRIVATE).getBoolean("sound", true)) try {
            android.media.MediaPlayer.create(this, R.raw.startup_chime)?.apply { setVolume(.8f, .8f); setOnCompletionListener { it.release() }; start() }
        } catch (_: Exception) {}
        val home = screens["Home"] as RowsScreen
        val bar = tabBar.parent as View
        val parts = listOf(bar, clock) + home.introViews()
        val mark = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ImageView(this@HomeActivity).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(Kit.dpi(this@HomeActivity, 84f), Kit.dpi(this@HomeActivity, 84f)))
            addView(Kit.text(this@HomeActivity, 34f, true).apply { setText(R.string.app_name) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(this@HomeActivity, 18f) })
        }
        val black = View(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(black, -1, -1); root.addView(mark, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        mark.alpha = 0f; mark.scaleX = .86f; mark.scaleY = .86f
        bgSharp.scaleX = 1.08f; bgSharp.scaleY = 1.08f
        for (v in parts) { v.alpha = 0f; v.translationY = Kit.dp(this, 26f) }
        shelf.alpha = 0f
        val d = Kit.t(1)  // reduce motion => everything appears at once
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520 * d).setInterpolator(Kit.EASE).withEndAction {
            mark.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setStartDelay(260 * d).setDuration(360 * d).start()
            black.animate().alpha(0f).setStartDelay(300 * d).setDuration(620 * d).start()
            bgSharp.animate().scaleX(1f).scaleY(1f).setStartDelay(300 * d).setDuration(1100 * d).setInterpolator(Kit.DECEL).start()
            shelf.animate().alpha(1f).setStartDelay(760 * d).setDuration(500 * d).start()
            for ((i, v) in parts.withIndex()) v.animate().alpha(1f).translationY(0f).setStartDelay((620 + i * 60L) * d).setDuration(420 * d).setInterpolator(Kit.EASE).start()
            ui.postDelayed({ endIntro(black, mark, parts) }, (620 + parts.size * 60L + 450) * d)
        }.start()
        introSkip = { endIntro(black, mark, parts) }
    }
    private var introSkip: (() -> Unit)? = null
    private fun endIntro(black: View, mark: View, parts: List<View>) {
        if (!introRunning) return
        introRunning = false; introSkip = null
        for (v in parts + listOf(black, mark, bgSharp, shelf)) v.animate().cancel()
        for (v in parts) { v.alpha = if (v is CardCell) 1f else v.alpha.coerceAtLeast(1f); v.translationY = 0f }
        bgSharp.scaleX = 1f; bgSharp.scaleY = 1f; shelf.alpha = if (scrolledShown) 0f else 1f
        root.removeView(black); root.removeView(mark)
    }

    private fun rebuildRows(all: List<Item.App>) {
        val byPkg = all.associateBy { it.component.packageName }
        val visible = all.filter { it.component.packageName !in store.hidden && it.component.packageName !in AppRepo.HIDDEN_DEFAULTS }
        if (store.dock.isEmpty()) {
            store.dock = AppRepo.DOCK_DEFAULTS.mapNotNull { byPkg[it]?.key }.distinct().take(5).toMutableList()
            if (store.dock.size < 5) store.dock.addAll(visible.map { it.key }.filter { it !in store.dock }.take(5 - store.dock.size))
            store.save()
        }
        val byKey = all.associateBy { it.key }
        val dock = store.dock.mapNotNull { byKey[it] }
        val recent = store.recent.mapNotNull { byKey[it] }.filter { it !in dock }.take(8)
        homeDock = dock; homeRecent = recent; rebuildHome()
        val ordered = visible.sortedWith(compareByDescending<Item.App> { store.launches[it.key] ?: 0 }.thenBy { it.title.lowercase() })
        (screens["Apps"] as RowsScreen).setRows(ordered.chunked(5).mapIndexed { i, c -> Row(if (i == 0) "All apps" else null, c, alwaysLabel = true) })
        (screens["Games"] as RowsScreen).setRows(listOf(Row("Android games", visible.filter { it.isGame }, alwaysLabel = true)))
        val yt = all.filter { it.component.packageName in AppRepo.YOUTUBE_APPS }
        (screens["YouTube"] as RowsScreen).setRows(listOf(Row("YouTube (search and your channels come in the next stage)", yt, alwaysLabel = true)))
        updateShelf()
    }

    // ---------------------------------------------------------------- art
    private fun bindArt(item: Item, cell: CardCell) {
        artCache[item.key]?.let { cell.card.art = it; return }
        cell.tag = item.key
        if (item is Item.Ch) { bindChannelArt(item.ch, cell); return }
        if (item !is Item.App) { cell.card.art = Art(null, 0xFF3A3F55.toInt(), 0xFF15171F.toInt(), null, item.title); return }
        val w = Kit.dpi(this, cell.wDp); val h = Kit.dpi(this, cell.hDp)
        bg.execute {
            val art = repo.banner(item)?.let { ArtMaker.fromBanner(it, w, h) }
                ?: ArtMaker.fromIcon(repo.icon(item), if (item.isGame) item.title else null, h)
            ui.post { artCache[item.key] = art; if (cell.tag == item.key) cell.card.art = art; if (screens[current]?.shelfItem()?.key == item.key) updateShelf(force = true) }
        }
    }

    /** Channel card: the name on its own colour straight away, the logo when it arrives. */
    private fun bindChannelArt(c: Channel, cell: CardCell) {
        val star = c.url in store.favourites
        val hue = ((c.name.hashCode() and 0x7fffffff) % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, .55f, .48f)); val bottom = Color.HSVToColor(floatArrayOf((hue + 20) % 360, .7f, .18f))
        val badge = if (c.radio) "RADIO" else "LIVE"; val bc = if (c.radio) 0xFF5E60CE.toInt() else 0xFFE5383B.toInt()
        cell.card.art = Art(null, top, bottom, null, null, bigText = c.name, badge = badge, badgeColor = bc, star = star)
        val logo = c.logo ?: return
        val w = Kit.dpi(this, cell.wDp); val h = Kit.dpi(this, cell.hDp)
        Images.load(logo, w, h) { b ->
            if (b != null && cell.tag == c.key) cell.card.art = Art(null, 0xFF2B2F3A.toInt(), 0xFF101218.toInt(), b, null, badge = badge, badgeColor = bc, star = star)
        }
    }

    // ---------------------------------------------------------------- top shelf + backdrop
    private var shelfKey: String? = null
    private fun updateShelf(force: Boolean = false) {
        val s = screens[current] ?: return
        val item = if (s.usesShelf) s.shelfItem() else null
        if (!force && item?.key == shelfKey) return
        shelfKey = item?.key
        for (i in 0 until shelf.childCount) { val old = shelf.getChildAt(i); old.animate().alpha(0f).setDuration(Kit.t(Kit.T_FADE)).withEndAction { shelf.removeView(old) }.start() }
        if (item == null) return
        val layer = FrameLayout(this).apply { alpha = 0f }
        // (no full-screen glow: on the Mali-450 every full-screen shader costs ~2 ms a frame)
        val title = Kit.text(this, Kit.SHELF_TITLE, true).apply { text = item.title; Kit.shadowText(this) }
        val sub = Kit.text(this, Kit.SHELF_SUB, false, 0xD9FFFFFF.toInt()).apply { text = "Press OK to open"; Kit.shadowText(this) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title, LinearLayout.LayoutParams(-1, -2)); addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(this@HomeActivity, 9f) }) }
        layer.addView(box, FrameLayout.LayoutParams(Kit.dpi(this, 760f), -2, Gravity.BOTTOM).apply { leftMargin = Kit.dpi(this@HomeActivity, Kit.SAFE_X); bottomMargin = Kit.dpi(this@HomeActivity, 50f) })
        shelf.addView(layer, -1, -1)
        layer.animate().alpha(1f).setDuration(Kit.t(Kit.T_FADE)).withLayer().start()
    }

    private fun updateScrolled() {
        val want = screens[current]?.scrolled() == true
        if (want == scrolledShown) return
        scrolledShown = want
        val d = Kit.t(350)
        // only one full-screen picture is drawn once the fade ends (the GPU's biggest cost)
        if (!want) { bgSharp.visibility = View.VISIBLE }
        bgBlur.animate().alpha(if (want) 1f else 0f).setDuration(d).withEndAction { if (scrolledShown) bgSharp.visibility = View.INVISIBLE }.start()
        shelf.animate().alpha(if (want) 0f else 1f).setDuration(d).start()
    }

    // ---------------------------------------------------------------- tabs
    private fun showTab(name: String, animate: Boolean = true) {
        val t0 = SystemClock.uptimeMillis()
        val old = screens[current]; val new = screens[name] ?: return
        val dir = if (tabs.indexOf(name) >= tabs.indexOf(current)) 1 else -1
        if (old != null && old !== new) {
            old.leave()
            // the old tab leaves at once; only the new one moves (half the drawing per frame)
            old.view.animate().cancel(); old.view.visibility = View.GONE
        }
        current = name; tabIdx = tabs.indexOf(name)
        new.view.visibility = View.VISIBLE
        if (animate && old !== new) { new.view.alpha = 0f; new.view.translationX = dir * Kit.dp(this, 40f); new.view.animate().alpha(1f).translationX(0f).setDuration(Kit.t(Kit.T_TAB)).setInterpolator(Kit.EASE).start() }
        else { new.view.alpha = 1f; new.view.translationX = 0f }
        if (!inTabBar) new.enter()
        drawTabs(); updateShelf(); updateScrolled()
        Perf.afterFrame("tab", t0)
    }

    private fun drawTabs(animate: Boolean = true) {
        if (!::pills.isInitialized || tabViews[0].width == 0) return
        val s = tabViews[tabs.indexOf(current)]; val f = tabViews[tabIdx]
        pills.setHeight(s.top.toFloat(), s.bottom.toFloat())
        pills.moveSelected(s.left.toFloat(), s.right.toFloat(), animate)
        pills.moveFocus(f.left.toFloat(), f.right.toFloat(), inTabBar, animate)
        for ((i, tv) in tabViews.withIndex()) {
            val sel = tabs[i] == current; val foc = inTabBar && i == tabIdx
            tv.setTextColor(if (foc) Kit.FOCUS_TEXT else if (sel) Kit.TEXT else 0xBFFFFFFF.toInt())
        }
    }

    // ---------------------------------------------------------------- input: remote, controller, mouse
    private var downAt = 0L; private var longFired = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (introRunning) { if (e.action == KeyEvent.ACTION_UP) introSkip?.invoke(); return true }
        if (::player.isInitialized && player.showing && player.alpha > 0f) {
            if (e.action == KeyEvent.ACTION_DOWN && player.onKey(e.keyCode)) return true
            if (e.action == KeyEvent.ACTION_UP) return true
        }
        val code = e.keyCode
        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { downAt = e.eventTime; longFired = false }
                else if (!longFired && e.eventTime - downAt > 600) { longFired = true; if (!inTabBar) screens[current]?.longOk() }
            } else if (e.action == KeyEvent.ACTION_UP && !longFired) onOk()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { Perf.afterFrame("key2focus", e.eventTime); move(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { Perf.afterFrame("key2focus", e.eventTime); move(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { Perf.afterFrame("key2focus", e.eventTime); move(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { Perf.afterFrame("key2focus", e.eventTime); move(0, 1); return true }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> { switchTab(-1); return true }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> { switchTab(1); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> { onBackKey(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START -> { if (!inTabBar) screens[current]?.longOk(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun move(dr: Int, dc: Int) {
        if (inTabBar) {
            if (dc != 0) { val n = (tabIdx + dc).coerceIn(0, tabs.size - 1); if (n != tabIdx) { tabIdx = n; showTab(tabs[n]) } }
            if (dr > 0) { inTabBar = false; screens[current]?.enter(); drawTabs() }
            return
        }
        val s = screens[current] ?: return
        if (!s.move(dr, dc) && dr < 0) { inTabBar = true; tabIdx = tabs.indexOf(current); s.leave(); drawTabs(); updateShelf() }
    }

    private fun switchTab(d: Int) { val n = (tabs.indexOf(current) + d).coerceIn(0, tabs.size - 1); showTab(tabs[n]) }

    private fun onOk() {
        if (inTabBar) { inTabBar = false; screens[current]?.enter(); drawTabs(); return }
        screens[current]?.ok()
    }

    private fun onBackKey() {
        // Back goes one step up; on Home's first row it does nothing.
        if (inTabBar) { if (current != "Home") showTab("Home"); return }
        val s = screens[current] ?: return
        if (s.back()) return
        if (current != "Home") { s.leave(); inTabBar = true; tabIdx = tabs.indexOf(current); drawTabs() }
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f && !inTabBar) { screens[current]?.move(if (v < 0) 1 else -1, 0); return true }
        }
        // controller hat / left stick act like the D-pad (with repeat handled by the stick timer)
        if (e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            val x = e.getAxisValue(MotionEvent.AXIS_HAT_X).let { if (it != 0f) it else e.getAxisValue(MotionEvent.AXIS_X) }
            val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (it != 0f) it else e.getAxisValue(MotionEvent.AXIS_Y) }
            stick(if (x > .5f) 1 else if (x < -.5f) -1 else 0, if (y > .5f) 1 else if (y < -.5f) -1 else 0)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    private var stickDir = 0 to 0
    private val stickRepeat = object : Runnable { override fun run() { if (stickDir != (0 to 0)) { move(stickDir.second, stickDir.first); ui.postDelayed(this, 140) } } }
    private fun stick(dx: Int, dy: Int) {
        if ((dx to dy) == stickDir) return
        stickDir = dx to dy; ui.removeCallbacks(stickRepeat)
        if (dx != 0 || dy != 0) { move(dy, dx); ui.postDelayed(stickRepeat, 380) }
    }

    // ---------------------------------------------------------------- opening things
    private fun open(item: Item, cell: CardCell) {
        when (item) {
            is Item.App -> {
                val i = Intent(Intent.ACTION_MAIN).setComponent(item.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                // the card zooms into the app: no dead black pause
                val opts = ActivityOptions.makeScaleUpAnimation(cell.card, 0, 0, cell.card.width, cell.card.height)
                if (safeStart(i, opts.toBundle())) { store.noteLaunch(item.key); returning = true }
            }
            is Item.Action -> item.run(this)
            is Item.Ch -> {
                val s = screens[current] as? RowsScreen
                val row = s?.currentRowItems()?.filterIsInstance<Item.Ch>()?.map { it.ch } ?: listOf(item.ch)
                openPlayer(row, row.indexOfFirst { it.url == item.ch.url }.coerceAtLeast(0))
            }
            is Item.More -> {}
        }
    }

    private fun safeStart(i: Intent, opts: Bundle? = null): Boolean = try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts); true } catch (e: Exception) { false }

    private fun openClassic() {
        val i = packageManager.getLaunchIntentForPackage("com.droidlogic.mboxlauncher")
            ?: Intent(Intent.ACTION_MAIN).setClassName("com.droidlogic.mboxlauncher", "com.droidlogic.mboxlauncher.Launcher")
        safeStart(i)
    }

    private fun nextWallpaper() {
        store.wallpaper = (store.wallpaper + 1) % Backdrop.SCENES.size; store.save()
        Backdrop.build(store.wallpaper, Backdrop.screenW, Backdrop.screenH)
        bgSharp.setImageBitmap(Backdrop.sharp); bgBlur.setImageBitmap(Backdrop.blurredDim)
        root.invalidate(); invalidateAll(root)
    }
    private fun invalidateAll(v: View) { v.invalidate(); if (v is ViewGroup) for (i in 0 until v.childCount) invalidateAll(v.getChildAt(i)) }

    // ---------------------------------------------------------------- Home key, coming back
    private var returning = false
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val t0 = SystemClock.uptimeMillis()
        if (::player.isInitialized && player.showing) player.close(keepRadio = true)
        if (Intent.ACTION_MAIN == intent.action && hasWindowFocus()) {
            // Home pressed while already home: go to the first card of Home
            inTabBar = false; if (current != "Home") showTab("Home"); screens["Home"]?.reset()
        }
        Perf.afterFrame("home", t0)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        tickClock()
        if (returning) {   // gentle scale-down from 1.05 and fade back in, focus on the same card
            returning = false
            stage.scaleX = 1.05f; stage.scaleY = 1.05f; stage.alpha = 0f
            stage.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Kit.t(280)).setInterpolator(Kit.EASE).withLayer().start()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* handled in dispatchKeyEvent; never leave Home */ }

    // ---------------------------------------------------------------- clock + app changes
    private val timeFmt get() = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(this)) "H:mm" else "h:mm", Locale.getDefault())
    private fun tickClock() { clock.text = timeFmt.format(Date()) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> tickClock()
                else -> { artCache.keys.removeAll { k -> i.data?.schemeSpecificPart?.let { k.contains(it) } == true }; loadApps() }
            }
        }
    }

    private fun registerReceivers() {
        registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        })
    }

    override fun onStop() { super.onStop(); if (::player.isInitialized && player.showing && player.current?.radio != true) player.close(keepRadio = false) }

    override fun onDestroy() { if (::player.isInitialized) player.release(); try { unregisterReceiver(receiver) } catch (_: Exception) {}; bg.shutdown(); super.onDestroy() }
}

/** Speed numbers for launcher/tools/speedcheck.py (logcat tag RLPerf). */
object Perf {
    /** The box drops log lines from ordinary apps, so numbers also go to
     *  /sdcard/Android/data/com.antonretro.launcher/files/perf.log (read by speedcheck.py). */
    @Volatile var file: java.io.File? = null
    private val lines = java.util.concurrent.LinkedBlockingQueue<String>()
    private val writer = Thread {
        while (true) { val l = lines.take(); try { file?.appendText(l + "\n") } catch (_: Exception) {} }
    }.apply { isDaemon = true; start() }
    fun log(what: String, ms: Long) { Log.i("RLPerf", "$what=$ms"); lines.offer("RLPerf $what=$ms") }
    /** Time from an input (uptime ms) to the start of the next drawn frame. */
    fun afterFrame(what: String, since: Long) {
        Choreographer.getInstance().postFrameCallback { frameNanos -> log(what, frameNanos / 1_000_000 - since) }
    }
}
