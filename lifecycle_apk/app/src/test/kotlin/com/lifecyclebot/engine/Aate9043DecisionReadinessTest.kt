package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real checkpoint, ledger and batch-owner behavior; no wallet, provider or fabricated live execution. */
class Aate9043DecisionReadinessTest {
    private fun clear() {
        PocketLedger8610.clearSeed9043(); PayingCell8723.clearSeed9043(); ManagedBands8719.clearSeed9043()
        AgreementPocket8874.clearSeed9043(); LlmBands8875.clearSeed9043()
    }
    private fun seed(row: JSONObject) {
        PocketLedger8610.seedRow8863(row); PayingCell8723.seedRow8863(row); ManagedBands8719.seedRow8863(row)
        AgreementPocket8874.seedRow8863(row); LlmBands8875.seedRow8863(row)
    }
    private fun snapshot(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            PocketLedger8610.writeSeed9043(it); PayingCell8723.writeSeed9043(it); ManagedBands8719.writeSeed9043(it)
            AgreementPocket8874.writeSeed9043(it); LlmBands8875.writeSeed9043(it)
        }
    }.toByteArray()
    private fun restore(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes)).use {
        PocketLedger8610.readSeed9043(it); PayingCell8723.readSeed9043(it); ManagedBands8719.readSeed9043(it)
        AgreementPocket8874.readSeed9043(it); LlmBands8875.readSeed9043(it)
        assertEquals(-1, it.read())
    }
    private fun row(i: Int): JSONObject {
        val mint = "D".repeat(38) + "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"[i % 58]
        val receipt = FrozenEdgeTarget9043.receipt9043(20.0, 15.0, 3.0, FrozenEdgeTarget9043.DECISION_FROZEN)!!
        return JSONObject("{\"mint\":\"$mint\",\"lane\":\"EXPRESS\",\"t\":${1_795_700_000_000L + i * 1_000L},\"horizonMs\":900000," +
            "\"net\":20,\"cost\":3,\"liq\":20000,\"mcap\":10000,\"age\":5,\"bp\":65,\"v3\":30,\"pred\":20,\"llmP\":0.8," +
            "\"phase\":\"EXPANDING\"" + receipt.fields9043() + "}")
    }

    @Test fun allFiveLedgersAndDeduplicationRestoreToTheSameTailResult() {
        clear()
        try {
            (0 until 40).forEach { seed(row(it)) }
            val prefix = snapshot()
            seed(row(40)); val uninterrupted = snapshot()
            clear(); restore(prefix); seed(row(40))
            assertArrayEquals("restored prefix plus one tail equals uninterrupted history", uninterrupted, snapshot())
            // Replay followed by a queued duplicate must not manufacture an extra managed-band observation.
            val managed = ByteArrayOutputStream().also { ManagedBands8719.writeSeed9043(DataOutputStream(it)) }.toByteArray()
            ManagedBands8719.seedRow8863(row(40))
            val again = ByteArrayOutputStream().also { ManagedBands8719.writeSeed9043(DataOutputStream(it)) }.toByteArray()
            assertArrayEquals(managed, again)
        } finally { clear() }
    }

    @Test fun timeoutAndInterruptCannotExecuteOutsideTheBatchPermit() {
        JobCost8813.pressureForTest9033 = "NORMAL"; JobCost8813.seedReadyForTest9043 = { true }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = Thread {
            JobCost8813.measureBackground8817("datasetSeed") { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
        try {
            owner.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            JobCost8813.turnWaitForTest9043 = 5L
            var executed = false
            try { JobCost8813.measureYielding8866("treeTrain") { executed = true }; fail("must defer") }
            catch (_: JobCost8813.BatchDeferred9043) {}
            assertFalse(executed)
            JobCost8813.turnWaitForTest9043 = 10_000L
            val started = CountDownLatch(1); val interrupted = AtomicBoolean(false); val ran = AtomicBoolean(false)
            val waiter = Thread {
                started.countDown()
                try { JobCost8813.measureYielding8866("treeParse") { ran.set(true) } }
                catch (_: InterruptedException) { interrupted.set(true) }
            }
            waiter.start(); assertTrue(started.await(5, TimeUnit.SECONDS)); waiter.interrupt(); waiter.join(5_000)
            assertFalse(waiter.isAlive); assertTrue(interrupted.get()); assertFalse(ran.get())
            assertTrue(JobCost8813.turnsLine9033().contains("ranWithoutTurn=0"))
        } finally {
            release.countDown(); owner.join(5_000); JobCost8813.pressureForTest9033 = null; JobCost8813.resetForTest8813()
        }
    }

    @Test fun researchAcquiresItsWorkingSetOnlyAfterDecisionEvidenceIsReady() {
        val order = ArrayList<String>()
        JobCost8813.pressureForTest9033 = "NORMAL"
        JobCost8813.seedReadyForTest9043 = { order.add("seed-ready"); true }
        try {
            JobCost8813.measureBackground8817("launchReplay") { order.add("research") }
            assertEquals(listOf("seed-ready", "research"), order)
            JobCost8813.seedReadyForTest9043 = { false }
            try { JobCost8813.measureBackground8817("launchReplay") { fail("must not allocate research before readiness") }; fail("must defer") }
            catch (_: JobCost8813.BatchDeferred9043) {}
        } finally { JobCost8813.pressureForTest9033 = null; JobCost8813.resetForTest8813() }
    }
}
