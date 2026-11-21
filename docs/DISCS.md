# Discs in AntonOS: architecture

AntonOS plays CDs and DVDs from a USB drive plugged into the box. Android on these boxes doesn't mount optical
drives and apps can't open the drive's device file, so AntonOS reaches the drive through Android's USB API (one
"Allow" the first time) and does the rest itself. This page explains how the pieces fit, so another disc format or
another way of reading a drive is an addition, not a rewrite.

## Layers

```
  Export      copy a disc's video or music to the box or a USB stick      (planned: data/DiscExport.kt)
  Playback    AntonOS's player (ExoPlayer) fed from the disc               (planned: DataSource over a DvdReader)
  Navigation  titles, chapters, menus of DVD-Video; tracks of audio CDs    (planned: data/DvdNav.kt)
  Filesystem  ISO 9660, UDF: folders and files on the disc                 (data/DvdReader.kt DiscProbe; planned: DiscFs.kt)
  Reader      a disc as numbered 2048-byte sectors + what the drive says   data/DvdReader.kt  (interface DvdReader)
  Transport   SCSI/MMC commands over USB bulk-only                         data/UsbScsi.kt
```

Each layer talks only to the one below it, through a small interface:

- **Transport** (`UsbScsi`) knows USB and SCSI: command blocks in both directions, the drive's sense codes, reset
  recovery, LUN selection, waiting for a drive to become ready, shrinking reads when a USB bridge can't take large
  transfers, remembering unreadable blocks. It knows nothing about what is on a disc.
- **Reader** (`DvdReader`) is the contract everything above uses: `capacity()`, `read(lba, count, into)`,
  `medium()`, and for CDs `toc()` and `readAudio()`. Implementations today:
  - `UsbDvdReader`: a USB drive through `UsbScsi`;
  - `ImageDvdReader`: a `.iso` / `.img` file (copied discs, and tests without a drive).
- **Filesystem**: ISO 9660 and UDF, read through `DvdReader`. `DiscProbe` already finds which ones a disc carries and
  its label.
- **Navigation**: DVD-Video's IFO files (titles, chapters, which VOB files make a title), audio-CD tracks.
- **Playback**: AntonOS's player reads titles as a stream (MPEG program stream from VOB files; PCM from audio CDs).
- **Export**: copies what navigation describes into ordinary files in Media.

Rules the whole system keeps:

- A disc is never refused because of its name, publisher, format or region. The only "can't play" message is for a
  disc whose own sectors can't be read as video (the drive reports the disc as protected and real reads come back
  scrambled). When unsure, AntonOS tries to play.
- AntonOS contains no decryption and no component for it. Encrypted commercial discs (CSS, AACS) are recognised and
  named, and the user is told plainly that they can't be played or copied here.
- Copying is always the user's choice from the disc's options; playing is the main action.

## Adding another way to read discs (native libraries)

Anything that can produce sectors can be a `DvdReader`. A native library is wrapped by a small Kotlin class that
calls it through JNI:

```kotlin
class NativeDvdReader(private val handle: Long, override val name: String) : DvdReader {
    override fun capacity() = nativeCapacity(handle)
    override fun read(lba: Long, count: Int, into: ByteArray, offset: Int) =
        check(nativeRead(handle, lba, count, into, offset) == count) { "read failed" }
    override fun medium() = Medium.ofProfile(nativeProfile(handle), -1)
    override fun close() = nativeClose(handle)
    private external fun nativeCapacity(h: Long): Long
    private external fun nativeRead(h: Long, lba: Long, count: Int, into: ByteArray, offset: Int): Int
    private external fun nativeProfile(h: Long): Int
    private external fun nativeClose(h: Long)
}
```

The USB device is opened on the Kotlin side as usual (permission, `UsbManager.openDevice`) and its file descriptor
(`UsbScsi.fileDescriptor`, from `UsbDeviceConnection.getFileDescriptor()`) is handed to the library, which then
drives the device itself. Only one side may talk to the device at a time: when a native reader owns it, AntonOS's
own `UsbScsi` for that device is not used.

### liblaser

[liblaser](https://github.com/liblaser/liblaser) (LGPL-2.1, C) is a transport and disc-identification library for
USB optical drives on unrooted Android, written for VLC for Android. Its lower layers map onto ours:

| liblaser | AntonOS layer | Notes |
|---|---|---|
| `usb.c`, `bot.c`, `scsi.c`, `ready.c`, `read.c` | Transport | Same job as `UsbScsi`; standards-based endpoint/LUN discovery, sense handling, retries, transfer-size negotiation, abort callback. |
| `laser_read_blocks`, `laser_read_cd_blocks`, `laser_capacity`, `laser_media_*`, `laser_set_speed` | Reader | Back a `NativeDvdReader`: `read` → `laser_read_blocks`, `capacity` → `laser_capacity`, `eject` → `laser_media_eject`, `setSpeed` → `laser_set_speed`; `laser_unreadable_blocks` tells the reader which blocks to skip. |
| `disc.c`, `medium.c` | Reader / Navigation | Identifies CD-DA, VCD/SVCD, DVD-Video, DVD-Audio and Blu-ray, with titles. Could replace `DiscProbe`. |
| `registry.c` (fd token in a VLC MRL) | — | Exists for VLC's architecture; a direct JNI wrapper passes the fd itself (`laser_acquire(fd)`). |
| `contrib/libudfread` (VideoLAN, LGPL) | Filesystem | A UDF reader AntonOS could use instead of writing its own. |
| `contrib/libusb` | Transport | Built into liblaser; nothing extra for AntonOS. |

Integration work:

1. Build liblaser (meson) and its contribs with the Android NDK for `armeabi-v7a` (the MXQ is 32-bit) and
   `arm64-v8a`, as shared libraries (LGPL: linked dynamically, the APK can stay MIT).
2. A small JNI file implementing `NativeDvdReader`'s functions on top of `laser_acquire`, `laser_capacity`,
   `laser_read_blocks`, `laser_media_poll`, `laser_release`.
3. Choose the reader at runtime: `NativeDvdReader` when the library is present and claims the drive, else
   `UsbDvdReader`.

liblaser also contains entry points used by VLC's DVD and Blu-ray decryption add-ons (CSS sessions; AACS through a
separate key file). AntonOS doesn't call or include those parts.

### Navigation and playback libraries

- **libdvdread** (GPL-2): reads IFO/VOB structure through a block-reading callback that a `DvdReader` can serve.
- **libdvdnav** (GPL-2): DVD menus and navigation on top of libdvdread.
  Linking either makes the AntonOS APK GPL; that is a licence decision to make before using them. Without them, AntonOS
  parses the IFO files itself (titles and chapters, no menus) and plays the VOB files directly.
- **libbluray** (LGPL-2.1): Blu-ray playlists and clips through a file-reading callback.
- **Player**: ExoPlayer plays MPEG program streams (VOB) and PCM. DVD audio is usually AC-3: it plays when the box's
  decoder supports it, else needs a decoder extension.

## Testing without hardware

`ImageDvdReader` reads disc images, so the filesystem, navigation and playback layers can be tested from files.
liblaser's repository has small test images (`tests/images/dvd-video.img`, `udf-data.img`, `vcd.iso`...) built by its
`make_images.py`, which are a convenient set.
