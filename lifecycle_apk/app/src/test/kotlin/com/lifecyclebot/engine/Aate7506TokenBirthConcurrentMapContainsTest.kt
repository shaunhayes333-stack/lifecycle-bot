package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7506TokenBirthConcurrentMapContainsTest {
    @Test fun dormant_prune_uses_explicit_concurrent_map_key_semantics() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/TokenBirthHydrator7441.kt").readText()
        assertTrue(s.contains("!inFlight.contains(it.key)"))
        assertTrue(s.contains("!progress.containsKey(it.key)"))
        assertFalse(s.contains("it.key !in progress"))
    }
}
