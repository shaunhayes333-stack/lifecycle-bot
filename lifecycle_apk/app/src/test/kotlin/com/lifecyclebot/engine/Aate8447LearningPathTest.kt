package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.8447 — the learning path through the ACTUAL Cortex publication (captureNow under its capture ownership), label
 * callback (onLabel) and checkpoint (pendingSnapshot8076 -> restorePending). No replacement scoring or runtime.
 *
 *  1. Two assessments of one token in one lane before the first label matures: each is graded on ITS OWN label. Before
 *     8444 the second capture marked the first lost and the first's priced label was dropped as LABEL_EPISODE_MISMATCH.
 *  2. Process death with the earlier episode still waiting: the checkpoint carries it, the restart restores it, and its
 *     label grades it (8447). No outcome is invented: an episode whose label never comes is not graded.
 */
class Aate8447LearningPathTest {
    private fun field(name: String): Any? = Cortex7885::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(Cortex7885)
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun call(name: String, vararg args: Any?): Any? = Cortex7885::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        .invoke(Cortex7885, *args)
    private fun owner() = field("captureOwners8397") as CortexCaptureOwnership8397
    private fun book() = field("gradeBook8144") as CortexGradeBook8144
    @Suppress("UNCHECKED_CAST") private fun pending() = field("pending") as MutableMap<String, Any>
    @Suppress("UNCHECKED_CAST") private fun superseded() = field("superseded8443") as MutableMap<String, Any>
    @Suppress("UNCHECKED_CAST") private fun paired() = field("pairedPending8401") as MutableMap<String, Any>
    private fun context(timing: String): Any = Class.forName("${Cortex7885::class.java.name}\$DecisionCtx8144")
        .declaredConstructors.single { it.parameterCount == 5 }.apply { isAccessible = true }
        .newInstance(timing, "C0_3", "TEST", true, false)
    private fun assessment(at: Long, score: Double) = Cortex7885.Assessment("MOONSHOT", true,
        doubleArrayOf(score), listOf("TEST_8447"), listOf(doubleArrayOf(0.0)), "TEST",
        CortexLedger7885.Fused(score, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()), score,
        if (score > 0) CortexScoreboard7885.Bucket.STRONG else CortexScoreboard7885.Bucket.REFUSE, 0, false, at)
    private fun capture(mint: String, at: Long, score: Double) {
        val ticket = owner().claim("$mint|MOONSHOT", at)!!
        call("captureNow", mint, "MOONSHOT", false, at, "TEST_8447", null, null, assessment(at, score), context("T$at"), "", "PAPER", ticket)
    }
    private fun label(mint: String, at: Long, net: Double) {
        val key = "$mint|MOONSHOT"
        val mark = LabelMark8386(mint, "TEST", 1.0 + net / 100.0, at + 5 * 60_000, System.currentTimeMillis(), "TEST")
        val proof = CortexOutcomeProof8395("$key|$at", 5 * 60_000L, mark.observedAtMs, net, net + 3.0, mark.copy(usd = 1.0, observedAtMs = at), mark)
        Cortex7885.onLabel(mint, "MOONSHOT", 60, net, net + 3.0, decisionAtMs8371 = at, proof8395 = proof)
    }
    private fun clean(mint: String, vararg ats: Long) {
        val key = "$mint|MOONSHOT"
        pending().remove(key)
        ats.forEach { superseded().remove("$key|$it"); paired().remove("$key|$it") }
        ats.maxOrNull()?.let { owner().terminal(key, it) }
    }

    @Test fun twoAssessmentsBeforeMaturityAreEachGradedOnTheirOwnLabel() {
        val mint = "learning-8447-two"; val key = "$mint|MOONSHOT"
        val at1 = System.currentTimeMillis() - 8 * 60_000; val at2 = at1 + 60_000
        try {
            capture(mint, at1, 5.0)
            capture(mint, at2, -3.0)
            assertEquals("the newer decision is the lane's pending one", at2, field(pending().getValue(key), "atMs"))
            assertTrue("the earlier one waits for its own label", superseded().containsKey("$key|$at1"))
            label(mint, at1, 7.0)
            assertEquals("the earlier decision's label is graded, not dropped", 7.0, book().decision("$key|$at1")!!.observedNetPct, 0.0)
            assertFalse(superseded().containsKey("$key|$at1"))
            label(mint, at2, -4.0)
            assertEquals(-4.0, book().decision("$key|$at2")!!.observedNetPct, 0.0)
            assertFalse(pending().containsKey(key))
        } finally { clean(mint, at1, at2) }
    }

    @Test fun anEpisodeWaitingAtProcessDeathIsRestoredAndGradedOnce() {
        val mint = "learning-8447-restart"; val key = "$mint|MOONSHOT"
        val at1 = System.currentTimeMillis() - 7 * 60_000; val at2 = at1 + 30_000
        try {
            capture(mint, at1, 4.0)
            capture(mint, at2, 2.0)
            @Suppress("UNCHECKED_CAST")
            val raw = (call("pendingSnapshot8076") as () -> String)()
            // process death: the in-memory episodes are gone; the checkpoint is what the next process reads
            pending().remove(key); superseded().remove("$key|$at1"); paired().remove("$key|$at1")
            call("restorePending", raw, System.currentTimeMillis())
            val restored = pending()[key]?.let { field(it, "atMs") == at1 } == true || paired().containsKey("$key|$at1")
            assertTrue("the waiting episode came back from the checkpoint", restored)
            label(mint, at1, 9.0)
            assertEquals(9.0, book().decision("$key|$at1")!!.observedNetPct, 0.0)
            label(mint, at1, 9.0)   // a redelivered label after the restart grades nothing twice
            assertEquals(9.0, book().decision("$key|$at1")!!.observedNetPct, 0.0)
        } finally { clean(mint, at1, at2) }
    }
}
