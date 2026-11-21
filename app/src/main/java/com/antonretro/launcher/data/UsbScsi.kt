package com.antonretro.launcher.data

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The transport layer of the disc system (docs/DISCS.md): talks to a USB CD/DVD/BD drive the way the drive expects,
 * SCSI / MMC commands wrapped in USB Mass Storage "bulk-only" packets (a 31-byte command block, the data in either
 * direction, then a 13-byte status). Android doesn't mount optical drives on these boxes and apps can't open the
 * drive's device file, so AntonOS asks Android for the USB device (one "Allow" the first time) and talks to it itself.
 *
 * Robustness follows the USB Mass Storage and MMC specifications rather than particular drives:
 *  - the interface and endpoints come from the device's own descriptors, and the LUN that is an optical drive is used;
 *  - a failed command is followed by REQUEST SENSE, so callers get the drive's reason (no disc, becoming ready...);
 *  - a stalled or confused transfer gets the class's reset recovery (reset, then clear both endpoints);
 *  - a "not ready" drive is waited for while it spins up;
 *  - reads start large and shrink when a USB bridge can't move that much at once;
 *  - blocks that won't read are remembered, so a scratched disc isn't hammered.
 *
 * This layer knows nothing about discs' contents: readers (DvdReader) build on it.
 */
