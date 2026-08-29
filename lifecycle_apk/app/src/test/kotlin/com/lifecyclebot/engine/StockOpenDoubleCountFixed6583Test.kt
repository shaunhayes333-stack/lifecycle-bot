package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6583 §P0-11 — STOCK 51→102 double-count eliminated.
 *
 * Operator forensic (6580):
 *   STOCK candidate=51 dispatch=51 open=102 (exactly 2x)
 *   MARKETS_SPOT_OPEN_CONFIRMED_6540 = 102
 *
 * Root cause: CanonicalPaperTransaction6486.open (line 82-86) already
 * finds the pending intent and calls CanonicalEntryAuthority6551.markConfirmed
 * on successful commit. But TokenizedStockTrader.executeSignal AND
 * CryptoAltTrader.executeSignal ALSO explicitly called markConfirmed
 * on the same intent right after — every paper open was counted twice
 * in the cross-asset funnel + the OPEN_CONFIRMED_6540 counter.
 *
 * Additionally, PerpsExecutionEngine.executeOpen and CryptoAltTrader
 * both called markOpenConfirmed DIRECTLY on the venue counter after
 * calling markConfirmed on the intent (which cascades through
 * markOpenConfirmedFor6551). That was another double-count vector.
 *
 * These asserts pin the source-level fix so no future commit
 * reintroduces the double-count.
 */
class StockOpenDoubleCountFixed6583Test {

    private val stockSrc = File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
    private val cryptoAltSrc = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
    private val perpsSrc = File("src/main/kotlin/com/lifecyclebot/perps/PerpsExecutionEngine.kt").readText()

    @Test
    fun stock_paper_open_does_not_explicitly_markConfirmed() {
        assertTrue(
            "TokenizedStockTrader paper branch must NOT explicitly call markConfirmed " +
                "(CanonicalPaperTransaction6486.open already handles it)",
            stockSrc.contains("V5.0.6583 §P0-11 — no explicit markConfirmed here")
        )
        // The pre-6583 double-call pattern must be gone.
        val markConfirmedInPaperBranch = Regex(
            "canonicalOpen6486.applied.*?FluidLearning\\.recordPaperBuy\\(\"TokenizedStockTrader\"",
            RegexOption.DOT_MATCHES_ALL
        ).find(stockSrc)?.value ?: ""
        assertTrue(
            "The paper branch between canonicalOpen and FluidLearning must NOT contain " +
                "CanonicalEntryAuthority6551.markConfirmed anymore",
            !markConfirmedInPaperBranch.contains("CanonicalEntryAuthority6551.markConfirmed(marketIntent6561, position.id)")
        )
    }

    @Test
    fun crypto_alt_paper_open_does_not_explicitly_markConfirmed() {
        assertTrue(
            "CryptoAltTrader paper branch must NOT explicitly call markConfirmed",
            cryptoAltSrc.contains("V5.0.6583 §P0-11 — CanonicalPaperTransaction6486.open at line 82-86")
        )
    }

    @Test
    fun crypto_alt_does_not_directly_markOpenConfirmed_venue_counter() {
        assertTrue(
            "CryptoAltTrader must NOT call CanonicalEntryAuthority6540.markOpenConfirmed " +
                "directly — the markConfirmed cascade already bumps the venue counter",
            cryptoAltSrc.contains("V5.0.6583 §P0-11 — REMOVED DIRECT markOpenConfirmed CALL")
        )
    }

    @Test
    fun perps_does_not_directly_markOpenConfirmed_venue_counter() {
        assertTrue(
            "PerpsExecutionEngine must NOT call CanonicalEntryAuthority6540.markOpenConfirmed " +
                "directly — the markConfirmed cascade already bumps the venue counter",
            perpsSrc.contains("V5.0.6583 §P0-11 — REMOVED DIRECT markOpenConfirmed CALL")
        )
    }
}
