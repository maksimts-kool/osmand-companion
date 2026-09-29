package dev.maksim.companion.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.WorkerThread
import dev.maksim.companion.BuildConfig
import dev.maksim.companion.core.AppLog
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the app up to date from [GitHubReleases]: checks when the app opens and once a day ([UpdateWorker]),
 * then streams the APK straight into an Android install session. Android installs it only if it's signed
 * with the same key as the installed app, and the download is checked against GitHub's SHA-256.
 *
 * State changes are announced on the main thread to [Listener]s.
 */
object Updater {

    fun interface Listener {
        fun onUpdateChanged()
    }

    const val CURRENT_VERSION = BuildConfig.VERSION_NAME

    private const val KEY_RELEASE = "release"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED = "notified_version"
    private const val KEY_PROMPTED = "prompted_version"
    private val LAUNCH_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10)

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val background = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    /** Main thread only, like the fields below. */
    var checking = false
        private set

    /** 0–100 while an update downloads, 100 while Android installs it, null otherwise. */
    var progress: Int? = null
        private set

    /** Android's "Do you want to update this app?" screen, waiting for an activity to show it. */
    private var confirmIntent: Intent? = null

    fun init(context: Context) {
        app = context.applicationContext
        UpdateWorker.schedule(app)
    }

    /** The newest release, while it's newer than this build. */
    val available: Release?
        get() = prefs.getString(KEY_RELEASE, null)
            ?.let { runCatching { Release.fromJson(it) }.getOrNull() }
            ?.takeIf { GitHubReleases.isNewer(it.version, CURRENT_VERSION) }

    /** When GitHub was last asked, in epoch millis; 0 if never. */
    val lastCheck: Long get() = prefs.getLong(KEY_LAST_CHECK, 0)

    /** Asks GitHub and remembers the answer. Throws [IOException] when GitHub can't be reached. */
    @WorkerThread
    fun checkNow(): Release? {
        val latest = try {
            GitHubReleases.latest()
        } catch (e: JSONException) {
            throw IOException("Unexpected answer from GitHub", e)
        }
        prefs.edit()
            .putString(KEY_RELEASE, latest?.toJson())
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
        return available?.also { AppLog.log("Update available: ${it.version} (installed $CURRENT_VERSION)") }
    }

    /** Checks in the background; [onDone] runs on the main thread. Skipped while a check is running. */
    fun check(onDone: ((Result<Release?>) -> Unit)? = null) {
        if (checking) return
        checking = true
        notifyListeners()
        background.execute {
            val result = try {
                Result.success(checkNow())
            } catch (e: IOException) {
                AppLog.log("Couldn't check for updates: ${e.message}")
                Result.failure(e)
            }
            mainHandler.post {
                checking = false
                notifyListeners()
                onDone?.invoke(result)
            }
        }
    }

    /** On app launch: checks unless it did a few minutes ago. */
    fun checkIfStale() {
        if (System.currentTimeMillis() - lastCheck > LAUNCH_CHECK_INTERVAL_MS) check()
    }

    /** True once per version: whether the app should pop up the update dialog on its own. */
    fun shouldPrompt(release: Release): Boolean {
        if (prefs.getString(KEY_PROMPTED, null) == release.version) return false
        prefs.edit().putString(KEY_PROMPTED, release.version).apply()
        return true
    }

    /** True once per version: whether the daily check should post a notification. */
    fun shouldNotify(release: Release): Boolean {
        if (prefs.getString(KEY_NOTIFIED, null) == release.version) return false
        prefs.edit().putString(KEY_NOTIFIED, release.version).apply()
        return true
    }

    /**
     * Downloads [release] and hands it to Android's installer. Needs "Install unknown apps" allowed for
     * this app (Android 8+); the caller asks for it. The process is replaced when the update installs.
     */
    fun install(release: Release) {
        if (progress != null) return
        progress = 0
        notifyListeners()
        AppLog.log("Downloading update ${release.version}")
        background.execute {
            try {
                downloadAndCommit(release)
            } catch (e: IOException) {
                mainHandler.post { onInstallFinished("download failed: ${e.message}") }
            }
        }
    }

    /**
     * The install screen Android wants shown; an activity in the foreground takes it and starts it. From
     * then on it's up to the user, so the update counts as no longer in progress: some Android versions
     * don't report it when that screen is backed out of.
     */
    fun takeConfirmIntent(): Intent? = confirmIntent?.also {
        confirmIntent = null
        progress = null
    }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    internal fun onConfirmationNeeded(intent: Intent) {
        confirmIntent = intent
        notifyListeners()
    }

    /** [error] is null on success, which is rarely seen: Android stops this process to replace it. */
    internal fun onInstallFinished(error: String?) {
        progress = null
        confirmIntent = null
        AppLog.log(if (error == null) "Update installed" else "Update not installed: $error")
        notifyListeners()
    }

    @WorkerThread
    private fun downloadAndCommit(release: Release) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (release.apkSize > 0) setSize(release.apkSize)
            // Once this app is the one that installed itself, Android 12+ updates it without asking again.
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            download(release, session)
            val intent = Intent(app, InstallResultReceiver::class.java)
            // Mutable: the installer adds the result to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(app, sessionId, intent, flags).intentSender)
            mainHandler.post {
                progress = 100
                notifyListeners()
            }
        } catch (e: Exception) {
            session.abandon()
            throw e as? IOException ?: IOException(e.message, e)
        } finally {
            session.close()
        }
    }

    @WorkerThread
    private fun download(release: Release, session: PackageInstaller.Session) {
        // GitHub redirects to its download host; HttpURLConnection follows https → https redirects.
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            session.openWrite("update.apk", 0, total).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        // Kept below 100, which means "installing".
                        val percent = if (total > 0) (done * 99 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            mainHandler.post {
                                if (progress != null) progress = percent
                                notifyListeners()
                            }
                        }
                    }
                }
                session.fsync(out)
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !sha256.equals(release.sha256, ignoreCase = true)) {
                throw IOException("the APK doesn't match GitHub's checksum")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun notifyListeners() = listeners.toList().forEach { it.onUpdateChanged() }
}
