package dev.maksim.companion.planner

import android.content.Context
import dev.maksim.companion.timetable.distanceMeters

/**
 * Estonia's cities, towns, bigger villages and city districts, from OpenStreetMap, bundled (res/raw/pl_towns.tsv):
 * searched on the phone, so they come up first and at once, typos and all.
 *
 * The file has a line per place: kind (C city, T town, V village, D district), name, lat, lon, the city a district
 * is in, and population (0 when unknown). Made from Overpass's
 * `area["ISO3166-1"="EE"]["admin_level"="2"]->.ee;node(area.ee)["place"~"^(city|town|suburb|borough|village|quarter)$"];out;`:
 * suburbs and quarters are districts of the nearest city or town within 15 km, each once.
 */
class Towns(val all: List<Town>) {

    class Town(val kind: Found.Kind, val name: String, val lat: Double, val lon: Double, val city: String?, val population: Int) {
        val match = Fuzzy.Name(name)
        val place get() = Place(name, lat, lon, city)
    }

    /** Those matching [query], best first: the better match, then the bigger kind of place and the bigger place. */
    fun search(query: Fuzzy.Query, near: Place?, max: Int): List<Found> = all.asSequence()
        .map { it to Fuzzy.score(query, it.match) }
        .filter { it.second >= Fuzzy.MATCH }
        .sortedWith(
            compareByDescending<Pair<Town, Int>> { it.second / Fuzzy.RANK }
                .thenBy { it.first.kind.ordinal }
                .thenByDescending { it.first.population }
                .thenBy { (town) -> near?.let { distanceMeters(it.lat, it.lon, town.lat, town.lon) } ?: 0.0 },
        )
        .take(max)
        .map { (town) -> Found(town.place, town.kind) }
        .toList()

    /** Where [lat], [lon] is, to tell stops of the same name apart: "Lasnamäe, Tallinn", "Keila", or null. */
    fun around(lat: Double, lon: Double): String? {
        val district = all.filter { it.kind == Found.Kind.DISTRICT }
            .minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) }
            ?.takeIf { distanceMeters(lat, lon, it.lat, it.lon) < DISTRICT_M }
        if (district != null) return "${district.name}, ${district.city}"
        return all.filter { it.kind != Found.Kind.DISTRICT }
            .minByOrNull { distanceMeters(lat, lon, it.lat, it.lon) }
            ?.takeIf { distanceMeters(lat, lon, it.lat, it.lon) < TOWN_M }
            ?.name
    }

    companion object {
        /** A stop this close to a district's center is said to be in it. */
        private const val DISTRICT_M = 1_500.0
        private const val TOWN_M = 6_000.0

        @Volatile
        private var loaded: Towns? = null

        /** The bundled ones, read once. */
        fun of(context: Context): Towns = loaded ?: synchronized(this) {
            loaded ?: parse(context.resources.openRawResource(R.raw.pl_towns).bufferedReader().use { it.readText() })
                .also { loaded = it }
        }

        fun parse(text: String): Towns = Towns(
            text.lineSequence().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 6) return@mapNotNull null
                val kind = when (f[0]) {
                    "C" -> Found.Kind.CITY
                    "T" -> Found.Kind.TOWN
                    "V" -> Found.Kind.VILLAGE
                    "D" -> Found.Kind.DISTRICT
                    else -> return@mapNotNull null
                }
                Town(kind, f[1], f[2].toDouble(), f[3].toDouble(), f[4].ifEmpty { null }, f[5].toIntOrNull() ?: 0)
            }.toList(),
        )
    }
}
