package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8033 — the two registries that filled the heap are bounded, streamed and saved once a minute. */
class Aate8033RegistryHeapTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun memeRegistryIsBoundedAndStreamed() {
        assertEquals(5_000, MemeMintRegistry.MAX_MINTS_8033)
        assertEquals(4L * 1024 * 1024, MemeMintRegistry.MAX_RESTORE_BYTES_8033)
        val m = src("engine/MemeMintRegistry.kt")
        assertTrue(m.contains("private const val PERSIST_DEBOUNCE_MS = 60_000L"))
        assertTrue(m.contains("file.bufferedWriter().use { w ->"))
        assertFalse(m.contains("file.writeText(arr.toString())"))
        assertTrue(m.contains("MEME_MINT_REGISTRY_OVERSIZE_SET_ASIDE_8033"))
        assertEquals(0, MemeMintRegistry.capToMax8033())
    }

    @Test fun altRegistryIsBoundedAndStreamed() {
        assertEquals(3_000, com.lifecyclebot.perps.DynamicAltTokenRegistry.MAX_PERSIST_ROWS_8033)
        val a = src("perps/DynamicAltTokenRegistry.kt")
        assertTrue(a.contains(".take(MAX_PERSIST_ROWS_8033)"))
        assertFalse(a.contains("file.writeText(arr.toString())"))
        assertTrue(a.contains("ALT_REGISTRY_OVERSIZE_SET_ASIDE_8033"))
    }

    @Test fun heroValuesTokensOnlyAtAFreshMark() {
        assertTrue(src("ui/MainActivity.kt").contains("val px = heroFreshPrice8033(t.mint, t.currentPriceUsd, now)"))
    }
}
