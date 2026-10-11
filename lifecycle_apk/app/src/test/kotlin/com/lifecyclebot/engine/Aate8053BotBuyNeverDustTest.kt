package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8053 — a bot buy whose sell route failed is never released as "dust" and left unmanaged (5.0.8051 Normie). */
class Aate8053BotBuyNeverDustTest {
    @Test fun routeFailureIsNotDust() {
        // Normie: bought 0.0306 SOL, worth ~0.0254 after a dip, routable minimum 0.0256 -> still a managed position.
        assertTrue(LiveCanonicalRecovery6686.dustIsRouteFailure8053(0.0306, 0.0254, 0.0256))
        // WBTC-style leftover after a sell: worth a fraction of a ticket -> dust as before.
        assertFalse(LiveCanonicalRecovery6686.dustIsRouteFailure8053(0.0306, 0.0010, 0.0256))
        // A holding never bought at routable size stays dust.
        assertFalse(LiveCanonicalRecovery6686.dustIsRouteFailure8053(0.0100, 0.0200, 0.0256))
    }

    @Test fun healReopensBotBuys() {
        val r = File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        assertTrue(r.contains("isDustUnroutable7714(mint) && !botJournalBought8053(mint)"))
        assertTrue(r.contains("position.quarantineReason.startsWith(\"DUST_UNROUTABLE_7714\") && botJournalBought8053(position.mint)"))
        val e = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(e.contains("LiveCanonicalRecovery6686.dustIsRouteFailure8053(p7714.costSol, valueSol7714, routableMin7714)"))
    }
}
