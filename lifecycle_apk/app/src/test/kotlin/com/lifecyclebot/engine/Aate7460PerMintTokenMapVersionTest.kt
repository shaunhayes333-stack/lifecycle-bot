package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TokenMapVersionGuard6411
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7460PerMintTokenMapVersionTest {

    @Test fun unrelated_mint_bump_does_not_stale_other_mint() {
        TokenMapVersionGuard6411.resetForTest()
        val b = TokenMapVersionGuard6411.stamp7460("MINT_B")
        TokenMapVersionGuard6411.beginMappingGeneration7460("MINT_A", "test")
        assertTrue(
            TokenMapVersionGuard6411.guardMetricWrite(
                "MINT_B", "test", b.mappingVersion, b.laneRoutingVersion,
            )
        )
    }

    @Test fun newer_same_mint_owner_invalidates_old_owner() {
        TokenMapVersionGuard6411.resetForTest()
        val old = TokenMapVersionGuard6411.beginMappingGeneration7460("MINT_A", "old")
        val lane = TokenMapVersionGuard6411.currentLaneRoutingVersion("MINT_A")
        val newer = TokenMapVersionGuard6411.beginMappingGeneration7460("MINT_A", "new")
        assertTrue(newer > old)
        assertFalse(TokenMapVersionGuard6411.guardMetricWrite("MINT_A", "old_commit", old, lane))
        assertTrue(TokenMapVersionGuard6411.guardMetricWrite("MINT_A", "new_commit", newer, lane))
    }

    @Test fun token_map_owner_stamps_then_guards_shared_commit() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        assertTrue(s.contains("beginMappingGeneration7460("))
        assertTrue(s.contains("TOKEN_MAP_COMMIT_7460"))
        assertTrue(s.contains("TOKEN_MAP_SHARED_COMMIT_STALE_DROPPED_7460"))
        assertTrue(s.indexOf("beginMappingGeneration7460(") <
            s.indexOf("canonicalResultByMint6492[target] = detached6492(tm)"))
    }

    @Test fun dead_authority_marker_is_removed() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/TokenMapVersionGuard6411.kt").readText()
        assertFalse(s.contains("NO_CALLERS_7035"))
        assertTrue(s.contains("trackedMints="))
    }
}
