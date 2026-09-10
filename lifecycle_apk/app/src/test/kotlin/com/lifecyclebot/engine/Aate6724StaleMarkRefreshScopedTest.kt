package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6724 — §STALE_MARK_REFRESH_SOLANA_SCOPED.
 *
 * Cross-asset mark provenance audit (5.0.6720 dump follow-up) proved
 * CanonicalPriceMark6522 is Solana-scoped by design — the publish()
 * barrier enforces `mark.baseMint == mark.mint` on mint-shaped identity,
 * and no perps/crypto/stock caller ever publishes to it. Iterating
 * cross-asset opens (`bsc|0x...`, `eth|0x...`, `robinhood|AAPL`) in the
 * exit-coordinator's proactive stale-mark refresh just wastes cycles
 * and inflates STALE_MARK_REFRESH_TRIGGERED counters with promotions
 * that can never succeed. The 6724 patch clamps the refresh to base58
 * Solana mints.
 */
class Aate6724StaleMarkRefreshScopedTest {

    @Test
    fun `stale mark refresh loop clamps to base58 solana mints`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6724 §STALE_MARK_REFRESH_SOLANA_SCOPED marker must appear",
            src.contains("V5.0.6724 §STALE_MARK_REFRESH_SOLANA_SCOPED"),
        )
        assertTrue(
            "Base58 Solana mint regex guard must exist",
            src.contains("val base58Solana6724 = Regex(\"^[1-9A-HJ-NP-Za-km-z]{32,44}\$\")"),
        )
        assertTrue(
            "guard must skip non-Solana opens before publish call",
            src.contains("if (!base58Solana6724.matches(p.mint)) continue"),
        )
    }
}
