package com.antonretro.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File

/** One song: a file on the box, or one of the songs that come with Retro Launcher (in the app's assets). */
class Song(val title: String, val artist: String, val album: String, val file: File?, val asset: String?) {
    val key get() = "song:" + (file?.path ?: "asset:$asset")
}

/**
 * The music library for the Media tab: Anthony's own songs that ship with Retro Launcher first, then music found in
 * RetroLauncher/Music, Music, Download and USB drives. Tags (title, artist, album) and embedded cover art are read
 * from the files; anything without them gets a tidy name from the file name and a drawn cover.
 */
object MusicRepo {
    private val EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "mid", "midi")

    /** Anthony's three songs (his own, MIT), rendered with the GeneralUser GS SoundFont by S. Christian Collins (licence in assets/music). */
    val builtIn = listOf(
        Song("Jetpack Postcard", "Anthony Micallef", "Retro Launcher", null, "music/jetpack-postcard.mp3"),
        Song("Lemonade Satellite", "Anthony Micallef", "Retro Launcher", null, "music/lemonade-satellite.mp3"),
        Song("Slap Happy Forklift", "Anthony Micallef", "Retro Launcher", null, "music/slap-happy-forklift.mp3"))

    val home get() = File(Environment.getExternalStorageDirectory(), "RetroLauncher/Music")

    /** Runs off the UI thread. */
    fun scan(): List<Song> {
        val sd = Environment.getExternalStorageDirectory()
        val places = mutableListOf(home, File(sd, "Music"), File(sd, "Download"))
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }?.forEach { places.add(it) }
        val files = ArrayList<File>()
        for (d in places) walk(d, files, 0)
        val r = MediaMetadataRetriever()
        val out = files.distinctBy { it.canonicalPath }.map { f ->
            var title: String? = null; var artist: String? = null; var album: String? = null
            if (f.extension.lowercase() !in setOf("mid", "midi")) try {
                r.setDataSource(f.path)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            } catch (_: Exception) {}
            Song(title?.takeIf { it.isNotBlank() } ?: tidy(f.nameWithoutExtension), artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", album ?: "", f, null)
        }
        try { r.release() } catch (_: Exception) {}
        return out.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.title.lowercase() }))
    }

    private fun tidy(n: String) = n.replace('_', ' ').replace(Regex("""^\d{1,3}[ .-]+"""), "").trim().ifEmpty { n }

    private fun walk(d: File, out: MutableList<File>, depth: Int) {
        if (depth > 4 || out.size > 800) return
        for (f in d.listFiles() ?: return) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) { if (f.name != "Android") walk(f, out, depth + 1) }
            else if (f.extension.lowercase() in EXT && (f.length() > 60_000 || f.extension.lowercase() in setOf("mid", "midi"))) out.add(f)
        }
    }

    /** The cover embedded in the file, sized for a card, or null. */
    fun cover(s: Song, maxPx: Int): Bitmap? {
        val f = s.file ?: return null
        return try {
            val r = MediaMetadataRetriever(); r.setDataSource(f.path)
            val bytes = r.embeddedPicture; r.release()
            bytes?.let { b ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var k = 1; while (o.outWidth / (k * 2) >= maxPx) k *= 2
                BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = k })
            }
        } catch (e: Exception) { null }
    }
}
