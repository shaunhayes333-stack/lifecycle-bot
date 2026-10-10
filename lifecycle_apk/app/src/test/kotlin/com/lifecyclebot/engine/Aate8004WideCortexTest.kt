package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexVoters7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8004WideCortexTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theWholeEstateHasSeats() {
        val ids = CortexVoters7885.IDS
        assertEquals("voter ids are unique", ids.size, ids.toSet().size)
        assertTrue("static voters ${ids.size}", ids.size >= 280)
        listOf(
            "FRL_CELL_MEAN", "TAIL_TICKET", "SPECIALIST_MATCH", "CHART_MOTIF_LIFT", "STRUCT_RECLAIM", "EXPERT_ENTRY_LIVE",
            "MEME_META_KOTH", "HARVARD_APPROVAL_NUDGE", "HARVARD_ACCURACY_COMMITTEE", "TRUSTNET_COMMITTEE",
            "BEHAVIOR_LEARNING_ADJ", "LOSING_PATTERN_MEAN", "SCANNER_DISCOVERY_BONUS", "SMART_CHART_BIAS",
            "CROSSTALK_CONF_BOOST", "SYMBOLIC_VOTE", "WHALE_SCORE", "INSIDER_ACCUMULATION", "ORDERFLOW_STATE",
            "LAUNCH_PHASE", "ML_ENTRY_CONFIDENCE", "META_EXHAUSTION", "HOLDER_BLEED", "MINT_AUTHORITY_LIVE", "X_VELOCITY",
        ).forEach { assertTrue(it, ids.contains(it)); assertNotNull(it, CortexVoters7885.edgesFor(it)) }
    }

    @Test fun wideVotersAreWiredAndShareReads() {
        assertTrue(src("engine/cortex/CortexVoters7885.kt").contains(") + CortexVotersWide8004.VOTERS"))
        val w = src("engine/cortex/CortexVotersWide8004.kt")
        assertEquals(1, Regex("WhaleDetector\\.evaluate").findAll(w).count())
        assertTrue(src("engine/SymbolicVerdictRegistry.kt").contains("fun peekVote8004(mint: String): Double?"))
    }
}
