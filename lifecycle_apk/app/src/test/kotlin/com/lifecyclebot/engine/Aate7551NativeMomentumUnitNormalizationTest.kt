package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7551NativeMomentumUnitNormalizationTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    // V5.0.7758 — the producer now writes the same signed percent its readers expect.
    @Test fun producer_writes_signed_percent_momentum() {
        val d=src("engine/DataOrchestrator.kt")
        assertFalse(d.contains("ts.momentum = (50.0 + chg6852 * 2.0).coerceIn(0.0, 100.0)"))
        assertTrue(d.contains("ts.momentum = chg6852.coerceIn(-100.0, 1000.0)"))
    }

    @Test fun specialist_bridge_uses_signed_percent_not_centered_score() {
        // 8522 shares the same dated input reader with the real CASHGEN/BLUECHIP doors.
        val unobserved = ContinuationRead8348.Continuation8348(0.0, ContinuationRead8348.Source8348.UNOBSERVED)
        val history = SpecialistMarketInputs8522.historyMomentum8522(listOf(100.0, 95.0))
        assertEquals(-5.0, history!!, 1e-9)
        assertEquals(-6.0, SpecialistMarketInputs8522.signedMomentum8522(-6.0, history, unobserved)!!, 0.0)
        assertEquals(-5.0, SpecialistMarketInputs8522.signedMomentum8522(null, history, unobserved)!!, 1e-9)
        assertNull(SpecialistMarketInputs8522.signedMomentum8522(null, null, unobserved))
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("val mom = marketInputs8522.momentumPct ?: 0.0"))
    }

    @Test fun cache_tracks_same_momentum_evidence_brains_consume() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        val fp=b.substringAfter("private fun fp").substringBefore("fun snapshot")
        assertTrue(fp.contains("ts.lastPriceChange5m"))
        assertFalse(fp.contains("ts.momentum,ts.volatility"))
    }

    @Test fun five_signed_percent_native_lanes_receive_normalized_mom() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        listOf("BlueChipTraderAI.evaluate","ShitCoinTraderAI.evaluate","CashGenerationAI.evaluate")
            .forEach { assertTrue(b.contains(it)) }
        assertTrue(b.contains("momentum=shitcoinInputs8527.momentumPct"))
        assertTrue(b.contains("SpecialistInputParity8527.shitcoinInputs8527(marketInputs8522, ts.safety.firstBlockSupplyPct)"))
        assertTrue(b.contains(",v3,v3c,mom,vol"))
        // The other two lanes use shared preview/entry adapters, still in signed-percent units.
        val shared=src("engine/SpecialistInputParity8527.kt")
        val bot=src("engine/BotService.kt")
        for ((adapter,native) in listOf("express8527" to "ShitCoinExpress.evaluate",
            "manipulated8527" to "ManipulatedTraderAI.evaluate")) {
            assertTrue(b.contains("SpecialistInputParity8527.$adapter("))
            assertTrue(bot.contains("laneDoor8105(SpecialistInputParity8527.$adapter("))
            val body=shared.substringAfter("fun $adapter(").substringBefore("\n    fun ")
            assertTrue(body.contains(native))
        }
        assertTrue(shared.contains("i.momentumPct, i.buyPressurePct, i.volumeRatio, i.change5mPct"))
        assertTrue(shared.contains("read.momentumPct ?: 0.0, read.buyPressurePct ?: 50.0"))
        val read=SpecialistMarketInputs8522.Read(
            NativeHistory8521.Read(listOf(100.0,95.0),listOf(100.0,95.0),100.0,1.0,emptyList()),
            NativeLaneAge8521.Evidence(2.0,2.0),95.0,90.0,-5.0,-5.0,
            ContinuationRead8348.Continuation8348(-5.0,ContinuationRead8348.Source8348.FROM_TAPE),70,60)
        val express=SpecialistInputParity8527.expressInputs8527(read)
        assertEquals(-5.0,express.momentumPct,0.0)
        assertEquals(-5.0,express.change5mPct,0.0)
        assertEquals(90.0,express.buyPressurePct,0.0)
    }
}
