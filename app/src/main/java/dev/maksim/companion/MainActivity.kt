package dev.maksim.companion

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
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

    private fun newTab(id: Int): Fragment = when (id) {
        R.id.tab_timetables -> TimetableFragment()
        R.id.tab_log -> LogFragment()
        else -> RouteLoggerFragment()
    }

    private fun isTimetableLink(intent: Intent?) = intent?.data?.toString() == OsmAndStopUi.DEEP_LINK

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
        // Replaces the tab bar's own handling, which pads it by the keyboard's height as well: with the root
        // already above the keyboard, that left a keyboard-sized blank under the tabs.
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
}
