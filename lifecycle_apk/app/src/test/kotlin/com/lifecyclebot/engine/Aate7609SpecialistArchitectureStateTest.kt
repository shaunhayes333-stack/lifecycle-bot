package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7609SpecialistArchitectureStateTest {
    private fun src(rel:String)=File("src/main/kotlin/com/lifecyclebot/"+rel).readText()
    @Test fun reportShowsArchitectureState() {
        val s=src("engine/ToolkitSignalSheet.kt")
        assertTrue(s.contains("liveQuarantine="))
        assertTrue(s.contains("buyerEnabled="))
        assertTrue(s.contains("ownershipModel="))
        assertTrue(s.contains("TREASURY_SHARED_EXEC_ALIAS"))
    }
    @Test fun manipulatedFlagHasReadOnlySurface() {
        assertTrue(src("engine/BotService.kt").contains("fun manipulatedBuyerEnabled7609(): Boolean = MANIPULATED_IS_A_BUYER_7395"))
    }
}
