package dev.maksim.companion

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import dev.maksim.companion.core.CompanionService
import dev.maksim.companion.core.OsmAndConnection
import dev.maksim.companion.databinding.ActivityMainBinding
import dev.maksim.companion.routelogger.RouteLoggerFragment
import dev.maksim.companion.timetable.OsmAndStopUi
import dev.maksim.companion.timetable.TimetableFragment

/** Home: OsmAnd's connection status on top, one tab per feature, and the shared log. */
class MainActivity : AppCompatActivity(), OsmAndConnection.Listener {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as CompanionApp

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
            tabs.setOnItemSelectedListener {
                showTab(it.itemId)
                true
            }
        }
        if (savedInstanceState == null) {
            binding.tabs.selectedItemId = if (isTimetableLink(intent)) R.id.tab_timetables else R.id.tab_trips
        }
        // Covers the reboot-less case: the service was killed, or the app was reinstalled.
        CompanionService.update(this)
    }

    /** OsmAnd's main menu item "Transit timetables" lands here while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isTimetableLink(intent)) binding.tabs.selectedItemId = R.id.tab_timetables
    }

    override fun onStart() {
        super.onStart()
        app.osmand.addListener(this)
        // The user may have just enabled us in OsmAnd → Plugins; re-check on every return.
        app.osmand.checkAccess()
        onConnectionChanged(app.osmand.isConnected)
    }

    override fun onStop() {
        app.osmand.removeListener(this)
        super.onStop()
    }

    override fun onConnectionChanged(connected: Boolean) {
        binding.statusText.text = when {
            !connected -> getString(R.string.status_disconnected)
            !app.osmand.hasAccess -> getString(R.string.status_no_access, app.osmand.osmandPackage)
            else -> getString(R.string.status_connected, app.osmand.osmandPackage)
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

    private fun newTab(id: Int): Fragment = when (id) {
        R.id.tab_timetables -> TimetableFragment()
        R.id.tab_log -> LogFragment()
        else -> RouteLoggerFragment()
    }

    private fun isTimetableLink(intent: Intent?) = intent?.data?.toString() == OsmAndStopUi.DEEP_LINK

    /** targetSdk 36 is always edge-to-edge: the header clears the status bar, the tabs the navigation bar. */
    private fun applySystemBarPadding() {
        val header = binding.header
        val base = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.updatePadding(top = base + bars.top)
            binding.root.updatePadding(left = bars.left, right = bars.right, bottom = ime.bottom)
            binding.tabs.updatePadding(bottom = if (ime.bottom > 0) 0 else bars.bottom)
            insets
        }
    }

    private fun openOsmand() {
        val pkg = app.osmand.osmandPackage ?: app.osmand.findInstalledOsmand() ?: return toast(R.string.osmand_missing)
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
