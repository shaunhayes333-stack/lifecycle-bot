package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7537TraderDesignMatrixTest {
    private val meme = listOf("QUALITY","BLUECHIP","SHITCOIN","CYCLIC","EXPRESS","CORE","MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN")

    @Test fun everyMemeSpecialistIsInCanonicalOwnershipAndRuntimeDeskSets() {
        val owner = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeOwnershipInvariant6620.kt").readText()
        val toolkit = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        meme.forEach {
            assertTrue("ownership missing $it", owner.contains("\"$it\""))
            assertTrue("runtime desk missing $it", toolkit.contains("\"$it\""))
        }
        assertTrue(owner.contains("setOf(\"STANDARD\", \"V3_CORE\")"))
        assertFalse(owner.contains("setOf(\"STANDARD\", \"V3_CORE\", \"CORE\""))
    }

    @Test fun allRegisteredTraderFamiliesRemainExplicitAndDistinct() {
        val enabled = File("src/main/kotlin/com/lifecyclebot/engine/EnabledTraderAuthority.kt").readText()
        listOf("MEME","SHITCOIN","MOONSHOT","EXPRESS","QUALITY","TREASURY","CASHGEN","BLUECHIP","MANIPULATED","DIP_HUNTER","PROJECT_SNIPER","CYCLIC","CRYPTO_ALT","MARKETS_STOCKS","PERPS","SHADOW_PAPER")
            .forEach { assertTrue("registered trader missing $it", enabled.contains(it)) }
    }

    @Test fun downstreamCausalStagesCannotBackfillTicketOrExec() {
        val funnel = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt").readText()
        assertTrue(funnel.contains("SPECIALIST_CAUSAL_ORPHAN_STAGE_7537"))
        assertFalse(funnel.contains("TICKET_INFERRED_FROM_\${stage.name}_6688"))
        assertFalse(funnel.contains("EXEC_INFERRED_FROM_OPEN_6688"))
        assertFalse(funnel.contains("EXPRESS_FUNNEL_TICKET_INFERRED_FROM_EXEC_6688"))
    }
}
