package com.creolecast.app.raop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaopTxtTest {
    @Test
    fun `et is a comma separated list`() {
        assertEquals(setOf(0, 1, 3, 5), RaopTxt.intList("0,1,3,5"))
        assertEquals(setOf(1), RaopTxt.intList(" 1 "))
        assertEquals(emptySet<Int>(), RaopTxt.intList(null))
    }

    @Test
    fun `RSA is used only when the receiver does not also accept plain audio`() {
        assertTrue(RaopTxt.requiresRsa(setOf(1)))
        assertTrue(RaopTxt.requiresRsa(setOf(1, 3)))
        assertFalse(RaopTxt.requiresRsa(setOf(0, 1)))
        assertFalse(RaopTxt.requiresRsa(setOf(0)))
        // A single "3" is FairPlay, not "bit 0 set".
        assertFalse(RaopTxt.requiresRsa(setOf(3)))
    }

    @Test
    fun `FairPlay or MFi only receivers are unsupported`() {
        assertTrue(RaopTxt.isSupported(emptySet()))
        assertTrue(RaopTxt.isSupported(setOf(0, 3, 5)))
        assertFalse(RaopTxt.isSupported(setOf(3)))
        assertFalse(RaopTxt.isSupported(setOf(4)))
    }
}
