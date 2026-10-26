package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexEntrySeal8395
import com.lifecyclebot.engine.cortex.CortexEntrySeal8395.State
import org.junit.Assert.*
import org.junit.Test

class Aate8395EntrySealTest {
    private val k = "mint|LIVE|MOONSHOT"

    @Test fun fillBindsApprovingReadAEvenAfterReassessmentB() {
        val s = CortexEntrySeal8395<String>()
        assertTrue(s.seal(k, 7, "A", 1_000))
        assertFalse("a later reassessment cannot replace the sealed read", s.seal(k, 7, "B", 2_000))
        val r = s.resolve(k, 7)
        assertEquals(State.SEALED, r.state); assertEquals("A", r.read)
    }
    @Test fun missingReadStaysUnresolvedNeverLatest() {
        val s = CortexEntrySeal8395<String>()
        assertEquals(State.UNSEALED, s.resolve(k, 9).state)
        assertTrue(s.seal(k, 9, null, 1_000))
        assertEquals(State.PENDING, s.resolve(k, 9).state)
        assertTrue("a read decided after the seal is a reassessment, not A", s.fillLate(k, "B", 9_000, 5_000).isEmpty())
        assertEquals(State.PENDING, s.resolve(k, 9).state)
        assertEquals(listOf(9L), s.fillLate(k, "A", 900, 5_000))
        assertEquals("A", s.resolve(k, 9).read)
        assertTrue("filled once", s.fillLate(k, "C", 900, 5_000).isEmpty())
    }
    @Test fun versionsAndModesAreIsolated() {
        val s = CortexEntrySeal8395<String>()
        s.seal(k, 1, "A1", 1_000); s.seal(k, 2, "A2", 1_500); s.seal("mint|PAPER|MOONSHOT", 1, "P", 1_000)
        assertEquals("A1", s.resolve(k, 1).read); assertEquals("A2", s.resolve(k, 2).read)
        assertEquals("P", s.resolve("mint|PAPER|MOONSHOT", 1).read)
        s.release(k, 1); assertEquals(State.UNSEALED, s.resolve(k, 1).state)
        assertFalse(s.seal(k, 0, "X", 1)); assertFalse(s.seal("", 3, "X", 1))
    }
    @Test fun restartRestoresTheSealedRead() {
        val s = CortexEntrySeal8395<String>()
        s.seal(k, 5, "A", 1_000)
        val restored = CortexEntrySeal8395<String>()
        s.snapshot().forEach { (rk, e, v) -> restored.restore(rk, v, e.read, e.sealedAtMs) }
        assertEquals("A", restored.resolve(k, 5).read)
    }
    @Test fun runtimeWiringSealsAtPublicationAndBindsByVersion() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.substringAfter("private fun publishFdgIntent6519(").take(900)
            .contains("Cortex7885.sealEntryRead8395(intent.mint, intent.mode, intent.canonicalLane, intent.candidateVersion)"))
        val cx = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val bind = cx.substringAfter("fun bindPosition8076(").substringBefore("private fun bindRead8187(")
        assertTrue(bind.indexOf("entrySeals8395.resolve(rk, candidateVersion8395)") < bind.indexOf("val a = entryReads["))
        assertTrue(bind.contains("ENTRY_BIND_UNRESOLVED_UNSEALED_8395"))
        assertTrue(cx.contains("append(\",\\\"seal8395\\\":\")"))
        val cpa = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(cpa.contains("candidateVersion8395 = if (p.assetClass == AssetClass.SOLANA_TOKEN) candidateVersion7863 else 0L"))
    }
}
