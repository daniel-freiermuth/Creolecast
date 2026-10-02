package com.creolecast.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiscoveryManager] is process-wide and shared by MainActivity and CastRouteProvider.
 * MainActivity used to call stopDiscovery() from onStop(), which killed discovery for the
 * still-bound route provider as soon as the app was backgrounded.
 */
class DiscoveryHoldersTest {

    @Test
    fun `releasing one holder keeps discovery running for another`() {
        val holders = DiscoveryHolders()
        holders.hold("CastRouteProvider")
        holders.hold("MainActivity")

        assertFalse(
            "MainActivity.onStop must not stop discovery the route provider still needs",
            holders.release("MainActivity")
        )
    }

    @Test
    fun `discovery stops once the last holder releases`() {
        val holders = DiscoveryHolders()
        holders.hold("MainActivity")

        assertTrue(holders.release("MainActivity"))
    }

    @Test
    fun `holding twice and releasing once frees the holder`() {
        // onStart can run repeatedly before onStop; one release must still let go.
        val holders = DiscoveryHolders()
        holders.hold("MainActivity")
        holders.hold("MainActivity")

        assertTrue(holders.release("MainActivity"))
    }

    @Test
    fun `releasing a holder that never held does not stop another`() {
        val holders = DiscoveryHolders()
        holders.hold("CastRouteProvider")

        assertFalse(holders.release("MainActivity"))
    }
}
