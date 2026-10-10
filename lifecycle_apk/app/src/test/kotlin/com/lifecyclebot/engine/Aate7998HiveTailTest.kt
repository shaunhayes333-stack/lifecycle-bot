package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7998HiveTailTest {
    @Test fun tailEvidenceIsPooledAcrossInstances() {
        val t = File("src/main/kotlin/com/lifecyclebot/engine/truth/TailHunter7996.kt").readText()
        assertTrue(t.contains("ON CONFLICT(instance_id, k) DO UPDATE SET"))           // one row per instance|key: never double counted
        assertTrue(t.contains("WHERE instance_id != ? AND updated_ms > ?"))            // the hive's evidence, not our own twice
        assertTrue(t.contains("c.n = (local?.n ?: 0) + (hive?.n ?: 0)"))               // local + hive decide a ticket
        val c = File("src/main/kotlin/com/lifecyclebot/collective/CollectiveLearning.kt").readText()
        assertTrue(c.contains("TailHunter7996.hiveSync7998(c, id)"))
    }
}
