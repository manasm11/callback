package com.shopcallback.tracker.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `an address without a scheme gets http`() {
        assertEquals("http://shop-pc:8787", normalizeServerUrl("shop-pc:8787"))
        assertEquals("http://100.64.0.1:8787", normalizeServerUrl("  100.64.0.1:8787  "))
    }

    @Test
    fun `an address with a scheme is only trimmed`() {
        assertEquals("http://shop-pc:8787", normalizeServerUrl("  http://shop-pc:8787 "))
        assertEquals("https://shop-pc", normalizeServerUrl("https://shop-pc"))
    }

    @Test
    fun `a blank address stays empty so sync stays off`() {
        assertEquals("", normalizeServerUrl("   "))
    }
}
