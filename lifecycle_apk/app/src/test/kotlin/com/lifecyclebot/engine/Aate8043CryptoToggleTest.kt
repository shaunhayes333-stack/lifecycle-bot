package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8043 — the crypto toggle stops live crypto entries; Solana coins belong to the meme stack. */
class Aate8043CryptoToggleTest {
    @Test fun toggleOffAndSolanaOwnershipAreHardReasons() {
        val ct = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(ct.contains("if (!paper7803 && cryptoToggleOff8043()) \"CRYPTO_TOGGLE_OFF_8043\" else null"))
        assertTrue(ct.contains("\"SOLANA_COIN_OWNED_BY_MEME_STACK_8043\""))
        // Entries only: the monitor loop's gate is untouched, so open crypto positions keep their exits.
        assertTrue(ct.contains("try { monitorPositions() }"))
    }
}
