package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7481BoundedDeskStageDedupeTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()

    @Test fun stage_dedupe_is_bounded_and_timestamped() {
        val s = src()
        assertTrue(s.contains("deskStageOnce7481 = ConcurrentHashMap<String, Long>()"))
        assertTrue(s.contains("DESK_STAGE_TTL_MS_7481 = 1_800_000L"))
        assertTrue(s.contains("DESK_STAGE_SOFT_CAP_7481 = 48_000"))
        assertTrue(s.contains("DESK_STAGE_DEDUPE_CACHE_PRUNED_7481"))
    }

    @Test fun record_stage_uses_bounded_dedupe_helper() {
        val s = src()
        val fn = s.substringAfter("fun recordDeskStage").substringBefore("private val fanOutDebounce6626")
        assertTrue(fn.contains("firstDeskStage7481"))
        assertTrue(fn.contains("DESK_STAGE_DEDUPED_7214_"))
        assertFalse(fn.contains("deskStageOnce6599.add"))
    }

    @Test fun execution_authorities_are_not_modified_here() {
        val s = src()
        assertTrue(s.contains("SpecialistCausalFunnel6625.stamp6625"))
        assertTrue(s.contains("PendingIntentBacklog6625"))
    }
}
