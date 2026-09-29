package dev.maksim.companion.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A published GitHub release with an APK attached. */
data class Release(
    /** The tag without its "v": "2.1.0". */
    val version: String,
    /** The release description (Markdown), shown as the changelog. */
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** SHA-256 of the APK as GitHub reports it, hex; null for releases made before GitHub computed digests. */
    val sha256: String?,
    val pageUrl: String,
) {
    /**
     * [notes] without Markdown's headings, bullets and bold, for a plain dialog. The "Full Changelog" link
     * GitHub adds is dropped: the dialog has a button for the release page.
     */
    val plainNotes: String
        get() = notes.lines().filterNot { it.startsWith("**Full Changelog**") }.joinToString("\n") { line ->
            line.trim()
                .replace(Regex("^#+\\s*"), "")
                .replace(Regex("^[*-]\\s+"), "• ")
                .replace("**", "")
        }.trim()

    fun toJson(): String = JSONObject()
        .put("version", version)
        .put("notes", notes)
        .put("apkUrl", apkUrl)
        .put("apkSize", apkSize)
        .put("sha256", sha256 ?: JSONObject.NULL)
        .put("pageUrl", pageUrl)
        .toString()

    companion object {
        fun fromJson(text: String): Release = JSONObject(text).run {
            Release(
                version = getString("version"),
                notes = getString("notes"),
                apkUrl = getString("apkUrl"),
                apkSize = getLong("apkSize"),
                sha256 = optStringOrNull("sha256"),
                pageUrl = getString("pageUrl"),
            )
        }
    }
}

/** Reads this app's releases from the GitHub API. No token: 60 requests an hour is plenty. */
object GitHubReleases {

    const val REPO = "maksimts-kool/osmand-maksimts"

    /**
     * The newest release (drafts and pre-releases are skipped by GitHub), or null if there's none yet or it
     * has no APK. Blocking: call it off the main thread.
     */
    fun latest(): Release? {
        val connection = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) throw IOException("GitHub answered HTTP $code")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })

            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return null
            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optStringOrNull("body").orEmpty().trim(),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.getLong("size"),
                sha256 = apk.optStringOrNull("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                pageUrl = json.getString("html_url"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Compares "major.minor.patch" versions; anything after a "-" (like "-dev") is ignored. */
    fun isNewer(version: String, than: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(version)
        val b = parts(than)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }
}

/** org.json's optString turns a JSON null into the text "null". */
private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
