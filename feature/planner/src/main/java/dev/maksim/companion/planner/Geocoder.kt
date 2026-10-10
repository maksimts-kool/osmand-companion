package dev.maksim.companion.planner

import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.Parallel
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
 * Finds addresses and places by name, with two searches side by side, no key needed for either:
 * - Photon (komoot's, open source, on OpenStreetMap), which forgives typos and missing Estonian letters
 *   ("parnu mnt", "kristine keskus") and finds shops and sights by name;
 * - peatus.ee's (Pelias, the one its planner uses), on Estonia's address register, which has every house number,
 *   but only finds names starting exactly as typed.
 *
 * Stops are left out: [StopIndex] finds those. Blocking: call it off the main thread.
 */
class Geocoder {

    /**
     * Addresses and places matching [text], Photon's first, then peatus.ee's it doesn't have; around [nearLat],
     * [nearLon] first if given. Villages and such from either are [Found.Kind.VILLAGE]. Fails only if both fail.
     */
    fun search(text: String, nearLat: Double?, nearLon: Double?, max: Int = MAX_RESULTS): List<Found> {
        val pelias = Parallel.submit { pelias(text, nearLat, nearLon) }
        val photon = runCatching { photon(text, nearLat, nearLon) }
        val other = runCatching { Parallel.await(pelias) }
        if (photon.isFailure && other.isFailure) throw photon.exceptionOrNull()!!
        val found = merge(photon.getOrDefault(emptyList()), other.getOrDefault(emptyList()))
        // "Tartu" shouldn't list Tartu mnt 1, 2, 4, 6...: houses only once a number is typed.
        val houses = text.any(Char::isDigit)
        return found.filter { houses || !isHouse(it) }.take(max)
    }

    /** What's at [lat], [lon], e.g. "Raekoja plats", or null. */
    fun reverse(lat: Double, lon: Double): String? {
        val json = get("$PELIAS/reverse?point.lat=$lat&point.lon=$lon&size=1&lang=${language()}", "api.peatus.ee reverse")
        return json.optJSONArray("features")?.objects()?.firstOrNull()
            ?.optJSONObject("properties")?.optNullableString("name")
    }

    private fun photon(text: String, nearLat: Double?, nearLon: Double?): List<Found> {
        val query = buildString {
            append("q=").append(URLEncoder.encode(text, "UTF-8"))
            append("&limit=").append(PHOTON_LIMIT)
            append("&bbox=").append(BBOX)
            if (nearLat != null && nearLon != null) append("&lat=$nearLat&lon=$nearLon")
        }
        return get("$PHOTON?$query", "photon.komoot.io api").optJSONArray("features")?.objects().orEmpty()
            .mapNotNull(::photonPlace)
    }

    private fun pelias(text: String, nearLat: Double?, nearLon: Double?): List<Found> {
        val query = buildString {
            append("text=").append(URLEncoder.encode(text, "UTF-8"))
            append("&lang=").append(language())
            if (nearLat != null && nearLon != null) append("&focus.point.lat=$nearLat&focus.point.lon=$nearLon")
        }
        return get("$PELIAS/autocomplete?$query", "api.peatus.ee autocomplete").optJSONArray("features")?.objects()
            .orEmpty().mapNotNull(::peliasPlace)
    }

    companion object {
        private const val PELIAS = "https://api.peatus.ee/geocoding/v1"
        private const val PHOTON = "https://photon.komoot.io/api/"
        private const val MAX_RESULTS = 10
        private const val PHOTON_LIMIT = 15

        /** Estonia, as Photon takes it: west, south, east, north. */
        private const val BBOX = "21.4,57.4,28.3,59.9"

        /** peatus.ee has names in Estonian, English and Russian. */
        private val LANGUAGES = setOf("et", "en", "ru")

        /** OpenStreetMap's tags for stops and stations, which [StopIndex] has. */
        private val STOP_TAGS = setOf(
            "highway" to "bus_stop", "highway" to "platform", "railway" to "tram_stop", "railway" to "station",
            "railway" to "halt", "railway" to "platform", "railway" to "stop", "amenity" to "bus_station",
            "amenity" to "ferry_terminal",
        )

        /** Photon's places that are a settlement rather than something in one. */
        private val SETTLEMENTS = setOf("city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood", "borough")

        /** A Photon feature as found, or null for a stop or one without a name or an address. */
        fun photonPlace(feature: JSONObject): Found? {
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
            val p = feature.optJSONObject("properties") ?: return null
            val key = p.optString("osm_key")
            val value = p.optString("osm_value")
            if (key == "public_transport" || key to value in STOP_TAGS) return null
            val street = p.optNullableString("street")
            val number = p.optNullableString("housenumber")
            val address = listOfNotNull(street, number).joinToString(" ").ifEmpty { null }
            val name = p.optNullableString("name") ?: address ?: return null
            val town = p.optNullableString("city") ?: p.optNullableString("locality")
            val county = p.optNullableString("county")
            val kind = if (key == "place" && value in SETTLEMENTS) Found.Kind.VILLAGE else Found.Kind.ADDRESS
            val detail = when (kind) {
                Found.Kind.VILLAGE -> listOfNotNull(town?.takeIf { it != name }, county)
                else -> listOfNotNull(
                    address?.takeIf { it != name },
                    p.optNullableString("district")?.takeIf { it != name && it != town },
                    town?.takeIf { it != name } ?: county,
                )
            }.distinct().joinToString(", ").ifEmpty { null }
            return Found(Place(name, coordinates.getDouble(1), coordinates.getDouble(0), detail), kind)
        }

        /** A Pelias feature as found, or null for a stop. */
        fun peliasPlace(feature: JSONObject): Found? {
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
            val properties = feature.optJSONObject("properties") ?: return null
            val kind = when (properties.optString("layer")) {
                "stop", "station" -> return null
                "locality", "localadmin", "neighbourhood", "borough", "county" -> Found.Kind.VILLAGE
                else -> Found.Kind.ADDRESS
            }
            val name = properties.optNullableString("name") ?: return null
            // "Kristiine keskus,  Kristiine linnaosa": the label's rest says where.
            val detail = properties.optNullableString("label")
                ?.removePrefix(name)?.trim(',', ' ')?.replace(Regex("""\s*,\s*"""), ", ")?.takeIf { it.isNotEmpty() }
            return Found(Place(name, coordinates.getDouble(1), coordinates.getDouble(0), detail), kind)
        }

        /**
         * [first], then those of [then] that aren't in it, each once: the same name within [SAME_NAME_M] is the same
         * place (a street comes in pieces), or within [SAME_PLACE_M] if it's said to be in the same place too (a long
         * one), and so is anything within [SAME_SPOT_M] that's called the same or has the same address.
         */
        fun merge(first: List<Found>, then: List<Found>): List<Found> {
            val kept = ArrayList<Found>()
            for (found in first + then) {
                val name = Fuzzy.fold(found.place.name)
                val duplicate = kept.any { other ->
                    val sameName = Fuzzy.fold(other.place.name) == name
                    (sameName && found.place.isNear(other.place, SAME_NAME_M)) ||
                        (sameName && found.place.detail == other.place.detail && found.place.isNear(other.place, SAME_PLACE_M)) ||
                        (found.place.isNear(other.place, SAME_SPOT_M) && (sameName || sameAddress(found, other)))
                }
                if (!duplicate) kept += found
            }
            return kept
        }

        /** An address of a house rather than a place with a name: "Tartu Maantee 13", "Lille 4a". */
        private fun isHouse(found: Found) =
            found.kind == Found.Kind.ADDRESS && Fuzzy.tokens(found.place.name).let { it.size > 1 && it.last()[0].isDigit() }

        private const val SAME_NAME_M = 2_000.0
        private const val SAME_PLACE_M = 15_000.0
        private const val SAME_SPOT_M = 40.0

        /** "Tartu mnt 1" and "Tartu Maantee 1": the same number, and the street's name starting the same. */
        private fun sameAddress(a: Found, b: Found): Boolean {
            val x = Fuzzy.tokens(a.place.name)
            val y = Fuzzy.tokens(b.place.name)
            val number = x.lastOrNull()?.takeIf { it.any(Char::isDigit) } ?: return false
            return number == y.lastOrNull() && x.first() == y.first()
        }

        /** Photon asks its users to say who they are. */
        private const val USER_AGENT = "OsmAndCompanion (github.com/maksimts-kool/osmand-maksimts)"

        private fun language(): String = Locale.getDefault().language.takeIf { it in LANGUAGES } ?: "et"

        private fun get(url: String, what: String): JSONObject = Analytics.timed("http.client", "GET $what") {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val host = connection.url.host
            val (code, text) = readResponse(connection)
            if (code !in 200..299) throw IOException("$host: HTTP $code")
            try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw IOException("$host: unreadable answer", e)
            }
        }
    }
}
