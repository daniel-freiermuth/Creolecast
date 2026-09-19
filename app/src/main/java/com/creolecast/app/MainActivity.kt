package com.creolecast.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
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

    private lateinit var stateTextView: TextView
    private lateinit var castButton: MaterialButton
    private lateinit var discoveryButton: MaterialButton
    private lateinit var serverRecyclerView: RecyclerView
    private lateinit var statusCard: MaterialCardView
    private lateinit var syncSection: LinearLayout
    private lateinit var syncSliderContainer: LinearLayout

    lateinit var discoveryManager: DiscoveryManager
    private lateinit var serverListAdapter: ServerAdapter
    private lateinit var sharedPreferences: SharedPreferences


    private val _audioCastServiceFlow = MutableStateFlow<AudioCastService?>(null)
    val audioCastServiceFlow = _audioCastServiceFlow.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0)
    
    private val activeTouchHosts = mutableSetOf<String>()

    private var currentCardAnimator: ValueAnimator? = null

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
                    updateUi(state)
                    updateSyncUi()
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

    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK && it.data != null) {
            val server = selectedServer ?: return@registerForActivityResult
                val serviceIntent = Intent(this, AudioCastService::class.java).apply {
                    action = AudioCastService.ACTION_START
                    putExtra(AudioCastService.EXTRA_MEDIA_PROJECTION_TOKEN, it.data)
                    putExtra(AudioCastService.EXTRA_SERVER_HOST, server.host)
                    putExtra(AudioCastService.EXTRA_SERVER_PORT, server.port)
                    putExtra(AudioCastService.EXTRA_SERVER_NAME, server.name)
                    putExtra(AudioCastService.EXTRA_SERVER_PLATFORM, server.platform)
                    putExtra("com.creolecast.app.EXTRA_SERVER_EXTRA", server.extra)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            Toast.makeText(this, getString(R.string.media_projection_denied), Toast.LENGTH_SHORT).show()
        }
    }


    private val requestCastPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val recordAudioGranted = grants[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (recordAudioGranted) {
            startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // System won't show the request dialog again (permanently denied, or a device
            // policy) - re-requesting from here on is a silent no-op, so the only way
            // back in is the app's own Settings page.
            showOpenSettingsForPermissionDialog()
        } else {
            Toast.makeText(this, getString(R.string.record_audio_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private fun showOpenSettingsForPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.record_audio_permission_required))
            .setMessage(getString(R.string.record_audio_permission_permanently_denied))
            .setPositiveButton(getString(R.string.settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun beginCast() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        discoveryManager = (application as CreoleCastApp).discoveryManager

        stateTextView = findViewById(R.id.stateTextView)
        castButton = findViewById(R.id.castButton)
        discoveryButton = findViewById(R.id.discoveryButton)
        serverRecyclerView = findViewById(R.id.serverRecyclerView)
        statusCard = findViewById(R.id.statusCard)
        syncSection = findViewById(R.id.syncSection)
        syncSliderContainer = findViewById(R.id.syncSliderContainer)

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
            if (audioCastService?.state?.value == CastState.CASTING) {
                val serviceIntent = Intent(this, AudioCastService::class.java).apply {
                    action = AudioCastService.ACTION_STOP
                }
                startService(serviceIntent)
            } else {
                if (selectedServer != null) {
                    beginCast()
                } else {
                    Toast.makeText(this, getString(R.string.select_server_first), Toast.LENGTH_SHORT).show()
                }
            }
        }

        discoveryButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            isUserSelecting = false
            discoveryManager.startDiscovery()
            serverRecyclerView.scheduleLayoutAnimation()
        }
        


        lifecycleScope.launch {
            combine(discoveryManager.servers, _audioCastServiceFlow, _refreshTrigger) { servers, service, _ ->
                Pair(servers, service)
            }.collectLatest { (servers, service) ->
                serverListAdapter.submitList(servers)


                val lastHost = sharedPreferences.getString(AudioCastService.KEY_LAST_SERVER_HOST, null)
                
                if (isUserSelecting && selectedServer != null) {
                    val sel = selectedServer!!
                    val found = servers.find { it.host == sel.host && it.platform == sel.platform }
                        ?: servers.find { it.host == sel.host }
                    if (found != null) {
                        selectedServer = found
                        serverListAdapter.setSelectedItem(servers.indexOf(found))
                    }
                } else if (lastHost != null && selectedServer == null) {
                    val lastPlatform = sharedPreferences.getString(AudioCastService.KEY_LAST_SERVER_PLATFORM, null)
                    val lastServer = servers.find { it.host == lastHost && (lastPlatform == null || it.platform == lastPlatform) }
                        ?: servers.find { it.host == lastHost }
                    if (lastServer != null) {
                        selectedServer = lastServer
                        serverListAdapter.setSelectedItem(servers.indexOf(lastServer))
                    }
                }
                
                updateSyncUi()
            }
        }

        lifecycleScope.launch {
            discoveryManager.state.collectLatest {
                discoveryButton.text = when (it) {
                    DiscoveryState.SCANNING -> getString(R.string.scanning)
                    else -> getString(R.string.refresh)
                }
            }
        }
        
        lifecycleScope.launch {
            audioCastServiceFlow.collectLatest { s ->
                s?.pairingPinRequest?.collectLatest { host ->
                    if (host != null) {
                        showPairingPinDialog(host)
                    }
                }
            }
        }
        

    }


    private fun updateSyncUi() {
        val s = audioCastService
        if (s != null && s.state.value == CastState.CASTING) {
            val destinations = s.activeDestinations.value
            if (destinations.size > 1) {
                syncSection.visibility = View.VISIBLE
                
                val currentHosts = destinations.map { it.host }
                val existingSliders = mutableMapOf<String, View>()
                for (i in 0 until syncSliderContainer.childCount) {
                    val child = syncSliderContainer.getChildAt(i)
                    val host = child.tag as? String
                    if (host != null) {
                        if (host in currentHosts) {
                            existingSliders[host] = child
                        } else {
                            syncSliderContainer.removeView(child)
                        }
                    }
                }

                destinations.forEach { dest ->
                    var sliderItem = existingSliders[dest.host]
                    if (sliderItem == null) {
                        sliderItem = layoutInflater.inflate(R.layout.item_sync_slider, syncSliderContainer, false)
                        sliderItem.tag = dest.host
                        syncSliderContainer.addView(sliderItem)
                    }

                    val label = sliderItem.findViewById<TextView>(R.id.label)
                    val slider = sliderItem.findViewById<Slider>(R.id.slider)
                    
                    label.text = "${dest.name} (${dest.delayMs}ms)"
                    
                    if (!activeTouchHosts.contains(dest.host)) {
                        slider.apply {
                            valueFrom = 0f
                            valueTo = 2000f
                            stepSize = 20f
                            value = dest.delayMs.toFloat().coerceIn(0f, 2000f)
                            
                            clearOnChangeListeners()
                            addOnChangeListener { _, value, fromUser ->
                                if (fromUser) {
                                    s.setDelay(dest.host, value.toInt())
                                    label.text = "${dest.name} (${value.toInt()}ms)"
                                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                            
                            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                                override fun onStartTrackingTouch(slider: Slider) {
                                    activeTouchHosts.add(dest.host)
                                    slider.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                                override fun onStopTrackingTouch(slider: Slider) {
                                    activeTouchHosts.remove(dest.host)
                                    s.setDelay(dest.host, slider.value.toInt())
                                    slider.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                }
                            })
                        }
                    }
                }
            } else {
                syncSection.visibility = View.GONE
            }
        } else {
            syncSection.visibility = View.GONE
        }
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
        discoveryManager.startDiscovery()
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
        discoveryManager.stopDiscovery()
    }

    override fun onResume() {
        super.onResume()
        

        _refreshTrigger.value++
        updateSyncUi()
    }


    private fun showPairingPinDialog(host: String) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            hint = "0000"
        }
        
        val container = android.widget.FrameLayout(this).apply {
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(64, 32, 64, 32)
            layoutParams = params
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.airplay_pin_title)
            .setMessage(getString(R.string.airplay_pin_message, host))
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = input.text.toString()
                if (pin.isNotEmpty()) {
                    audioCastService?.submitPairingPin(host, pin)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Reset PIN request if canceled so it can be triggered again
                audioCastService?.resetPairingPinRequest()
            }
            .show()
    }

    private fun updateUi(state: CastState) {
        val oldStateText = stateTextView.text.toString()
        if (oldStateText != state.name) {
            stateTextView.animate().alpha(0f).setDuration(150).withEndAction {
                stateTextView.text = state.name
                stateTextView.animate().alpha(1f).setDuration(150).start()
            }.start()
        }
        
        castButton.isEnabled = (state == CastState.OFF && selectedServer != null) || state == CastState.CASTING
        
        val activeColor = ContextCompat.getColor(this, R.color.accent_blue)
        val idleColor = ContextCompat.getColor(this, R.color.light_grey)
        val surfaceColor = ContextCompat.getColor(this, R.color.surface_card)

        if (state == CastState.CASTING) {
            castButton.text = getString(R.string.stop)
            castButton.setIconResource(android.R.drawable.ic_media_pause)
            animateCardColors(idleColor, activeColor, surfaceColor, ColorUtils.setAlphaComponent(activeColor, 40))
        } else {
            castButton.text = getString(R.string.start)
            castButton.setIconResource(android.R.drawable.ic_media_play)
            animateCardColors(activeColor, idleColor, ColorUtils.setAlphaComponent(activeColor, 40), surfaceColor)
        }
    }

    private fun animateCardColors(fromStroke: Int, toStroke: Int, fromBg: Int, toBg: Int) {
        currentCardAnimator?.cancel()
        
        currentCardAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            val argbEvaluator = ArgbEvaluator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val strokeColor = argbEvaluator.evaluate(fraction, fromStroke, toStroke) as Int
                val bgColor = argbEvaluator.evaluate(fraction, fromBg, toBg) as Int
                
                statusCard.setStrokeColor(ColorStateList.valueOf(strokeColor))
                statusCard.setCardBackgroundColor(ColorStateList.valueOf(bgColor))
            }
            start()
        }
    }
}


