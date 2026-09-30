package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7551NativeMomentumUnitNormalizationTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun producer_documents_centered_momentum_score() {
        val d=src("engine/DataOrchestrator.kt")
        assertTrue(d.contains("ts.momentum = (50.0 + chg6852 * 2.0).coerceIn(0.0, 100.0)"))
        assertTrue(d.contains("50 = flat"))
    }

    @Test fun specialist_bridge_uses_signed_percent_not_centered_score() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        val block=b.substringAfter("val bp=").substringBefore("val v3=")
        assertFalse(block.contains("ts.momentum?.takeIf"))
        assertTrue(block.contains("val histMomentumPct7551"))
        assertTrue(block.contains("val mom = ts.lastPriceChange5m"))
        assertTrue(block.contains("((last - first) / first) * 100.0"))
    }

    @Test fun cache_tracks_same_momentum_evidence_brains_consume() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        val fp=b.substringAfter("private fun fp").substringBefore("fun snapshot")
        assertTrue(fp.contains("ts.lastPriceChange5m"))
        assertFalse(fp.contains("ts.momentum,ts.volatility"))
    }

    @Test fun five_signed_percent_native_lanes_receive_normalized_mom() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        listOf("BlueChipTraderAI.evaluate","ShitCoinTraderAI.evaluate","ShitCoinExpress.evaluate","ManipulatedTraderAI.evaluate","CashGenerationAI.evaluate")
            .forEach { assertTrue(b.contains(it)) }
        assertTrue(b.contains("momentum=mom"))
        assertTrue(b.contains(",mom,bp,"))
        assertTrue(b.contains(",v3,v3c,mom,vol"))
    }
}
