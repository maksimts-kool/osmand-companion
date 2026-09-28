package dev.maksim.osmandsample

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.maksim.osmandsample.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), OsmAndConnection.Listener {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as SampleApp
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding()

        val plugin = app.plugin
        with(binding) {
            connectButton.setOnClickListener {
                if (!app.osmand.connect()) showOsmandMissing()
                app.osmand.checkAccess()
                onConnectionChanged(app.osmand.isConnected)
            }
            enableSwitch.isChecked = plugin.enabled
            enableSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) plugin.enable() else plugin.disable()
            }
            showTallinnButton.setOnClickListener { plugin.showTallinn() }
            addMarkerButton.setOnClickListener { plugin.addMarker(plugin.places.first()) }
            showPointButton.setOnClickListener { plugin.showPoint(plugin.places[1]) }
            bumpWidgetButton.setOnClickListener { plugin.bumpWidget() }
            routeButton.setOnClickListener { plugin.routeTo(plugin.places.last()) }
            stopNavigationButton.setOnClickListener { plugin.stopNavigation() }
            navUpdatesSwitch.setOnCheckedChangeListener { _, checked -> plugin.setNavigationUpdates(checked) }
            openOsmandButton.setOnClickListener { openOsmand() }
            clearLogButton.setOnClickListener { logText.text = "" }
        }
        logDeepLink(intent)
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
        listOf(
            binding.enableSwitch, binding.showTallinnButton, binding.addMarkerButton, binding.showPointButton,
            binding.bumpWidgetButton, binding.routeButton, binding.stopNavigationButton, binding.navUpdatesSwitch,
        ).forEach { it.isEnabled = connected && app.osmand.hasAccess }
    }

    override fun onLog(message: String) {
        binding.logText.append("${timeFormat.format(Date())}  $message\n")
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /** targetSdk 36 is always edge-to-edge, so keep content clear of the status and navigation bars. */
    private fun applySystemBarPadding() {
        val root = binding.root
        val base = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(base + bars.left, bars.top, base + bars.right, base + bars.bottom)
            insets
        }
    }

    private fun openOsmand() {
        val pkg = app.osmand.osmandPackage ?: app.osmand.findInstalledOsmand() ?: return showOsmandMissing()
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun showOsmandMissing() {
        Toast.makeText(this, R.string.osmand_missing, Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logDeepLink(intent)
    }

    private fun logDeepLink(intent: Intent?) {
        intent?.data?.let { onLog("Opened from OsmAnd menu via $it") }
    }
}
