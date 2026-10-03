package dev.maksim.companion.planner

import android.content.Context
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.Analytics
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tallinn's city timetables ([TallinnNetwork]) and the [TallinnRouter] on them, downloaded from transport.tallinn.ee
 * once a day into the app's files, so a search needs nothing but the live feed. If the download fails, yesterday's
 * will do.
 */
object TallinnData {

    private const val ROUTES = "https://transport.tallinn.ee/data/routes.txt"
    private const val STOPS = "https://transport.tallinn.ee/data/stops.txt"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** After a failed download, how long to make do with the files there are before trying again. */
    private const val RETRY_MS = 10 * 60 * 1000L

    private var router: TallinnRouter? = null

    /** When the files [router] was made from were downloaded. */
    private var loadedFrom = 0L
    private var retryAt = 0L

    /** Blocking. The router on today's timetables. */
    fun router(context: Context): TallinnRouter = synchronized(this) {
        val dir = File(context.filesDir, "tallinn").apply { mkdirs() }
        val routes = File(dir, "routes.txt")
        val stops = File(dir, "stops.txt")
        val now = System.currentTimeMillis()
        val have = routes.isFile && stops.isFile
        val stale = !have || now - minOf(routes.lastModified(), stops.lastModified()) > MAX_AGE_MS
        if (stale && now >= retryAt) {
            try {
                download(STOPS, stops)
                download(ROUTES, routes)
                AppLog.log("Planner: downloaded Tallinn's timetables")
            } catch (e: IOException) {
                retryAt = now + RETRY_MS
                if (!routes.isFile || !stops.isFile) throw e
                AppLog.log("Planner: couldn't update Tallinn's timetables (${e.message}), using the ones from before")
            }
        }
        val from = minOf(routes.lastModified(), stops.lastModified())
        router?.takeIf { loadedFrom == from }?.let { return it }
        val network = TallinnNetwork.parse(stops.readText(), routes.readText())
        return TallinnRouter(network).also {
            router = it
            loadedFrom = from
        }
    }

    /** Into a temporary file first, so a broken download doesn't replace a good file. */
    private fun download(url: String, into: File) {
        val part = File(into.parentFile, into.name + ".part")
        Analytics.timed("http.client", "GET transport.tallinn.ee data") {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("transport.tallinn.ee: HTTP $code")
                connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
        }
        if (part.length() < MIN_BYTES || !part.renameTo(into)) {
            part.delete()
            throw IOException("transport.tallinn.ee: incomplete ${into.name}")
        }
    }

    /** Both files are hundreds of kilobytes; less is an error page. */
    private const val MIN_BYTES = 10_000L
}
