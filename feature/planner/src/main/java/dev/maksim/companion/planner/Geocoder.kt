package dev.maksim.companion.planner

import dev.maksim.companion.core.Analytics
import dev.maksim.companion.timetable.objects
import dev.maksim.companion.timetable.optNullableString
import dev.maksim.companion.timetable.readResponse
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Finds places by name with peatus.ee's search (Pelias, on Estonia's address register, OpenStreetMap and the
 * stops), the one its planner uses. No key needed.
 *
 * Blocking: call it off the main thread.
 */
class Geocoder {

    /** Places whose name starts like [text], nearest to [nearLat], [nearLon] first if given. */
    fun search(text: String, nearLat: Double?, nearLon: Double?, max: Int = MAX_RESULTS): List<Place> {
        val query = buildString {
            append("text=").append(URLEncoder.encode(text, "UTF-8"))
            append("&lang=").append(language())
            if (nearLat != null && nearLon != null) append("&focus.point.lat=$nearLat&focus.point.lon=$nearLon")
        }
        val json = get("$ENDPOINT/autocomplete?$query", "autocomplete")
        return json.optJSONArray("features")?.objects().orEmpty().mapNotNull(::place).take(max)
    }

    /** What's at [lat], [lon], e.g. "Raekoja plats", or null. */
    fun reverse(lat: Double, lon: Double): String? {
        val json = get("$ENDPOINT/reverse?point.lat=$lat&point.lon=$lon&size=1&lang=${language()}", "reverse")
        return json.optJSONArray("features")?.objects()?.firstOrNull()
            ?.optJSONObject("properties")?.optNullableString("name")
    }

    private fun place(feature: JSONObject): Place? {
        val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val properties = feature.optJSONObject("properties") ?: return null
        val name = properties.optNullableString("name") ?: return null
        // "Kristiine keskus,  Kristiine linnaosa": the label's rest says where.
        val detail = properties.optNullableString("label")
            ?.removePrefix(name)?.trim(',', ' ')?.replace(Regex("""\s*,\s*"""), ", ")?.takeIf { it.isNotEmpty() }
        return Place(name, coordinates.getDouble(1), coordinates.getDouble(0), detail)
    }

    private fun get(url: String, what: String): JSONObject = Analytics.timed("http.client", "GET api.peatus.ee $what") {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        val (code, text) = readResponse(connection)
        if (code !in 200..299) throw IOException("peatus.ee: HTTP $code")
        try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw IOException("peatus.ee: unreadable answer", e)
        }
    }

    /** peatus.ee has names in Estonian, English and Russian. */
    private fun language(): String = Locale.getDefault().language.takeIf { it in LANGUAGES } ?: "et"

    private companion object {
        const val ENDPOINT = "https://api.peatus.ee/geocoding/v1"
        const val MAX_RESULTS = 10
        val LANGUAGES = setOf("et", "en", "ru")
    }
}
