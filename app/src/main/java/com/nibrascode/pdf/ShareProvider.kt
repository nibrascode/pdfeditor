package com.nibrascode.pdf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

// Paylaşılan fayllar üçün sadə FileProvider (androidx-siz): cache/share qovluğundakı faylları oxumağa verir
class ShareProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.nibrascode.pdf.share"

        fun dir(ctx: Context): File = File(ctx.cacheDir, "share").apply { mkdirs() }

        fun uriFor(f: File): Uri = Uri.parse("content://$AUTH/" + Uri.encode(f.name))
    }

    private fun fileOf(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val f = File(dir(context!!), name)
        if (f.parentFile?.canonicalPath != dir(context!!).canonicalPath || !f.exists()) throw FileNotFoundException()
        return f
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment ?: ""
        return when {
            n.endsWith(".pdf", true) -> "application/pdf"
            n.endsWith(".png", true) -> "image/png"
            n.endsWith(".jpg", true) -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, sel: String?, args: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?): Int = 0
}
