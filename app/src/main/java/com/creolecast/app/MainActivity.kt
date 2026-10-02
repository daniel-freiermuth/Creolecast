package com.creolecast.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var audioCastService: AudioCastService? = null
    private var isBound = false
    private var selectedServer: Server? = null
    private var isUserSelecting = false

    private lateinit var castButton: MaterialButton
    private lateinit var discoveryButton: MaterialButton
    private lateinit var serverRecyclerView: RecyclerView
    private lateinit var statusCard: MaterialCardView
    private lateinit var castStatusCard: CastStatusCard
    private lateinit var syncSliders: SyncSliderController

    lateinit var discoveryManager: DiscoveryManager
    private lateinit var serverListAdapter: ServerAdapter
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val DISCOVERY_HOLDER = "MainActivity"
    }

    private val _audioCastServiceFlow = MutableStateFlow<AudioCastService?>(null)
    val audioCastServiceFlow = _audioCastServiceFlow.asStateFlow()

    private val refreshTrigger = MutableStateFlow(0)

    // Bound to the current connection's lifetime, not the Activity's - onServiceConnected
    // fires again on every rebind (e.g. each time the app comes back to the foreground),
    // and without cancelling the previous one here each rebind piled on another live
    // collector on the same StateFlow that never got torn down.
    private var serviceStateJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioCastService.AudioCastBinder
            val s = binder.getService()
            audioCastService = s
            _audioCastServiceFlow.value = s
            isBound = true
            serviceStateJob?.cancel()
            serviceStateJob = lifecycleScope.launch {
                s.state.collectLatest { state ->
                    castStatusCard.render(state, hasSelectedServer = selectedServer != null)
                    syncSliders.update(audioCastService)
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioCastService = null
            _audioCastServiceFlow.value = null
            isBound = false
            serviceStateJob?.cancel()
            serviceStateJob = null
        }
    }

    private val startMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK && it.data != null) {
                val server = selectedServer ?: return@registerForActivityResult
                val serviceIntent = Intent(this, AudioCastService::class.java).apply {
                    action = AudioCastService.ACTION_START
                    putExtra(AudioCastService.EXTRA_MEDIA_PROJECTION_TOKEN, it.data)
                    putExtra(AudioCastService.EXTRA_SERVER_HOST, server.host)
                    putExtra(AudioCastService.EXTRA_SERVER_PORT, server.port)
                    putExtra(AudioCastService.EXTRA_SERVER_NAME, server.name)
                    putExtra(AudioCastService.EXTRA_SERVER_PLATFORM, server.platform.label)
                    putExtra("com.creolecast.app.EXTRA_SERVER_EXTRA", server.extra)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.media_projection_denied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val requestCastPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val recordAudioGranted = grants[Manifest.permission.RECORD_AUDIO]
                ?: (
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    )
            if (recordAudioGranted) {
                startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // System won't show the request dialog again (permanently denied, or a device
                // policy) - re-requesting from here on is a silent no-op, so the only way
                // back in is the app's own Settings page.
                showOpenSettingsForPermissionDialog()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.record_audio_permission_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun showOpenSettingsForPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.record_audio_permission_required))
            .setMessage(getString(R.string.record_audio_permission_permanently_denied))
            .setPositiveButton(getString(R.string.settings)) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun beginCast() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissionsNeeded.isNotEmpty()) {
            requestCastPermissions.launch(permissionsNeeded.toTypedArray())
        } else {
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    fun castToServer(server: Server) {
        selectedServer = server
        beginCast()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        discoveryManager = (application as CreoleCastApp).discoveryManager

        castButton = findViewById(R.id.castButton)
        discoveryButton = findViewById(R.id.discoveryButton)
        serverRecyclerView = findViewById(R.id.serverRecyclerView)
        statusCard = findViewById(R.id.statusCard)
        castStatusCard = CastStatusCard(statusCard, findViewById(R.id.stateTextView), castButton)
        syncSliders = SyncSliderController(
            layoutInflater,
            findViewById(R.id.syncSection),
            findViewById(R.id.syncSliderContainer)
        )

        serverListAdapter = ServerAdapter(
            onServerClick = { server ->
                statusCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                isUserSelecting = true
                castToServer(server)
            },
            onDeleteClick = { server ->
                statusCard.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                discoveryManager.removeServer(server)
            }
        )

        serverRecyclerView.apply {
            adapter = serverListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        castButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when {
                audioCastService?.state?.value == CastState.CASTING -> startService(
                    Intent(this, AudioCastService::class.java)
                        .setAction(AudioCastService.ACTION_STOP)
                )

                selectedServer != null -> beginCast()

                else -> Toast.makeText(
                    this,
                    getString(R.string.select_server_first),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        discoveryButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            isUserSelecting = false
            discoveryManager.startDiscovery()
            serverRecyclerView.scheduleLayoutAnimation()
        }

        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            // The service flow only re-triggers the list refresh; its value is read elsewhere.
            combine(discoveryManager.servers, _audioCastServiceFlow, refreshTrigger) {
                    servers,
                    _,
                    _
                ->
                servers
            }.collectLatest { servers -> onServersChanged(servers) }
        }

        lifecycleScope.launch {
            discoveryManager.state.collectLatest {
                discoveryButton.text = when (it) {
                    DiscoveryState.SCANNING -> getString(R.string.scanning)

                    DiscoveryState.IDLE, DiscoveryState.FOUND, DiscoveryState.NONE ->
                        getString(R.string.refresh)
                }
            }
        }

        lifecycleScope.launch {
            audioCastServiceFlow.collectLatest { s ->
                s?.pairingPinRequest?.collectLatest { host ->
                    if (host != null) {
                        showPairingPinDialog(
                            host,
                            onSubmit = { pin -> audioCastService?.submitPairingPin(host, pin) },
                            onCancel = { audioCastService?.resetPairingPinRequest() }
                        )
                    }
                }
            }
        }
    }

    private fun onServersChanged(servers: List<Server>) {
        serverListAdapter.submitList(servers)

        val lastPlatformLabel =
            sharedPreferences.getString(AudioCastService.KEY_LAST_SERVER_PLATFORM, null)
        val lastPlatform = CastPlatform.fromLabel(lastPlatformLabel)
        if (lastPlatformLabel != null && lastPlatform == null) {
            Log.e(TAG, "Ignoring unknown saved platform '$lastPlatformLabel'")
        }
        val serverToSelect = findServerToSelect(
            servers,
            current = selectedServer,
            isUserSelecting = isUserSelecting,
            lastHost = sharedPreferences.getString(AudioCastService.KEY_LAST_SERVER_HOST, null),
            lastPlatform = lastPlatform
        )
        if (serverToSelect != null) {
            selectedServer = serverToSelect
            serverListAdapter.setSelectedItem(servers.indexOf(serverToSelect))
        }

        syncSliders.update(audioCastService)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        statusCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, AudioCastService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        discoveryManager.startDiscovery(DISCOVERY_HOLDER)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            // unbindService() does NOT trigger onServiceDisconnected, so that callback
            // can't be relied on alone to cancel serviceStateJob here.
            unbindService(connection)
            isBound = false
            serviceStateJob?.cancel()
            serviceStateJob = null
        }
        // The DiscoveryManager is shared with CastRouteProvider; only stop it if nobody
        // else still needs it, or the system output picker stops seeing receivers.
        discoveryManager.stopDiscovery(DISCOVERY_HOLDER)
    }

    override fun onResume() {
        super.onResume()

        refreshTrigger.value++
        syncSliders.update(audioCastService)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

/**
 * Which discovered server the list should highlight after an update, or null to leave the
 * selection alone. A server the user picked is re-found in the new list (same host, preferring
 * the same platform); otherwise, with nothing selected yet, the last server cast to is restored.
 */
private fun findServerToSelect(
    servers: List<Server>,
    current: Server?,
    isUserSelecting: Boolean,
    lastHost: String?,
    lastPlatform: CastPlatform?
): Server? = when {
    isUserSelecting && current != null ->
        servers.find { it.host == current.host && it.platform == current.platform }
            ?: servers.find { it.host == current.host }

    lastHost != null && current == null ->
        servers.find {
            it.host == lastHost && (lastPlatform == null || it.platform == lastPlatform)
        }
            ?: servers.find { it.host == lastHost }

    else -> null
}
