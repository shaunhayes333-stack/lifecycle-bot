package com.lifecyclebot.engine

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class Aate8528OwnershipReportTest {
    @Before fun reset() = OwnershipChain8364.resetForTests8364()
    @After fun clear() = OwnershipChain8364.resetForTests8364()

    @Test fun expiredMissingReceiptNamesItsCandidateAndClock() {
        OwnershipChain8364.onDeskStage8364("QUALITY", "QUALIFIED", "FullMintIdentity:7", 1000L)
        val report = OwnershipChain8364.reportLine8364("QUALITY", 601001L)!!
        assertTrue(report.contains("IDLE_TTL=1"))
        assertTrue(report.contains("FullMintIdentity:v7:QUALIFIED:IDLE_TTL:lastSeen=1000:idleMs=600001"))
        assertTrue(report.contains("candidate records, not unique tokens"))
    }

    @Test fun capacityEvictionIsNotReportedAsARecordedTradeBlock() {
        repeat(4098) { OwnershipChain8364.onDeskStage8364("QUALITY", "QUALIFIED", "Mint$it:7", 1000L) }
        val report = OwnershipChain8364.reportLine8364("QUALITY", 1001L)!!
        assertTrue(report.contains("CAPACITY_EVICTION="))
        assertFalse(report.contains("IDLE_TTL="))
        assertTrue(report.contains("fdg=0/0"))
    }
}
