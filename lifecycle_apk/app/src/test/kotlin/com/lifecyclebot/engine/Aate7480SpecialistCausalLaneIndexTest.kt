package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7480SpecialistCausalLaneIndexTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt").readText()

    @Test fun lane_snapshots_use_secondary_index_not_global_scan() {
        val s = src()
        val snap = s.substringAfter("fun laneSnapshot6647").substringBefore("/** Resolve position/finality")
        assertTrue(snap.contains("laneRecords7480(lane)"))
        assertFalse(snap.contains("records.values"))
    }

    @Test fun record_creation_and_eviction_keep_index_in_sync() {
        val s = src()
        assertTrue(s.contains("getOrCreateRecord7480(key)"))
        assertTrue(s.contains("recordKeysByLane7480.computeIfAbsent"))
        assertTrue(s.contains("deindexRecord7480"))
        assertTrue(s.contains("records.remove(e.key, e.value)"))
    }

    @Test fun causal_records_and_soft_cap_are_preserved() {
        val s = src()
        assertTrue(s.contains("private val records = ConcurrentHashMap<String, Record>()"))
        assertTrue(s.contains("RECORD_SOFT_CAP_6899 = 12_000"))
        assertTrue(s.contains("Stage.DISCOVER"))
        assertTrue(s.contains("Stage.LEARN"))
    }
}
