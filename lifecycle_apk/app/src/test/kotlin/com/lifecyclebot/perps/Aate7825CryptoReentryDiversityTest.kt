package com.lifecyclebot.perps

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7825CryptoReentryDiversityTest {
    private fun src()=File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()

    @Test fun closed_crypto_identity_has_bounded_reentry_cooldown_with_high_edge_escape() {
        val s=src()
        assertTrue(s.contains("cryptoLastClosedAt7825"))
        assertTrue(s.contains("CRYPTO_REENTRY_COOLDOWN_MS_7825 = 10L * 60_000L"))
        assertTrue(s.contains("CRYPTO_REENTRY_ESCAPE_SCORE_7825 = 70"))
        assertTrue(s.contains("CRYPTO_REENTRY_ESCAPE_COMBINED_7825 = 155"))
        val e=s.substringAfter("val reentryKey7825 = cryptoAssetKey(signal, isSpot).trim()")
            .substringBefore("// V5.9.198: Trust gate")
        assertTrue(e.contains("CRYPTO_REENTRY_COOLDOWN_7825"))
        assertTrue(e.contains("CRYPTO_REENTRY_HIGH_EDGE_ESCAPE_7825"))
        assertTrue(e.contains("terminalDisposition6613"))
    }

    @Test fun close_stamps_exact_canonical_asset_not_ticker() {
        val s=src()
        val c=s.substringAfter("val closedKey7825 = pos.canonicalAssetKey.trim()")
            .substringBefore("if (closedPositions.size > MAX_CLOSED_HISTORY)")
        assertTrue(c.contains("cryptoLastClosedAt7825[closedKey7825] = timestamp"))
        assertTrue(c.contains("CRYPTO_REENTRY_CLOSE_STAMPED_7825"))
    }
}
