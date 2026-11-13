package com.antonretro.launcher.kit

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Lets another app open one file from Files (a PDF, a picture, an APK to install), read-only, for that one request.
 * Only files on the box's storage and USB drives are shared, never RetroOS's own private files.
 */
class FileShare : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = ctx.packageName + ".files"
        fun uriFor(ctx: Context, f: File): Uri = Uri.Builder().scheme("content").authority(authority(ctx)).path(f.absolutePath).build()
        fun mimeOf(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        /** An intent that opens [f] in whatever app can, with read access for that app only. */
        fun viewIntent(ctx: Context, f: File): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, f), mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    private fun allowed(f: File): Boolean {
        val p = try { f.canonicalPath } catch (e: Exception) { return false }
        val sd = Environment.getExternalStorageDirectory().canonicalPath
        return (p.startsWith("$sd/") || p.startsWith("/storage/")) && !p.contains("/Android/data/") && f.isFile
    }
    private fun fileOf(uri: Uri): File? = uri.path?.let { File(it) }?.takeIf { allowed(it) }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = fileOf(uri)?.let { mimeOf(it) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val f = fileOf(uri) ?: throw SecurityException("not shared")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
