package dev.maksim.companion.routelogger

import net.osmand.aidlapi.gpx.AGpxFile
import net.osmand.aidlapi.gpx.AGpxFileDetails
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns OsmAnd's track statistics into a Telegram message (HTML parse mode).
 *
 * Units as OsmAnd reports them: distance in meters, times in ms (epoch or duration),
 * speeds in m/s, elevation in meters.
 */
object TrackSummary {

    fun format(track: AGpxFile): String {
        val path = track.relativePath ?: track.fileName
        val d = track.details
        val lines = mutableListOf("🏁 <b>Trip recorded</b>")
        if (d == null) {
            lines += "OsmAnd has no statistics for this track yet."
        } else {
            lines += details(d)
        }
        lines += "📁 <code>${escape(path)}</code>"
        return lines.joinToString("\n")
    }

    private fun details(d: AGpxFileDetails): List<String> = buildList {
        if (d.startTime > 0 && d.endTime > 0) add("📅 ${timeRange(d.startTime, d.endTime)}")

        add("📏 Distance: <b>${km(d.totalDistance)}</b>")

        val duration = StringBuilder("⏱ Duration: <b>${duration(d.timeSpan)}</b>")
        if (d.timeMoving in 1 until d.timeSpan) duration.append(" (moving ${duration(d.timeMoving)})")
        add(duration.toString())

        if (d.timeSpan > 0) {
            val speed = StringBuilder("🚀 Avg speed: <b>${kmh(d.avgSpeed)}</b>")
            if (d.timeMoving > 0 && d.totalDistanceMoving > 0) {
                speed.append(" (moving ${kmh(d.totalDistanceMoving / (d.timeMoving / 1000f))})")
            }
            if (d.maxSpeed > 0) speed.append(", max ${kmh(d.maxSpeed)}")
            add(speed.toString())
        }

        // OsmAnd leaves min at 99999 / max at -100 when the track has no elevation.
        if (d.minElevation < 99_999 && d.maxElevation >= d.minElevation) {
            add(
                "⛰ Elevation: ↑ ${m(d.diffElevationUp)} ↓ ${m(d.diffElevationDown)} " +
                    "(${m(d.minElevation)} – ${m(d.maxElevation)})",
            )
        }

        val points = StringBuilder("📍 ${d.points} points")
        if (d.totalTracks > 1) points.append(", ${d.totalTracks} tracks")
        if (d.wptPoints > 0) points.append(", ${d.wptPoints} waypoints")
        add(points.toString())
    }

    private fun timeRange(start: Long, end: Long): String {
        val day = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        val startDay = day.format(Date(start))
        val endDay = day.format(Date(end))
        return if (startDay == endDay) {
            "$startDay, ${time.format(Date(start))} – ${time.format(Date(end))}"
        } else {
            "$startDay ${time.format(Date(start))} – $endDay ${time.format(Date(end))}"
        }
    }

    private fun km(meters: Float) =
        if (meters < 1000) "${meters.toInt()} m" else String.format(Locale.US, "%.2f km", meters / 1000)

    private fun kmh(metersPerSecond: Float) = String.format(Locale.US, "%.1f km/h", metersPerSecond * 3.6f)

    private fun m(meters: Double) = "${Math.round(meters)} m"

    private fun duration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val min = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, min, s) else String.format(Locale.US, "%d:%02d", min, s)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
