package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7616TwelveLaneExecutionParityContractTest {
    private fun src(rel:String)=File("src/main/kotlin/com/lifecyclebot/"+rel).readText()
    private val lanes=listOf(
        "QUALITY","BLUECHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
        "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN"
    )

    @Test fun canonicalTwelveArePresentAcrossBrainOwnershipAndDeskRegistry() {
        val brain=src("engine/SpecialistBrainBridge7542.kt")
        val ownership=src("engine/truth/MemeOwnershipInvariant6620.kt")
        val sheet=src("engine/ToolkitSignalSheet.kt")
        lanes.forEach { lane ->
            assertTrue("native brain missing "+lane, brain.contains("out[\""+lane+"\"]"))
            assertTrue("ownership contract missing "+lane, ownership.contains("\""+lane+"\""))
            assertTrue("specialist desk registry missing "+lane, sheet.contains("\""+lane+"\""))
        }
    }

    @Test fun executionBooksContainAllTwelveSpecialists() {
        val auth=src("engine/TradeAuthorizer.kt")
        val enumBlock=auth.substringAfter("enum class ExecutionBook").substringBefore("enum class TokenState")
        lanes.forEach { lane -> assertTrue("ExecutionBook missing "+lane, enumBlock.contains(lane)) }
    }

    @Test fun cyclicAndCashgenAreExplicitElectionLanes() {
        val coord=src("engine/LaneExecutionCoordinator.kt")
        assertTrue(coord.contains("\"CYCLIC\" to 50"))
        assertTrue(coord.contains("\"CASHGEN\" to 50"))
    }

    @Test fun cashgenDiagnosticsMatchDistinctCanonicalOwnership() {
        val sheet=src("engine/ToolkitSignalSheet.kt")
        assertTrue(sheet.contains("val ownershipModel7609 = \"SELF\""))
        assertFalse(sheet.contains("TREASURY_SHARED_EXEC_ALIAS"))
    }

    @Test fun manipulatedPaperRestorationContractRemainsPinned() {
        val bot=src("engine/BotService.kt")
        assertTrue(bot.contains("MANIPULATED_LIVE_BUYER_7395 = false"))
        assertTrue(bot.contains("RuntimeModeAuthority.isPaper() || MANIPULATED_LIVE_BUYER_7395"))
    }
}
