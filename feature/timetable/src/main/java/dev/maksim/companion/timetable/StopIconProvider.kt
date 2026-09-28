package dev.maksim.companion.timetable

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the stop icons OsmAnd draws on its map and in the stop's menu. OsmAnd only takes a map point's
 * picture as a URI it opens itself (AMapPoint.POINT_IMAGE_URI_PARAM), so the icons are rendered to PNG once
 * and handed out here. Read-only and nothing private, hence exported without a permission.
 */
class StopIconProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val kind = uri.lastPathSegment?.removeSuffix(".png")
            ?.let { name -> Mode.entries.find { it.name.equals(name, ignoreCase = true) } }
            ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(render(context!!, kind), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        private const val SIZE_PX = 96

        fun uri(context: Context, mode: Mode): String = "content://${context.packageName}.stopicons/${mode.name.lowercase()}.png"

        /** White vehicle on a circle of the mode's color. Cached per app version, since the drawing may change. */
        @Synchronized
        private fun render(context: Context, mode: Mode): File {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val file = File(context.cacheDir, "stop-icons/$version-${mode.name}.png")
            if (file.exists()) return file
            file.parentFile?.run {
                deleteRecursively()
                mkdirs()
            }
            val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val half = SIZE_PX / 2f
            canvas.drawCircle(half, half, half, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mode.color })
            val icon = ContextCompat.getDrawable(context, mode.icon)!!.mutate()
            val inset = SIZE_PX / 5
            icon.setBounds(inset, inset, SIZE_PX - inset, SIZE_PX - inset)
            icon.draw(canvas)
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
            return file
        }
    }
}
