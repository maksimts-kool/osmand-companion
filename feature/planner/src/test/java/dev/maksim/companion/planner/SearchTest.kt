package dev.maksim.companion.planner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchTest {

    private fun score(query: String, name: String) = Fuzzy.score(Fuzzy.Query(query), Fuzzy.Name(name))
    private fun matches(query: String, name: String) = score(query, name) >= Fuzzy.MATCH

    /** The bundled towns, as the app has them. */
    private val towns = Towns.parse(File("src/main/res/raw/pl_towns.tsv").readText())
    private val tallinn = Place("", 59.437, 24.745)

    private fun town(query: String) = towns.search(Fuzzy.Query(query), tallinn, 4).firstOrNull()?.place?.name

    @Test
    fun estonianLettersMayBeTypedPlain() {
        assertTrue(matches("parnu", "Pärnu"))
        assertTrue(matches("ulemiste", "Ülemiste"))
        assertTrue(matches("oismae", "Õismäe"))
        assertTrue(matches("sopruse", "Sõpruse pst"))
        assertTrue(matches("zoo", "Zoo"))
        assertTrue(matches("pohja tallinn", "Põhja-Tallinn"))
        assertTrue(matches("sostakovitsi", "Šostakovitši"))
    }

    @Test
    fun typosAreForgiven() {
        assertTrue(matches("kristine", "Kristiine")) // a double letter as one
        assertTrue(matches("talin", "Tallinn"))
        assertTrue(matches("lasnamae", "Lasnamäe"))
        assertTrue(matches("mustmae", "Mustamäe")) // a letter missing
        assertTrue(matches("hapsalu", "Haapsalu"))
        assertTrue(matches("kadirorg", "Kadriorg")) // two swapped
        assertTrue(matches("viljnadi", "Viljandi"))
        assertTrue(matches("tarto", "Tartu"))
    }

    @Test
    fun wordsAsTyped() {
        assertTrue(matches("balti j", "Balti jaam")) // still typing
        assertTrue(matches("baltijaam", "Balti jaam")) // run together
        assertTrue(matches("jaam balti", "Balti jaam")) // any order
        assertTrue(matches("kohtla jarve", "Kohtla-Järve"))
    }

    @Test
    fun unrelatedNamesDontMatch() {
        assertEquals(0, score("tartu", "Narva"))
        assertEquals(0, score("kalamaja", "Kadaka"))
        assertEquals(0, score("viru", "Valga")) // short words get no typos
        assertEquals(0, score("balti jaam", "Balti"))
    }

    @Test
    fun exactBeforeTypedSoFarBeforeTypos() {
        assertTrue(score("tartu", "Tartu") > score("tartu", "Tartu mnt"))
        assertTrue(score("tartu", "Tartu mnt") > score("tartu", "Tarbu"))
        assertTrue(score("keila", "Keila") > score("keil", "Keila"))
    }

    @Test
    fun citiesComeBeforeDistrictsAndVillages() {
        assertEquals("Tartu", town("tartu"))
        assertEquals("Pärnu", town("parnu"))
        assertEquals("Tallinn", town("talinn"))
        assertEquals("Kohtla-Järve", town("kohtla jarve"))
        assertEquals("Lasnamäe", town("lasnamae"))
        val kristiine = towns.search(Fuzzy.Query("kristine"), tallinn, 4).first()
        assertEquals(Found.Kind.DISTRICT, kristiine.kind)
        assertEquals("Tallinn", kristiine.place.detail)
    }

    @Test
    fun stopsAroundATownAreSaidToBeInIt() {
        assertEquals("Lasnamäe, Tallinn", towns.around(59.4355, 24.853))
        assertEquals("Kesklinn, Tartu", towns.around(58.378, 26.729))
        assertEquals("Keila", towns.around(59.3036, 24.4131))
    }

    private fun stop(name: String, lat: Double, lon: Double, vararg routes: String, mode: String = "BUS") =
        StopIndex.Stop(name, lat, lon, mode, routes.toList())

    @Test
    fun sidesOfTheStreetAndBaysAreOneStop() {
        val groups = StopIndex.group(
            listOf(
                stop("Hobujaama", 59.4370, 24.7560, "1", "5"),
                stop("Hobujaama", 59.4372, 24.7566, "5", "8"),
                stop("Balti jaam 1 (train station)", 59.4400, 24.7370, "3", "4"),
                stop("Balti jaam 2 (train station)", 59.4402, 24.7375, "17"),
                stop("Balti jaam (train station)", 59.4405, 24.7380, "1", "2", mode = "TRAM"),
                // Same name, another village.
                stop("Kooli", 58.0, 25.0, "101"),
                stop("Kooli", 59.0, 26.0, "202"),
            ),
            towns = null,
        )
        val byName = groups.groupBy { it.name }
        assertEquals(listOf("1", "5", "8"), byName.getValue("Hobujaama").single().lines)
        val balti = byName.getValue("Balti jaam (train station)").single()
        assertEquals(listOf("1", "2", "3", "4", "17"), balti.lines)
        assertEquals("TRAM", balti.mode)
        assertEquals(2, byName.getValue("Kooli").size)
    }

    @Test
    fun stopsNearerFirstAmongEquallyGoodMatches() {
        val index = StopIndex(
            StopIndex.group(
                listOf(stop("Kooli", 58.0, 25.0, "101"), stop("Kooli", 59.43, 24.75, "5"), stop("Koolimaja", 59.43, 24.75, "7")),
                towns = null,
            ),
        )
        val found = index.search(Fuzzy.Query("kooli"), tallinn, 6)
        assertEquals(listOf(listOf("5"), listOf("101"), listOf("7")), found.map { it.lines })
    }

    @Test
    fun peatusStopListLeavesOutUnservedStops() {
        val data = JSONObject(
            """{"stops":[
                {"name":"Sillake","lat":58.1488,"lon":26.5566,"vehicleMode":"BUS","routes":[{"shortName":"77A"},{"shortName":"331"}]},
                {"name":"Vana","lat":58.1,"lon":26.5,"vehicleMode":"BUS","routes":[]},
                {"name":"Riga","lat":56.95,"lon":24.1,"vehicleMode":"BUS","routes":[{"shortName":"1"}]}
            ]}""",
        )
        assertEquals(listOf("Sillake"), StopIndex.parse(data).map { it.name })
    }

    private fun photon(json: String) = Geocoder.photonPlace(JSONObject(json))

    @Test
    fun photonPlacesAddressesAndStops() {
        val shop = photon(
            """{"geometry":{"coordinates":[24.76,59.44]},"properties":{"osm_key":"shop","osm_value":"mall","name":"Artius",
                "street":"Narva mnt","housenumber":"5","city":"Tallinn","district":"Kesklinn"}}""",
        )!!
        assertEquals("Artius", shop.place.name)
        assertEquals("Narva mnt 5, Kesklinn, Tallinn", shop.place.detail)
        assertEquals(Found.Kind.ADDRESS, shop.kind)

        val house = photon(
            """{"geometry":{"coordinates":[24.76,59.44]},"properties":{"osm_key":"place","osm_value":"house",
                "street":"Narva mnt","housenumber":"5","city":"Tallinn"}}""",
        )!!
        assertEquals("Narva mnt 5", house.place.name)
        assertEquals("Tallinn", house.place.detail)

        val city = photon(
            """{"geometry":{"coordinates":[24.5,58.38]},"properties":{"osm_key":"place","osm_value":"town","name":"Pärnu",
                "county":"Pärnu maakond"}}""",
        )!!
        assertEquals(Found.Kind.VILLAGE, city.kind)

        assertEquals(
            null,
            photon("""{"geometry":{"coordinates":[24.7,59.4]},"properties":{"osm_key":"highway","osm_value":"bus_stop","name":"Hobujaama"}}"""),
        )
    }

    @Test
    fun mergedResultsAreEachPlaceOnce() {
        fun found(name: String, lat: Double, lon: Double) = Found(Place(name, lat, lon), Found.Kind.ADDRESS)
        val photon = listOf(found("Tartu mnt", 59.43, 24.77), found("Tartu mnt", 59.425, 24.79), found("Tartu mnt 1", 59.4345, 24.7570))
        val pelias = listOf(
            found("Tartu mnt", 59.425, 24.78), // a piece of the same street
            found("Tartu Maantee 1", 59.43452, 24.75705), // the same house
            found("Tartu Maantee 2", 59.4348, 24.7580),
            found("Tartu mnt", 59.0, 25.6), // another town's
        )
        assertEquals(
            listOf("Tartu mnt", "Tartu mnt 1", "Tartu Maantee 2", "Tartu mnt"),
            Geocoder.merge(photon, pelias).map { it.place.name },
        )
    }
}
