package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7508CausalLaneIndexCorrectnessTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt").readText()

    @Test fun helper_creates_record_without_self_recursion() {
        val s = src()
        val fn = s.substringAfter("private fun getOrCreateRecord7480").substringBefore("private fun deindexRecord7480")
        assertTrue(fn.contains("records.computeIfAbsent(ks) { Record(key) }"))
        assertFalse(fn.contains("val rec = getOrCreateRecord7480(key)"))
    }

    @Test fun ordinary_stamp_path_also_populates_lane_index() {
        val s = src()
        val fn = s.substringAfter("fun stamp6625").substringBefore("fun stageCounts6625")
        assertTrue(fn.contains("val rec = getOrCreateRecord7480(key)"))
        assertFalse(fn.contains("records.computeIfAbsent"))
    }

    @Test fun lane_read_path_remains_indexed() {
        val s = src()
        assertTrue(s.contains("laneRecords7480(lane)"))
        assertTrue(s.contains("recordKeysByLane7480.computeIfAbsent(key.lane.uppercase())"))
    }
}
