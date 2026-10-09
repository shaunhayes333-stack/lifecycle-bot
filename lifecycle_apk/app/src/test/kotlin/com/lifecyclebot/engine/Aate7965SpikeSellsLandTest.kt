package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.CloseLease
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7965 — spike / runner-lock sells never wait out a backoff; losing residuals exit from today's mark. */
class Aate7965SpikeSellsLandTest {
    @Test fun urgentProfitReasons() {
        assertTrue(CloseLease.urgentProfitCapture7965("SPIKE_CAPTURE_7943_T3_532PCT"))
        assertTrue(CloseLease.urgentProfitCapture7965("RAPID_DRAWDOWN_FROM_PEAK_SETTLE_BYPASS_6080"))
        assertTrue(CloseLease.urgentProfitCapture7965("MONSTER_LOCK_T1"))
        assertTrue(CloseLease.urgentProfitCapture7965("CHART_CAPTURE_TOP_7950_TOP_MOTIF"))
        assertFalse(CloseLease.urgentProfitCapture7965("STALE_FLAT_CULL_7353"))
        assertFalse(CloseLease.urgentProfitCapture7965(null))
    }

    @Test fun wired() {
        val lease = File("src/main/kotlin/com/lifecyclebot/engine/sell/CloseLease.kt").readText()
        assertTrue(lease.contains("ProtectiveExitClass7807.isEmergency(rawReason) || urgentProfitCapture7965(rawReason)"))
        val rec = File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        assertTrue(rec.contains("if (!residualPriceUsable7962(r) || r < 1.0) return mark"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/SpikeCapture7943.kt").readText().contains("MAX_REARMS_7944 = 16"))
    }
}