class ServerAdapter(
    private val onServerClick: (Server) -> Unit,
    private val onDeleteClick: (Server) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

    private var servers = emptyList<Server>()
    private var selectedItem = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        holder.serverName.text = server.name
        holder.serverHost.text = if (server.platform != null) "${server.host} • ${server.platform}" else server.host
        
        val context = holder.itemView.context
        val colorRes = ContextCompat.getColor(context, R.color.accent_blue)

        if (selectedItem == position) {
            holder.cardView.setStrokeWidth(4)
            holder.cardView.setStrokeColor(ColorStateList.valueOf(colorRes))
            holder.icon.imageTintList = ColorStateList.valueOf(colorRes)
            holder.cardView.scaleX = 1.02f
            holder.cardView.scaleY = 1.02f
        } else {
            holder.cardView.setStrokeWidth(0)
            holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.light_grey))
            holder.cardView.scaleX = 1.0f
            holder.cardView.scaleY = 1.0f
        }

        if (server.platform == "Manual") {
            holder.moreButton.setImageResource(android.R.drawable.ic_menu_delete)
            holder.moreButton.visibility = View.VISIBLE
            holder.moreButton.setOnClickListener { 
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onDeleteClick(server) 
            }
        } else {
            holder.moreButton.setImageResource(android.R.drawable.ic_menu_more)
            holder.moreButton.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onServerClick(server)
            setSelectedItem(position)
        }
    }

    override fun getItemCount(): Int = servers.size

    fun submitList(newServers: List<Server>) {
        servers = newServers
        notifyDataSetChanged()
    }

    fun setSelectedItem(position: Int) {
        val previousItem = selectedItem
        selectedItem = position
        if (previousItem != -1) {
            notifyItemChanged(previousItem)
        }
        notifyItemChanged(selectedItem)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view as MaterialCardView
        val serverName: TextView = view.findViewById(R.id.serverName)
        val serverHost: TextView = view.findViewById(R.id.serverHost)
        val icon: ImageView = view.findViewById(R.id.serverIcon)
        val moreButton: ImageView = view.findViewById(R.id.moreButton)
    }
}
