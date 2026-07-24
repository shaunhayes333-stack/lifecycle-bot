package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * V5.0.6354 — Scanner emit + executor lane-contract wire-up into the
 * ScannerHydrationQueues6347 router.
 *
 * This is the wire-up promised in the V5.0.6351 header (SCANNER_LIVE_READY_QUEUE
 * pillar is advisory until 6354 lands). After 6354 the router receives:
 *   • HYDRATING/PROBATION rows from admitProtectedMemeIntake in BotService
 *   • LIVE_READY promotions when LaneEntryContract6342 allows a live entry
 *   • SHADOW routing when the contract vetoes a live entry
 */
class ScannerRouterWireUp6354Test {

    @Test
    fun intake_path_enqueues_into_hydrating_or_probation() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("intake path must enqueue into ScannerHydrationQueues6347",
            txt.contains("ScannerHydrationQueues6347.enqueue("))
        assertTrue("intake path must route probation to PROBATION bucket",
            txt.contains("ScannerHydrationQueues6347.Bucket.PROBATION"))
        assertTrue("intake path must route non-probation to HYDRATING bucket",
            txt.contains("ScannerHydrationQueues6347.Bucket.HYDRATING"))
    }

    @Test
    fun executor_promotes_to_live_ready_when_lane_contract_allows() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("executor must promote to LIVE_READY on contract-pass",
            txt.contains("ScannerHydrationQueues6347.Bucket.LIVE_READY"))
        assertTrue("promotion note must record contract passing",
            txt.contains("lane_contract_allowed"))
    }

    @Test
    fun executor_routes_to_shadow_when_lane_contract_vetoes() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("executor must route veto path to SHADOW bucket",
            txt.contains("ScannerHydrationQueues6347.Bucket.SHADOW"))
        assertTrue("veto note must carry contract verdict",
            txt.contains("lane_contract_"))
    }
}
