package com.creolecast.app.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.creolecast.app.MediaCommand
import java.io.IOException
import kotlinx.coroutines.launch

private const val DACP_SERVICE_TYPE = "_dacp._tcp"

/**
 * Runs the [DacpServer] while an AirPlay cast is on and advertises it over mDNS as
 * `iTunes_Ctrl_<DACP-ID>`, the name receivers look up from our `DACP-ID` header. Remote
 * commands go to the phone's player, volume requests to [volume].
 */
internal class DacpAdvertiser(
    context: Context,
    ctx: CastContext,
    private val identity: AirPlayIdentity,
    volume: ReceiverVolume
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var server: DacpServer? = null
    private var registration: NsdManager.RegistrationListener? = null

    private val handler = object : DacpHandler {
        override fun onMediaCommand(command: MediaCommand) {
            ctx.scope.launch { ctx.controlCommands.emit(command) }
        }

        override fun onVolumeStep(direction: Int) = volume.adjust(direction)

        override fun onVolumeDb(dB: Double) = volume.applyReceiverDb(dB)
    }

    fun start() {
        if (server != null) return
        val dacp = DacpServer(identity.activeRemote, handler)
        val port = try {
            dacp.start()
        } catch (e: IOException) {
            Log.w(LOG_TAG, "DACP server failed to start: ${e.reason}")
            return
        }
        server = dacp
        val info = NsdServiceInfo().apply {
            serviceName = "iTunes_Ctrl_${identity.dacpId}"
            serviceType = DACP_SERVICE_TYPE
            setPort(port)
            setAttribute("txtvers", "1")
            setAttribute("Ver", "131075")
            setAttribute("DbId", identity.dacpId)
            setAttribute("OSsi", "0x1F5")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(LOG_TAG, "DACP advertised as ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(LOG_TAG, "DACP advertisement failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registration = listener
    }

    fun stop() {
        registration?.let { listener ->
            try {
                nsd.unregisterService(listener)
            } catch (e: IllegalArgumentException) {
                // Registration had failed, so there is nothing to unregister.
                Log.d(LOG_TAG, "DACP unregister: ${e.reason}")
            }
        }
        registration = null
        server?.stop()
        server = null
    }
}
