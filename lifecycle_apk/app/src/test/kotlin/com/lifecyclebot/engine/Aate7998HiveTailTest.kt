package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7998HiveTailTest {
    @Test fun tailEvidenceIsPooledThroughTheHiveBrain() {
        val t = File("src/main/kotlin/com/lifecyclebot/engine/truth/TailHunter7996.kt").readText()
        assertTrue(t.contains("HiveEdge8000.net8000(\"TAIL|\$key\")"))
        assertTrue(t.contains("c.n = (local?.n ?: 0) + (hive?.get(0)?.toInt() ?: 0)"))
        val c = File("src/main/kotlin/com/lifecyclebot/collective/CollectiveLearning.kt").readText()
        assertTrue(c.contains("HiveEdge8000.sync8000(c, id)"))
        assertTrue(c.contains("HiveEdge8000.fastSync8000(c, id)"))
    }
}
