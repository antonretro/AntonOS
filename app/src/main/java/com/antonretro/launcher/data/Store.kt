package com.antonretro.launcher.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.jar.JarInputStream

/** One app in the Store: what the catalogue says, and the newest build that runs on this box. */
class StoreApp(val pkg: String, val name: String, val summary: String, val description: String, val icon: String?,
               val versionName: String, val versionCode: Long, val apkName: String, val sha256: String, val size: Long)

/**
 * The Store's catalogue: F-Droid's official repository index (open-source apps only). The index is a jar signed by
 * F-Droid: it is only used if its signature checks out and the signing certificate is F-Droid's (the fingerprint the
 * F-Droid client itself pins). Every APK is checked against the SHA-256 in that signed index before it is handed to
 * Android's installer. Only apps that install on this box are kept (minSdk, 32-bit ARM or no native code).
 */
object StoreRepo {
    private const val REPO = "https://f-droid.org/repo"
    /** F-Droid's repository signing certificate, SHA-256 (as published by F-Droid and pinned in its client) */
    private const val FDROID_CERT = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"

    /** Picked for a TV box; anything missing from the index or not compatible is simply left out. */
    val FEATURED = listOf("org.videolan.vlc", "org.jellyfin.androidtv", "org.schabi.newpipe", "net.programmierecke.radiodroid2",
        "de.danoeh.antennapod", "org.moire.ultrasonic", "com.nutomic.syncthingandroid", "org.kde.kdeconnect_tp",
        "me.zhanghai.android.files", "io.github.muntashirakon.AppManager", "org.mozilla.fennec_fdroid", "org.fdroid.fdroid")

