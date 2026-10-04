package dev.maksim.companion.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A published GitHub release with an APK attached. */
data class Release(
    /** The tag without its "v": "2.1.0", or "2.1.0-beta.1" for a beta. */
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

    /** How many of the newest releases [latest] looks through for betas: there's never a longer run of them. */
    private const val RECENT_RELEASES = 20

    /**
     * The newest release, or null if there's none yet or it has no APK. Drafts are never offered; betas
     * (GitHub's pre-releases) only with [betas], and then whichever is newest, beta or not. Blocking: call it off
     * the main thread.
     */
    fun latest(betas: Boolean): Release? {
        if (!betas) return get("releases/latest")?.let { release(JSONObject(it)) }
        val list = JSONArray(get("releases?per_page=$RECENT_RELEASES") ?: return null)
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filterNot { it.optBoolean("draft") }
            .mapNotNull { release(it) }
            .reduceOrNull { newest, release -> if (isNewer(release.version, newest.version)) release else newest }
    }

    /** The API's answer at [path] under this repo, or null if it's not there (no releases yet). */
    private fun get(path: String): String? {
        val connection = URL("https://api.github.com/repos/$REPO/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) throw IOException("GitHub answered HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** A release as the API describes it; null if it has no APK. */
    private fun release(json: JSONObject): Release? {
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
    }

    /**
     * Compares "major.minor.patch" versions, where a beta ("2.1.0-beta.1") comes before its release ("2.1.0") and
     * after the one before ("2.0.3"), and betas go by their number. Any other suffix (like "-dev") is ignored.
     */
    fun isNewer(version: String, than: String): Boolean {
        fun parts(v: String): List<Int> {
            val numbers = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val beta = Regex("-beta\\.(\\d+)$").find(v)?.groupValues?.get(1)?.toIntOrNull()
            // A release counts as the last beta there could be.
            return List(3) { numbers.getOrElse(it) { 0 } } + (beta ?: Int.MAX_VALUE)
        }
        val a = parts(version)
        val b = parts(than)
        for (i in a.indices) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return false
    }

    fun isBeta(version: String) = version.contains("-beta.")
}

/** org.json's optString turns a JSON null into the text "null". */
private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
