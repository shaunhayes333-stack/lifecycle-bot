package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7507ConcurrentMapMembershipCompileTest {
    @Test fun token_map_uses_explicit_concurrent_map_key_membership() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        val prune = s.substringAfter("private fun pruneStaleCache7505").substringBefore("private fun updateActivePeak")
        assertTrue(prune.contains("!activeHydrationByMint.containsKey(e.key)"))
        assertFalse(prune.contains("e.key !in activeHydrationByMint"))
    }

    @Test fun token_birth_compile_repair_remains_present() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/TokenBirthHydrator7441.kt").readText()
        assertFalse(s.contains(" in pendingByMint"))
    }
}
