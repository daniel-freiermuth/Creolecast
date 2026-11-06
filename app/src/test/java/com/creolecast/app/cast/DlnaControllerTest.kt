package com.creolecast.app.cast

import com.creolecast.app.CastDestination
import com.creolecast.app.CastPlatform
import com.creolecast.app.CastState
import com.creolecast.app.ExtraFields
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SSDP discovery lists every UPnP root device as DLNA, including routers and media servers
 * that have no AVTransport service. Casting to one must end in [CastState.ERROR]; it used
 * to stay in CONNECTING, with the Cast/Stop button disabled.
 */
class DlnaControllerTest {

    private fun startAndGetState(extra: String?): CastState {
        // Runs I/O inline; nothing on the failure paths under test is dispatched anyway.
        val inline = Executor { it.run() }.asCoroutineDispatcher()
        val ctx = CastContext(CoroutineScope(EmptyCoroutineContext), inline) {}
        ctx.state.value = CastState.CONNECTING
        val dest =
            CastDestination("FRITZ!Box 3272", "192.168.188.1", 0, CastPlatform.DLNA, extra = extra)

        runBlocking { DlnaController(ctx).start(dest) }

        return ctx.state.value
    }

    @Test
    fun `a device without any control URL ends the cast in ERROR`() {
        assertEquals(CastState.ERROR, startAndGetState(extra = null))
    }

    @Test
    fun `a device with only a RenderingControl URL ends the cast in ERROR`() {
        val extra = ExtraFields.encode("rc_control", "http://192.168.188.1:49000/rc")

        assertEquals(CastState.ERROR, startAndGetState(extra))
    }
}
