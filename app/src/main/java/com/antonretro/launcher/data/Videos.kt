package com.antonretro.launcher.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

class LocalVideo(val file: File, val folder: String) {
    val key get() = "vid:" + file.path
    val title get() = file.nameWithoutExtension.replace('_', ' ').replace('.', ' ').trim()
}

/** Finds the user's own videos: RetroLauncher/Videos, Downloads, Movies, camera and any USB drive. */
object VideoRepo {
    private val EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mpg", "mpeg", "3gp", "wmv", "flv")
    val home get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/Videos")

    fun scan(): List<Pair<String, List<LocalVideo>>> {
        val sd = Environment.getExternalStorageDirectory()
        val places = mutableListOf("Your videos" to home, "Downloads" to File(sd, "Download"), "Movies" to File(sd, "Movies"), "Camera" to File(sd, "DCIM"))
        // USB drives and SD cards show up under /storage (and /mnt/media_rw on some boxes)
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }?.forEach { places.add("USB drive ${it.name}" to it) }
        val out = ArrayList<Pair<String, List<LocalVideo>>>()
        for ((name, dir) in places) {
            val found = ArrayList<LocalVideo>()
            walk(dir, name, found, 0)
            if (found.isNotEmpty()) out.add(name to found.sortedBy { it.title.lowercase() })
        }
        return out
    }

    private fun walk(d: File, folder: String, out: MutableList<LocalVideo>, depth: Int) {
        if (depth > 4 || out.size > 500) return
        val list = d.listFiles() ?: return
        for (f in list) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) { if (f.name != "Android") walk(f, folder, out, depth + 1) }
            else if (f.extension.lowercase() in EXT && f.length() > 200_000) out.add(LocalVideo(f, folder))
        }
    }

    /** A frame from the video as its picture, cached on disk. Runs off the UI thread. */
    fun thumb(v: LocalVideo, cacheDir: File, w: Int, h: Int): Bitmap? {
        val f = File(cacheDir, "vthumb_" + Integer.toHexString(v.file.path.hashCode()) + "_" + v.file.length() + ".jpg")
        if (f.exists()) return android.graphics.BitmapFactory.decodeFile(f.path)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(v.file.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val at = if (dur > 60_000) 30_000_000L else dur * 300L   // 30 s in, or 30% of a short clip (microseconds)
            val frame = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
            FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            scaled
        } catch (e: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }
}
