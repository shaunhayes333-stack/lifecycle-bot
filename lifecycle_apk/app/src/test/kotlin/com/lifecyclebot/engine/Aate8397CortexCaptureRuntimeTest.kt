package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses the actual Cortex publication and label callbacks; no replacement scoring/runtime implementation. */
class Aate8397CortexCaptureRuntimeTest {
    private fun field(name: String): Any? = Cortex7885::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(Cortex7885)
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun call(name: String, vararg args: Any?): Any? = Cortex7885::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        .invoke(Cortex7885, *args)
    private fun owner() = field("captureOwners8397") as CortexCaptureOwnership8397
    @Suppress("UNCHECKED_CAST") private fun pending() = field("pending") as MutableMap<String, Any>
    @Suppress("UNCHECKED_CAST") private fun contexts() = field("pendingCtx8144") as MutableMap<String, Any>
    private fun context(timing: String): Any = Class.forName("${Cortex7885::class.java.name}\$DecisionCtx8144")
        .declaredConstructors.single { it.parameterCount == 5 }.apply { isAccessible = true }
        .newInstance(timing, "C0_3", "TEST", true, false)
    private fun assessment(at: Long, score: Double) = Cortex7885.Assessment("MOONSHOT", true,
        doubleArrayOf(score), listOf("TEST_8397"), listOf(doubleArrayOf(0.0)), "TEST",
        CortexLedger7885.Fused(score, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()), score,
        if (score > 0) CortexScoreboard7885.Bucket.STRONG else CortexScoreboard7885.Bucket.REFUSE, 0, false, at)
    private fun publish(mint: String, at: Long, a: Cortex7885.Assessment, ctx: Any, ticket: CortexCaptureOwnership8397.Ticket) =
        call("captureNow", mint, "MOONSHOT", false, at, "SAFETY_TEST_8397", null, null, a, ctx, "", "PAPER", ticket)
    private fun clean(key: String, at: Long) {
        pending().remove(key); contexts().remove(key); owner().terminal(key, at)
    }

    @Test fun actualPublicationRejectsOlderWorkerAndKeepsNewerFrozenContext() {
        val mint = "capture-runtime-8397-order"
        val key = "$mint|MOONSHOT"
        val at = System.currentTimeMillis() - 1_000
        val old = owner().claim(key, at)!!
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val oldContext = context("OLD"); val newContext = context("NEW")
        try {
            val oldJob = workers.submit {
                val frozen = assessment(at, -8.0)
                started.countDown(); check(release.await(5, TimeUnit.SECONDS))
                publish(mint, at, frozen, oldContext, old)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val newer = owner().claim(key, at + 1)!!
            workers.submit { publish(mint, at + 1, assessment(at + 1, 12.0), newContext, newer) }.get(5, TimeUnit.SECONDS)
            release.countDown(); oldJob.get(5, TimeUnit.SECONDS)
            val p = pending().getValue(key)
            assertEquals(at + 1, field(p, "atMs"))
            assertSame(newContext, field(p, "context8372"))
            assertSame(newContext, contexts()[key])
            assertEquals(12.0, (field(p, "a") as Cortex7885.Assessment).calibratedEdge, 0.0)
        } finally { release.countDown(); workers.shutdownNow(); clean(key, at + 1) }
    }

    @Test fun actualUnavailableCallbackClosesAnInFlightCaptureBeforeItCanResurrect() {
        val mint = "capture-runtime-8397-terminal"; val key = "$mint|MOONSHOT"
        val at = System.currentTimeMillis() - 1_000
        val ticket = owner().claim(key, at)!!
        Cortex7885.onLabelLost8187(mint, "MOONSHOT", "OBSERVATION_DEADLINE_TEST", at)
        publish(mint, at, assessment(at, 9.0), context("TERMINAL"), ticket)
        assertFalse(pending().containsKey(key)); assertFalse(contexts().containsKey(key))
    }

    @Test fun actualFirstLabelWaitsForItsQueuedCaptureAndKeepsSafetyExclusion() {
        val mint = "capture-runtime-8397-label-first"; val key = "$mint|MOONSHOT"
        val at = System.currentTimeMillis() - 6 * 60_000
        val ticket = owner().claim(key, at)!!
        val mark = LabelMark8386(mint, "TEST", 1.1, at + 5 * 60_000, System.currentTimeMillis(), "TEST")
        val proof = CortexOutcomeProof8395("$key|$at", 5 * 60_000, mark.observedAtMs, 7.0, 10.0,
            mark.copy(usd = 1.0, observedAtMs = at), mark)
        try {
            Cortex7885.onLabel(mint, "MOONSHOT", 60, 7.0, 10.0, decisionAtMs8371 = at, proof8395 = proof)
            assertTrue(owner().awaiting(key, at))
            publish(mint, at, assessment(at, 11.0), context("UNEXECUTABLE"), ticket)
            assertFalse(pending().containsKey(key))
            val row = (field("gradeBook8144") as CortexGradeBook8144).decision("$key|$at")!!
            assertEquals(7.0, row.observedNetPct, 0.0)
            assertTrue("Waiting must not turn a safety-excluded decision into training", row.executablePct.isNaN())
            assertEquals("", row.modelContribution8395)
            assertFalse(owner().current(ticket))
        } finally { clean(key, at) }
    }

    @Test fun preparationFailureReleasesItsExactOwnerAndWaitingLabelWithoutClosingANewerEpisode() {
        val mint = "capture-runtime-8397-prepare"; val key = "$mint|MOONSHOT"
        val at = System.currentTimeMillis() - 1_000
        val ticket = owner().claim(key, at)!!
        val failure = IllegalStateException("feature freeze failed")
        Cortex7885.onLabel(mint, "MOONSHOT", 60, 7.0, 10.0, decisionAtMs8371 = at)
        fun failPreparation(t: CortexCaptureOwnership8397.Ticket, prepare: () -> Unit) {
            var caught: Throwable? = null
            try { call("prepareCapture8397", t, prepare) }
            catch (wrapped: java.lang.reflect.InvocationTargetException) { caught = wrapped.cause }
            assertSame(failure, caught)
        }
        try {
            failPreparation(ticket) { throw failure }
            assertFalse(owner().awaiting(key, at))
            assertFalse(owner().current(ticket))
            assertFalse((field("deferredLabels8397") as Map<*, *>).containsKey("$key|$at"))
            val second = owner().claim(key, at + 1)!!
            var newer: CortexCaptureOwnership8397.Ticket? = null
            failPreparation(second) {
                newer = owner().claim(key, at + 2)!!
                throw failure
            }
            assertTrue("A failed older preparation cannot close its successor", owner().current(newer!!))
            assertTrue(owner().awaiting(key, at + 2))
        } finally { clean(key, at + 2) }
    }
}
