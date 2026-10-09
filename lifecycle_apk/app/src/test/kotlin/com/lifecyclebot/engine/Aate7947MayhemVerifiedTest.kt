package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** V5.0.7947 — only the chain's supply convicts a mayhem coin; an implied read waits, then clears. */
class Aate7947MayhemVerifiedTest {
    @Test fun onlyTheChainConvicts() {
        assertEquals("MAYHEM", MayhemMode7943.verdict7947(2.0e9, 1.0e9, 0L))
        assertNull(MayhemMode7943.verdict7947(1.0e9, 2.0e9, 0L))
        assertEquals("WAIT", MayhemMode7943.verdict7947(0.0, 2.0e9, 10_000L))
        assertNull(MayhemMode7943.verdict7947(0.0, 2.0e9, 61_000L))
        assertNull(MayhemMode7943.verdict7947(0.0, 1.0e9, 0L))
    }
}
