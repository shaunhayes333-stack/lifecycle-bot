package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7541AllSpecialistSpineTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test fun all_twelve_specialists_have_distinct_runtime_role_surface() {
        val toolkit = src("engine/ToolkitSignalSheet.kt")
        val owner = src("engine/truth/MemeOwnershipInvariant6620.kt")
        val lanes = listOf("QUALITY","BLUECHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
            "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN")
        lanes.forEach {
            assertTrue("toolkit missing $it", toolkit.contains("\"$it\""))
            assertTrue("ownership missing $it", owner.contains("\"$it\""))
        }
        assertTrue(toolkit.contains("Setup.CASHFLOW_SCALP"))
        assertTrue(toolkit.contains("lanes = setOf(\"CASHGEN\")"))
        assertTrue(toolkit.contains("Setup.CYCLIC_COMPOUND"))
        assertTrue(toolkit.contains("lanes = setOf(\"CYCLIC\")"))
    }

    @Test fun core_is_executable_ensemble_not_trunk() {
        val p = src("engine/ExecutionAuthorityPolicy6533.kt")
        val trunk = p.substringAfter("private val trunk =").substringBefore("\n")
        assertFalse(trunk.contains("CORE"))
        val growth = src("engine/LiveGrowthDoctrine.kt")
        assertTrue(growth.contains("l == \"CORE\" || l.contains(\"CORE_ENSEMBLE\") -> \"CORE\""))
        assertTrue(growth.contains("\"CORE\", \"CYCLIC\", \"CASHGEN\""))
    }

    @Test fun cashgen_never_collapses_to_treasury_or_standard_in_growth_router() {
        val growth = src("engine/LiveGrowthDoctrine.kt")
        assertTrue(growth.contains("CASH_GENERATION\") -> \"CASHGEN\""))
        val agent = src("engine/AgenticStyleRouter.kt")
        assertTrue(agent.contains("\"CASHGEN\" -> if (fallback.lanes.contains(\"CASHGEN\")) fallback else Style.CASHFLOW_SCALP"))
    }

    @Test fun sealed_fdg_owner_replaces_preseal_caller_order() {
        val c = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(c.contains("PRESEAL_OWNER_REPLACED_BY_FDG_7541"))
        assertTrue(c.contains("val sealedFdgOwner6679 = sealedFdgOwnerLane6679(mint, candidateVersion)"))
        assertFalse(c.contains("val sealedFdgOwner6679 = if (existing == null)"))
    }
}
