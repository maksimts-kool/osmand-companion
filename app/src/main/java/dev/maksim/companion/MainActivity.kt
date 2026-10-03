package dev.maksim.companion

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.CompanionService
import dev.maksim.companion.core.FollowOsmAnd
import dev.maksim.companion.core.OsmAndConnection
import dev.maksim.companion.core.feature
import dev.maksim.companion.databinding.ActivityMainBinding
import dev.maksim.companion.databinding.DialogUpdateProgressBinding
import dev.maksim.companion.planner.PlannerFragment
import dev.maksim.companion.timetable.OpenedScreens
import dev.maksim.companion.timetable.OsmAndStopUi
import dev.maksim.companion.timetable.TimetableFeature
import dev.maksim.companion.timetable.TimetableFragment
import dev.maksim.companion.update.Release
import dev.maksim.companion.update.UpdateWorker
import dev.maksim.companion.update.Updater

/** Home: OsmAnd's connection status on top, one tab per feature, and the shared log. */
class MainActivity : AppCompatActivity(), OsmAndConnection.Listener, Updater.Listener {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as CompanionApp

    private var updateDialog: AlertDialog? = null
    private var analyticsDialog: AlertDialog? = null

    /** Follows a running update; see [showProgress]. */
    private var progressDialog: AlertDialog? = null
    private var progressBinding: DialogUpdateProgressBinding? = null

    /** Hide was tapped for the running update: the header's button follows it, and brings the popup back. */
    private var progressHidden = false

    /** Opened from the "update available" notification: show the dialog even if it was shown before. */
    private var updateRequested = false

