package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7440CanonicalBirthTimeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun birth_authority_never_uses_observation_time() {
        val s = src("engine/truth/CanonicalTokenBirthTime7440.kt")
        assertTrue(s.contains("TOKEN_META_CREATION"))
        assertTrue(s.contains("PUMP_CREATE_EVENT"))
        assertTrue(s.contains("FIRST_POOL_CREATION"))
        assertFalse(s.contains("addedToWatchlistAt"))
        assertFalse(s.contains("history.firstOrNull"))
        assertFalse(s.contains("firstSeenMs"))
    }

    @Test fun launch_authority_has_explicit_hydration_phase() {
        val s = src("engine/truth/LaunchPhaseAuthority7401.kt")
        assertTrue(s.contains("METADATA_HYDRATING"))
        assertTrue(s.contains("CanonicalTokenBirthTime7440.resolve"))
        assertFalse(s.contains("val firstHistory ="))
        assertFalse(s.contains("val fallback = ts.addedToWatchlistAt"))
    }

    @Test fun sniper_and_moonshot_never_assume_unresolved_birth_is_fresh() {
        val sniper = src("v3/scoring/ProjectSniperAI.kt")
        val contract = src("engine/LaneEntryContract6342.kt")
        val moon = src("engine/truth/MoonshotFreshLaunchAdmission7044.kt")
        assertTrue(sniper.contains("BIRTH_METADATA_HYDRATING_7440"))
        assertFalse(sniper.contains("System.currentTimeMillis() - ts.addedToWatchlistAt"))
        assertTrue(contract.contains("SNIPER_BIRTH_HYDRATION_PENDING_7440"))
        assertTrue(moon.contains("BIRTH_METADATA_HYDRATING_7440"))
    }

    @Test fun v3_toolkit_stage_adapter_do_not_fabricate_age() {
        val v3 = src("v3/V3EngineManager.kt")
        val toolkit = src("engine/ToolkitSignalSheet.kt")
        val stage = src("engine/TokenMetricStageRouter.kt")
        val adapter = src("v3/bridge/V3Adapter.kt")
        assertTrue(v3.contains("CanonicalTokenBirthTime7440.resolvedAgeMinutes"))
        assertTrue(toolkit.contains("CanonicalTokenBirthTime7440.resolvedAgeMinutes"))
        assertTrue(stage.contains("CanonicalTokenBirthTime7440.resolvedAgeMinutes"))
        assertFalse(stage.contains("now - ts.addedToWatchlistAt"))
        assertTrue(adapter.contains("CanonicalTokenBirthTime7440.resolvedAgeMs"))
        assertFalse(adapter.contains("(now - discoveredAt).coerceAtLeast(0L)"))
    }
}
