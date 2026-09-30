package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7554DipHunterUnknownHolderChangeTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun dip_holder_change_is_nullable_and_unknown_is_neutral() {
        val d=src("v3/scoring/DipHunterAI.kt")
        assertTrue(d.contains("holderChange24h: Int?"))
        assertTrue(d.contains("holderChange24h == null -> 0"))
        assertTrue(d.contains("holderChange24h != null && holderChange24h < -10"))
    }

    @Test fun production_callers_do_not_fabricate_zero_holder_delta() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        val c=src("perps/CryptoAltTrader.kt")
        assertTrue(b.contains("holders,null,devSelling,bounce"))
        assertTrue(c.contains("holderChange24h = null"))
        assertFalse(c.contains("holderCount = 100, holderChange24h = 0"))
    }
}
