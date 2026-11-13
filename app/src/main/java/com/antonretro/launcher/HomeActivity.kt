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
import com.antonretro.launcher.data.Guide
import com.antonretro.launcher.data.MusicRepo
import com.antonretro.launcher.data.Song
import com.antonretro.launcher.data.Prog
import com.antonretro.launcher.data.Reminder
import com.antonretro.launcher.data.GameRepo
import com.antonretro.launcher.data.Rom
import com.antonretro.launcher.data.VideoRepo
import com.antonretro.launcher.data.LocalVideo
import com.antonretro.launcher.data.Video
import com.antonretro.launcher.data.YouTubeRepo
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
    private lateinit var drift: DriftView
    private var screenW = 1280; private var screenH = 720
    private lateinit var saver: Screensaver
    private var lastInput = SystemClock.uptimeMillis()
    private val testMode get() = java.io.File(android.os.Environment.getExternalStorageDirectory(), "RetroLauncher/.test-mute").exists()
    private lateinit var bgBlur: ImageView
    private lateinit var dim: View
    private lateinit var shelf: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var clock: TextView
    private val tabViews = ArrayList<TextView>()
    private lateinit var pills: PillTrack

    private val tabs = listOf("Home", "TV", "Radio", "YouTube", "Media", "Games", "Apps", "Settings")
    private val screens = LinkedHashMap<String, Screen>()
    private var current = "Home"
    private var inTabBar = false
    private var tabIdx = 0
    private var scrolledShown = false

    /**
     * Android unbinds an accessibility helper when its app updates. If ours is switched on in settings but not
     * running, write the setting again (we hold WRITE_SECURE_SETTINGS, granted at setup) so it binds again.
     */
    private fun reattachKeysHelper() {
        val me = "$packageName/${RetroKeys::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: ""
        // Android drops the helper from this list when the app is reinstalled: put it back (we hold WRITE_SECURE_SETTINGS)
        if (!enabled.split(':').any { it.equals(me, true) || it.equals("$packageName/.RetroKeys", true) }) safely("keys enable") {
            Settings.Secure.putString(contentResolver, "enabled_accessibility_services", if (enabled.isBlank()) me else "$enabled:$me")
            Settings.Secure.putInt(contentResolver, "accessibility_enabled", 1)
            Perf.note("keys helper was off: switched back on")
        }
        // the helper runs in our own process: if it hasn't connected a few seconds after start, it isn't bound
        ui.postDelayed({
            val now = Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: ""
            if (!RetroKeys.alive) {
                safely("keys rebind") { rebindKeys(now) }
                ui.postDelayed({ if (!RetroKeys.alive) helperOff() }, 4000)
            }
            else if (now.split(':').count { it == me } > 1) safely("keys tidy") { Settings.Secure.putString(contentResolver, "enabled_accessibility_services", now.split(':').distinct().joinToString(":")) }
        }, 3000)
    }

    /** Said once per start, plainly: without the helper the Xbox button can't get you out of games and apps. */
    private var helperWarned = false
    private fun helperOff() {
        if (helperWarned) return
        helperWarned = true
        Perf.note("keys helper still off after re-attach")
        dialog.show("The exit button isn't working", "AntonOS's key helper is off, so the Xbox button can't bring you back from games and apps. Restarting the box usually fixes it. The Home button on the remote always works.", "OK" to {})
    }

    /** Never blank the setting (if the app died halfway the helper would stay off): change it by adding ourselves
     *  once more, which makes Android bind the helper again. The next healthy start tidies the duplicate away. */
    private fun rebindKeys(enabled: String) {
        // off, then on: Android makes a fresh connection. (Adding a second copy of ourselves could leave Android
        // holding a dead connection whose key filter swallowed every remote key until the next change.)
        val me = "$packageName/${RetroKeys::class.java.name}"
        val others = enabled.split(':').filter { it.isNotBlank() && !it.contains("antonretro", true) }.joinToString(":")
        Settings.Secure.putString(contentResolver, "enabled_accessibility_services", others)
        ui.postDelayed({
            safely("keys on") {
                Settings.Secure.putString(contentResolver, "enabled_accessibility_services", if (others.isBlank()) me else "$others:$me")
                Settings.Secure.putInt(contentResolver, "accessibility_enabled", 1)
                Perf.note("keys helper re-attached (off and on)")
            }
        }, 800)
    }


    /** Start-up must never crash the Home button: anything optional that fails is logged and skipped. */
    private inline fun safely(what: String, f: () -> Unit) { try { f() } catch (t: Throwable) { Perf.note("startup step '$what' failed: $t") } }

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = SystemClock.uptimeMillis()
        super.onCreate(null)   // never restore old view state: Home always starts clean on the Home tab
        Perf.file = getExternalFilesDir(null)?.let { java.io.File(it, "perf.log") }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e -> Perf.note("CRASH " + e + " " + android.util.Log.getStackTraceString(e).replace("\n", " ")); Thread.sleep(150); previous?.uncaughtException(th, e) }
        Kit.init(this)
        repo = AppRepo(this); store = Store(this)
        if (store.hiddenTabs.remove("Videos")) { store.hiddenTabs.add("Media"); store.save() }
        val dm = resources.displayMetrics
        screenW = maxOf(dm.widthPixels, dm.heightPixels); screenH = minOf(dm.widthPixels, dm.heightPixels)
        Backdrop.loadPhotos(this)
        // world photos are the default now (Anthony): move older saves to them once
        if (store.wallVersion < 2) { store.wallVersion = 2; store.wallpaper = 0; store.save() }
        if (store.wallpaper !in 0 until wallpaperCount) store.wallpaper = 0
        safely("wallpaper") { buildWall(store.wallpaper) }
        if (Backdrop.blurred == null) Backdrop.build(0, screenW, screenH)
        buildViews()
        setContentView(root)
        window.setBackgroundDrawable(null)   // the wallpaper covers everything: skip one full-screen fill
        hideSystemBars()
        // overlays exist before anything can ask for them
        keyboard = Keyboard(this); dialog = Dialog(this)
        saver = Screensaver(this) { i -> Backdrop.photo(this, i, screenW, screenH) ?: Backdrop.render(i, screenW, screenH) }
        yt = YouTubeRepo(filesDir)
        Images.init(this)
        buildScreens()
        root.addView(saver, -1, -1); root.addView(keyboard, -1, -1); root.addView(dialog, -1, -1)
        Kit.motion = if (store.reduceMotion) 0.01f else 1f
        Kit.coverRadius = if (store.squareCovers) 0f else Kit.R_XS
        safely("tabs") { applyHiddenTabs() }
        showTab("Home", animate = false)
        ui.postDelayed(idleCheck, 15_000)
        ui.postDelayed(worldTimer, 30 * 60_000L)
        safely("youtube") { rebuildYouTube() }
        safely("inputs") {
            (getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager).registerInputDeviceListener(inputListener, ui)
            for (id in InputDevice.getDeviceIds()) InputDevice.getDevice(id)?.let { inputNames[id] = it.name }
        }
        safely("receivers") {
            registerReceiver(deviceReceiver, IntentFilter().apply { addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED); addDataScheme("file") })
            registerReceiver(deviceReceiver, IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION))
            registerReceivers()
        }
        ui.postDelayed({ safely("videos") { rebuildVideos() } }, 1200)
        safely("apps") { loadApps { safely("intro") { if (wantIntro()) playIntro() } } }
        safely("channels") { loadChannels() }
        // started fresh with something to open (a Recent card in the quick panel, or a test): open it once the lists are loaded
        intent?.getStringExtra("rl_open")?.let { key -> ui.postDelayed({ safely("open at start") { openKey(key) } }, 3000) }
        // started fresh by the game menu (Android closed the launcher while the game ran): still do what it asked
        intent?.getStringExtra("rl_game")?.let { act -> val key = intent.getStringExtra("rl_rom"); ui.postDelayed({ safely("game action at start") { gameAction(act, key) } }, 500) }
        ui.postDelayed({ maybeRefreshGuide(12 * HOUR) }, 60_000)
        ui.postDelayed(guideTimer, 30_000)
        safely("keys helper") { reattachKeysHelper() }
        ui.postDelayed({ safely("player") { player.prepare() } }, 1500)   // the first channel doesn't pay for creating the player
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean { root.viewTreeObserver.removeOnPreDrawListener(this); Perf.log("cold", SystemClock.uptimeMillis() - t0); return true }
        })
    }


    // ---------------------------------------------------------------- views
    private fun buildViews() {
        root = FrameLayout(this).apply { clipChildren = false }
        // one picture each: fading them needs no off-screen copy (Android makes a full-screen one by default,
        // which on this GPU was most of the cost of moving between the dock and the rows)
        bgSharp = object : ImageView(this) { override fun hasOverlappingRendering() = false }.apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(Backdrop.sharp) }
        bgBlur = object : ImageView(this) { override fun hasOverlappingRendering() = false }.apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(Backdrop.blurredDim); alpha = 0f }
        dim = View(this).apply { setBackgroundColor(Kit.DIM_SCROLLED); alpha = 0f }
        // the home title area fades as one picture with no off-screen copy (its texts never overlap): measured,
        // that fade and the wallpaper's were almost all the dropped frames between the dock and the rows
        shelf = object : FrameLayout(this) { override fun hasOverlappingRendering() = false }
        stage = FrameLayout(this).apply { clipChildren = false }
        drift = DriftView(this).apply { visibility = View.GONE }
        root.addView(drift, -1, -1)
        root.addView(bgSharp, -1, -1); root.addView(bgBlur, -1, -1); root.addView(dim, -1, -1)
        applyWallpaper()
        root.addView(shelf, FrameLayout.LayoutParams(-1, Kit.dpi(this, 400f)))
        // content is clipped under the tab bar with a soft fade (pre-made gradient, no per-frame work)
        // the screens end above the button-hint bar: the row you are on never sits under it
        root.addView(stage, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = Kit.dpi(this@HomeActivity, 44f) })

        pills = PillTrack(this)
        val bar = pills
        bar.background = GlassDrawable(bar, Kit.dp(this, Kit.pill(43f + 8f)))
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
        statusRow.orientation = LinearLayout.HORIZONTAL; statusRow.gravity = Gravity.CENTER_VERTICAL
        statusRow.addView(clock)
        root.addView(statusRow, FrameLayout.LayoutParams(-2, Kit.dpi(this, 52f), Gravity.END).apply { rightMargin = Kit.dpi(this@HomeActivity, Kit.SAFE_X); topMargin = Kit.dpi(this@HomeActivity, 28f) })
        root.addView(hintBar, FrameLayout.LayoutParams(-1, Kit.dpi(this, 44f), Gravity.BOTTOM))
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
        screens["YouTube"] = RowsScreen(this, 150f, false, art, open)
        screens["Media"] = RowsScreen(this, 150f, false, art, open, "Your music and videos will show up here.")
        screens["Games"] = RowsScreen(this, 150f, false, art, open)
        screens["Apps"] = RowsScreen(this, 150f, false, art, open, "Looking for apps…")
        screens["Settings"] = SettingsScreen(this, settingsPages())
        // after every screen exists (this ran too early once and crashed the launcher at start)
        for ((n, sc) in screens) (sc as? RowsScreen)?.onLong = { item, _ -> cardOptions(n, item) }
        PhoneRemote.handler = phoneBox; if (PhoneRemote.enabled(this)) PhoneRemote.start(this)
        com.antonretro.launcher.kit.Pads.init(this); watchPads()
        com.antonretro.launcher.kit.UiSound.init(this)
        com.antonretro.launcher.kit.UiSound.quiet = { (::player.isInitialized && player.showing) || (::music.isInitialized && music.playing) || game != null || !resumed }
        // the Guide button saves the game's place in RetroArch (it used to quit, and leaving then lost the place)
        bg.execute { if (RetroConfig.get("input_save_state_btn") != "110") { RetroConfig.set("input_exit_emulator_btn", "nul"); RetroConfig.set("input_save_state_btn", "110") } }
        player = PlayerOverlay(this, art).apply {
            onTuned = { c -> store.noteChannel(c); if (!c.radio) nowPlaying(null) }
            onClosed = { c, keep -> nowPlaying(if (keep) c else null); rebuildChannelRows() }
            onStar = { c -> star(c) }
            numberOf = { c -> this@HomeActivity.numberOf(c) }
            onBroken = { c -> markBroken(c.url, true) }
            backMode = getSharedPreferences("tv", MODE_PRIVATE).getInt("backMode", 0)
            onMini = { c -> if (c != null) { bringToFront(); hintBar.bringToFront(); dialog.bringToFront(); notice("Still watching ${c.name}", "Menu: full screen  ·  Back on the tabs: stop") } ; ui.post { updateHints() } }
            onWorks = { c -> markBroken(c.url, false) }
        }
        applyPlayerSettings()
        safely("guide") { Guide.open(this) }
        guide = GuideView(this, { c -> numberOf(c) }, { store.clock24 }).apply {
            onWatch = { list, i -> hide(); openPlayer(list, i) }
            onLater = { c, p -> guideLater(c, p) }
            onCover = { on -> coverHome(on) }
        }
        gameMenu = GameMenu(this).apply {
            onResume = { r -> RetroKeys.beforeLauncher = null; gameAway = null; playGame(r, null) }
            onLeave = { r -> RetroKeys.beforeLauncher = null; gameAway = null; endEngine(); notice("Game closed", r.title) }
            onRestart = { r -> RetroKeys.beforeLauncher = null; endEngine(); ui.postDelayed({ playGame(r, null) }, 900) }
            onAllSettings = { showTab("Settings"); settingsScreen.open("Games") }
        }
        root.addView(gameMenu, -1, -1)
        music = MusicPlayer(this).apply { onChanged = { ui.post { if (::nowPlayingView.isInitialized && nowPlayingView.showing) nowPlayingView.refresh(); musicChip() } } }
        nowPlayingView = NowPlaying(this, music).apply {
            onClose = { coverHome(false); musicChip(); ui.postDelayed({ musicChip() }, Kit.t(260)); calmKeys() }
            onVersion = { d -> switchSongVersion(d) }
        }
        player.onGuide = { c -> openGuide(c.url) }
        player.nowNext = { c -> Guide.nowNext(c.url) }
        player.timeText = { t -> timeFmt.format(Date(t)) }
        player.nowLine = { c -> Guide.nowNext(c.url).first?.let { "Now: ${it.title}, until ${timeFmt.format(Date(it.stop))}" } }
        for ((name, s) in screens) {
            s.view.visibility = View.GONE
            stage.addView(s.view, -1, -1)
            s.onFocusChanged = { if (name == current) { updateShelf(); updateScrolled() } }
            s.onHoverFocus = { if (name == current) { inTabBar = false; drawTabs() } }
        }
    }

    // ---------------------------------------------------------------- Settings: one menu, not three
    private fun go(action: String) = { safeStart(Intent(action)); Unit }
    private val settingsScreen get() = screens["Settings"] as SettingsScreen

    private fun settingsPages(): List<SPage> = listOf(
        SPage("wifi", "Wi-Fi & Internet") { wifiRows() },
        SPage("image", "Picture") { listOf(
            SRow("Screensaver", { "Starts when nobody has pressed anything for a while" }, value = { if (store.saverMinutes == 0) "Off" else "After ${store.saverMinutes} minutes" },
                onClick = { val opts = listOf(2, 5, 10, 30, 0); store.saverMinutes = opts[(opts.indexOf(store.saverMinutes) + 1) % opts.size]; store.save() }),
            SRow("Screensaver style", value = { if (store.saverPhotos) "Wallpapers, shuffled" else "Moving colours" }, onClick = { store.saverPhotos = !store.saverPhotos; store.save() }),
            SRow("Show the screensaver now", onClick = { ui.postDelayed({ startSaver() }, 300) }),
            SRow("New wallpaper every day", toggle = { store.dailyWallpaper }, onClick = { store.dailyWallpaper = !store.dailyWallpaper; store.save() }),
            SRow("Reduce motion", { "Fewer animations, everything appears at once" }, toggle = { store.reduceMotion }, onClick = { store.reduceMotion = !store.reduceMotion; Kit.motion = if (store.reduceMotion) 0.01f else 1f; store.save() }),
            SRow("Screen size and position", { "Fit the picture to your TV" }, onClick = go(Settings.ACTION_DISPLAY_SETTINGS)),
        ) + (0 until wallpaperCount).map { i ->
            SRow("Wallpaper: " + wallpaperName(i), { when { i == 0 -> "A new place every 30 minutes"; isDrift(i) -> "Moves slowly"; i <= Backdrop.PHOTOS.size -> "Photo: " + Backdrop.PHOTOS[i - 1].by + " (Unsplash)"; else -> null } }, value = { if (store.wallpaper == i) "✓ In use" else "" }, onClick = { setWallpaper(i) })
        } },
        SPage("power", "Power & TV") { listOf(
            SRow("Keep the box on when the TV turns off", { "When on, the TV remote can't control the box over HDMI" }, toggle = { hdmiControl() == 0 }, onClick = { setHdmiControl(if (hdmiControl() == 0) 1 else 0) }),
            SRow("Show the start-up animation", onClick = { playIntro() }),
        ) },
        SPage("sound", "Sound") { listOf(
            SRow("Start-up sound", { "The chime when the box starts" }, toggle = { store.startupSound }, onClick = { store.startupSound = !store.startupSound; store.save() }),
            SRow("Interface sounds", { "Soft sounds when you move, choose and go back" }, toggle = { com.antonretro.launcher.kit.UiSound.on }, onClick = { com.antonretro.launcher.kit.UiSound.on = !com.antonretro.launcher.kit.UiSound.on; com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.SELECT) }),
            SRow("Interface sound level", value = { arrayOf("Low", "Medium", "High")[com.antonretro.launcher.kit.UiSound.level] }, onClick = { com.antonretro.launcher.kit.UiSound.level = (com.antonretro.launcher.kit.UiSound.level + 1) % 3; com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.SELECT) }),
            SRow("Volume and sound output", onClick = go(Settings.ACTION_SOUND_SETTINGS)),
        ) },
        SPage("pad", "Controllers & Remotes") { controllerRows() },
        SPage("home", "Home screen") { listOf(
            SRow("Box art corners", { "Game boxes and covers: slightly rounded, or sharp like the real box" }, value = { if (store.squareCovers) "Square" else "Rounded" },
                onClick = { store.squareCovers = !store.squareCovers; Kit.coverRadius = if (store.squareCovers) 0f else Kit.R_XS; store.save(); rebuildGames() }),
            SRow("Clock", value = { if (store.clock24) "24-hour (17:30)" else "12-hour (5:30 PM)" }, onClick = { store.clock24 = !store.clock24; store.save(); tickClock() }),
        ) + listOf("TV", "Radio", "YouTube", "Media", "Games").map { t ->
            SRow("Show the $t tab", toggle = { t !in store.hiddenTabs }, onClick = { if (!store.hiddenTabs.remove(t)) store.hiddenTabs.add(t); store.save(); applyHiddenTabs() })
        } + listOf(
            SRow("Reset the home screen", { "Put the dock and tabs back as they started" }, onClick = { dialog.show("Reset the home screen?", "Your dock, hidden apps and tabs go back to how they started. Your channels and games stay.",
                "Reset" to { store.dock.clear(); store.hidden.clear(); store.hiddenTabs.clear(); store.save(); applyHiddenTabs(); loadApps(); notice("Home screen reset", "") }, "Cancel" to {}) }),
        ) },
        SPage("tv", "TV & Radio") { tvRows() },
        SPage("game", "Games") { gameRows() },
        SPage("apps", "Apps") { appRows() },
        SPage("usb", "Storage") { storageRows() },
        SPage("info", "About") { listOf(
            SRow("This box", value = { android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE }),
            SRow(getString(R.string.app_name), value = { BuildConfig.VERSION_NAME }),
            SRow("Classic launcher", { "The box's original menu" }, onClick = { openClassic() }),
            SRow("Music", { "Songs by AntonRetro, rendered with MuseScore General (MIT, from FluidR3 by Frank Wen) and GeneralUser GS by S. Christian Collins, plus the original MIDI" }),
            SRow("Music visualizer", { "Retro Visualizer by AntonRetro (MIT). Includes maths tables from AMD LibM (BSD 3-Clause)" }),
            SRow("Advanced Android settings", onClick = go(Settings.ACTION_SETTINGS)),
        ) },
    )

    private fun hdmiControl(): Int = try { Settings.Global.getInt(contentResolver, "hdmi_control_enabled", 1) } catch (e: Exception) { 1 }
    private fun setHdmiControl(v: Int) {
        try { Settings.Global.putInt(contentResolver, "hdmi_control_enabled", v); notice(if (v == 0) "The box stays on" else "The box follows the TV", if (v == 0) "when the TV turns off" else "and sleeps when the TV turns off") }
        catch (e: SecurityException) { notice("Can't change this yet", "Needs a one-time permission over ADB") }
    }

    private fun wifiRows(): List<SRow> {
        @Suppress("DEPRECATION") val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION") val now = wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        val out = ArrayList<SRow>()
        out.add(SRow(if (now != null) "Connected to $now" else "Not connected", { if (now != null) "Signal: " + bars(wm.connectionInfo.rssi) else "Pick a network below" }))
        val seen = HashSet<String>()
        val saved = try { @Suppress("DEPRECATION") wm.configuredNetworks?.map { it.SSID.trim('"') }?.toSet() } catch (e: Exception) { null } ?: emptySet()
        val results = try { wm.scanResults } catch (e: Exception) { null } ?: emptyList()
        for (r in results.sortedByDescending { it.level }) {
            val ssid = r.SSID; if (ssid.isNullOrEmpty() || ssid == now || !seen.add(ssid)) continue
            val open = !(r.capabilities.contains("WPA") || r.capabilities.contains("WEP"))
            out.add(SRow(ssid, { if (ssid in saved) "Saved" else if (open) "Open" else "Locked" }, value = { bars(r.level) }, onClick = { joinWifi(wm, ssid, open, ssid in saved) }))
            if (out.size > 12) break
        }
        if (results.isEmpty()) out.add(SRow("Looking for networks…", { "If nothing shows, AntonOS needs location permission for Wi-Fi" }))
        out.add(SRow("Look again", onClick = { wm.startScan(); ui.postDelayed({ if (current == "Settings") settingsScreen.refresh() }, 3000) }))
        out.add(SRow("Advanced Wi-Fi settings", onClick = go(Settings.ACTION_WIFI_SETTINGS)))
        return out
    }

    private fun bars(rssi: Int) = when { rssi > -55 -> "●●●●"; rssi > -67 -> "●●●○"; rssi > -78 -> "●●○○"; else -> "●○○○" }

    @Suppress("DEPRECATION")
    private fun joinWifi(wm: android.net.wifi.WifiManager, ssid: String, open: Boolean, saved: Boolean) {
        fun connect(pass: String?) {
            try {
                val existing = wm.configuredNetworks?.firstOrNull { it.SSID.trim('"') == ssid }
                val id = if (existing != null && pass == null) existing.networkId else {
                    val c = android.net.wifi.WifiConfiguration().apply {
                        SSID = "\"$ssid\""
                        if (pass == null) allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE) else preSharedKey = "\"$pass\""
                    }
                    wm.addNetwork(c)
                }
                if (id < 0) { notice("Couldn't add that network", ssid); return }
                wm.disconnect(); wm.enableNetwork(id, true); wm.reconnect()
                notice("Connecting…", ssid)
                ui.postDelayed({ if (current == "Settings") settingsScreen.refresh() }, 6000)
            } catch (e: Exception) { notice("Couldn't connect", ssid) }
        }
        if (open || saved) connect(null)
        else keyboard.open("Password for $ssid", password = true, doneText = "Connect") { pw -> if (pw.isNotEmpty()) connect(pw) }
    }

    private fun controllerRows(): List<SRow> {
        val out = ArrayList<SRow>()
        if (!RetroKeys.alive) out.add(SRow("The key helper is off", { "Without it, some apps can trap you. Restart the box to switch it back on" }))
        out.add(SRow("Get out of any app", { "Controller: the Guide button, or hold Start, or press Select and Start together. Then OK goes Home" }))
        out.add(SRow("With the remote", { "Home, or hold Back for a second. A short Back works as normal" }))
        out.add(SRow("With a mouse or keyboard", { "Touch the top edge of the screen with the pointer, or hold Esc" }))
        val pads = com.antonretro.launcher.kit.Pads.connected()
        for ((i, d) in pads.withIndex())
            out.add(SRow("Player ${i + 1}  ·  " + com.antonretro.launcher.kit.Pads.nameOf(d), { if (i == 0) "Player 1 in games and menus" else "Press OK to make this Player 1" },
                onClick = { if (i > 0) { com.antonretro.launcher.kit.Pads.makeFirst(d.descriptor); notice(com.antonretro.launcher.kit.Pads.nameOf(d) + " is Player 1", ""); settingsScreen.refresh() } }))
        if (pads.size > 1) out.add(SRow("Choose Player 1 by pressing A", { "Then press A on the controller that should be Player 1" }, onClick = { waitingForPad = SystemClock.uptimeMillis() + 15_000; notice("Press A on the controller", "that should be Player 1") }))
        if (pads.isEmpty()) out.add(SRow("No controller connected", { "Plug in a USB controller or its wireless receiver" }))
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual || com.antonretro.launcher.kit.Pads.isPad(d)) continue
            val s = d.sources
            val kind = when {
                s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK -> "Controller"
                s and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE -> "Mouse"
                d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC -> "Keyboard"
                d.name.contains("cec", true) -> "TV remote (HDMI)"
                d.name.contains("keypad", true) || d.name.contains("ir", true) -> "Remote"
                else -> null
            } ?: continue
            out.add(SRow(d.name.trim(), { kind }))
        }
        val bt = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH)
        if (bt) out.add(SRow("Pair a Bluetooth controller", onClick = go(Settings.ACTION_BLUETOOTH_SETTINGS)))
        else out.add(SRow("Connect a wireless controller", { "This box has no Bluetooth. Use a controller that comes with its own USB receiver, or an 8BitDo USB Wireless Adapter: plug it in, hold its pair button and your controller's pair button, done" }))
        out.add(SRow("Use your phone as a remote", { if (PhoneRemote.enabled(this)) "On: open to show the code" else "Any phone on your Wi-Fi, no app needed" },
            toggle = { PhoneRemote.enabled(this) }, onClick = { if (!PhoneRemote.enabled(this)) PhoneRemote.setEnabled(this, true); showPhoneCode(); settingsScreen.refresh() }))
        return out
    }

    // ---------------------------------------------------------------- phone remote (PhoneRemote.kt serves the page)
    private fun showPhoneCode() {
        val url = PhoneRemote.url(this)
        if (url == null) { dialog.show("Not on a network", "Connect the box to your Wi-Fi first, then try again.", "OK" to {}); return }
        dialog.show("Use your phone as a remote",
            "Point your phone's camera at the code. The phone must be on the same Wi-Fi.\nOr open ${PhoneRemote.shortUrl()} and type ${PhoneRemote.code(this)}",
            "Done" to {},
            "Forget phones" to { PhoneRemote.newToken(this); notice("Phones forgotten", "Scan the new code to connect again") },
            "Turn off" to { PhoneRemote.setEnabled(this, false); settingsScreen.refresh() })
        dialog.picture(PhoneRemote.qr(url, 440))
    }

    /** What the phone page can do. Arrows and OK only reach AntonOS's own screens (Android doesn't let an app press keys in other apps). */
    private val phoneBox = object : PhoneRemote.Box {
        private val am get() = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        private fun press(code: Int) {
            val t = SystemClock.uptimeMillis()
            dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
        }
        private fun media(code: Int) { am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
        private fun front() { if (!resumed) startActivity(Intent(this@HomeActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        private fun global(a: Int) = RetroKeys.instance?.performGlobalAction(a) == true
        override fun key(k: String): String? {
            val vol = android.media.AudioManager.STREAM_MUSIC; val ui = android.media.AudioManager.FLAG_SHOW_UI
            when (k) {
                "volup" -> { am.adjustStreamVolume(vol, android.media.AudioManager.ADJUST_RAISE, ui); return null }
                "voldown" -> { am.adjustStreamVolume(vol, android.media.AudioManager.ADJUST_LOWER, ui); return null }
                "mute" -> { am.adjustStreamVolume(vol, android.media.AudioManager.ADJUST_TOGGLE_MUTE, ui); return null }
                "home" -> { if (resumed) goHome() else if (!global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) front(); return null }
            }
            if (!resumed) return when (k) {
                "back" -> if (global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) null else "Back only works in AntonOS right now"
                "playpause" -> { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); null }
                "next" -> { media(KeyEvent.KEYCODE_MEDIA_NEXT); null }
                "prev" -> { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); null }
                else -> "Arrows work in AntonOS only. Use the TV remote in this app"
            }
            val code = when (k) {
                "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN; "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK; "menu" -> KeyEvent.KEYCODE_MENU
                "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> return "Unknown button"
            }
            press(code); return null
        }
        override fun text(t: String, submit: Boolean, target: String): String? {
            val s = t.trim()
            if (target != "youtube" && resumed && ::keyboard.isInitialized && keyboard.showing) { keyboard.fromPhone(t, submit); return null }
            if (!submit) return null
            if (s.isEmpty()) return "Type something first"
            // Send to TV: a link opens where it plays; plain words search YouTube
            val link = s.startsWith("http://") || s.startsWith("https://") || s.startsWith("www.") || s.startsWith("youtu")
            front()
            if (link) {
                val u = if (s.startsWith("http")) s else "https://$s"
                if (u.contains("youtube.com") || u.contains("youtu.be")) return if (openInSmartTube(u, null)) "Opening on the TV" else "SmartTube isn't installed"
                return if (safeStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)))) "Opening on the TV" else "The TV can't open that link"
            }
            return if (openInSmartTube("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(s), null)) "Searching YouTube on the TV" else "SmartTube isn't installed"
        }
        override fun tab(name: String): String? {
            if (name !in tabs) return "Unknown tab"
            front()
            if (::player.isInitialized && player.showing) player.close(keepRadio = true)
            if (::nowPlayingView.isInitialized && nowPlayingView.showing) { nowPlayingView.hide(); coverHome(false); musicChip() }
            if (dialog.showing) dialog.hide()
            showTab(name); return null
        }
        override fun number(n: String): String? {
            val num = n.toIntOrNull() ?: return null
            val c = (ChannelRepo.tv + ChannelRepo.radio).firstOrNull { numberOf(it) == num && it.url !in store.hiddenChannels } ?: return "No channel $num"
            front(); digits = ""; for (ch in n) typeDigit(ch - '0'); finishDigits()
            return "Tuning ${c.name}"
        }
        override fun status(): String = when {
            ::player.isInitialized && player.showing && player.current != null -> player.current!!.let { "Watching ${numberOf(it)} · ${it.name}" }
            ::music.isInitialized && music.active && music.current != null -> music.current!!.let { "Playing ${it.title}" + if (it.artist.isNotBlank()) " · ${it.artist}" else "" }
            !resumed -> "The TV is in another app"
            else -> "On the TV: $current"
        }
    }

    private fun applyPlayerSettings() { if (::player.isInitialized) player.reconfigure(store.playerSmooth, if (store.quality == 1) 720 else 1080) }

    private fun tvRows(): List<SRow> {
        val out = ArrayList<SRow>()
        val qualities = listOf("Automatic", "Save data (720p)", "Best (1080p)")
        out.add(SRow("Channel list", { "${ChannelRepo.tv.size} TV channels, ${ChannelRepo.radio.size} radio stations" }, value = { "RetroLauncher/channels.m3u" }))
        out.add(SRow("Reload the channel list", { "After putting a new list on the box" }, onClick = { loadChannels(); notice("Channel list reloaded", "") }))
        val backModes = listOf("Stop", "Keep the sound", "Small picture in the corner")
        out.add(SRow("Back while watching TV", { when (player.backMode) { 1 -> "The channel keeps playing as sound, its card by the clock"; 2 -> "Keeps playing in a corner while you browse (browsing stutters on this box)"; else -> "Stops the channel" } },
            value = { backModes[player.backMode] }, onClick = { player.backMode = (player.backMode + 1) % 3; tvPrefs.edit().putInt("backMode", player.backMode).apply() }))
        out.add(SRow("Starting a channel", { if (store.playerSmooth) "Waits a little longer, then fewer pauses" else "Picture comes up as fast as possible" }, value = { if (store.playerSmooth) "Smoother" else "Faster" },
            onClick = { store.playerSmooth = !store.playerSmooth; store.save(); applyPlayerSettings() }))
        out.add(SRow("Picture quality", value = { qualities[store.quality.coerceIn(0, 2)] }, onClick = { store.quality = (store.quality + 1) % 3; store.save(); applyPlayerSettings() }))
        out.add(SRow("Channel numbers", { "Type a number on the remote to go straight to a channel. Hold OK on a channel to change its number" }))
        out.add(SRow("Number all channels again", { "Favourites first, then country by country" }, onClick = {
            dialog.show("Number all channels again?", "Every channel gets a new number, starting from 1 with your favourites.", "Renumber" to { store.numbers.clear(); rebuildChannelRows(); notice("Channels renumbered", "Favourites start at 1") }, "Cancel" to {}) }))
        out.add(SRow("TV Guide", { guideStatus() }, value = { "Open" }, onClick = { openGuide(null) }))
        out.add(SRow("Update the guide now", { if (Guide.refreshing) "Updating… ${Guide.progress}" else "It also updates by itself once a day" }, onClick = { maybeRefreshGuide(0) }))
        out.add(SRow("Guide sources", { "${Guide.sources().size} free guide feeds. To change them, edit RetroLauncher/guide-sources.txt" }))
        for (r in Guide.reminders.sortedBy { it.start }) out.add(SRow("⏰  " + r.title, { "${r.channel} · ${timeFmt.format(Date(r.start))}" }, value = { "Cancel" }, onClick = { Guide.removeReminder(r); notice("Reminder cancelled", r.title) }))
        val byUrl = (ChannelRepo.tv + ChannelRepo.radio).associateBy { it.url }
        for (u in store.favourites) byUrl[u]?.let { c -> out.add(SRow("★  " + c.name, { "Favourite · " + c.country }, value = { "Remove" }, onClick = { star(c) })) }
        for ((g, chans) in ChannelRepo.groups(ChannelRepo.tv) + ChannelRepo.groups(ChannelRepo.radio).map { ("Radio - " + it.first) to it.second }) {
            out.add(SRow("Show $g", { "${chans.size} channels" }, toggle = { g !in store.hiddenGroups }, onClick = { if (!store.hiddenGroups.remove(g)) store.hiddenGroups.add(g); store.save(); rebuildChannelRows() }))
        }
        for (u in store.hiddenChannels) byUrl[u]?.let { c -> out.add(SRow(c.name, { "Hidden channel · " + c.country }, value = { "Show again" }, onClick = { store.hiddenChannels.remove(u); store.save(); rebuildChannelRows() })) }
        return out
    }

    /** Library-wide game options, written straight into the tuned retroarch.cfg. */
    private fun gameRows(): List<SRow> {
        fun on(k: String) = RetroConfig.get(k) == "true"
        fun flip(k: String) { RetroConfig.set(k, if (on(k)) "false" else "true") }
        val shapes = listOf("22" to "As the game was made", "0" to "4:3 (old TV)", "1" to "16:9 (fill the screen)")
        return listOf(
            SRow("Pause menu", { "In a game: the Guide button, Start + Select, or Back on the remote" }),
            SRow("Pick up where you left off", { "Press the controller's Guide button in a game to save your place; Continue starts there" }, toggle = { on("savestate_auto_save") }, onClick = { flip("savestate_auto_save"); RetroConfig.set("savestate_auto_load", RetroConfig.get("savestate_auto_save") ?: "true") }),
            SRow("Save in-game progress often", { "Writes battery saves every 10 seconds" }, toggle = { (RetroConfig.get("autosave_interval") ?: "0") != "0" }, onClick = { RetroConfig.set("autosave_interval", if ((RetroConfig.get("autosave_interval") ?: "0") != "0") "0" else "10") }),
            SRow("Picture shape", value = { val v = RetroConfig.get("aspect_ratio_index") ?: "22"; shapes.firstOrNull { it.first == v }?.second ?: "As the game was made" },
                onClick = { val v = RetroConfig.get("aspect_ratio_index") ?: "22"; val i = shapes.indexOfFirst { it.first == v }; RetroConfig.set("aspect_ratio_index", shapes[(i + 1) % shapes.size].first) }),
            SRow("Picture style", { if (on("video_smooth")) "Softened edges" else "Crisp, blocky pixels as on the old TVs" }, value = { if (on("video_smooth")) "Smooth" else "Sharp pixels" }, onClick = { flip("video_smooth") }),
            SRow("Show messages in games", { "Small notes like \"Saved\"" }, toggle = { on("video_font_enable") }, onClick = { flip("video_font_enable") }),
            SRow("Whole-pixel scaling", { "Every pixel the same size; may leave a border" }, toggle = { on("video_scale_integer") }, onClick = { flip("video_scale_integer") }),
            SRow("Rewind", { "Hold a button to go back a few seconds (uses more power)" }, toggle = { on("rewind_enable") }, onClick = { flip("rewind_enable") }),
            SRow("Game volume", value = { when (RetroConfig.get("audio_volume")?.toFloatOrNull() ?: 0f) { in -100f..-3f -> "Quieter"; in 3f..100f -> "Louder"; else -> "Normal" } },
                onClick = { val v = RetroConfig.get("audio_volume")?.toFloatOrNull() ?: 0f; RetroConfig.set("audio_volume", if (v < -3f) "0.000000" else if (v < 3f) "6.000000" else "-6.000000") }),
        ) + controllerGameRows() + GameRepo.SYSTEMS.distinctBy { it.shelf }.map { sys ->
            SRow(sys.shelf, { "Engine: " + sys.core.substringBefore("_libretro").replace('_', ' ') }, value = { "${roms.count { it.system == sys }} games" })
        } + listOf(
            SRow("PlayStation BIOS", { "PlayStation games need a BIOS file in RetroLauncher/BIOS" }, value = {
                val f = listOf("scph5501.bin", "scph1001.bin", "scph7001.bin", "scph5500.bin", "scph5502.bin").firstOrNull { java.io.File(android.os.Environment.getExternalStorageDirectory(), "RetroLauncher/BIOS/$it").exists() }
                f?.let { "Found ($it)" } ?: "Not found" }),
            SRow("Games folder", value = { "RetroLauncher/Games" }),
            SRow("Look for new games", onClick = { rebuildGames(); ui.postDelayed({ notice("Games updated", "${roms.size} games in your library") }, 1200) }),
        )
    }

    private fun controllerGameRows(): List<SRow> {
        val pads = InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }.filter { d -> !d.isVirtual && ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) }
        if (pads.isEmpty()) return listOf(SRow("Controllers", { "None connected. Plug one in: it sets itself up" }))
        return pads.mapIndexed { i, d -> SRow("Player ${i + 1}: " + d.name.trim(), { "Set up automatically" }) }
    }

    private fun appRows(): List<SRow> = repo.apps.map { a ->
        val pkg = a.component.packageName
        SRow(a.title, { if (pkg in store.hidden || pkg in AppRepo.HIDDEN_DEFAULTS) "Hidden from Apps" else null }, onClick = { appActions(a) })
    }

    private fun appActions(a: Item.App) {
        val pkg = a.component.packageName
        val hidden = pkg in store.hidden || pkg in AppRepo.HIDDEN_DEFAULTS
        dialog.show(a.title, "What would you like to do?",
            "Open" to { safeStart(Intent(Intent.ACTION_MAIN).setComponent(a.component)) },
            (if (hidden) "Show in Apps" else "Hide") to { if (!store.hidden.remove(pkg)) store.hidden.add(pkg); store.save(); loadApps(); ui.postDelayed({ settingsScreen.refresh() }, 600) },
            "Uninstall" to { safeStart(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))) },
            "Cancel" to {})
    }

    private fun storageRows(): List<SRow> {
        fun gb(b: Long) = if (b < 1_000_000_000L) String.format(Locale.getDefault(), "%d MB", b / 1_000_000) else String.format(Locale.getDefault(), "%.1f GB", b / 1e9)
        val out = ArrayList<SRow>()
        out.add(SRow("Box storage", {
            val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            gb(st.availableBytes) + " free of " + gb(st.totalBytes) + if (st.availableBytes < LOW_STORAGE) "  ·  almost full: apps may stop installing" else "" }))
        out.add(SRow("AntonOS's own files", { "Logos and box art " + gb(Images.sizeOnDisk()) + " (kept under 40 MB), TV guide " + gb(java.io.File(filesDir, "guide.db").length()) },
            value = { "Clear" }, onClick = { bg.execute { Images.clear(); ui.post { notice("Cleared", "Logos and box art download again when shown"); settingsScreen.refresh() } } }))
        for (u in usbRoots()) { val s = try { android.os.StatFs(u.path) } catch (e: Exception) { null }; out.add(SRow("USB drive ${u.name}", { s?.let { gb(it.availableBytes) + " free" } }, onClick = { offerGamesFrom(listOf(u), quietIfNone = false) })) }
        out.add(SRow("Add games from a USB drive", onClick = { offerGamesFrom(usbRoots(), quietIfNone = false) }))
        out.add(SRow("Storage details", onClick = go(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)))
        return out
    }

    /** Hidden tabs disappear from the bar (Home, Apps and Settings always stay). */
    private fun applyHiddenTabs() {
        for ((i, t) in tabs.withIndex()) tabViews[i].visibility = if (t in store.hiddenTabs) View.GONE else View.VISIBLE
        if (current in store.hiddenTabs) showTab("Home")
        tabBar.post { drawTabs(animate = false) }
    }
    private fun nextVisibleTab(from: Int, d: Int): Int { var n = from + d; while (n in tabs.indices && tabs[n] in store.hiddenTabs) n += d; return if (n in tabs.indices) n else from }

    private fun settingsEntries(): List<Item.Action> {
        fun intent(a: String): (Context) -> Unit = { _ -> safeStart(Intent(a)) }
        return listOf(
            Item.Action("set:phone", "Phone remote", "Control the TV from your phone", "remote") { _ -> openKey("phone") },
            Item.Action("set:wifi", "Wi-Fi & Internet", "", "wifi", intent(Settings.ACTION_WIFI_SETTINGS)),
            Item.Action("set:picture", "Picture", "", "image", intent(Settings.ACTION_DISPLAY_SETTINGS)),
            Item.Action("set:wall", "Wallpaper", wallpaperName(store.wallpaper), "image") { _ -> nextWallpaper() },
            Item.Action("set:sound", "Sound", "", "sound", intent(Settings.ACTION_SOUND_SETTINGS)),
            Item.Action("set:apps", "Apps", "", "apps", intent(Settings.ACTION_APPLICATION_SETTINGS)),
            Item.Action("set:storage", "Storage", "", "usb", intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
            Item.Action("set:about", "About", "AntonOS " + BuildConfig.VERSION_NAME + " by AntonRetro", "info", intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
            Item.Action("set:classic", "Classic launcher", "the box's original menu", "grid") { _ -> openClassic() },
            Item.Action("set:android", "Advanced Android settings", "", "gear", intent(Settings.ACTION_SETTINGS)),
        )
    }

    // ---------------------------------------------------------------- YouTube (our tab, SmartTube plays)
    private lateinit var yt: YouTubeRepo
    private lateinit var keyboard: Keyboard
    private var smartTube: String? = null

    private fun rebuildYouTube(force: Boolean = false) {
        bg.execute {
            val shelves = yt.pinned.map { p -> p to (yt.feed(p.id, force) ?: emptyList()) }
            ui.post {
                val rows = ArrayList<Row>()
                rows.add(Row(null, listOf(Item.YtSearch()), alwaysLabel = false))
                if (yt.watched.isNotEmpty()) rows.add(Row("Keep watching", yt.watched.map { Item.Yt(it) }, alwaysLabel = true))
                for ((p, vids) in shelves) if (vids.isNotEmpty()) rows.add(Row(p.name, vids.map { Item.Yt(it) }, alwaysLabel = true))
                rows.add(Row("More", listOf(Item.YtAdd()), alwaysLabel = true))
                (screens["YouTube"] as RowsScreen).setRows(rows)
            }
        }
    }

    private fun openInSmartTube(uri: String, cell: CardCell?): Boolean {
        val pkg = smartTube ?: return false.also { notice("SmartTube isn't installed", "Install it to watch YouTube") }
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(pkg)
        val opts = cell?.let { ActivityOptions.makeScaleUpAnimation(it.card, 0, 0, it.card.width, it.card.height).toBundle() }
        return safeStart(i, opts).also { if (it) returning = true }
    }

    private fun youtubeSearch() {
        keyboard.open("Search YouTube", doneText = "Search") { q ->
            if (q.isBlank()) return@open
            if (!openInSmartTube("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(q.trim()), null)) return@open
        }
    }

    private fun youtubeAdd() {
        keyboard.open("Add a channel: type or paste its link (youtube.com/channel/UC…)", doneText = "Add") { link ->
            if (link.isBlank()) return@open
            bg.execute { val name = yt.pin(link); ui.post { if (name != null) { notice("Channel added", name); rebuildYouTube() } else notice("Couldn't find that channel", "Use a link with /channel/UC…") } }
        }
    }

    // ---------------------------------------------------------------- Videos (your own, built-in player)
    private var videoGroups: List<Pair<String, List<LocalVideo>>> = emptyList()
    private fun rebuildVideos() {
        bg.execute {
            val groups = try { VideoRepo.scan() } catch (e: Exception) { emptyList() }
            val found = try { MusicRepo.scan() } catch (e: Exception) { emptyList() }
            ui.post {
                songs = MusicRepo.builtIn(songVersion) + found
                videoGroups = groups
                val all = groups.flatMap { it.second }.associateBy { it.file.path }
                val keep = store.positions.keys.mapNotNull { all[it] }.map { Item.Vid(it) }
                val rows = ArrayList<Row>()
                rows.add(Row("Music", songs.map { Item.Track(it) }, alwaysLabel = true, poster = true, aspect = 1f))
                // every version as its own album, to play a whole set in one sound
                for (v in MusicRepo.VERSIONS.indices) rows.add(Row("AntonRetro · " + MusicRepo.VERSIONS[v] + if (v == songVersion) "  (chosen)" else "", MusicRepo.builtIn(v).map { Item.Track(it) }, alwaysLabel = true, poster = true, aspect = 1f))
                if (keep.isNotEmpty()) rows.add(Row("Keep watching", keep, alwaysLabel = true))
                for ((name, vids) in groups) rows.add(Row(name, vids.map { Item.Vid(it) }, alwaysLabel = true))
                val vs = screens["Media"] as RowsScreen
                vs.setEmptyText("Put music in RetroLauncher/Music and videos in RetroLauncher/Videos, or plug in a USB drive.")
                vs.setRows(rows)
            }
        }
    }

    // ---------------------------------------------------------------- music (Media tab)
    /** which version of AntonRetro's songs plays by default (0 MuseScore, 1 GeneralUser, 2 MIDI), remembered */
    private var songVersion: Int
        get() = getSharedPreferences("music", MODE_PRIVATE).getInt("version", 0)
        set(v) { getSharedPreferences("music", MODE_PRIVATE).edit().putInt("version", v).apply() }
    /** Now Playing's Version control: the same song, same place, in another version; remembered as the choice */
    private fun switchSongVersion(d: Int) {
        val cur = music.current ?: return
        val from = MusicRepo.versionOf(cur.asset); if (from < 0) return
        val to = ((from + d) % 3 + 3) % 3
        val list = MusicRepo.builtIn(to)
        val i = list.indexOfFirst { it.title == cur.title }.coerceAtLeast(0)
        val at = music.position
        songVersion = to
        music.play(list, i); if (at > 1000) ui.postDelayed({ music.seekTo(at) }, 300)
        nowPlayingView.showVersion(MusicRepo.VERSIONS[to])
        rebuildVideos()
        notice("Version: " + MusicRepo.VERSIONS[to], cur.title)
    }
    private var songs: List<Song> = emptyList()
    private lateinit var music: MusicPlayer
    private lateinit var nowPlayingView: NowPlaying

    private fun playSong(list: List<Song>, i: Int) {
        // the song that's already playing: just open Now Playing again, the song and the visualizer carry on
        if (::music.isInitialized && music.active && music.current?.key == list.getOrNull(i)?.key) { if (!music.playing) { if (::player.isInitialized) player.stopBackground(); music.toggle() }; openNowPlaying(); return }
        if (::player.isInitialized && player.showing) player.close(keepRadio = false)
        if (::player.isInitialized) player.stopBackground()   // one thing at a time: radio stops
        nowPlaying(null)
        music.play(list, i)
        openNowPlaying()
    }
    private fun openNowPlaying() {
        if (nowPlayingView.parent == null) root.addView(nowPlayingView, -1, -1)
        nowPlayingView.showVersion(MusicRepo.versionOf(music.current?.asset).takeIf { it >= 0 }?.let { MusicRepo.VERSIONS[it] })
        nowPlayingView.show(); dialog.bringToFront(); coverHome(true); musicChip()
    }
    /** The small "Now playing" chip by the clock while music plays with Now Playing closed. */
    private var musicChipView: TextView? = null
    /** Chips by the clock sit to its LEFT with a gap, whatever the clock's width (they once covered it). */
    /** The top-right status row: the TV/radio chip, the music chip, then the clock, each 14 dp apart. Chips are
     *  added once, in that order, and are GONE while hidden, so they never sit on top of each other or the clock. */
    private val statusRow by lazy { LinearLayout(this) }
    private fun addStatusChip(t: View, order: Int) {
        // order 0 = TV/radio, 1 = music; the clock is always last
        var at = 0
        for (i in 0 until statusRow.childCount) { val o = statusRow.getChildAt(i).getTag(R.id.status_order) as? Int ?: continue; if (o < order) at = i + 1 }
        t.setTag(R.id.status_order, order)
        statusRow.addView(t, at, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Kit.dpi(this@HomeActivity, 14f) })
    }
    private fun fadeChip(v: View?, show: Boolean) {
        v ?: return; v.animate().cancel()
        if (show) { if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }; v.animate().alpha(1f).setDuration(Kit.t(250)).start() }
        else if (v.visibility == View.VISIBLE) v.animate().alpha(0f).setDuration(Kit.t(200)).withEndAction { v.visibility = View.GONE }.start()
    }
    private fun musicChip() {
        val show = ::music.isInitialized && music.active && !(::nowPlayingView.isInitialized && nowPlayingView.showing)
        if (!show) { fadeChip(musicChipView, false); return }
        val chip = musicChipView ?: Kit.text(this, 16f, true).also { t ->
            t.setPadding(Kit.dpi(this, 14f), Kit.dpi(this, 8f), Kit.dpi(this, 14f), Kit.dpi(this, 8f))
            t.background = GlassDrawable(t, Kit.dp(this, Kit.pill(36f)))
            t.setOnClickListener { openNowPlaying() }
            t.visibility = View.GONE; addStatusChip(t, 1)
            musicChipView = t
        }
        chip.text = (if (music.playing) "♪  " else "❚❚  ") + (music.current?.title ?: "")
        fadeChip(chip, true)
    }

    private fun playVideo(v: LocalVideo) {
        quietMusic()
        if (player.parent == null) root.addView(player, -1, -1)
        player.bringToFront()
        player.playVideo(v.file.path, v.title, store.positions[v.file.path] ?: 0L) { pos, dur ->
            // remember where you stopped; forget it near the end
            if (pos > 10_000 && (dur <= 0 || pos < dur - 60_000)) { store.positions[v.file.path] = pos; store.durations[v.file.path] = dur }
            else { store.positions.remove(v.file.path); store.durations.remove(v.file.path) }
            store.save(); rebuildVideos()
        }
    }

    // ---------------------------------------------------------------- Games (the launcher is the library, RetroArch the hidden engine)
    private lateinit var dialog: Dialog
    private var roms: List<Rom> = emptyList()
    private var androidGames: List<Item.App> = emptyList()

    private fun rebuildGames() {
        bg.execute {
            val found = try { GameRepo.scan() } catch (e: Exception) { emptyList() }
            ui.post {
                roms = found
                val byKey: Map<String, Item> = found.associate { it.key to Item.Game(it) } + androidGames.associateBy { it.key }
                val rows = ArrayList<Row>()
                rows.add(Row("Built-in games", builtInGames, alwaysLabel = true))
                // retro games you played lately, as boxes (Android games stay in their own row below)
                val keep = store.recentGames.mapNotNull { byKey[it] }.filterIsInstance<Item.Game>()
                if (keep.isNotEmpty()) rows.add(Row("Keep playing", keep, alwaysLabel = true, poster = true, aspect = .86f))
                if (androidGames.isNotEmpty()) rows.add(Row("Android games", androidGames, alwaysLabel = true))
                // one shelf per console, each card the shape of that console's box
                for ((sys, list) in found.groupBy { it.system }) rows.add(Row(sys.shelf, list.map { Item.Game(it) }, alwaysLabel = true, poster = true, aspect = sys.boxAspect))
                rows.add(Row("Add more", listOf(Item.AddGames()), alwaysLabel = true))
                (screens["Games"] as RowsScreen).setRows(rows)
            }
        }
    }

    /**
     * Pressing Home in a game lands here: RetroArch is paused in the background (pause_nonactive) and this
     * panel, in our own app, offers what to do with it. No overlay and no key helper needed. Stay on Home is
     * first, so Home then OK always gets you out.
     */
    // ---------------------------------------------------------------- built-in games (our own, drawn on a Canvas)
    private val builtInGames: List<Item> by lazy { listOf(
        Item.Action("bi:tiki", "Totem Tide", "Swap the masks, stop the tide", "tiki") { openBuiltIn("tiki") },
        Item.Action("bi:gear", "Gear & Ghost", "Route the energy, wake the portal", "gear") { openBuiltIn("gear") },
        Item.Action("bi:crate", "Crate Cascade", "Stack the cargo, clear the dock", "crate") { openBuiltIn("crate") },
        Item.Action("bi:orchard", "Orchard Swap", "Line up the fruit, beat the target", "orchard") { openBuiltIn("orchard") }) }
    private var game: com.antonretro.launcher.games.PixelGame? = null

    private fun openBuiltIn(id: String) {
        if (game != null) return
        val g = when (id) { "gear" -> com.antonretro.launcher.games.GearGhost(this); "crate" -> com.antonretro.launcher.games.CrateCascade(this); "orchard" -> com.antonretro.launcher.games.OrchardSwap(this); else -> com.antonretro.launcher.games.TotemTide(this) }
        // the game draws its own title, pause and game-over screens in its own pixel style; Quit comes back here
        g.onQuit = { closeGame(); showTab("Games") }
        quietMusic()
        root.addView(g, -1, -1); dialog.bringToFront()
        game = g; coverHome(true); g.start()
        store.noteLaunch("bi:$id")
    }

    /** What the game menu over a RetroArch game asked for. */
    private fun gameAction(act: String, key: String?) {
        val rom = key?.let { itemFor(it) as? Item.Game }?.rom ?: com.antonretro.launcher.GameOverlay.lastRom(this)
        Perf.note("game action $act rom=${rom?.title}")
        when (act) {
            "leave" -> {
                gameAway = null; RetroKeys.beforeLauncher = null; ui.removeCallbacks(leaveKill); ui.postDelayed(leaveKill, 700); showTab("Games")
                // say what was really kept: the exact place only if the Guide button saved it in the last half hour
                val placed = rom?.let { r -> autoSave(r)?.lastModified()?.let { System.currentTimeMillis() - it < 30 * 60_000 } } == true
                rom?.let { notice("Game closed", if (placed) "${it.title} · your place is saved" else "${it.title} · in-game saves are kept") }
            }
            "restart" -> { RetroKeys.beforeLauncher = null; ui.postDelayed({ endEngine(); ui.postDelayed({ rom?.let { playGame(it, null) } }, 900) }, 600) }
            "settings" -> { RetroKeys.beforeLauncher = null; showTab("Settings"); settingsScreen.open("Games") }
        }
        calmKeys()
    }

    private fun closeGame() {
        val g = game ?: return
        game = null; g.release(); root.removeView(g); coverHome(false); calmKeys()
        if (dialog.showing) dialog.hide()
    }

    /** A card cover drawn from the game's own sprites: three masks big and sharp on a dark jungle gradient. */
    private fun builtInCover(key: String, w: Int, h: Int): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        if (key == "bi:orchard") {
            // Orchard Swap: warm golden-hour sky, a row of round fruit, the name on top
            c.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF7EB0DC.toInt(), 0xFF4A7838.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val cols = intArrayOf(0xFFDE3A34.toInt(), 0xFFF49228.toInt(), 0xFFF6D640.toInt(), 0xFF7AC446.toInt(), 0xFF9254C4.toInt(), 0xFF4878E2.toInt())
            val r = h / 9f
            for (i in cols.indices) {
                val x = w / 2f + (i - 2.5f) * r * 2.3f; val y = h * .72f + (if (i % 2 == 0) 0f else -r * .5f)
                p.color = 0xFF2B1408.toInt(); c.drawCircle(x, y, r + 2, p); p.color = cols[i]; c.drawCircle(x, y, r, p)
                p.color = 0x99FFFFFF.toInt(); c.drawCircle(x - r * .35f, y - r * .35f, r * .22f, p)
            }
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF4DC.toInt(); textSize = h * .17f; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER; setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt()) }
            c.drawText("Orchard Swap", w / 2f, h * .32f, tp)
            return b
        }
        if (key == "bi:crate") {
            // Crate Cascade: a sunset harbour sky, painted crates stacked like the game's pieces, the name on top
            c.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF5A2E58.toInt(), 0xFFE07A48.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            val p = android.graphics.Paint()
            val s = h / 7f
            val cols = intArrayOf(0xFF40A0BA.toInt(), 0xFFDEB240.toInt(), 0xFF9C60BA.toInt(), 0xFF62A852.toInt(), 0xFFD65640.toInt(), 0xFF466EC4.toInt())
            val stack = listOf(0 to 0, 1 to 0, 2 to 0, 4 to 0, 5 to 0, 0 to 1, 1 to 1, 4 to 1, 5 to 1, 6 to 1, 1 to 2, 5 to 2)
            val left = w / 2f - 3.5f * s
            for ((i, xy) in stack.withIndex()) {
                val x = left + xy.first * s; val y = h - (xy.second + 1) * s - s * .3f
                p.color = 0xFF2B1408.toInt(); c.drawRect(x, y, x + s, y + s, p)
                p.color = cols[i % cols.size]; c.drawRect(x + s * .1f, y + s * .1f, x + s * .9f, y + s * .9f, p)
                p.color = 0x55000000; c.drawRect(x + s * .1f, y + s * .48f, x + s * .9f, y + s * .54f, p)
            }
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF4DC.toInt(); textSize = h * .17f; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER; setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt()) }
            c.drawText("Crate Cascade", w / 2f, h * .32f, tp)
            return b
        }
        if (key == "bi:gear") {
            // Gear & Ghost: warm workshop dark, gears turning, Kiko floating, the name underneath
            c.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF3A2416.toInt(), 0xFF0E0806.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            val sharp = android.graphics.Paint().apply { isFilterBitmap = false }
            val s = (h * .5f / 16).toInt().coerceAtLeast(1)
            fun put(bm: android.graphics.Bitmap, x: Int, y: Int, sc: Int) = c.drawBitmap(bm, null, android.graphics.Rect(x, y, x + bm.width * sc, y + bm.height * sc), sharp)
            val gs = s; val gearW = 16 * gs
            put(com.antonretro.launcher.games.GearArt.gear(false, 0, stubs = false), w / 2 - gearW - gearW / 3, (h * .1f).toInt(), gs)
            put(com.antonretro.launcher.games.GearArt.gear(true, 1, stubs = false), w / 2 + gearW / 3, (h * .16f).toInt(), (gs * 3 / 4).coerceAtLeast(1))
            put(com.antonretro.launcher.games.GearArt.kiko(0), w / 2 - 6 * gs, (h * .22f).toInt(), gs)
            val t = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Kit.font(this@HomeActivity, true); textSize = h * .17f }
            c.drawText("Gear & Ghost", (w - t.measureText("Gear & Ghost")) / 2, h * .9f, t)
            return b
        }
        c.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF14424A.toInt(), 0xFF061214.toInt(), android.graphics.Shader.TileMode.CLAMP) })
        try {
            val sheet = assets.open("games/tiki/tiles.png").use { android.graphics.BitmapFactory.decodeStream(it) }
            val sharp = android.graphics.Paint().apply { isFilterBitmap = false }
            val s = (h * .42f / 14).toInt().coerceAtLeast(1); val size = 14 * s; val gap = s * 2
            val total = size * 3 + gap * 2; var x = (w - total) / 2; val y = (h * .14f).toInt()
            for (v in listOf(0, 3, 1)) { c.drawBitmap(sheet, android.graphics.Rect(v * 14, 0, v * 14 + 14, 14), android.graphics.Rect(x, y, x + size, y + size), sharp); x += size + gap }
        } catch (_: Exception) {}
        val t = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; typeface = Kit.font(this@HomeActivity, true); textSize = h * .17f }
        c.drawText("Totem Tide", (w - t.measureText("Totem Tide")) / 2, h * .9f, t)
        return b
    }

    private lateinit var gameMenu: GameMenu
    /** Ends the paused game engine (Android lets the launcher do that while it is in the background). */
    private fun endEngine() {
        // the engine must be in the background for Android to end it: try now and once more a moment later
        killEngineNow(); ui.removeCallbacks(lateKill); ui.postDelayed(lateKill, 2500)
    }
    private fun killEngineNow() = try { (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses("com.retroarch.ra32") } catch (e: Exception) { Perf.note("can't end the game engine: $e") }
    private val lateKill = Runnable { killEngineNow() }
    private val leaveKill = Runnable { endEngine() }
    /** A game is starting: any engine-ending still waiting from the last game must not end the new one. */
    private fun cancelEngineKills() { ui.removeCallbacks(lateKill); ui.removeCallbacks(leaveKill) }
    private fun engineRunning(pkg: String): Boolean = try {
        java.io.File("/proc").listFiles()?.any { f -> f.name.all { it.isDigit() } && try { java.io.File(f, "cmdline").readText().trimEnd('\u0000').startsWith(pkg) } catch (_: Exception) { false } } == true
    } catch (_: Exception) { true }
    private var gameAway: Rom? = null
    private var homeFromGame = false
    private var homePressed = false
    /** The quick panel's Recent cards: an app, a channel or a game, by key. */
    private fun openKey(key: String) {
        // also used by tools/soundcheck.sh to start each kind of sound without steering the remote
        when {
            key == "phone" -> { if (!PhoneRemote.enabled(this)) PhoneRemote.setEnabled(this, true); showPhoneCode(); return }
            key.startsWith("song:") || key == "test:song" -> { if (songs.isNotEmpty()) playSong(songs, songs.indexOfFirst { it.key == key }.coerceAtLeast(0)); return }
            key.startsWith("bi:") -> { openBuiltIn(key.removePrefix("bi:")); return }
            key == "test:tv" || key == "test:radio" -> { val l = byNumber(key == "test:radio"); if (l.isNotEmpty()) openPlayer(l, 0); return }
            key == "test:video" -> { videoGroups.firstOrNull()?.second?.firstOrNull()?.let { playVideo(it) }; return }
        }
        when (val item = itemFor(key)) {
            is Item.App -> safeStart(Intent(Intent.ACTION_MAIN).setComponent(item.component).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            is Item.Ch -> { val l = byNumber(item.ch.radio); openPlayer(l, l.indexOfFirst { it.url == item.ch.url }.coerceAtLeast(0)) }
            is Item.Game -> playGame(item.rom, null)
            // the game library may still be loading (just after a restart): open the game straight from its file
            else -> if (key.startsWith("rom:")) java.io.File(key.removePrefix("rom:")).let { f -> GameRepo.systemFor(f, f.parentFile?.name)?.let { playGame(Rom(f, it), null) } }
        }
    }

    // ---------------------------------------------------------------- the 3D game page (ui/GamePage.kt)
    private var gamePageView: com.antonretro.launcher.ui.GamePage? = null
    private var gamePageRoms: List<Rom> = emptyList()

    /** The game's saved places: RetroArch's automatic save (.state.auto, written when it quits properly) and the
     *  Guide button's save (.state, slot 0). Either can be newer. */
    private fun saveFiles(rom: Rom): List<java.io.File> {
        val dir = java.io.File(ChannelRepo.dir, "Saves/states")
        val base = rom.file.nameWithoutExtension
        return dir.walkTopDown().maxDepth(2).filter { it.isFile && (it.name == "$base.state.auto" || it.name == "$base.state") }.toList()
    }
    private fun autoSave(rom: Rom): java.io.File? = saveFiles(rom).maxByOrNull { it.lastModified() }
    /** RetroArch loads only .state.auto at start: if the Guide button's save is newer, put it there first. */
    private fun newestSaveLoads(rom: Rom) {
        val all = saveFiles(rom); val slot = all.firstOrNull { it.name.endsWith(".state") } ?: return
        val auto = all.firstOrNull { it.name.endsWith(".state.auto") }
        if (auto == null || slot.lastModified() > auto.lastModified() + 1000) try {
            slot.copyTo(java.io.File(slot.parentFile, slot.name + ".auto"), overwrite = true)
            Perf.note("continue from the Guide save of ${rom.title}")
        } catch (e: Exception) { Perf.note("can't use the Guide save: $e") }
    }

    private fun showGamePage(roms: List<Rom>, index: Int) {
        val page = gamePageView ?: com.antonretro.launcher.ui.GamePage(this).also { pg ->
            root.addView(pg, -1, -1); gamePageView = pg
            pg.onClose = { coverHome(false); calmKeys() }
            pg.wantCover = { e -> gamePageRoms.firstOrNull { it.key == e.key }?.boxArt?.let { url -> Images.load(url, 600, 800) { b -> ui.post { pg.coverArrived(e.key, b) } } } }
            pg.onOptions = { e -> gamePageRoms.firstOrNull { it.key == e.key }?.let { cardOptions("Games", Item.Game(it)) } }
            pg.onPlay = { e, fresh ->
                gamePageRoms.firstOrNull { it.key == e.key }?.let { rom ->
                    // starting over keeps the old save as a backup next to it instead of deleting it
                    if (fresh) saveFiles(rom).forEach { f -> f.renameTo(java.io.File(f.path + ".before-restart")) }
                    pg.hide(); playGame(rom, null)
                }
            }
        }
        gamePageRoms = roms
        val recent = store.recentGames
        val entries = roms.map { r ->
            val played = recent.indexOf(r.key)
            com.antonretro.launcher.ui.GamePage.Entry(r.key, r.title, r.system.shelf, r.system.colour, r.system.boxAspect,
                when { played == 0 -> "Played last"; played > 0 -> "Played recently"; else -> "" }, autoSave(r) != null, badge = r.system.badge)
        }
        dialog.bringToFront()
        coverHome(true)
        page.show(entries, index)
    }

    private fun playGame(rom: Rom, cell: CardCell?) {
        val i = GameRepo.launchIntent(this, rom)
        if (i == null) {
            dialog.show("Games need one more piece", "The game engine (RetroArch) isn't installed on this box yet. It runs hidden: you'll only ever see your games.", "OK" to {})
            return
        }
        val opts = cell?.let { ActivityOptions.makeScaleUpAnimation(it.card, 0, 0, it.card.width, it.card.height) }
        cancelEngineKills()
        if (gameAway?.key != rom.key) newestSaveLoads(rom)
        // Android won't let us see RetroArch's process, so don't guess whether it's alive: we know ourselves whether
        // this game is the one waiting paused (gameAway is cleared when you Leave)
        val same = gameAway?.key == rom.key
        var tries = 0
        fun go() {
            tries++
            Perf.note("game start ${rom.title} same=$same try=$tries")
            if (safeStart(i, opts?.toBundle())) {
                gameAway = rom; homeFromGame = false; store.noteGame(rom.key); returning = true; ui.postDelayed({ rebuildGames() }, 800)
                // if an old engine took the start and died with it, we're still in front: start it once more
                if (tries == 1) ui.postDelayed({
                    Perf.note("game open check: resumed=$resumed away=${gameAway?.title}")
                    if (resumed && game == null && gameAway?.key == rom.key) { Perf.note("game didn't open, starting again"); i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK); killEngineNow(); ui.postDelayed({ go() }, 700) }
                }, 3000)
            }
        }
        if (same) {
            // the same game, still paused in the engine: bring it back where you left it
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK; go()
        } else {
            // a different game: end whatever engine is left first (starting straight away can hand the old, dying
            // engine the new game, and nothing opens), then start it
            // and give it a brand-new task: handed the old task's leftovers, RetroArch quits itself the moment it starts
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            killEngineNow()
            ui.postDelayed({ go() }, 600)
        }
    }

    /** Looks on USB drives for games and offers to copy them into the library in one press. */
    private fun offerGamesFrom(roots: List<java.io.File>, quietIfNone: Boolean) {
        bg.execute {
            val have = roms.map { it.file.name }.toSet()
            val found = roots.flatMap { try { GameRepo.scan(it) } catch (e: Exception) { emptyList() } }.filter { it.file.name !in have }
            ui.post {
                if (found.isEmpty()) { if (!quietIfNone) notice("No new games found", "Plug in a USB drive with game files on it"); return@post }
                val kinds = found.groupBy { it.system.shelf }.entries.joinToString(", ") { "${it.value.size} ${it.key}" }
                dialog.show("Found ${found.size} game" + (if (found.size == 1) "" else "s"), "On the USB drive: $kinds. Add them to Games?",
                    "Add ${found.size} game" + (if (found.size == 1) "" else "s") to {
                        notice("Adding games…", "This takes a moment")
                        bg.execute { val n = GameRepo.copyIn(found); ui.post { notice("$n games added", "Find them in Games"); rebuildGames() } }
                    },
                    "Not now" to {})
            }
        }
    }

    private fun usbRoots(): List<java.io.File> = java.io.File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" } ?: emptyList()

    // ---------------------------------------------------------------- friendly notices: devices, drives, network
    private val inputNames = HashMap<Int, String>()
    private val inputListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) {
            val d = InputDevice.getDevice(id) ?: return
            if (d.isVirtual) return
            inputNames[id] = d.name
            val s = d.sources
            when {
                s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK -> notice("Controller connected", d.name)
                s and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE -> notice("Mouse connected", d.name)
                d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC -> notice("Keyboard connected", d.name)
            }
        }
        override fun onInputDeviceRemoved(id: Int) { inputNames.remove(id)?.let { notice("Disconnected", it) } }
        override fun onInputDeviceChanged(id: Int) {}
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_MEDIA_MOUNTED -> {
                    val path = i.data?.path?.let { java.io.File(it) }
                    notice("USB drive connected", path?.name ?: "")
                    rebuildVideos()
                    if (path != null) offerGamesFrom(listOf(path), quietIfNone = true)
                }
                Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED -> { notice("USB drive removed", ""); ui.postDelayed({ rebuildVideos() }, 800) }
                android.net.ConnectivityManager.CONNECTIVITY_ACTION -> {
                    val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    @Suppress("DEPRECATION") val up = cm.activeNetworkInfo?.isConnected == true
                    if (lastOnline != null && lastOnline != up) notice(if (up) "Back online" else "No internet connection", if (up) "Everything works again" else "TV and YouTube need the internet")
                    lastOnline = up
                }
            }
        }
    }
    private var lastOnline: Boolean? = null

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
                    val msg = if (denied) "AntonOS needs permission to read your channel list." else "No channel list yet. Put one at /sdcard/RetroLauncher/channels.m3u"
                    (screens["TV"] as RowsScreen).setEmptyText(msg); (screens["Radio"] as RowsScreen).setEmptyText(msg)
                }
                rebuildChannelRows()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 7 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) loadChannels()
    }

    // ---------------------------------------------------------------- channel numbers
    /** Gives every channel a number once: favourites first, then country by country. Numbers are kept in the
     *  layout file, so they never shuffle; channels that appear later get the next free numbers. */
    private fun assignNumbers() {
        val all = ChannelRepo.tv + ChannelRepo.radio
        if (all.isEmpty()) return
        val urls = all.map { it.url }.toSet()
        var next = (store.numbers.values.maxOrNull() ?: 0) + 1
        var changed = false
        fun give(c: Channel) { if (!store.numbers.containsKey(c.url)) { store.numbers[c.url] = next++; changed = true } }
        if (store.numbers.isEmpty()) store.favourites.mapNotNull { u -> all.firstOrNull { it.url == u } }.filter { !it.radio }.forEach { give(it) }
        for ((_, chans) in ChannelRepo.groups(ChannelRepo.tv)) chans.forEach { give(it) }
        if (ChannelRepo.radio.isNotEmpty()) { next = maxOf(next, 900); for ((_, chans) in ChannelRepo.groups(ChannelRepo.radio)) chans.forEach { give(it) } }
        if (store.numbers.keys.any { it !in urls }) { store.numbers.keys.retainAll(urls); changed = true }
        if (changed) store.save()
    }
    fun numberOf(c: Channel) = store.numbers[c.url] ?: c.number
    private fun byNumber(radio: Boolean) = (if (radio) ChannelRepo.radio else ChannelRepo.tv).filter { it.url !in store.hiddenChannels }.sortedBy { numberOf(it) }

    /** Typing a channel number: the number shows big with the channel's name, then tunes after 1.5 s or on OK. */
    private var digits = ""
    private var digitView: LinearLayout? = null
    private val tuneDigits = Runnable { finishDigits() }
    private fun digitRadio() = if (::player.isInitialized && player.showing) player.current?.radio == true else current == "Radio"

    private fun typeDigit(d: Int) {
        if (digits.length >= 4) digits = ""
        digits += d
        val n = digits.toInt()
        val c = (ChannelRepo.tv + ChannelRepo.radio).firstOrNull { numberOf(it) == n && it.url !in store.hiddenChannels }
        val box = digitView ?: LinearLayout(this).also { v ->
            v.orientation = LinearLayout.VERTICAL; v.gravity = Gravity.END
            v.setPadding(Kit.dpi(this, 26f), Kit.dpi(this, 16f), Kit.dpi(this, 26f), Kit.dpi(this, 18f))
            v.background = GlassDrawable(v, Kit.dp(this, Kit.R_L), Kit.GLASS_STRONG)
            v.addView(Kit.text(this, 64f, true)); v.addView(Kit.text(this, 20f, true, Kit.TEXT_DIM))
            root.addView(v, FrameLayout.LayoutParams(Kit.dpi(this, 380f), -2, Gravity.END).apply { rightMargin = Kit.dpi(this@HomeActivity, Kit.SAFE_X); topMargin = Kit.dpi(this@HomeActivity, 96f) })   // wide enough for 4 digits and a long name
            digitView = v
        }
        (box.getChildAt(0) as TextView).text = digits
        (box.getChildAt(1) as TextView).text = c?.name ?: if (digits.length >= 2) "No channel $digits" else ""
        box.bringToFront(); box.animate().cancel(); box.alpha = 1f; box.visibility = View.VISIBLE
        ui.removeCallbacks(tuneDigits); ui.postDelayed(tuneDigits, 1500)
    }

    private fun finishDigits() {
        ui.removeCallbacks(tuneDigits)
        val n = digits.toIntOrNull(); digits = ""
        val c = n?.let { num -> (ChannelRepo.tv + ChannelRepo.radio).firstOrNull { numberOf(it) == num && it.url !in store.hiddenChannels } }
        val box = digitView
        if (c == null) { if (n != null) (box?.getChildAt(1) as? TextView)?.text = "No channel $n"; box?.animate()?.alpha(0f)?.setStartDelay(1200)?.setDuration(Kit.t(300))?.start(); return }
        box?.animate()?.alpha(0f)?.setStartDelay(300)?.setDuration(Kit.t(300))?.start()
        val list = byNumber(c.radio)
        openPlayer(list, list.indexOfFirst { it.url == c.url }.coerceAtLeast(0))
    }

    private fun renumber(c: Channel) {
        keyboard.open("New number for ${c.name} (now ${numberOf(c)})", doneText = "Set") { t ->
            val n = t.trim().toIntOrNull()
            if (n == null || n < 1 || n > 9999) { notice("Use a number from 1 to 9999", ""); return@open }
            val other = store.numbers.entries.firstOrNull { it.value == n }?.key
            val old = numberOf(c)
            if (other != null) store.numbers[other] = old   // the two swap places
            store.numbers[c.url] = n; store.save(); rebuildChannelRows(); notice("${c.name} is now channel $n", if (other != null) "The channel that had $n is now $old" else "")
        }
    }

    private fun rebuildChannelRows() {
        assignNumbers()
        val tv = ChannelRepo.tv; val radio = ChannelRepo.radio
        val byUrl = (tv + radio).associateBy { it.url }
        fun rows(list: List<Channel>, radioTab: Boolean): List<Row> {
            val favs = store.favourites.mapNotNull { byUrl[it] }.filter { it.radio == radioTab && it.url !in store.hiddenChannels }
            val recent = store.recentChannels.mapNotNull { byUrl[it] }.filter { it.radio == radioTab }
            val out = ArrayList<Row>()
            if (!radioTab) {
                val last = store.lastTv?.let { byUrl[it] }
                out.add(Row(if (last != null) "Keep watching" else "What's on", listOf<Item>(guideItem) + listOfNotNull(last?.let { Item.Ch(it) }), alwaysLabel = true))
            }
            if (favs.isNotEmpty()) out.add(Row("Favourites", favs.map { Item.Ch(it) }, alwaysLabel = true))
            if (recent.isNotEmpty()) out.add(Row("Recent", recent.map { Item.Ch(it) }, alwaysLabel = true))
            for ((g, all) in ChannelRepo.groups(list)) {
                if (g in store.hiddenGroups) continue
                val chans = all.filter { it.url !in store.hiddenChannels }
                if (chans.isNotEmpty()) out.add(Row("$g  ·  ${chans.size}", chans.map { Item.Ch(it) }, alwaysLabel = true))
            }
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
    /** Anything can be pinned to the dock: an app, a channel or a game. */
    private fun itemFor(key: String): Item? = when {
        key.startsWith("app:") -> repo.apps.firstOrNull { it.key == key }
        key.startsWith("ch:") -> (ChannelRepo.tv + ChannelRepo.radio).firstOrNull { it.key == key }?.let { Item.Ch(it) }
        key.startsWith("rom:") -> roms.firstOrNull { it.key == key }?.let { Item.Game(it) }
        else -> null
    }

    /** Speed experiments only: words in RetroLauncher/.perf-exp switch parts of Home off for measuring. */
    private val perfExp: String by lazy { try { java.io.File(android.os.Environment.getExternalStorageDirectory(), "RetroLauncher/.perf-exp").readText() } catch (e: Exception) { "" } }

    private fun rebuildHome() {
        val dock = store.dock.mapNotNull { itemFor(it) }.ifEmpty { homeDock }
        val playing = store.recentGames.mapNotNull { itemFor(it) }.take(6)
        (screens["Home"] as RowsScreen).setRows(if ("nokeep" in perfExp) listOf(Row(null, dock, glass = "noglass" !in perfExp)) else listOf(
            Row(null, dock, glass = "noglass" !in perfExp),
            Row("Keep watching", homeExtra, alwaysLabel = true),
            Row("Keep playing", playing, alwaysLabel = true),
            Row("Recently used", homeRecent.filter { r -> dock.none { it.key == r.key } }, alwaysLabel = true)))
    }

    /** Hold OK (or press Menu) on any card: plain choices in the one dialog. */
    private fun cardOptions(tab: String, item: Item) {
        val inDock = tab == "Home" && store.dock.contains(item.key) && (screens["Home"] as RowsScreen).currentRowItems()?.any { it.key == item.key && it === (screens["Home"] as RowsScreen).currentItem() } == true
        val pinnable = item is Item.App || item is Item.Ch || item is Item.Game
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (inDock) {
            val i = store.dock.indexOf(item.key)
            if (i > 0) choices.add("Move left" to { store.dock.removeAt(i); store.dock.add(i - 1, item.key); store.save(); rebuildHome(); (screens["Home"] as RowsScreen).jumpTo(0, i - 1) })
            if (i < store.dock.size - 1) choices.add("Move right" to { store.dock.removeAt(i); store.dock.add(i + 1, item.key); store.save(); rebuildHome(); (screens["Home"] as RowsScreen).jumpTo(0, i + 1) })
            choices.add("Remove from Home" to { store.dock.remove(item.key); store.save(); rebuildHome(); notice("Removed from Home", item.title) })
        } else if (pinnable) {
            if (store.dock.contains(item.key)) choices.add("Already on Home" to {})
            else choices.add("Pin to Home" to { store.dock.add(item.key); store.save(); rebuildHome(); notice("Pinned to Home", item.title) })
        }
        if (item is Item.Ch) {
            choices.add((if (item.ch.url in store.favourites) "Remove from Favourites" else "Add to Favourites") to { star(item.ch) })
            choices.add("Change channel number (${numberOf(item.ch)})" to { renumber(item.ch) })
            choices.add("Hide this channel" to { store.hiddenChannels.add(item.ch.url); store.favourites.remove(item.ch.url); store.save(); rebuildChannelRows(); notice("Channel hidden", "Show it again in Settings, TV") })
        }
        if (item is Item.App) {
            choices.add("Hide" to { store.hidden.add(item.component.packageName); store.save(); loadApps(); notice("Hidden from Apps", "Show it again in Settings, Apps") })
            choices.add("Uninstall" to { safeStart(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + item.component.packageName))) })
        }
        if (choices.isEmpty()) return
        choices.add("Cancel" to {})
        dialog.show(item.title, if (inDock) "Arrange your Home screen" else "What would you like to do?", *choices.toTypedArray())
    }

    private fun star(c: Channel) {
        val on = store.toggleFavourite(c)
        notice(if (on) "Added to Favourites" else "Removed from Favourites", c.name)
        rebuildChannelRows()
    }

    /** The small "Now playing" chip next to the clock while radio plays in the background. */
    private var nowChip: TextView? = null
    private fun nowPlaying(c: Channel?) {
        if (c == null) { fadeChip(nowChip, false); return }
        val chip = nowChip ?: Kit.text(this, 16f, true).also { t ->
            t.setPadding(Kit.dpi(this, 14f), Kit.dpi(this, 8f), Kit.dpi(this, 14f), Kit.dpi(this, 8f))
            t.background = GlassDrawable(t, Kit.dp(this, Kit.pill(36f)))
            t.setOnClickListener { player.current?.let { openPlayer(listOf(it), 0) } }
            t.setOnLongClickListener { player.stopBackground(); nowPlaying(null); true }
            t.visibility = View.GONE; addStatusChip(t, 0)
            nowChip = t
        }
        chip.text = "♪  ${c.name}"; fadeChip(chip, true)
    }

    private fun openPlayer(list: List<Channel>, index: Int) {
        if (player.mini) {
            if (list.getOrNull(index)?.url == player.current?.url) { player.expand(); dialog.bringToFront(); return }
            player.closeMini()
        }
        quietMusic()
        if (player.parent == null) root.addView(player, -1, -1)
        player.bringToFront()
        player.play(list, index)
    }

    // ---------------------------------------------------------------- TV Guide
    private lateinit var guide: GuideView
    private val HOUR = 3600_000L
    private val guideItem = Item.Action("tv:guide", "TV Guide", "What's on now and next", "guide") { tvGuideFirst = true; openGuide(null) }

    /** Favourites first, then channels that have a guide, then the rest; each by channel number. */
    private fun guideList(): List<Channel> {
        val shown = ChannelRepo.tv.filter { it.url !in store.hiddenChannels && it.country !in store.hiddenGroups }
        val favs = store.favourites.mapNotNull { u -> shown.firstOrNull { it.url == u } }
        val rest = shown.filter { it.url !in store.favourites }.sortedBy { numberOf(it) }
        return favs + rest.filter { Guide.hasData(it.url) } + rest.filter { !Guide.hasData(it.url) }
    }

    /** While the guide covers the screen, nothing under it is drawn (the Mali-450 was spending most of each frame on it). */
    private val covered = HashMap<View, Int>()
    private fun coverHome(on: Boolean) {
        if (on) { for (v in listOf<View>(drift, bgSharp, bgBlur, dim, shelf, stage, pills, clock)) if (!covered.containsKey(v)) { covered[v] = v.visibility; v.visibility = View.INVISIBLE } }
        else { for ((v, vis) in covered) v.visibility = vis; covered.clear() }
    }

    // ---------------------------------------------------------------- the Guide: default TV view, filters, search
    private val tvPrefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }
    /** urls that failed to play, with when (a channel that works again is forgiven at once; failures fade after a week) */
    private val broken by lazy { HashMap<String, Long>().apply { tvPrefs.getStringSet("broken", emptySet())!!.forEach { e -> e.substringBefore(' ').toLongOrNull()?.let { put(e.substringAfter(' '), it) } } } }
    private fun markBroken(url: String, bad: Boolean) {
        if (bad) broken[url] = System.currentTimeMillis() else if (broken.remove(url) == null) return
        tvPrefs.edit().putStringSet("broken", broken.map { (u, t) -> "$t $u" }.toSet()).apply()
    }
    private fun isBroken(url: String) = broken[url]?.let { System.currentTimeMillis() - it < 7 * 24 * 3600_000L } == true
    /** the Guide opens by itself when you go down into the TV tab, unless Cards was chosen (remembered) */
    private var tvGuideFirst: Boolean
        get() = tvPrefs.getBoolean("guideFirst", true)
        set(v) { tvPrefs.edit().putBoolean("guideFirst", v).apply() }

    private fun guideFilters(): List<GuideView.Filter> {
        val out = ArrayList<GuideView.Filter>()
        out.add(GuideView.Filter("All") { true })
        if (store.favourites.isNotEmpty()) out.add(GuideView.Filter("★  Favourites") { it.url in store.favourites })
        if (store.recentChannels.isNotEmpty()) out.add(GuideView.Filter("Recent") { it.url in store.recentChannels })
        for ((g, _) in ChannelRepo.groups(ChannelRepo.tv.filter { it.country !in store.hiddenGroups })) out.add(GuideView.Filter(g) { it.country == g })
        return out
    }

    private fun openGuide(startUrl: String?) {
        if (guide.parent == null) root.addView(guide, -1, -1)
        guide.filters = guideFilters()
        guide.isBroken = { c -> isBroken(c.url) }
        guide.workingOnly = tvPrefs.getBoolean("workingOnly", true)
        guide.onWorkingOnly = { on -> tvPrefs.edit().putBoolean("workingOnly", on).apply() }
        guide.onSearch = { keyboard.open("Find a channel", doneText = "Find") { q -> guide.search(q) } }
        guide.onCards = { tvGuideFirst = false; guide.hide(); inTabBar = false; screens[current]?.enter(); drawTabs(); notice("Cards view", "The Guide is still the first card") }
        guide.onTop = { guide.hide(); inTabBar = true; tabIdx = tabs.indexOf(current); drawTabs() }
        val watching = player.showing && player.current?.radio == false
        guide.onHidden = { if (player.peeking) { player.unpeek(); dialog.bringToFront() } }
        guide.show(guideList(), startUrl ?: player.current?.takeIf { player.showing }?.url ?: store.lastTv)
        // what you were watching keeps playing behind the Guide, named in its corner
        val w = player.current?.takeIf { watching }
        guide.preview(w, w?.let { numberOf(it) } ?: 0, w?.let { player.nowLine?.invoke(it) })
        dialog.bringToFront()
        if (Guide.channelCount == 0) maybeRefreshGuide(0)
    }

    private fun guideLater(c: Channel, p: Prog) {
        val has = Guide.hasReminder(c.url, p.start)
        val day = if (android.text.format.DateUtils.isToday(p.start)) "" else "tomorrow "
        dialog.show(p.title, "${c.name} · ${day}${timeFmt.format(Date(p.start))}",
            (if (has) "Cancel reminder" else "Remind me") to {
                if (has) Guide.reminders.firstOrNull { it.url == c.url && it.start == p.start }?.let { Guide.removeReminder(it) }
                else Guide.addReminder(Reminder(c.url, c.name, p.start, p.title))
                notice(if (has) "Reminder cancelled" else "Reminder set", if (has) p.title else "A minute before it starts")
                guide.reload()
            },
            "Watch ${c.name} now" to { guide.hide(); val l = guideList(); openPlayer(l, l.indexOfFirst { it.url == c.url }.coerceAtLeast(0)) },
            "Cancel" to {})
    }

    private fun guideStatus(): String {
        if (Guide.refreshing) return "Updating… ${Guide.progress}"
        val at = Guide.lastUpdated()
        if (at == 0L) return "Not downloaded yet"
        val total = ChannelRepo.tv.size.takeIf { it > 0 } ?: Guide.totalChannels()
        val day = if (android.text.format.DateUtils.isToday(at)) "today" else "on " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(at))
        return "${Guide.channelCount} of $total channels have a guide · updated $day at ${timeFmt.format(Date(at))}"
    }

    /** Downloads the guide when it is older than maxAge. Never while a channel is starting. */
    private fun maybeRefreshGuide(maxAge: Long) {
        if (Guide.refreshing || ChannelRepo.tv.isEmpty()) return
        if (maxAge > 0 && System.currentTimeMillis() - Guide.lastUpdated() < maxAge) return
        if (maxAge > 0 && ::player.isInitialized && player.showing) { ui.postDelayed({ maybeRefreshGuide(maxAge) }, 10 * 60_000L); return }
        Perf.note("guide refresh start")
        Guide.refresh(ChannelRepo.tv) { result ->
            ui.post {
                Perf.note("guide $result")
                if (guide.showing) { guide.reload(); notice("Guide updated", result) }
                rebuildChannelRows()
            }
        }
    }

    /** Below this much free space, say so plainly (installs fail and apps misbehave when the box is full). */
    private val LOW_STORAGE = 200L * 1_000_000
    private var lowWarnedAt = 0L
    private fun checkStorage() {
        val free = try { android.os.StatFs(filesDir.path).availableBytes } catch (e: Exception) { return }
        if (free < LOW_STORAGE && SystemClock.uptimeMillis() - lowWarnedAt > 6 * HOUR || (lowWarnedAt == 0L && free < LOW_STORAGE)) {
            lowWarnedAt = SystemClock.uptimeMillis()
            notice("Storage almost full", "${free / 1_000_000} MB left. See Settings, Storage")
        }
    }

    /** Every 30 s: reminders due within a minute; every hour: the daily guide update. */
    private var guideTicks = 0
    private val guideTimer = object : Runnable {
        override fun run() {
            for (r in Guide.dueReminders()) {
                if (resumed) dialog.show("Starting in a minute", "${r.title} on ${r.channel}",
                    "Watch" to { if (guide.showing) guide.hide(); val l = guideList(); openPlayer(l, l.indexOfFirst { it.url == r.url }.coerceAtLeast(0)) }, "Not now" to {})
                else android.widget.Toast.makeText(this@HomeActivity, "Starting in a minute: ${r.title} on ${r.channel}", android.widget.Toast.LENGTH_LONG).show()
            }
            if (++guideTicks % 120 == 0) { maybeRefreshGuide(24 * HOUR); checkStorage() }
            if (guideTicks == 2) checkStorage()
            ui.postDelayed(this, 30_000)
        }
    }

    // ---------------------------------------------------------------- notices
    private var noticeView: TextView? = null
    private val hideNotice = Runnable { noticeView?.animate()?.translationX(Kit.dp(this, 420f))?.alpha(0f)?.setDuration(Kit.t(350))?.start() }
    /** A small glass pill top right for three seconds; never takes focus. */
    fun notice(title: String, sub: String) {
        com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.NOTICE)
        val n = noticeView ?: Kit.text(this, 17f, true).also { t ->
            t.isSingleLine = false; t.maxLines = 2
            t.setPadding(Kit.dpi(this, 22f), Kit.dpi(this, 12f), Kit.dpi(this, 22f), Kit.dpi(this, 12f))
            t.background = GlassDrawable(t, Kit.dp(this, Kit.R_L), Kit.GLASS_STRONG)
            root.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { rightMargin = Kit.dpi(this@HomeActivity, Kit.SAFE_X); topMargin = Kit.dpi(this@HomeActivity, 92f) })
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
        if (store.startupSound) try {
            android.media.MediaPlayer.create(this, R.raw.startup_chime)?.apply { setVolume(.8f, .8f); setOnCompletionListener { it.release() }; start() }
        } catch (_: Exception) {}
        val home = screens["Home"] as RowsScreen
        val bar = tabBar.parent as View
        val parts = listOf(bar, clock) + home.introViews()
        val mark = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ImageView(this@HomeActivity).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(Kit.dpi(this@HomeActivity, 84f), Kit.dpi(this@HomeActivity, 84f)))
            addView(Kit.text(this@HomeActivity, 34f, true).apply { setText(R.string.app_name) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(this@HomeActivity, 18f) })
            addView(Kit.text(this@HomeActivity, 16f, false, Kit.TEXT_DIM).apply { text = "by AntonRetro" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dpi(this@HomeActivity, 4f) })
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
            ui.postDelayed({ endIntro(black, mark, parts) }, ((620 + parts.size * 60L + 450) * Kit.motion).toLong())   // timers are not scaled by Android, animations are
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
        if (store.dock.isEmpty()) store.dock = dock.map { it.key }.toMutableList()
        homeDock = dock; homeRecent = recent; rebuildHome()
        val ordered = visible.sortedWith(compareByDescending<Item.App> { store.launches[it.key] ?: 0 }.thenBy { it.title.lowercase() })
        (screens["Apps"] as RowsScreen).setRows(ordered.chunked(5).mapIndexed { i, c -> Row(if (i == 0) "All apps" else null, c, alwaysLabel = true) })
        androidGames = visible.filter { it.isGame }
        rebuildGames()
        smartTube = all.firstOrNull { it.component.packageName in AppRepo.YOUTUBE_APPS }?.component?.packageName
        updateShelf()
    }

    // ---------------------------------------------------------------- art
    private fun bindArt(item: Item, cell: CardCell) {
        artCache[item.key]?.let { cell.card.art = it; return }
        cell.tag = item.key
        if (item is Item.Ch) { bindChannelArt(item.ch, cell); return }
        if (item is Item.Vid) {
            val pos = store.positions[item.v.file.path]; val dur = store.durations[item.v.file.path]
            val badge = if (pos != null && dur != null && dur > 0) "${(pos * 100 / dur)}% WATCHED" else null
            cell.card.art = Art(null, 0xFF2B2F3A.toInt(), 0xFF101218.toInt(), null, null, bigText = item.v.title, badge = badge, badgeColor = 0xFF4F8CFF.toInt())
            val w = Kit.dpi(this, cell.wDp); val h = Kit.dpi(this, cell.hDp)
            bg.execute { val b = VideoRepo.thumb(item.v, cacheDir, w, h); ui.post { if (b != null && cell.tag == item.key) cell.card.art = Art(b, 0xFF111111.toInt(), 0xFF000000.toInt(), badge = badge, badgeColor = 0xFF4F8CFF.toInt()) } }
            return
        }
        if (item is Item.Game) {
            val s = item.rom.system
            val dark = Color.rgb(Color.red(s.colour) / 4, Color.green(s.colour) / 4, Color.blue(s.colour) / 4)
            cell.card.art = Art(null, s.colour, dark, null, null, bigText = item.rom.title, badge = s.badge, badgeColor = 0x99000000.toInt())
            val w = Kit.dpi(this, cell.wDp); val h = Kit.dpi(this, cell.hDp)
            // your own cover first (RetroLauncher/Covers/<game file name>.png or .jpg), then the box art matched by the
            // game's exact catalogue name; no match shows the title card above, never another game's cover
            val own = listOf("png", "jpg", "jpeg").map { java.io.File(android.os.Environment.getExternalStorageDirectory(), "RetroLauncher/Covers/${item.rom.file.nameWithoutExtension}.$it") }.firstOrNull { it.isFile }
            if (own != null) bg.execute {
                val b = try { android.graphics.BitmapFactory.decodeFile(own.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }) } catch (e: Exception) { null }
                ui.post { if (b != null && cell.tag == item.key) cell.card.art = Art(b, 0, 0, fit = true, cover = true) }
            } else Images.load(item.rom.boxArt, w, h) { b -> if (b != null && cell.tag == item.key) cell.card.art = Art(b, 0, 0, fit = true, cover = true) }
            return
        }
        if (item is Item.Track) {
            val w = Kit.dpi(this, cell.wDp); val h = Kit.dpi(this, cell.hDp)
            cell.card.art = Art(NowPlaying.coverFor(item.s, minOf(w, h)), 0xFF111111.toInt(), 0xFF000000.toInt(), fit = true, cover = true)
            bg.execute { val b = MusicRepo.cover(item.s, minOf(w, h)); if (b != null) ui.post { if (cell.tag == item.key) cell.card.art = Art(b, 0xFF111111.toInt(), 0xFF000000.toInt(), fit = true, cover = true) } }
            return
        }
        if (item.key.startsWith("bi:")) {
            // your own box art for a built-in game: RetroLauncher/Covers/<game name>.png or .jpg (e.g. "Totem Tide.png")
            val own = listOf("png", "jpg", "jpeg").map { java.io.File(android.os.Environment.getExternalStorageDirectory(), "RetroLauncher/Covers/${item.title}.$it") }.firstOrNull { it.isFile }
            if (own != null) {
                cell.card.art = Art(null, 0xFF0C2228.toInt(), 0xFF061012.toInt())
                bg.execute {
                    val bm = try { android.graphics.BitmapFactory.decodeFile(own.path) } catch (e: Exception) { null }
                    ui.post { if (bm != null && cell.tag == item.key) cell.card.art = Art(bm, 0, 0, fit = true, cover = true) }
                }
                return
            }
            cell.card.art = artCache.getOrPut(item.key) { Art(builtInCover(item.key, Kit.dpi(this, cell.wDp), Kit.dpi(this, cell.hDp)), 0xFF0C2228.toInt(), 0xFF061012.toInt()) }; return }
        if (item.key == "tv:guide") { cell.card.art = Art(null, 0xFF3B6FD9.toInt(), 0xFF121A33.toInt(), null, null, bigText = "TV Guide", badge = if (Guide.channelCount > 0) "${Guide.channelCount} CHANNELS" else null, badgeColor = 0x99000000.toInt()); return }
        if (item is Item.AddGames) { cell.card.art = Art(null, 0xFF3A3F55.toInt(), 0xFF15171F.toInt(), null, null, bigText = "+  Add games from a USB drive"); return }
        if (item is Item.YtSearch) { cell.card.art = Art(null, 0xFFE5383B.toInt(), 0xFF6A040F.toInt(), null, null, bigText = "Search YouTube"); return }
        if (item is Item.YtAdd) { cell.card.art = Art(null, 0xFF3A3F55.toInt(), 0xFF15171F.toInt(), null, null, bigText = "+  Add a channel"); return }
        if (item is Item.Yt) {
            cell.card.art = Art(null, 0xFF2B2F3A.toInt(), 0xFF101218.toInt(), null, null, bigText = item.v.title)
            Images.load(item.v.thumb, Kit.dpi(this, cell.wDp), Kit.dpi(this, cell.hDp)) { b -> if (b != null && cell.tag == item.key) cell.card.art = Art(b, 0xFF111111.toInt(), 0xFF000000.toInt()) }
            return
        }
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
        val badge = numberOf(c).toString() + (if (c.radio) "  RADIO" else "  LIVE"); val bc = if (c.radio) 0xFF5E60CE.toInt() else 0xFFE5383B.toInt()
        cell.card.art = Art(null, top, bottom, null, null, bigText = c.name, badge = badge, badgeColor = bc, star = star)
        val logo = c.logo ?: return
        val w = Kit.dpi(this, cell.wDp); val h = Kit.dpi(this, cell.hDp)
        Images.load(logo, w, h) { b ->
            if (b != null && cell.tag == c.key) cell.card.art = Art(null, 0xFF2B2F3A.toInt(), 0xFF101218.toInt(), b, null, badge = badge, badgeColor = bc, star = star)
        }
    }

    // ---------------------------------------------------------------- top shelf + backdrop
    private var shelfKey: String? = null
    private var shelfTitle: TextView? = null
    private var shelfSub: TextView? = null

    /**
     * The big title above the dock. One title and one line under it, created once: on a move the text dips out
     * (90 ms), changes and comes back (220 ms). No new layers per move: stacking cross-fading layers cost the
     * Mali-450 most of each frame when moving quickly.
     */
    private fun updateShelf(force: Boolean = false) {
        val s = screens[current] ?: return
        val item = if (s.usesShelf) s.shelfItem() else null
        if (!force && item?.key == shelfKey) return
        shelfKey = item?.key
        if ("noshelf" in perfExp) return
        val title = shelfTitle ?: Kit.text(this, Kit.SHELF_TITLE, true).also { t ->
            Kit.shadowText(t)
            val sub = Kit.text(this, Kit.SHELF_SUB, false, 0xD9FFFFFF.toInt()).apply { Kit.shadowText(this) }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t, LinearLayout.LayoutParams(-1, -2)); addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dpi(this@HomeActivity, 9f) }) }
            shelf.addView(box, FrameLayout.LayoutParams(Kit.dpi(this, 760f), -2, Gravity.BOTTOM).apply { leftMargin = Kit.dpi(this@HomeActivity, Kit.SAFE_X); bottomMargin = Kit.dpi(this@HomeActivity, 50f) })
            shelfTitle = t; shelfSub = sub
        }
        val sub = shelfSub!!
        val newTitle = item?.title ?: ""; val newSub = when (item) { null -> ""; is Item.Ch -> "Channel ${numberOf(item.ch)}  ·  Press OK to watch"; is Item.Game -> "Press OK to play"; else -> "Press OK to open" }
        for (v in listOf(title, sub)) v.animate().cancel()
        title.animate().alpha(0f).translationX(-Kit.dp(this, 8f)).setDuration(Kit.t(90)).withEndAction {
            title.text = newTitle; sub.text = newSub
            title.translationX = Kit.dp(this, 10f); sub.translationX = Kit.dp(this, 10f)
            title.animate().alpha(1f).translationX(0f).setDuration(Kit.t(220)).setInterpolator(Kit.EASE).start()
            sub.animate().alpha(1f).translationX(0f).setStartDelay(Kit.t(40)).setDuration(Kit.t(220)).setInterpolator(Kit.EASE).start()
        }.start()
        sub.animate().alpha(0f).setDuration(Kit.t(90)).start()
    }

    private fun updateScrolled() {
        val want = screens[current]?.scrolled() == true
        if (want == scrolledShown) return
        scrolledShown = want
        val d = if ("nofade" in Kit.perfFlags) 0L else Kit.t(130)
        if ("noshelf" in Kit.perfFlags) shelf.visibility = View.GONE
        // only one full-screen picture is drawn once the fade ends (the GPU's biggest cost)
        if (!want) { bgSharp.visibility = View.VISIBLE }
        bgBlur.animate().alpha(if (want) 1f else 0f).setDuration(d).withEndAction { if (scrolledShown) bgSharp.visibility = View.INVISIBLE }.start()
        shelf.animate().alpha(if (want) 0f else 1f).setDuration(d).withLayer().start()
    }

    // ---------------------------------------------------------------- tabs
    private fun showTab(name: String, animate: Boolean = true) {
        ui.post { updateHints() }
        if (name == "TV") maybeRefreshGuide(12 * HOUR)
        val t0 = SystemClock.uptimeMillis()
        val old = screens[current]; val new = screens[name] ?: return
        val dir = if (tabs.indexOf(name) >= tabs.indexOf(current)) 1 else -1
        if (old != null && old !== new) {
            old.leave()
            // the old tab leaves at once; only the new one moves (half the drawing per frame)
            old.view.animate().cancel(); old.view.visibility = View.GONE
        }
        (old as? RowsScreen)?.let { if (it !== new) it.layers = false }
        (new as? RowsScreen)?.layers = true
        current = name; tabIdx = tabs.indexOf(name)
        Perf.note("state tab=$name")
        // pick up games and videos added since (copied over USB or ADB) every time the tab opens
        if (name == "Games" && old !== new) rebuildGames()
        if (name == "Media" && old !== new) rebuildVideos()
        new.view.visibility = View.VISIBLE
        if (animate && old !== new) { new.view.alpha = 0f; new.view.translationX = dir * Kit.dp(this, 40f); new.view.animate().alpha(1f).translationX(0f).setDuration(Kit.t(Kit.T_TAB)).setInterpolator(Kit.EASE).start() }
        else { new.view.alpha = 1f; new.view.translationX = 0f }
        if (!inTabBar) new.enter()
        drawTabs(); updateShelf(); updateScrolled()
        Perf.afterFrame("tab", t0)
    }

    // ---------------------------------------------------------------- the playing cards by the clock, with the remote
    /** -1 = not on the cards; else which card (TV/radio first, then music) has focus */
    private var chipFocus = -1
    private fun playingChips(): List<TextView> = listOfNotNull(nowChip, musicChipView).filter { it.visibility == View.VISIBLE }
    private fun paintChips() {
        for ((i, c) in listOfNotNull(nowChip, musicChipView).withIndex()) {
            val on = chipFocus >= 0 && playingChips().getOrNull(chipFocus) === c
            c.background = if (on) android.graphics.drawable.GradientDrawable().apply { cornerRadius = Kit.dp(this@HomeActivity, Kit.pill(36f)); setColor(Kit.FOCUS_FILL) } else GlassDrawable(c, Kit.dp(this, Kit.pill(36f)))
            c.setTextColor(if (on) Kit.FOCUS_TEXT else Kit.TEXT)
            c.animate().scaleX(if (on) 1.06f else 1f).scaleY(if (on) 1.06f else 1f).setDuration(Kit.t(140)).start()
        }
    }
    private fun leaveChips() { chipFocus = -1; paintChips(); ui.post { updateHints() } }
    /** OK opens what plays (Now Playing, or the channel full screen); holding OK stops it. */
    private var chipHeldFired = false
    /** held for 0.6 s: stop what plays (timed, so it works however the remote repeats its keys) */
    private val chipHold = Runnable {
        if (chipFocus < 0) return@Runnable
        val c = playingChips().getOrNull(chipFocus) ?: return@Runnable
        chipHeldFired = true
        if (c === musicChipView) { music.stop(); musicChip(); notice("Music stopped", "") } else { player.stopBackground(); nowPlaying(null); notice("Stopped", "") }
        leaveChips(); drawTabs()
    }
    private fun chipKey(e: KeyEvent): Boolean {
        val chips = playingChips()
        if (chips.isEmpty()) { leaveChips(); return false }
        val c = chips.getOrNull(chipFocus) ?: chips[0]
        val isMusic = c === musicChipView
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_BUTTON_A
        if (ok) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { chipHeldFired = false; ui.removeCallbacks(chipHold); ui.postDelayed(chipHold, 600) }
            if (e.action == KeyEvent.ACTION_UP) ui.removeCallbacks(chipHold)
            if (false) {
                chipHeldFired = true
                if (isMusic) { music.stop(); musicChip(); notice("Music stopped", "") } else { player.stopBackground(); nowPlaying(null); notice("Stopped", "") }
                leaveChips(); drawTabs()
            }
            if (e.action == KeyEvent.ACTION_UP && !chipHeldFired) {
                leaveChips(); inTabBar = false; drawTabs()
                if (isMusic) openNowPlaying() else player.current?.let { if (player.mini) player.expand() else openPlayer(listOf(it), 0) }
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (chipFocus > 0) { chipFocus--; paintChips() } else { leaveChips(); drawTabs() } }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (chipFocus < chips.size - 1) { chipFocus++; paintChips() } else com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.BUMP) }
            KeyEvent.KEYCODE_DPAD_DOWN -> { leaveChips(); inTabBar = false; screens[current]?.enter(); drawTabs() }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP -> { leaveChips(); drawTabs() }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- controllers coming and going
    /** until when "press A on the controller that should be Player 1" is waiting (0 = not) */
    private var waitingForPad = 0L
    private val padNames = HashMap<Int, Pair<String, Int>>()   // device id -> (name, player) while connected
    private fun watchPads() {
        for (d in com.antonretro.launcher.kit.Pads.connected()) padNames[d.id] = com.antonretro.launcher.kit.Pads.nameOf(d) to com.antonretro.launcher.kit.Pads.playerOf(d)
        (getSystemService(INPUT_SERVICE) as android.hardware.input.InputManager).registerInputDeviceListener(object : android.hardware.input.InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) {
                val d = InputDevice.getDevice(id) ?: return
                if (!com.antonretro.launcher.kit.Pads.isPad(d)) return
                val p = com.antonretro.launcher.kit.Pads.playerOf(d); padNames[id] = com.antonretro.launcher.kit.Pads.nameOf(d) to p
                notice(com.antonretro.launcher.kit.Pads.nameOf(d) + " connected", "Player $p")
                if (current == "Settings") settingsScreen.refresh()
            }
            override fun onInputDeviceRemoved(id: Int) {
                val (name, p) = padNames.remove(id) ?: return
                // a built-in game waits for its player instead of carrying on without them
                game?.let { g -> if (g.screen == com.antonretro.launcher.games.PixelGame.Screen.PLAY) g.pauseGame() }
                notice("Player $p's controller disconnected", name)
                if (current == "Settings") settingsScreen.refresh()
            }
            override fun onInputDeviceChanged(id: Int) {}
        }, ui)
    }

    // ---------------------------------------------------------------- the button-hint bar (kit/HintBar.kt)
    private val hintBar by lazy { com.antonretro.launcher.kit.HintBar(this) }
    /** What the buttons do on the screen in front, in the glyphs of the last thing used. */
    private fun updateHints() {
        if (!::player.isInitialized) return
        val h: List<Pair<com.antonretro.launcher.kit.HintBar.Btn, String>> = when {
            overlayOpen() || (::nowPlayingView.isInitialized && nowPlayingView.showing) -> emptyList()
            chipFocus >= 0 -> listOf(com.antonretro.launcher.kit.HintBar.Btn.OK to "Open", com.antonretro.launcher.kit.HintBar.Btn.HOLD_OK to "Stop", com.antonretro.launcher.kit.HintBar.Btn.LR to "Move")
            inTabBar -> listOf(com.antonretro.launcher.kit.HintBar.Btn.LR to "Tabs", com.antonretro.launcher.kit.HintBar.Btn.UD to "Into ${current}", com.antonretro.launcher.kit.HintBar.Btn.OK to "Open")
            current == "Settings" -> listOf(com.antonretro.launcher.kit.HintBar.Btn.OK to "Change", com.antonretro.launcher.kit.HintBar.Btn.UD to "Move", com.antonretro.launcher.kit.HintBar.Btn.BACK to "Back")
            current == "TV" -> listOf(com.antonretro.launcher.kit.HintBar.Btn.OK to "Watch", com.antonretro.launcher.kit.HintBar.Btn.HOLD_OK to "Options", com.antonretro.launcher.kit.HintBar.Btn.BACK to "Tabs")
            current == "Games" -> listOf(com.antonretro.launcher.kit.HintBar.Btn.OK to "Game page", com.antonretro.launcher.kit.HintBar.Btn.HOLD_OK to "Options", com.antonretro.launcher.kit.HintBar.Btn.BACK to "Tabs")
            else -> listOf(com.antonretro.launcher.kit.HintBar.Btn.OK to "Open", com.antonretro.launcher.kit.HintBar.Btn.HOLD_OK to "Options", com.antonretro.launcher.kit.HintBar.Btn.BACK to "Tabs")
        }
        hintBar.set(h); hintBar.bringToFront()
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
    /** After a game opens, closes or shows its menu, keys are ignored briefly, and a button release only counts
     *  if its press was seen here: the press that chose "Leave" must not land as OK on the game's card and
     *  start it again (that sent Anthony straight back into the game). */
    private var keysCalmUntil = 0L
    private var gamePausedAt = 0L
    private fun calmKeys(ms: Long = 400) { keysCalmUntil = SystemClock.uptimeMillis() + ms; downAt = 0L; longFired = false }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (e.action == KeyEvent.ACTION_DOWN) {
            hintBar.pad = e.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD) || e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)
            ui.post { updateHints() }
        }
        RetroKeys.launcherKeyAt = e.eventTime   // the helper's watchdog checks keys really arrive here
        // test mode (file RetroLauncher/.test-mute, used by tools/hometest.sh): note the state after every key
        if (e.action == KeyEvent.ACTION_UP && testMode) ui.postDelayed({ noteState("key${e.keyCode}") }, 600)
        if (::saver.isInitialized && saver.showing) { if (e.action == KeyEvent.ACTION_UP) stopSaver(); return true }   // any key just wakes it
        if (introRunning) { if (e.action == KeyEvent.ACTION_UP) introSkip?.invoke(); return true }
        // the controller's Guide/Xbox button = Home, from anywhere (before any panel can swallow it)
        if (SystemClock.uptimeMillis() < keysCalmUntil) return true
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) goHome(); return true }
        if (::dialog.isInitialized && dialog.showing) return dialog.onKey(e)
        if (waitingForPad > SystemClock.uptimeMillis() && e.keyCode == KeyEvent.KEYCODE_BUTTON_A && com.antonretro.launcher.kit.Pads.isPad(e.device)) {
            if (e.action == KeyEvent.ACTION_UP) { waitingForPad = 0L; com.antonretro.launcher.kit.Pads.makeFirst(e.device.descriptor); notice(com.antonretro.launcher.kit.Pads.nameOf(e.device) + " is Player 1", ""); if (current == "Settings") settingsScreen.refresh() }
            return true
        }
        gamePageView?.let { if (it.showing) return it.onKey(e) }
        if (chipFocus >= 0 && inTabBar && !overlayOpen()) return chipKey(e)
        if (::player.isInitialized && player.mini && (e.keyCode == KeyEvent.KEYCODE_MENU || e.keyCode == KeyEvent.KEYCODE_BUTTON_Y || e.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { player.expand(); dialog.bringToFront() }
            return true
        }
        // channel numbers: digits in the TV or Radio tab, or while watching
        val digit = when (e.keyCode) { in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> e.keyCode - KeyEvent.KEYCODE_0; in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> e.keyCode - KeyEvent.KEYCODE_NUMPAD_0; else -> -1 }
        val inChannels = (::player.isInitialized && player.showing && player.current != null) || ((current == "TV" || current == "Radio") && !(::keyboard.isInitialized && keyboard.showing))
        if (digit >= 0 && inChannels) { if (e.action == KeyEvent.ACTION_DOWN) typeDigit(digit); return true }
        if (digits.isNotEmpty() && e.action == KeyEvent.ACTION_UP && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) { finishDigits(); return true }
        if (digits.isNotEmpty() && (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) return true
        if (::keyboard.isInitialized && keyboard.showing) return keyboard.onKey(e)
        if (::gameMenu.isInitialized && gameMenu.showing) return gameMenu.onKey(e)
        if (::nowPlayingView.isInitialized && nowPlayingView.showing) return nowPlayingView.onKey(e)
        if (::music.isInitialized && music.active && e.action == KeyEvent.ACTION_DOWN && game == null && !(::player.isInitialized && player.showing)) when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { music.toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { music.next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { music.previous(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { music.stop(); return true }
        }
        game?.let { g -> if (!(::keyboard.isInitialized && keyboard.showing)) { g.key(e); return true } }
        if (::guide.isInitialized && guide.showing && guide.alpha > 0f) return guide.onKey(e)
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
            } else if (e.action == KeyEvent.ACTION_UP && !longFired && downAt != 0L) { downAt = 0L; onOk() }
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
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> { com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.BACK); onBackKey(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_START -> { if (!inTabBar) screens[current]?.longOk(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun move(dr: Int, dc: Int) {
        if (inTabBar) {
            if (dc > 0 && nextVisibleTab(tabIdx, dc) == tabIdx && playingChips().isNotEmpty()) { chipFocus = 0; paintChips(); com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.MOVE2); ui.post { updateHints() }; return }
            if (dc != 0) { val n = nextVisibleTab(tabIdx, dc); if (n != tabIdx) { tabIdx = n; showTab(tabs[n]); com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.TAB) } else com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.BUMP) }
            if (dr > 0) {
                if (current == "TV" && tvGuideFirst && ChannelRepo.tv.isNotEmpty()) { inTabBar = false; drawTabs(); openGuide(null); guide.focusBar(); return }
                inTabBar = false; screens[current]?.enter(); drawTabs()
            }
            return
        }
        val s = screens[current] ?: return
        val moved = s.move(dr, dc)
        if (moved) com.antonretro.launcher.kit.UiSound.play(if (dr > 0 || dc > 0) com.antonretro.launcher.kit.UiSound.Kind.MOVE2 else com.antonretro.launcher.kit.UiSound.Kind.MOVE)
        else if (dr < 0) { inTabBar = true; tabIdx = tabs.indexOf(current); s.leave(); drawTabs(); updateShelf(); com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.MOVE) }
        else com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.BUMP)
    }

    private fun switchTab(d: Int) { val n = nextVisibleTab(tabs.indexOf(current), d); showTab(tabs[n]) }

    private fun onOk() {
        com.antonretro.launcher.kit.UiSound.play(com.antonretro.launcher.kit.UiSound.Kind.SELECT)
        if (inTabBar) { inTabBar = false; screens[current]?.enter(); drawTabs(); return }
        screens[current]?.ok()
    }

    /**
     * The one way back to Home (Home key, Guide button, mouse): closes whatever is open (player, dialog,
     * keyboard, screensaver, a Settings page) and lands on the first card of the Home tab. Radio keeps playing.
     */
    fun goHome() {
        if (introRunning) introSkip?.invoke()
        if (::saver.isInitialized && saver.showing) stopSaver()
        if (::guide.isInitialized && guide.showing) guide.hide()
        if (::nowPlayingView.isInitialized && nowPlayingView.showing) { nowPlayingView.hide(); coverHome(false); musicChip() }
        closeGame()
        if (::player.isInitialized && player.showing) player.close(keepRadio = true)
        if (::dialog.isInitialized && dialog.showing) dialog.hide()
        if (::keyboard.isInitialized && keyboard.showing) keyboard.close()
        if (returning || stage.alpha < 1f) { stage.animate().cancel(); stage.alpha = 1f; stage.scaleX = 1f; stage.scaleY = 1f }
        if (current == "Settings") (screens["Settings"] as? SettingsScreen)?.reset()   // only rebuild Settings if it's the tab being left
        inTabBar = false
        if (current != "Home") showTab("Home")
        screens["Home"]?.reset(); screens["Home"]?.enter()
        drawTabs()
        ui.postDelayed({ noteState("afterHome") }, 400)
    }

    /** For the Home/Back checklist test: what is on screen right now. */
    private fun noteState(why: String) = Perf.note("state $why tab=$current tabbar=$inTabBar player=${::player.isInitialized && player.showing} dialog=${dialog.showing} keyboard=${keyboard.showing}")

    private fun onBackKey() {
        if (inTabBar && ::player.isInitialized && player.mini) { player.closeMini(); notice("Stopped", "") ; return }
        // Back goes one step up. At the top of any tab it goes to the Home tab; on Home it does nothing.
        if (inTabBar) { if (current != "Home") goHome() else { inTabBar = false; screens["Home"]?.enter(); drawTabs() }; return }
        val s = screens[current] ?: return
        if (s.back()) { ui.postDelayed({ noteState("afterBack") }, 400); return }
        if (current != "Home") goHome() else ui.postDelayed({ noteState("afterBack") }, 400)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) && (ev.action == MotionEvent.ACTION_HOVER_MOVE || ev.action == MotionEvent.ACTION_SCROLL)) RetroKeys.lastMouse = lastInput
        if (::saver.isInitialized && saver.showing && ev.action != MotionEvent.ACTION_HOVER_MOVE) { stopSaver(); return true }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            // mouse wheel: up/down scrolls rows, sideways (or Shift+wheel) scrolls along a row
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL); val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val shift = e.metaState and KeyEvent.META_SHIFT_ON != 0
            // something open on top (guide, game menu, dialog, keyboard, player): the wheel goes to it, as arrow keys
            if (overlayOpen()) { if (v != 0f) arrow(if (v < 0) 1 else -1, 0) else if (h != 0f) arrow(0, if (h < 0) 1 else -1); return true }
            if (inTabBar && v != 0f) { inTabBar = false; screens[current]?.enter(); drawTabs() }
            if (h != 0f || (shift && v != 0f)) { screens[current]?.move(0, if ((if (h != 0f) h else v) < 0) 1 else -1); return true }
            if (v != 0f) { screens[current]?.move(if (v < 0) 1 else -1, 0); return true }
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
    private val stickRepeat = object : Runnable { override fun run() { if (stickDir != (0 to 0)) { arrow(stickDir.second, stickDir.first); ui.postDelayed(this, 140) } } }
    private fun stick(dx: Int, dy: Int) {
        if ((dx to dy) == stickDir) return
        stickDir = dx to dy; ui.removeCallbacks(stickRepeat)
        if (dx != 0 || dy != 0) { arrow(dy, dx); ui.postDelayed(stickRepeat, 380) }
    }

    /** The pad's D-pad/stick and the mouse wheel arrive as motion: turn them into real arrow keys, so they reach
     *  whatever is on top (the guide, the game menu, a dialog, the player) exactly like the remote does.
     *  (Before, they moved the tab underneath, and the guide looked frozen with a pad.) */
    private fun arrow(dr: Int, dc: Int) {
        val code = when { dr < 0 -> KeyEvent.KEYCODE_DPAD_UP; dr > 0 -> KeyEvent.KEYCODE_DPAD_DOWN; dc < 0 -> KeyEvent.KEYCODE_DPAD_LEFT; else -> KeyEvent.KEYCODE_DPAD_RIGHT }
        val t = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun overlayOpen() = (gamePageView?.showing == true) || game != null || (::nowPlayingView.isInitialized && nowPlayingView.showing) || (::guide.isInitialized && guide.showing) || (::gameMenu.isInitialized && gameMenu.showing) ||
        (::dialog.isInitialized && dialog.showing) || (::keyboard.isInitialized && keyboard.showing) || (::player.isInitialized && player.showing)

    // ---------------------------------------------------------------- opening things
    private fun open(item: Item, cell: CardCell) {
        Perf.log("open_" + item.javaClass.simpleName, 0)
        when (item) {
            is Item.App -> {
                val i = Intent(Intent.ACTION_MAIN).setComponent(item.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                // the card zooms into the app: no dead black pause
                val opts = ActivityOptions.makeScaleUpAnimation(cell.card, 0, 0, cell.card.width, cell.card.height)
                if (safeStart(i, opts.toBundle())) { store.noteLaunch(item.key); if (item.isGame) store.noteGame(item.key); returning = true }
            }
            is Item.Action -> item.run(this)
            is Item.Track -> {
                // a song from one of the version albums plays within that album
                val v = MusicRepo.versionOf(item.s.asset)
                val list = if (v >= 0 && v != songVersion) MusicRepo.builtIn(v) else songs
                playSong(list, list.indexOfFirst { it.key == item.key }.coerceAtLeast(0))
            }
            is Item.Ch -> {
                val s = screens[current] as? RowsScreen
                val row = s?.currentRowItems()?.filterIsInstance<Item.Ch>()?.map { it.ch } ?: listOf(item.ch)
                openPlayer(row, row.indexOfFirst { it.url == item.ch.url }.coerceAtLeast(0))
            }
            is Item.More -> {}
            is Item.Yt -> if (openInSmartTube(item.v.url, cell)) { yt.noteWatched(item.v); ui.postDelayed({ rebuildYouTube() }, 800) }
            is Item.YtSearch -> youtubeSearch()
            is Item.Vid -> playVideo(item.v)
            is Item.Game -> {
                val row = (screens[current] as? RowsScreen)?.currentRowItems()?.filterIsInstance<Item.Game>()?.map { it.rom } ?: listOf(item.rom)
                showGamePage(row, row.indexOfFirst { it.key == item.rom.key }.coerceAtLeast(0))
            }
            is Item.AddGames -> offerGamesFrom(usbRoots(), quietIfNone = false)
            is Item.YtAdd -> youtubeAdd()
        }
    }

    private fun safeStart(i: Intent, opts: Bundle? = null): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
        if (i.action?.startsWith("android.settings") != true && i.action != Intent.ACTION_DELETE) quietMusic()
        true
    } catch (e: Exception) { false }
    /** One sound at a time: whatever starts next, the music fades out and waits paused (it doesn't come back by itself). */
    private fun quietMusic() { if (::player.isInitialized && player.mini) player.closeMini(); if (::music.isInitialized) { music.fadePause(); musicChip() } }

    private fun openClassic() {
        val i = packageManager.getLaunchIntentForPackage("com.droidlogic.mboxlauncher")
            ?: Intent(Intent.ACTION_MAIN).setClassName("com.droidlogic.mboxlauncher", "com.droidlogic.mboxlauncher.Launcher")
        safeStart(i)
    }

    // wallpapers: 0 world photos (rotating), then each photo on its own, then the drawn scenes, then the moving colours
    private val wallpaperCount get() = 1 + Backdrop.PHOTOS.size + Backdrop.SCENES.size + Drift.PALETTES.size
    private fun isDrift(i: Int) = i >= 1 + Backdrop.PHOTOS.size + Backdrop.SCENES.size
    private fun wallpaperName(i: Int) = when {
        i == 0 -> "World photos"
        i <= Backdrop.PHOTOS.size -> Backdrop.PHOTOS[i - 1].place
        !isDrift(i) -> Backdrop.SCENES[i - 1 - Backdrop.PHOTOS.size].name
        else -> Drift.PALETTES[i - 1 - Backdrop.PHOTOS.size - Backdrop.SCENES.size].name
    }
    /** Builds the still picture (and its glass) for wallpaper [i]; moving colours build theirs in applyWallpaper. */
    private fun buildWall(i: Int) {
        val p = Backdrop.PHOTOS.size
        when {
            i == 0 && p > 0 -> Backdrop.buildPhoto(this, store.worldIndex, screenW, screenH)
            i in 1..p -> Backdrop.buildPhoto(this, i - 1, screenW, screenH)
            !isDrift(i) -> Backdrop.build(maxOf(0, i - 1 - p), screenW, screenH)
        }
    }
    /** World photos: the next place, cross-faded slowly (on waking, and every 30 minutes on the launcher). */
    private fun nextWorldPhoto() {
        if (store.wallpaper != 0 || Backdrop.PHOTOS.isEmpty()) return
        val old = Backdrop.sharp
        store.worldIndex = (store.worldIndex + 1) % Backdrop.PHOTOS.size; store.worldAt = System.currentTimeMillis(); store.save()
        if (!Backdrop.buildPhoto(this, store.worldIndex, screenW, screenH)) return
        val now = Backdrop.sharp
        if (old != null && now != null && bgSharp.visibility == View.VISIBLE) {
            val t = android.graphics.drawable.TransitionDrawable(arrayOf(android.graphics.drawable.BitmapDrawable(resources, old), android.graphics.drawable.BitmapDrawable(resources, now)))
            t.isCrossFadeEnabled = true; bgSharp.setImageDrawable(t); t.startTransition(2500)
            ui.postDelayed({ if (Backdrop.sharp === now) bgSharp.setImageBitmap(now) }, 2700)
        } else bgSharp.setImageBitmap(now)
        bgBlur.setImageBitmap(Backdrop.blurredDim)
        root.invalidate(); invalidateAll(root)
    }
    private val worldTimer = object : Runnable { override fun run() { if (resumed && !saver.showing && game == null) nextWorldPhoto(); ui.postDelayed(this, 30 * 60_000L) } }

    private fun nextWallpaper() = setWallpaper((store.wallpaper + 1) % wallpaperCount)

    private fun setWallpaper(i: Int) {
        store.wallpaper = i; store.save()
        if (!isDrift(i)) buildWall(i)
        applyWallpaper()
        root.invalidate(); invalidateAll(root)
    }

    /** Photo-style scene, or a moving colour wallpaper drawn by DriftView. */
    private fun applyWallpaper() {
        val i = store.wallpaper.coerceIn(0, wallpaperCount - 1)
        if (!isDrift(i)) {
            drift.stop(); drift.visibility = View.GONE
            bgSharp.visibility = if (scrolledShown) View.INVISIBLE else View.VISIBLE; bgSharp.setImageBitmap(Backdrop.sharp)
        } else {
            drift.palette = Drift.PALETTES[i - 1 - Backdrop.PHOTOS.size - Backdrop.SCENES.size]
            Backdrop.buildFromSmall(drift.snapshot(), screenW, screenH)
            bgSharp.setImageDrawable(null); bgSharp.visibility = View.GONE
            drift.visibility = View.VISIBLE; drift.start()
        }
        bgBlur.setImageBitmap(Backdrop.blurredDim)
    }

    // ---------------------------------------------------------------- screensaver (no burn-in)
    private val idleCheck = object : Runnable {
        override fun run() {
            val mins = store.saverMinutes
            if (mins > 0 && resumed && !saver.showing && !(::player.isInitialized && player.showing) && !(::guide.isInitialized && guide.showing) && game == null && !dialog.showing && !keyboard.showing &&
                SystemClock.uptimeMillis() - lastInput > mins * 60_000L) startSaver()
            ui.postDelayed(this, 15_000)
        }
    }
    private var resumed = false
    private fun startSaver() { saver.clock24 = store.clock24; saver.bringToFront(); saver.show(store.saverPhotos); drift.stop() }
    private fun stopSaver() { saver.hide(); if (isDrift(store.wallpaper)) drift.start() }
    private fun invalidateAll(v: View) { v.invalidate(); if (v is ViewGroup) for (i in 0 until v.childCount) invalidateAll(v.getChildAt(i)) }

    // ---------------------------------------------------------------- Home key, coming back
    private var returning = false
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val t0 = SystemClock.uptimeMillis()
        calmKeys()
        // from the game menu shown over a RetroArch game
        Perf.note("new intent action=${intent.action} rl_game=${intent.getStringExtra("rl_game")} rl_open=${intent.getStringExtra("rl_open")}")
        intent.getStringExtra("rl_game")?.let { act -> gameAction(act, intent.getStringExtra("rl_rom")); return }
        // Home or the Xbox button in a built-in game: its own pause menu (pressed again on the menu: leave)
        game?.let { g -> if (Intent.ACTION_MAIN == intent.action && intent.getStringExtra("rl_open") == null) {
            // Android pauses us just before delivering Home, and that pause already paused the game: that's still the first press
            val firstPress = g.screen == com.antonretro.launcher.games.PixelGame.Screen.PLAY || SystemClock.uptimeMillis() - gamePausedAt < 800
            if (firstPress) { g.pauseGame(); return } else { closeGame(); showTab("Games"); return } } }
        // Home again while the game's Home Menu is up: back to the game (like a console)
        if (Intent.ACTION_MAIN == intent.action && intent.getStringExtra("rl_open") == null && ::gameMenu.isInitialized && gameMenu.showing && gameMenu.alpha > 0f) {
            // a paused game: Home again goes back into it; a game that has closed: Home just means Home
            if (gameMenu.alive) { gameMenu.resume(); return }
            gameMenu.hide(); RetroKeys.beforeLauncher = null
        }
        if (Intent.ACTION_MAIN == intent.action) { goHome(); homePressed = intent.getStringExtra("rl_open") == null; if (gameAway != null) homeFromGame = true }   // Home always means the Home tab, from anywhere
        intent.getStringExtra("rl_open")?.let { key -> ui.postDelayed({ safely("open recent") { openKey(key) } }, 250) }
        Perf.afterFrame("home", t0)
    }

    override fun onPause() { super.onPause(); resumed = false; if (::player.isInitialized && player.mini) player.closeMini(); drift.stop(); game?.let { if (it.screen == com.antonretro.launcher.games.PixelGame.Screen.PLAY) { it.pauseGame(); gamePausedAt = SystemClock.uptimeMillis() }; it.hostPause(true) }; RetroKeys.launcherShowing = false; RetroKeys.instance?.launcherChanged() }

    override fun onResume() {
        super.onResume()
        resumed = true; lastInput = SystemClock.uptimeMillis()
        RetroKeys.launcherShowing = true; RetroKeys.instance?.launcherChanged()
        game?.hostPause(false)
        calmKeys()
        if (isDrift(store.wallpaper) && !saver.showing) drift.start()
        // world photos: waking up after half an hour or more shows a new place
        if (store.wallpaper == 0 && System.currentTimeMillis() - store.worldAt > 30 * 60_000L) ui.postDelayed({ safely("world photo") { nextWorldPhoto() } }, 400)
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        if (store.dailyWallpaper && store.wallpaperDay != day) { store.wallpaperDay = day; setWallpaper((store.wallpaper + 1) % wallpaperCount) }
        hideSystemBars()
        Kit.init(this)
        tickClock()
        // back from a game by Home (the game is paused behind us): offer the game panel. The key helper knows which
        // app was in front before us; without it, fall back to remembering the last game we started.
        // back from a game, by Home, the Xbox button, Select+Start or the game closing: the game's Home Menu.
        // The key helper knows which app was in front before us; without it, remember the last game we started.
        val pressed = homePressed
        ui.postDelayed({
            val before = RetroKeys.beforeLauncher
            val fromGame = if (RetroKeys.alive) before?.startsWith("com.retroarch") == true else homeFromGame
            val rom = gameAway ?: store.recentGames.firstOrNull()?.let { itemFor(it) as? Item.Game }?.rom
            if (fromGame) Perf.note("back from game: before=$before rom=${rom?.title}")
            if (fromGame && rom != null && !gameMenu.showing) {
                RetroKeys.beforeLauncher = null
                if (pressed) { gameMenu.show(rom, gameAlive = true); calmKeys() }    // paused behind us (helper off, or Home got through)
                else { gameAway = null; if (current != "Games") showTab("Games"); calmKeys(); notice("Game closed", rom.title) }
            }
            if (!fromGame) gameAway = null
            homeFromGame = false
        }, 450)
        homePressed = false
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
    private val timeFmt get() = SimpleDateFormat(if (store.clock24) "H:mm" else "h:mm a", Locale.getDefault())   // 12-hour "5:33 PM" unless 24-hour is chosen
    private fun tickClock() { clock.text = timeFmt.format(Date()); Unit }

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

    override fun onDestroy() {
        if (PhoneRemote.handler === phoneBox) PhoneRemote.handler = null
        try { unregisterReceiver(deviceReceiver) } catch (_: Exception) {}
        try { (getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager).unregisterInputDeviceListener(inputListener) } catch (_: Exception) {}
        if (::player.isInitialized) player.release(); try { unregisterReceiver(receiver) } catch (_: Exception) {}; bg.shutdown(); super.onDestroy() }
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
    fun note(t: String) { lines.offer("RLNote $t") }
    /** Time from an input (uptime ms) to the start of the next drawn frame. */
    fun afterFrame(what: String, since: Long) {
        Choreographer.getInstance().postFrameCallback { frameNanos -> log(what, frameNanos / 1_000_000 - since) }
    }
}