class UsbScsi private constructor(private val conn: UsbDeviceConnection, private val iface: UsbInterface,
                                  private val inEp: UsbEndpoint, private val outEp: UsbEndpoint, val device: UsbDevice) {
    private var tag = 1
    /** the logical unit that is the optical drive (most enclosures have one, card-reader combos several) */
    var lun = 0; private set
    /** sectors per READ, negotiated downwards when a transfer fails */
    var maxSectors = 32; private set
    private val unreadable = HashSet<Long>()

    companion object {
        private const val CBW = 0x43425355
        private const val CSW = 0x53425355

        /** A USB device that speaks SCSI over bulk-only transport (mass storage, protocol 0x50). */
        fun isMassStorage(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { i -> d.getInterface(i).let { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceProtocol == 0x50 } }

        /** Opens the device (Android must already have given permission for it) and finds its optical LUN. */
        fun open(m: UsbManager, d: UsbDevice): UsbScsi {
            val iface = (0 until d.interfaceCount).map { d.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceProtocol == 0x50 }
                ?: throw IOException("Not a USB storage device")
            var inEp: UsbEndpoint? = null; var outEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep }
            }
            val conn = m.openDevice(d) ?: throw IOException("The drive couldn't be opened")
            // force = true: if the system's own storage driver holds the drive, AntonOS takes it over until it lets go
            if (!conn.claimInterface(iface, true)) { conn.close(); throw IOException("The drive is in use") }
            val s = UsbScsi(conn, iface, inEp ?: throw IOException("No input endpoint"), outEp ?: throw IOException("No output endpoint"), d)
            s.pickLun()
            return s
        }
    }

    fun close() { try { conn.releaseInterface(iface) } catch (_: Exception) {}; conn.close() }
    /** the raw descriptor, for a native reader that drives the device itself (docs/DISCS.md) */
    val fileDescriptor: Int get() = conn.fileDescriptor

    /** The drive's reason for refusing a command: sense key, additional sense code and qualifier (MMC / SPC). */
    class Sense(val key: Int, val asc: Int, val ascq: Int) {
        val noDisc get() = key == 2 && asc == 0x3A
        val becomingReady get() = key == 2 && asc == 0x04 && (ascq == 0x01 || ascq == 0x07 || ascq == 0x08)
        val discChanged get() = key == 6 && asc == 0x28
        val mediumError get() = key == 3
        override fun toString() = "sense %X/%02X/%02X".format(key, asc, ascq)
    }
    class ScsiError(val opcode: Int, val sense: Sense?, msg: String) : IOException(msg)

    // ------------------------------------------------------------------ bulk-only transport
    private fun control(type: Int, req: Int, value: Int, index: Int, data: ByteArray?) =
        conn.controlTransfer(type, req, value, index, data, data?.size ?: 0, 2000)

    /** GET MAX LUN (a stall means one LUN, as the class says), then the first LUN whose INQUIRY says CD/DVD. */
    private fun pickLun() {
        val b = ByteArray(1)
        val max = if (control(0xA1, 0xFE, 0, iface.id, b) == 1) b[0].toInt() and 0x0F else 0
        for (l in 0..max) {
            lun = l
            val t = try { val r = ByteArray(36); command(byteArrayOf(0x12, 0, 0, 0, 36, 0), dataIn = r); r[0].toInt() and 0x1F } catch (e: IOException) { -1 }
            if (t == 5) return   // peripheral device type 5: CD/DVD
        }
        lun = 0
    }

    /** Bulk-only mass storage reset, then clear the halt on both endpoints. */
    private fun resetRecovery() {
        control(0x21, 0xFF, 0, iface.id, null)
        control(0x02, 0x01, 0, inEp.address, null)    // CLEAR_FEATURE(ENDPOINT_HALT)
        control(0x02, 0x01, 0, outEp.address, null)
    }

    /**
     * Sends one command block. Data flows from the drive into [dataIn], or from [dataOut] to the drive, or neither.
     * Returns the bytes moved. A failed command throws [ScsiError] with the drive's [Sense].
     */
    @Synchronized fun command(cdb: ByteArray, dataIn: ByteArray? = null, dataOut: ByteArray? = null, timeoutMs: Int = 10_000): Int {
        require(dataIn == null || dataOut == null)
        val len = dataIn?.size ?: dataOut?.size ?: 0
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(CBW); val t = tag++; cbw.putInt(t); cbw.putInt(len)
        cbw.put(if (dataIn != null) 0x80.toByte() else 0)
        cbw.put(lun.toByte()); cbw.put(cdb.size.toByte()); cbw.put(cdb.copyOf(16))
        if (conn.bulkTransfer(outEp, cbw.array(), 31, timeoutMs) != 31) { resetRecovery(); throw IOException("The drive didn't take the command") }
        var moved = 0
        if (dataIn != null) {
            while (moved < len) {
                val n = conn.bulkTransfer(inEp, dataIn, moved, minOf(len - moved, 64 * 1024), timeoutMs)
                if (n <= 0) { control(0x02, 0x01, 0, inEp.address, null); break }   // stalled: clear it, the status follows
                moved += n
                if (n % inEp.maxPacketSize != 0) break   // a short packet: the drive sent all it had
            }
        } else if (dataOut != null) {
            while (moved < len) {
                val n = conn.bulkTransfer(outEp, dataOut, moved, minOf(len - moved, 64 * 1024), timeoutMs)
                if (n <= 0) { control(0x02, 0x01, 0, outEp.address, null); break }
                moved += n
            }
        }
        val csw = ByteArray(13)
        var n = conn.bulkTransfer(inEp, csw, 13, timeoutMs)
        if (n != 13) { control(0x02, 0x01, 0, inEp.address, null); n = conn.bulkTransfer(inEp, csw, 13, timeoutMs) }
        val bb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (n != 13 || bb.getInt(0) != CSW || bb.getInt(4) != t) { resetRecovery(); throw IOException("The drive's reply was garbled") }
        when (csw[12].toInt()) {
            0 -> return moved
            1 -> { val s = requestSense(); throw ScsiError(cdb[0].toInt() and 0xFF, s, "Command 0x%02x refused (%s)".format(cdb[0], s)) }
            else -> { resetRecovery(); throw IOException("Phase error on command 0x%02x".format(cdb[0])) }
        }
    }

    private fun requestSense(): Sense? = try {
        val b = ByteArray(18); command(byteArrayOf(0x03, 0, 0, 0, 18, 0), dataIn = b)
        Sense(b[2].toInt() and 0x0F, b[12].toInt() and 0xFF, b[13].toInt() and 0xFF)
    } catch (e: IOException) { null }

    // ------------------------------------------------------------------ MMC commands the readers use
    /** Vendor and model, e.g. "ASUS SDRW-08D2S-U". */
    fun inquiry(): String {
        val b = ByteArray(36); command(byteArrayOf(0x12, 0, 0, 0, 36, 0), dataIn = b)
        return (String(b, 8, 8, Charsets.US_ASCII).trim() + " " + String(b, 16, 16, Charsets.US_ASCII).trim()).trim()
    }

    enum class Ready { READY, NO_DISC, NOT_READY }
    /** TEST UNIT READY, waiting up to [waitMs] while the drive spins up or after a disc change. */
    fun waitReady(waitMs: Long = 20_000): Ready {
        val until = SystemClock.uptimeMillis() + waitMs
        while (true) {
            try { command(ByteArray(6)); return Ready.READY }
            catch (e: ScsiError) {
                val s = e.sense
                if (s?.noDisc == true) return Ready.NO_DISC
                if (SystemClock.uptimeMillis() > until) return Ready.NOT_READY
                if (s == null || s.becomingReady || s.discChanged) { SystemClock.sleep(500); continue }
                return Ready.NOT_READY
            } catch (e: IOException) { if (SystemClock.uptimeMillis() > until) return Ready.NOT_READY; SystemClock.sleep(500) }
        }
    }

    /** number of 2048-byte sectors on the disc */
    fun capacity(): Long {
        val b = ByteArray(8); command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), dataIn = b)
        return (ByteBuffer.wrap(b).int.toLong() and 0xFFFFFFFFL) + 1
    }

    /**
     * Reads [count] 2048-byte sectors from [lba] into [into] (READ 10), in pieces of at most [maxSectors]. A piece that
     * fails is retried smaller; a single sector that still won't read is remembered and reported by [isUnreadable].
     */
    fun read(lba: Long, count: Int, into: ByteArray = ByteArray(count * 2048)): ByteArray {
        var done = 0
        while (done < count) {
            val n = minOf(count - done, maxSectors)
            try { read10(lba + done, n, into, done * 2048); done += n }
            catch (e: IOException) {
                val sense = (e as? ScsiError)?.sense
                if (sense?.mediumError == true && n == 1) { unreadable.add(lba + done); throw e }
                if (sense?.mediumError != true && maxSectors > 1) { maxSectors = maxOf(1, maxSectors / 2); continue }   // a bridge that can't move that much
                if (n > 1) { maxSectors = maxOf(1, n / 2); continue }
                throw e
            }
        }
        return into
    }
    fun isUnreadable(lba: Long) = lba in unreadable

    private fun read10(lba: Long, count: Int, into: ByteArray, offset: Int) {
        val c = ByteArray(10); c[0] = 0x28
        ByteBuffer.wrap(c, 2, 4).putInt(lba.toInt()); ByteBuffer.wrap(c, 7, 2).putShort(count.toShort())
        val buf = if (offset == 0 && into.size == count * 2048) into else ByteArray(count * 2048)
        command(c, dataIn = buf, timeoutMs = 20_000)
        if (buf !== into) System.arraycopy(buf, 0, into, offset, count * 2048)
    }

    class Track(val number: Int, val start: Long, val data: Boolean)
    /** The disc's tracks (READ TOC, format 0); the last entry (number 0xAA) marks where the disc ends. */
    fun toc(): List<Track> {
        val b = ByteArray(804); command(byteArrayOf(0x43, 0, 0, 0, 0, 0, 0, (b.size shr 8).toByte(), b.size.toByte(), 0), dataIn = b)
        val n = (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)) - 2
        return (0 until n / 8).map { i ->
            val o = 4 + i * 8
            Track(b[o + 2].toInt() and 0xFF, ByteBuffer.wrap(b, o + 4, 4).int.toLong() and 0xFFFFFFFFL, (b[o + 1].toInt() and 0x04) != 0)
        }
    }

    /** The current medium (GET CONFIGURATION): 0x08 CD-ROM, 0x09/0x0A CD-R/RW, 0x10 DVD-ROM, 0x11-0x2B recordable DVDs, 0x40+ BD. */
    fun profile(): Int = try {
        val b = ByteArray(8); command(byteArrayOf(0x46, 0x01, 0, 0, 0, 0, 0, 0, 8, 0), dataIn = b)
        ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
    } catch (e: IOException) { 0 }

    /** The copyright flag a DVD carries (READ DVD STRUCTURE, format 1): 0 none, else the disc reports a protection system; -1 unknown. */
    fun copyrightFlag(): Int = try {
        val b = ByteArray(8); command(byteArrayOf(0xAD.toByte(), 0, 0, 0, 0, 0, 0, 0x01, 0, 8, 0, 0), dataIn = b)
        b[4].toInt() and 0xFF
    } catch (e: IOException) { -1 }

    /** Raw audio from an audio CD (READ CD, 2352 bytes of 16-bit stereo PCM per sector). */
    fun readAudio(lba: Long, count: Int, into: ByteArray = ByteArray(count * 2352)): ByteArray {
        val c = ByteArray(12); c[0] = 0xBE.toByte(); c[1] = 0x04
        ByteBuffer.wrap(c, 2, 4).putInt(lba.toInt())
        c[6] = (count shr 16).toByte(); c[7] = (count shr 8).toByte(); c[8] = count.toByte(); c[9] = 0x10
        command(c, dataIn = into, timeoutMs = 20_000); return into
    }

    /** Asks the drive to read at about [kbPerSecond] (SET CD SPEED; 0xFFFF = as fast as it can). Quieter for films. */
    fun setSpeed(kbPerSecond: Int) = try {
        command(byteArrayOf(0xBB.toByte(), 0, (kbPerSecond shr 8).toByte(), kbPerSecond.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0)); true
    } catch (e: IOException) { false }

    /** MODE SELECT (10): sends a mode page to the drive (a data-out command). */
    fun modeSelect(page: ByteArray) {
        val c = ByteArray(10); c[0] = 0x55; c[1] = 0x10   // PF
        c[7] = (page.size shr 8).toByte(); c[8] = page.size.toByte()
        command(c, dataOut = page)
    }

    /** Opens or closes the tray (START STOP UNIT with LoEj); [lock] stops the button opening it during playback. */
    fun eject(open: Boolean) { command(byteArrayOf(0x1B, 0, 0, 0, if (open) 0x02 else 0x03, 0)) }
    fun lockTray(lock: Boolean) = try { command(byteArrayOf(0x1E, 0, 0, 0, if (lock) 1 else 0, 0)); true } catch (e: IOException) { false }
}
