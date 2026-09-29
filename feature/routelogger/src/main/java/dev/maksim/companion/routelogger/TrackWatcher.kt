package dev.maksim.companion.routelogger

import android.content.Context
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.OsmAndConnection
import net.osmand.aidlapi.gpx.AGpxFile
import java.util.concurrent.TimeUnit

/**
 * Notices trip recordings that OsmAnd has just saved and sends their summary to Telegram.
 *
 * OsmAnd has no "recording stopped" callback and doesn't expose the recording state over AIDL.
 * But when a recording is finished (Trip recording → Finish, which stops and saves the GPX), OsmAnd writes
 * the track to `tracks/rec/` (or `tracks/rec/yyyy-MM/`) and adds it, with its statistics, to the
 * list returned by `getImportedGpx`. Pausing writes nothing, so it's naturally ignored.
 * We poll that list and report every recorded track we haven't reported yet.
 */
class TrackWatcher(private val context: Context, private val osmand: OsmAndConnection, private val settings: RouteLoggerSettings) {

    /** Polls a new track has been seen without statistics; we wait a little for OsmAnd to analyze it. */
    private val waitingForDetails = mutableMapOf<String, Int>()

    /** Reports every recorded track that ended after [RouteLoggerSettings.watchingSince] and wasn't reported yet. */
    @Synchronized
    fun poll() {
        if (settings.watchingSince == 0L) settings.watchingSince = System.currentTimeMillis()
        if (!osmand.hasAccess) return // the status line in MainActivity already says why
        val tracks = recordedTracks() ?: return

        // Forget reported entries older than the retention window; they can't match again.
        val floor = maxOf(settings.watchingSince, System.currentTimeMillis() - RETENTION_MS)
        val reported = settings.reportedTracks.filterTo(mutableSetOf()) { entryTime(it) >= floor }
        val reportedKeys = reported.map { it.substringAfter('|') }.toSet()

        val fresh = tracks
            .filter { endTime(it) >= floor && keyOf(it) !in reportedKeys }
            .sortedBy(::endTime)
        for (track in fresh) {
            val path = pathOf(track)
            if (track.details == null) {
                val polls = (waitingForDetails[path] ?: 0) + 1
                waitingForDetails[path] = polls
                if (polls < MAX_POLLS_WITHOUT_DETAILS) continue
            }
            waitingForDetails.remove(path)
            AppLog.log("New recorded track: $path")
            report(track)
            reported += "${endTime(track)}|${keyOf(track)}"
        }
        settings.reportedTracks = reported
    }

    /** Sends the newest recorded track again, regardless of whether it was reported. For testing. */
    fun resendLatest(): Boolean {
        val latest = recordedTracks()?.maxByOrNull(::endTime) ?: run {
            AppLog.log("No recorded tracks found in OsmAnd")
            return false
        }
        report(latest)
        return true
    }

    private fun report(track: AGpxFile) {
        if (!settings.telegramConfigured) {
            AppLog.log("Telegram isn't set up, not sending ${pathOf(track)}")
            return
        }
        TelegramWorker.enqueue(context, TrackSummary.format(context, track), pathOf(track))
    }

    /** Tracks saved by trip recording, or null if OsmAnd can't be asked right now. */
    private fun recordedTracks(): List<AGpxFile>? {
        if (!osmand.hasAccess) {
            AppLog.log("Can't check tracks: ${if (osmand.isConnected) "no API access" else "not connected"}")
            return null
        }
        val files = mutableListOf<AGpxFile>()
        val ok = osmand.call("getImportedGpx") { it.getImportedGpx(files) } ?: return null
        if (!ok) {
            AppLog.log("getImportedGpx returned false")
            return null
        }
        return files.filter { pathOf(it).startsWith(REC_DIR) }
    }

    private fun pathOf(track: AGpxFile) = track.relativePath ?: track.fileName

    /**
     * Identifies a recording by its statistics rather than its path: right after Finish, OsmAnd offers
     * to rename the file, and a renamed track must not be reported twice.
     */
    private fun keyOf(track: AGpxFile) =
        track.details?.let { "${it.startTime}/${it.endTime}/${it.points}" } ?: pathOf(track)

    /** When the recording ended; falls back to when the file was written if OsmAnd has no statistics yet. */
    private fun endTime(track: AGpxFile) = track.details?.endTime?.takeIf { it > 0 } ?: track.modifiedTime

    private fun entryTime(entry: String) = entry.substringBefore('|').toLongOrNull() ?: 0L

    companion object {
        /** Relative to OsmAnd's tracks/ folder (IndexConstants.GPX_RECORDED_INDEX_DIR). */
        private const val REC_DIR = "rec/"
        private const val MAX_POLLS_WITHOUT_DETAILS = 3
        private val RETENTION_MS = TimeUnit.DAYS.toMillis(30)
    }
}