    @Volatile var apps: List<StoreApp> = emptyList(); private set
    @Volatile var status = ""; private set

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "store.json")
    fun iconUrl(a: StoreApp) = a.icon?.let { "$REPO/icons-320/$it" }
    fun downloads(): File = File(Environment.getExternalStorageDirectory(), "RetroLauncher/Downloads").apply { mkdirs() }

    /** The catalogue saved last time (fast, offline). */
    fun loadCache(ctx: Context) {
        try {
            val a = JSONArray(cacheFile(ctx).readText())
            apps = (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                StoreApp(o.getString("p"), o.getString("n"), o.optString("s"), o.optString("d"), o.optString("i").ifEmpty { null },
                    o.optString("v"), o.optLong("c"), o.getString("a"), o.getString("h"), o.optLong("z")) } }
        } catch (_: Exception) {}
    }
    fun age(ctx: Context): Long = System.currentTimeMillis() - cacheFile(ctx).lastModified()

    /** Downloads and checks F-Droid's signed index, keeps what runs here. Runs off the UI thread. */
    fun refresh(ctx: Context, progress: (String) -> Unit): Boolean {
        val abis = Build.SUPPORTED_ABIS.toSet()
        val sdk = Build.VERSION.SDK_INT
        val c = URL("$REPO/index-v1.jar").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        try {
            progress("Downloading the catalogue…")
            val jar = JarInputStream(c.inputStream.buffered(64 * 1024), true)
            while (true) {
                val e = jar.nextJarEntry ?: break
                if (e.name != "index-v1.json") continue
                progress("Reading the catalogue…")
                val meta = HashMap<String, Array<String?>>()       // pkg -> name, summary, description, icon
                val best = HashMap<String, StoreApp>()
                val r = JsonReader(InputStreamReader(jar, Charsets.UTF_8))
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "apps" -> { r.beginArray(); while (r.hasNext()) readApp(r, meta); r.endArray() }
                    "packages" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val pkg = r.nextName(); var chosen: StoreApp? = null
                            r.beginArray()
                            while (r.hasNext()) { val v = readVersion(r, pkg, sdk, abis); if (chosen == null && v != null) chosen = v }
                            r.endArray()
                            chosen?.let { best[pkg] = it }
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
                // read to the very end: the signature is checked when the whole entry has been read
                val buf = ByteArray(8192); while (jar.read(buf) >= 0) {}
                val certs = e.codeSigners?.mapNotNull { it.signerCertPath.certificates.firstOrNull() } ?: emptyList()
                val ok = certs.any { cert -> MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) } == FDROID_CERT }
                if (!ok) { status = "The catalogue's signature didn't check out, so it wasn't used"; return false }
                val list = best.mapNotNull { (pkg, v) ->
                    val m = meta[pkg] ?: return@mapNotNull null
                    StoreApp(pkg, m[0] ?: pkg, m[1] ?: "", m[2] ?: "", m[3], v.versionName, v.versionCode, v.apkName, v.sha256, v.size)
                }.sortedBy { it.name.lowercase() }
                apps = list
                cacheFile(ctx).writeText(JSONArray(list.map { a -> JSONObject().put("p", a.pkg).put("n", a.name).put("s", a.summary).put("d", a.description.take(600))
                    .put("i", a.icon ?: "").put("v", a.versionName).put("c", a.versionCode).put("a", a.apkName).put("h", a.sha256).put("z", a.size) }).toString())
                status = "${list.size} apps"
                return true
            }
            status = "The catalogue was empty"; return false
        } catch (e: Exception) {
            status = "Couldn't reach F-Droid: " + (e.message ?: e.javaClass.simpleName); return false
        } finally { c.disconnect() }
    }

    private fun readApp(r: JsonReader, meta: HashMap<String, Array<String?>>) {
        var pkg: String? = null; var name: String? = null; var sum: String? = null; var desc: String? = null; var icon: String? = null
        var lName: String? = null; var lSum: String? = null; var lDesc: String? = null
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "packageName" -> pkg = r.nextString()
            "name" -> name = r.nextString()
            "summary" -> sum = r.nextString()
            "description" -> desc = r.nextString()
            "icon" -> icon = r.nextString()
            "localized" -> {
                r.beginObject()
                while (r.hasNext()) {
                    val lang = r.nextName()
                    if (lang == "en-US" || lang == "en-GB" || lang == "en") {
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "name" -> lName = r.nextString(); "summary" -> lSum = r.nextString(); "description" -> lDesc = r.nextString()
                            else -> r.skipValue()
                        }
                        r.endObject()
                    } else r.skipValue()
                }
                r.endObject()
            }
            else -> r.skipValue()
        }
        r.endObject()
        if (pkg != null) meta[pkg] = arrayOf(lName ?: name, lSum ?: sum, plain(lDesc ?: desc), icon)
    }

    /** One build of an app: kept if it installs on this box. */
    private fun readVersion(r: JsonReader, pkg: String, sdk: Int, abis: Set<String>): StoreApp? {
        var apk: String? = null; var hash: String? = null; var hashType = "sha256"; var min = 1; var native: List<String>? = null
        var vn = ""; var vc = 0L; var size = 0L
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "apkName" -> apk = r.nextString(); "hash" -> hash = r.nextString(); "hashType" -> hashType = r.nextString()
            "minSdkVersion" -> min = if (r.peek() == JsonToken.STRING) r.nextString().toIntOrNull() ?: 1 else r.nextInt()
            "nativecode" -> { val l = ArrayList<String>(); r.beginArray(); while (r.hasNext()) l.add(r.nextString()); r.endArray(); native = l }
            "versionName" -> vn = r.nextString(); "versionCode" -> vc = r.nextLong(); "size" -> size = r.nextLong()
            else -> r.skipValue()
        }
        r.endObject()
        if (apk == null || hash == null || hashType != "sha256" || min > sdk) return null
        if (native != null && native.none { it in abis }) return null
        return StoreApp(pkg, "", "", "", null, vn, vc, apk, hash, size)
    }

    /** The catalogue's descriptions are a little HTML: keep the words. */
    private fun plain(s: String?): String? = s?.replace(Regex("<br\\s*/?>|</p>|</li>"), "\n")?.replace(Regex("<[^>]+>"), "")
        ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&#39;", "'")
        ?.replace(Regex("\n{3,}"), "\n\n")?.trim()

    /** Downloads an app's APK and checks it against the signed catalogue's SHA-256. Returns the file, or null. */
    fun download(a: StoreApp, progress: (Int) -> Unit): File? {
        val out = File(downloads(), a.apkName)
        val c = URL("$REPO/${a.apkName}").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val total = c.contentLength.toLong().takeIf { it > 0 } ?: a.size
            var got = 0L; var last = -1
            c.inputStream.use { inp -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); got += n
                    val pc = if (total > 0) (got * 100 / total).toInt() else 0; if (pc != last) { last = pc; progress(pc) } }
            } }
            val h = md.digest().joinToString("") { "%02x".format(it) }
            if (h != a.sha256.lowercase()) { out.delete(); null } else out
        } catch (e: Exception) { out.delete(); null } finally { c.disconnect() }
    }
}
