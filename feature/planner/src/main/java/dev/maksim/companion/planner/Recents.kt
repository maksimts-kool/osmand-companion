package dev.maksim.companion.planner

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** The places last picked to plan from or to, newest first, kept on the phone only. */
object Recents {

    private const val PREFS = "planner"
    private const val KEY = "recent"
    private const val MAX = 8

    fun list(context: Context): List<Place> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getJSONObject(it) }.map {
                Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.optString("detail").ifEmpty { null })
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, place: Place) {
        if (place.isMyLocation) return
        val places = (listOf(place) + list(context).filterNot { it.name == place.name && it.near(place) }).take(MAX)
        val array = JSONArray()
        for (p in places) {
            array.put(JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("detail", p.detail ?: ""))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, array.toString()) }
    }

    /** About the same point: 1e-4° is about 10 m. */
    private fun Place.near(other: Place) = kotlin.math.abs(lat - other.lat) < 1e-4 && kotlin.math.abs(lon - other.lon) < 1e-4
}
