package dev.maksim.companion.routelogger

import android.content.Context
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

    fun format(context: Context, track: AGpxFile): String {
        val path = track.relativePath ?: track.fileName
        val d = track.details
        val lines = mutableListOf("🏁 <b>${escape(context.getString(R.string.rl_summary_title))}</b>")
        if (d == null) {
            lines += escape(context.getString(R.string.rl_summary_no_stats))
        } else {
            lines += Details(context).lines(d)
        }
        lines += "📁 <code>${escape(path)}</code>"
        return lines.joinToString("\n")
    }

    /** The statistics lines, in [context]'s language. */
    private class Details(private val context: Context) {

        fun lines(d: AGpxFileDetails): List<String> = buildList {
            if (d.startTime > 0 && d.endTime > 0) add("📅 ${timeRange(d.startTime, d.endTime)}")

            add("📏 ${label(R.string.rl_summary_distance)}: <b>${km(d.totalDistance)}</b>")

            val duration = StringBuilder("⏱ ${label(R.string.rl_summary_duration)}: <b>${duration(d.timeSpan)}</b>")
            if (d.timeMoving in 1 until d.timeSpan) duration.append(" (${label(R.string.rl_summary_moving, duration(d.timeMoving))})")
            add(duration.toString())

            if (d.timeSpan > 0) {
                val speed = StringBuilder("🚀 ${label(R.string.rl_summary_avg_speed)}: <b>${kmh(d.avgSpeed)}</b>")
                if (d.timeMoving > 0 && d.totalDistanceMoving > 0) {
                    speed.append(" (${label(R.string.rl_summary_moving, kmh(d.totalDistanceMoving / (d.timeMoving / 1000f)))})")
                }
                if (d.maxSpeed > 0) speed.append(", ${label(R.string.rl_summary_max, kmh(d.maxSpeed))}")
                add(speed.toString())
            }

            // OsmAnd leaves min at 99999 / max at -100 when the track has no elevation.
            if (d.minElevation < 99_999 && d.maxElevation >= d.minElevation) {
                add(
                    "⛰ ${label(R.string.rl_summary_elevation)}: ↑ ${m(d.diffElevationUp)} ↓ ${m(d.diffElevationDown)} " +
                        "(${m(d.minElevation)} – ${m(d.maxElevation)})",
                )
            }

            val points = StringBuilder("📍 ${count(R.plurals.rl_summary_points, d.points)}")
            if (d.totalTracks > 1) points.append(", ${count(R.plurals.rl_summary_tracks, d.totalTracks)}")
            if (d.wptPoints > 0) points.append(", ${count(R.plurals.rl_summary_waypoints, d.wptPoints)}")
            add(points.toString())
        }

        private fun label(id: Int, vararg args: Any) = escape(context.getString(id, *args))

        private fun count(id: Int, n: Int) = escape(context.resources.getQuantityString(id, n, n))

        private fun km(meters: Float) =
            if (meters < 1000) m(meters.toDouble()) else label(R.string.rl_unit_km, meters / 1000)

        private fun kmh(metersPerSecond: Float) = label(R.string.rl_unit_kmh, metersPerSecond * 3.6f)

        private fun m(meters: Double) = label(R.string.rl_unit_m, Math.round(meters).toInt())
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

    private fun duration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val min = totalSeconds % 3600 / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, min, s) else String.format(Locale.US, "%d:%02d", min, s)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
