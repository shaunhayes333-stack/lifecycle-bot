package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainConfirm8950
import com.lifecyclebot.engine.truth.NativePriceEvidence9062
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate9062ChainPingTest {
    @Test fun missingNativeProofReachesTheExistingAnswerDrivenChainQueue() {
        val inputs = ChainConfirm8950.inputsOf8950(NativePriceEvidence9062.missingInputs9062(null, null))
        assertEquals(setOf(ChainConfirm8950.Input8950.PRICE_DEPTH, ChainConfirm8950.Input8950.SUPPLY), inputs)
        assertTrue(ChainConfirm8950.RERUN_USES_8957.contains("EVIDENCE"))
        assertFalse(ChainConfirm8950.needsTimedFallback8959(setOf(ChainConfirm8950.Input8950.PRICE_DEPTH)))
        val native = File("src/main/kotlin/com/lifecyclebot/engine/truth/NativeZoneLab9058.kt").readText()
        assertTrue(native.contains("if(mode==\"LIVE\" && admitted) requestProof9062(ts,basis,at)"))
        assertTrue(native.contains("ChainConfirm8950.confirm8950(ts,NativePriceEvidence9062.missingInputs9062"))
        assertTrue(native.contains("mark?.takeIf{it.mint==ts.mint}"))
        assertTrue(native.contains("if(actionable && !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper())requestProof9062(local,null,now)"))
        val crypto = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
            .substringAfter("if (wantsFreshBasis7275 && basis7275 == null)").substringBefore("val entryBasisSource7275")
        assertTrue(crypto.contains("if (!paper7803) try"))
        assertTrue(crypto.contains("ChainConfirm8950.confirmMint8950("))
        assertTrue(crypto.contains("cryptoAssetKey(signal, isSpot), listOf(\"PRICE\"), \"DECISION\""))
        assertTrue(crypto.contains("terminalDisposition6613(reason8870, \"PRE_SUBMIT\")"))
    }
}