    /** Android 8+ asks once whether this app may install apps; the update continues when it may. */
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstallUpdates()) Updater.available?.let { Updater.install(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding()

        with(binding) {
            connectButton.setOnClickListener {
                if (!app.osmand.connect()) toast(R.string.osmand_missing)
                app.osmand.checkAccess()
                onConnectionChanged(app.osmand.isConnected)
            }
            openOsmandButton.setOnClickListener { openOsmand() }
            updateButton.setOnClickListener {
                if (Updater.progress != null) {
                    progressHidden = false
                    onUpdateChanged()
                } else {
                    Updater.available?.let { showUpdateDialog(it) }
                }
            }
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) = showTab(TAB_IDS[tab.position])
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
        if (savedInstanceState == null) {
            if (isPlannerLink(intent)) openPlanner(intent) else showTab(R.id.tab_timetables)
            updateRequested = isUpdateLink(intent)
            Updater.checkIfStale()
            if (Analytics.shouldAsk()) askAnalytics()
            binding.root.postDelayed(::reportOpened, OPENED_REPORT_DELAY_MS)
        } else {
            // The tab bar doesn't keep its selection when recreated (e.g. night mode); the shown fragment does.
            supportFragmentManager.fragments.firstOrNull { !it.isHidden }?.tag?.toIntOrNull()?.let { selectTab(it) }
        }
        // Covers the reboot-less case: the service was killed, or the app was reinstalled.
        CompanionService.update(this)
    }

    /** OsmAnd's main menu items and a stop's "Trip to here" land here while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isTimetableLink(intent)) selectTab(R.id.tab_timetables)
        if (isPlannerLink(intent)) openPlanner(intent)
        if (isUpdateLink(intent)) {
            updateRequested = true
            onUpdateChanged()
        }
    }

    override fun onStart() {
        super.onStart()
        app.osmand.addListener(this)
        // The user may have just enabled us in OsmAnd → Plugins; re-check on every return.
        app.osmand.checkAccess()
        onConnectionChanged(app.osmand.isConnected)
        Updater.addListener(this)
        onUpdateChanged()
    }

    override fun onStop() {
        app.osmand.removeListener(this)
        Updater.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        updateDialog?.dismiss()
        // Going away, not answered: nothing to show next.
        analyticsDialog?.setOnDismissListener(null)
        analyticsDialog?.dismiss()
        progressDialog?.dismiss()
        super.onDestroy()
    }

    override fun onConnectionChanged(connected: Boolean) {
        val osmand = app.osmand
        val name = osmandName(osmand.osmandPackage)
        val (text, hint, color) = when {
            !connected -> Triple(getString(R.string.status_disconnected), R.string.status_disconnected_hint, R.color.status_error)
            !osmand.hasAccess -> Triple(getString(R.string.status_no_access, name), R.string.status_no_access_hint, R.color.status_warning)
            else -> Triple(getString(R.string.status_connected, name), null, R.color.status_ok)
        }
        with(binding) {
            statusText.text = text
            statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, color))
            statusHint.isVisible = hint != null
            hint?.let { statusHint.setText(it) }
            connectButton.isVisible = hint != null
            connectButton.setText(if (connected) R.string.connect_check else R.string.connect)
        }
    }

    /**
     * The header's update button and a popup ([showProgress]) follow the download; a new version pops up the
     * dialog once.
     */
    override fun onUpdateChanged() {
        Updater.takeConfirmIntent()?.let { startActivity(it) }
        val release = Updater.available
        val progress = Updater.progress
        showProgress(release, progress)
        with(binding.updateButton) {
            isVisible = release != null
            text = when {
                release == null -> null
                progress == null -> getString(R.string.update_button, release.version)
                progress >= 100 -> getString(R.string.update_installing)
                else -> getString(R.string.update_downloading, progress)
            }
        }
        // Not over the analytics question; it comes once that's answered.
        if (analyticsDialog?.isShowing == true) return
        if (release != null && progress == null && (updateRequested || Updater.shouldPrompt(release))) {
            updateRequested = false
            showUpdateDialog(release)
        }
    }

    /**
     * While an update downloads and installs, a popup with an animation and a progress bar, so it's clear that
     * something is happening. Hide leaves it to the header's button. It goes away when the update is done or
     * failed (with a message saying why), or when Android asks the user to confirm the install.
     */
    private fun showProgress(release: Release?, progress: Int?) {
        if (release == null || progress == null) {
            progressDialog?.dismiss()
            progressDialog = null
            progressBinding = null
            progressHidden = false
            Updater.takeError()?.let { Toast.makeText(this, getString(R.string.update_failed, it), Toast.LENGTH_LONG).show() }
            return
        }
        if (progressHidden) return
        val view = progressBinding ?: DialogUpdateProgressBinding.inflate(layoutInflater).also { progressBinding = it }
        if (progressDialog?.isShowing != true) {
            progressDialog = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.update_progress_title, release.version))
                .setView(view.root)
                .setCancelable(false)
                .setNegativeButton(R.string.update_hide) { _, _ ->
                    progressHidden = true
                    progressDialog = null
                    progressBinding = null
                }
                .show()
        }
        view.progress.setProgressCompat(progress, true)
        view.status.text = if (progress >= 100) getString(R.string.update_installing) else getString(R.string.update_downloading, progress)
    }

    fun showUpdateDialog(release: Release) {
        if (updateDialog?.isShowing == true) return
        val installed = getString(R.string.update_installed_version, Updater.CURRENT_VERSION)
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_dialog_title, release.version))
            .setMessage(listOf(installed, release.plainNotes).filter { it.isNotEmpty() }.joinToString("\n\n"))
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(release) }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_release_page) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
            }
            .show()
    }

    /** Asked once; both answers are as easy. Settings has the switch to change it later. */
    private fun askAnalytics() {
        analyticsDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.analytics_dialog_title)
            .setMessage(R.string.analytics_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.analytics_dialog_yes) { _, _ -> Analytics.setEnabled(true) }
            .setNegativeButton(R.string.analytics_dialog_no) { _, _ -> Analytics.setEnabled(false) }
            .setOnDismissListener { onUpdateChanged() }
            .show()
    }

    /**
     * How far setup got, once per launch (if the user opted in): where people get stuck. After a moment, so the
     * connection to OsmAnd is up by then.
     */
    private fun reportOpened() {
        if (isDestroyed) return
        val osmand = app.osmand
        val state = when {
            osmand.osmandPackage == null && osmand.findInstalledOsmand() == null -> "missing"
            !osmand.isConnected -> "disconnected"
            !osmand.hasAccess -> "noAccess"
            else -> "ready"
        }
        Analytics.signal(
            "App.opened",
            mapOf(
                "osmand" to state,
                "osmandApp" to (osmand.osmandPackage ?: "none"),
                "timetables" to feature<TimetableFeature>().isEnabled.toString(),
                "displayOverApps" to Settings.canDrawOverlays(this).toString(),
                "startWithOsmAnd" to FollowOsmAnd.isWatcherOn(this).toString(),
            ),
        )
    }

    private fun startUpdate(release: Release) {
        if (canInstallUpdates()) return Updater.install(release)
        toast(R.string.update_allow_install)
        installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }

    private fun canInstallUpdates() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    /** "OsmAnd+" rather than "net.osmand.plus". */
    private fun osmandName(pkg: String?): String? = pkg?.let {
        try {
            packageManager.getApplicationInfo(it, 0).loadLabel(packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            it
        }
    }

    private fun showTab(id: Int) {
        val tag = id.toString()
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments.forEach { if (it.tag != tag) transaction.hide(it) }
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.add(R.id.tabContent, newTab(id), tag)
        }
        transaction.commit()
    }

    private fun selectTab(id: Int) {
        binding.tabs.getTabAt(TAB_IDS.indexOf(id))?.select()
    }

    private fun newTab(id: Int): Fragment = when (id) {
        R.id.tab_log -> LogFragment()
        R.id.tab_trips -> PlannerFragment()
        else -> TimetableFragment()
    }

    private fun isTimetableLink(intent: Intent?) = intent?.data?.toString() == OsmAndStopUi.DEEP_LINK

    private fun isPlannerLink(intent: Intent?) =
        intent?.data?.let { it.scheme + "://" + it.host } == OsmAndStopUi.PLANNER_LINK

    /** The Trips tab, with where to go from the link; it came up, for the feature waiting to see if it would. */
    private fun openPlanner(intent: Intent) {
        val link = intent.data ?: return
        PlannerFragment.open(link)
        link.getQueryParameter(OsmAndStopUi.PARAM_STOP)?.let { OpenedScreens.resumed(this, OpenedScreens.Screen.PLANNER, it) }
        selectTab(R.id.tab_trips)
        // On screen already, it takes the link now; otherwise once it shows.
        (supportFragmentManager.findFragmentByTag(R.id.tab_trips.toString()) as? PlannerFragment)?.linkArrived()
    }

    private fun isUpdateLink(intent: Intent?) = intent?.getBooleanExtra(UpdateWorker.EXTRA_SHOW_UPDATE, false) == true

    /**
     * targetSdk 36 is always edge-to-edge: the header clears the status bar, the tabs the navigation bar, and
     * with the keyboard up everything sits above it.
     */
    private fun applySystemBarPadding() {
        val header = binding.header
        val base = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.updatePadding(top = base + bars.top)
            binding.root.updatePadding(left = bars.left, right = bars.right, bottom = ime.bottom)
            insets
        }
        // The tab bar clears the navigation bar, except with the keyboard up: the root is already above it.
        ViewCompat.setOnApplyWindowInsetsListener(binding.tabs) { tabs, insets ->
            val keyboardUp = insets.isVisible(WindowInsetsCompat.Type.ime())
            tabs.updatePadding(bottom = if (keyboardUp) 0 else insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
    }

    private fun openOsmand() {
        val pkg = app.osmand.osmandPackage ?: app.osmand.findInstalledOsmand() ?: return toast(R.string.osmand_missing)
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        /** The tabs in activity_main's tab bar, in order. */
        val TAB_IDS = listOf(R.id.tab_timetables, R.id.tab_trips, R.id.tab_log)

        const val OPENED_REPORT_DELAY_MS = 3000L
    }
}
