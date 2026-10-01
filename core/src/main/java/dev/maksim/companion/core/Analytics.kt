package dev.maksim.companion.core

import android.content.Context
import androidx.core.content.edit
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.android.core.SentryAndroid
import io.sentry.metrics.SentryMetricsParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports, usage counts and traces (how long things take), all in Sentry, off until the user opts in: asked once on the home screen, then a
 * switch in Settings. A build without the DSN (README → Crash reports and usage stats) has neither and never asks.
 *
 * Nothing personal goes out: no location, no stops or searches, no account, no IP address. Reports and counts carry
 * the release, Android version, phone model and Sentry's random id for this install (so it can count users), which is
 * forgotten on opting out.
 */
object Analytics {

    /** From the app's build config; an empty DSN means no analytics. */
    class Keys(val sentryDsn: String, val version: String, val debug: Boolean)

    private const val PREFS = "analytics"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ASKED = "asked"
    private const val KEY_DAILY_PREFIX = "daily_"
    private const val SENTRY_INSTALLATION_FILE = "INSTALLATION"
    private const val SENTRY_CACHE_DIR = "sentry"
    private const val TRACES_SAMPLE_RATE = 0.2

    private lateinit var context: Context
    private var keys: Keys? = null

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Whether this build has analytics at all; without, Settings hides the switch. */
    val isAvailable: Boolean get() = keys != null

    /** Call first thing in Application.onCreate, so a crash while starting up is caught too. */
    fun init(context: Context, keys: Keys) {
        this.context = context.applicationContext
        this.keys = keys.takeIf { it.sentryDsn.isNotEmpty() }
        if (isEnabled()) start()
    }

    fun isEnabled(): Boolean = isAvailable && prefs().getBoolean(KEY_ENABLED, false)

    /** Whether to ask the user: once, and only in a build that has analytics. */
    fun shouldAsk(): Boolean = isAvailable && !prefs().getBoolean(KEY_ASKED, false)

    fun setEnabled(enabled: Boolean) {
        if (!isAvailable) return
        val was = isEnabled()
        prefs().edit {
            // Opting out forgets the daily marks too.
            if (!enabled) clear()
            putBoolean(KEY_ASKED, true)
            putBoolean(KEY_ENABLED, enabled)
        }
        if (enabled && !was) start()
        if (!enabled && was) stop()
        AppLog.log(if (enabled) "Crash reports and usage stats on" else "Crash reports and usage stats off")
    }

    /**
     * Counts [name] (like "Timetables.button") with [params], which must not identify anyone: a Sentry metric, to
     * chart and group by its parameters. Does nothing unless the user opted in. Any thread.
     */
    fun signal(name: String, params: Map<String, String> = emptyMap()) {
        if (!Sentry.isEnabled()) return
        Sentry.metrics().count(name, 1.0, null, SentryMetricsParameters.create(params))
    }

    /** Like [signal], but at most once a calendar day per device: for "used it today" counts. */
    fun daily(name: String, params: Map<String, String> = emptyMap()) {
        if (!Sentry.isEnabled()) return
        val today = dayFormat.format(Date())
        val key = KEY_DAILY_PREFIX + name
        synchronized(this) {
            if (prefs().getString(key, null) == today) return
            prefs().edit { putString(key, today) }
        }
        signal(name, params)
    }

    /**
     * Times [block] as a span in Sentry's traces (like "http.client" / "POST api.peatus.ee stop"): inside the screen
     * that's loading when there is one, else on its own. [description] must not identify anyone. Just runs [block]
     * unless the user opted in. Any thread.
     */
    fun <T> timed(operation: String, description: String, block: () -> T): T {
        if (!Sentry.isEnabled()) return block()
        val span = Sentry.getSpan()?.takeUnless { it.isFinished }?.startChild(operation, description)
            ?: Sentry.startTransaction(description, operation)
        try {
            return block().also { span.status = SpanStatus.OK }
        } catch (e: Throwable) {
            span.throwable = e
            span.status = SpanStatus.INTERNAL_ERROR
            throw e
        } finally {
            span.finish()
        }
    }

    /** What led up to a crash, attached to its report: the app's log lines ([AppLog]). */
    internal fun breadcrumb(message: String) {
        if (Sentry.isEnabled()) Sentry.addBreadcrumb(message)
    }

    private fun start() {
        val keys = keys ?: return
        SentryAndroid.init(context) { options ->
            options.dsn = keys.sentryDsn
            options.release = "osmand-companion@${keys.version}"
            options.environment = if (keys.debug) "debug" else "release"
            // No IP address, user or device name.
            options.isSendDefaultPii = false
            // AppLog's lines are the breadcrumbs; taps on views by id add little.
            options.isEnableUserInteractionBreadcrumbs = false
            // The usage counts ([signal]).
            options.metrics.isEnabled = true
            // Traces: app start, screen loads with slow and frozen frames, and the requests timed by [timed]. A
            // sample in releases keeps within the free plan.
            options.tracesSampleRate = if (keys.debug) 1.0 else TRACES_SAMPLE_RATE
        }
    }

    /** Off the main thread: closing waits up to a couple of seconds for what's being sent. */
    private fun stop() {
        Thread({
            Sentry.close()
            // Sentry's random id for this install (it keeps it in memory until the app restarts), and what wasn't
            // sent yet: opting back in can't be linked to before.
            File(context.filesDir, SENTRY_INSTALLATION_FILE).delete()
            File(context.cacheDir, SENTRY_CACHE_DIR).deleteRecursively()
        }, "Analytics").start()
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
