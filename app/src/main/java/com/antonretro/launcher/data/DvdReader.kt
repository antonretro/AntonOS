package com.antonretro.launcher.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * The reader layer of the disc system (docs/DISCS.md): a disc as numbered 2048-byte sectors, plus what the drive can
 * say about it. Everything above (the filesystems, navigation, playback, copying to the box) uses only this, so a new
 * way of reaching a disc is one more implementation here, not a change to the players:
 *  - [UsbDvdReader]: a USB drive through AntonOS's own transport ([UsbScsi]);
 *  - [ImageDvdReader]: a disc image file (.iso / .img), for discs already copied and for tests without a drive;
 *  - a native library (liblaser, or a mounted block device on boxes that mount drives) can implement it too; how is
 *    in docs/DISCS.md.
 * Names say "Dvd" but CDs and Blu-rays fit the same shape: audio CDs add [toc] and [readAudio].
 */
interface DvdReader : Closeable {
    /** shown to the user: the drive's name, or the image's file name */
    val name: String
    /** number of 2048-byte sectors */
    fun capacity(): Long
    /** Reads [count] sectors from [lba] into [into] at [offset]; throws when they can't be read. */
    fun read(lba: Long, count: Int, into: ByteArray, offset: Int = 0)
    /** the medium as the drive reports it (see [Medium]) */
    fun medium(): Medium
    /** tracks, for CDs; empty when the reader can't tell */
    fun toc(): List<UsbScsi.Track> = emptyList()
    /** 2352-byte CD audio sectors; null when this reader can't read audio */
    fun readAudio(lba: Long, count: Int): ByteArray? = null
    /** asks a drive to spin quieter while a film plays; readers without a drive ignore it */
    fun setSpeed(kbPerSecond: Int) {}
    fun eject() {}
}

/** What kind of disc it is, from the drive (profile) and, for images, from the sectors themselves. */
class Medium(val kind: Kind, val profile: Int = 0, val copyrightFlag: Int = -1) {
    enum class Kind { CD, DVD, BLURAY, UNKNOWN }
    companion object {
        fun ofProfile(p: Int, flag: Int) = Medium(when (p) { in 0x08..0x0A -> Kind.CD; in 0x10..0x2B -> Kind.DVD; in 0x40..0x5F -> Kind.BLURAY; else -> Kind.UNKNOWN }, p, flag)
    }
}

/** A USB drive through [UsbScsi]. */
class UsbDvdReader(private val scsi: UsbScsi) : DvdReader {
    override val name: String = try { scsi.inquiry() } catch (e: IOException) { "USB drive" }
    override fun capacity() = scsi.capacity()
    override fun read(lba: Long, count: Int, into: ByteArray, offset: Int) {
        if (offset == 0 && into.size == count * 2048) scsi.read(lba, count, into)
        else System.arraycopy(scsi.read(lba, count), 0, into, offset, count * 2048)
    }
    override fun medium() = Medium.ofProfile(scsi.profile(), scsi.copyrightFlag())
    override fun toc() = try { scsi.toc() } catch (e: IOException) { emptyList() }
    override fun readAudio(lba: Long, count: Int) = try { scsi.readAudio(lba, count) } catch (e: IOException) { null }
    override fun setSpeed(kbPerSecond: Int) { scsi.setSpeed(kbPerSecond) }
    override fun eject() { try { scsi.eject(true) } catch (_: IOException) {} }
    override fun close() = scsi.close()
}

/** A disc image file: plain 2048-byte sectors (.iso, .img). */
class ImageDvdReader(private val file: File) : DvdReader {
    private val raf = RandomAccessFile(file, "r")
    override val name: String = file.nameWithoutExtension
    override fun capacity() = raf.length() / 2048
    @Synchronized override fun read(lba: Long, count: Int, into: ByteArray, offset: Int) {
        if (lba < 0 || lba + count > capacity()) throw IOException("Past the end of the image")
        raf.seek(lba * 2048); raf.readFully(into, offset, count * 2048)
    }
    /** an image has no drive to ask: a UDF volume of DVD size counts as a DVD, anything else as a CD */
    override fun medium() = Medium(if (DiscProbe.hasUdf(this) && capacity() > 400_000) Medium.Kind.DVD else Medium.Kind.CD)
    override fun close() = raf.close()
}

/**
 * The first look at a disc's contents, the same for every reader: which filesystems it carries and its label.
 * Deeper reading (folders, files) is the filesystem layer's job.
 */
object DiscProbe {
    private fun sector(r: DvdReader, lba: Long): ByteArray? = try { ByteArray(2048).also { r.read(lba, 1, it) } } catch (e: IOException) { null }

    /** ISO 9660: the primary volume descriptor at sector 16 ("CD001"); its volume label */
    fun isoLabel(r: DvdReader): String? {
        val s = sector(r, 16) ?: return null
        if (s[0].toInt() != 1 || String(s, 1, 5, Charsets.US_ASCII) != "CD001") return null
        return String(s, 40, 32, Charsets.US_ASCII).trim()
    }

    /** UDF: a "BEA01" / "NSR02" or "NSR03" recognition sequence from sector 16, and an anchor at sector 256 */
    fun hasUdf(r: DvdReader): Boolean {
        for (lba in 16L..32L) {
            val s = sector(r, lba) ?: return false
            val id = String(s, 1, 5, Charsets.US_ASCII)
            if (id == "NSR02" || id == "NSR03") return true
            if (id != "BEA01" && id != "CD001" && id != "CDW02" && id != "BOOT2" && id != "TEA01") break
        }
        return false
    }

    /** The UDF logical volume's name (the label most discs show), when there is one. */
    fun udfLabel(r: DvdReader): String? {
        val anchor = sector(r, 256) ?: return null
        if ((anchor[0].toInt() and 0xFF) or ((anchor[1].toInt() and 0xFF) shl 8) != 2) return null   // tag 2: anchor volume descriptor pointer
        val mainLoc = le32(anchor, 20); val mainLen = (le32(anchor, 16) / 2048).toInt()
        for (i in 0 until minOf(mainLen, 32)) {
            val s = sector(r, mainLoc + i) ?: return null
            val tag = (s[0].toInt() and 0xFF) or ((s[1].toInt() and 0xFF) shl 8)
            if (tag == 6) return dstring(s, 84, 128)   // logical volume descriptor: its identifier
            if (tag == 8) break                         // terminator
        }
        return null
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    /** a UDF "dstring": compression id (8 or 16 bits per character), the characters, and the length in the last byte */
    private fun dstring(b: ByteArray, o: Int, size: Int): String? {
        val len = b[o + size - 1].toInt() and 0xFF; if (len < 2) return null
        return when (b[o].toInt() and 0xFF) {
            8 -> String(b, o + 1, len - 1, Charsets.ISO_8859_1)
            16 -> String(b, o + 1, len - 1, Charset.forName("UTF-16BE"))
            else -> null
        }?.trim()
    }

    /** the label to show: UDF's when present (DVDs), else ISO 9660's */
    fun label(r: DvdReader): String? = (if (hasUdf(r)) udfLabel(r) else null)?.takeIf { it.isNotBlank() } ?: isoLabel(r)?.takeIf { it.isNotBlank() }
}
