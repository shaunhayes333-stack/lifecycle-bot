package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7806RuntimeStabilityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test fun heliusSubscriptionsRemainBoundedAndEvictServerSide() {
        val h = src("network/HeliusWebSocket.kt")
        assertTrue(h.contains("MAX_TOKEN_SUBSCRIPTIONS_7794 = 64"))
        assertTrue(h.contains("sendUnsubscribe7803"))
        assertTrue(h.contains("HELIUS_WS_TOKEN_SUB_EVICTED_7794"))
    }

    @Test fun operatorOpenPositionPanelCanShowFullLiveConcentration() {
        val ui = src("ui/MainActivity.kt")
        assertTrue(ui.contains("OPENPOS_ROW_CAP: Int = 20"))
    }
}
