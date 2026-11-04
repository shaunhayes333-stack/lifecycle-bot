package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.CortexVotersWide8004
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import org.junit.Assert.*
import org.junit.Test

class Aate8527CortexAgeProvenanceTest {
    @Test fun watchlistInsertionNeverBecomesTokenBirthInAnyAgeDependentVoter() {
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = "", symbol = "UNKNOWN")
        ts.addedToWatchlistAt = now - 60_000L
        assertNull(CortexVotersWide8004.observedAgeMs8527(ts, now))
        for (id in listOf("EXPERT_CELL_LIFT", "SCANNER_DISCOVERY_BONUS", "AGE_PATTERN_SCORE", "ML_ENTRY_CONFIDENCE")) {
            val voter = CortexVotersWide8004.VOTERS.single { it.id == id }
            assertNull(id + " must abstain without birth evidence", voter.read(ts, "MOONSHOT", now))
        }
        val watch = CortexVotersWide8004.VOTERS.single { it.id == "WATCHLIST_AGE_MIN" }
        assertEquals(1.0, watch.read(ts, "MOONSHOT", now)!!, 0.0)
    }

    @Test fun actualBirthSurvivesWatchlistRemovalAndReinsertion() {
        val now = System.currentTimeMillis()
        val mint = "8527CortexBirthProof".padEnd(44, 'C')
        AdmissionBirth8144.noteCreateFrame8144(mint, now - 10L * 60_000L)
        val ts = TokenState(mint = mint, symbol = "KNOWN")
        ts.addedToWatchlistAt = now - 1_000L
        assertEquals(600_000L, CortexVotersWide8004.observedAgeMs8527(ts, now)!!)
        ts.addedToWatchlistAt = now
        assertEquals(600_000L, CortexVotersWide8004.observedAgeMs8527(ts, now)!!)
    }

    @Test fun absentAndFutureSourceClocksCannotBecomeZeroMinuteLaunches() {
        val now = 1_800_000_000_000L
        for (birth in listOf(null, 0L, -1L, now + 1L, Long.MAX_VALUE)) {
            assertNull(CortexVotersWide8004.ageFromBirth8527(birth, now))
        }
        assertEquals(0L, CortexVotersWide8004.ageFromBirth8527(now, now)!!)
        assertEquals(60_000L, CortexVotersWide8004.ageFromBirth8527(now - 60_000L, now)!!)
        assertNull(CortexVotersWide8004.watchAgeMinutes8527(0L, now))
        assertNull(CortexVotersWide8004.watchAgeMinutes8527(now + 1L, now))
        assertEquals(0.0, CortexVotersWide8004.watchAgeMinutes8527(now, now)!!, 0.0)
    }
}
