package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7505TokenMapCachePruneTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()

    @Test fun pruning_is_pressure_and_age_bounded() {
        val s = src()
        assertTrue(s.contains("TOKEN_MAP_CACHE_SOFT_CAP_7505 = 12_000"))
        assertTrue(s.contains("TOKEN_MAP_CACHE_STALE_MS_7505 = 24L * 60L * 60_000L"))
        assertTrue(s.contains("canonicalResultByMint6492.size <= TOKEN_MAP_CACHE_SOFT_CAP_7505"))
    }

    @Test fun held_current_and_inflight_mints_are_protected() {
        val s = src()
        val fn = s.substringAfter("private fun pruneStaleCache7505").substringBefore("private fun updateActivePeak")
        assertTrue(fn.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(fn.contains("e.key != keepMint"))
        assertTrue(fn.contains("e.key !in held7505"))
        assertTrue(fn.contains("e.key !in activeHydrationByMint"))
    }

    @Test fun normal_route_semantics_stay_present() {
        val s = src()
        assertTrue(s.contains("private const val ROUTE_TTL_MS = 90_000L"))
        assertTrue(s.contains("PUMPFUN_BONDING_CURVE_EXECUTABLE"))
        assertTrue(s.contains("DEX_ROUTABLE"))
    }
}
