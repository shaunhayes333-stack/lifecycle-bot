package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.cortex.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.*
import java.util.concurrent.*

class Aate8401DeliveryTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun call(owner: Any, name: String, vararg args: Any?): Any? = owner.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(owner, *args)

    @Test fun companionCompressionPreservesFrozenPredictionsAndRejectsCorruption() {
        val raw = "{\"predictions\":[" + List(1100) { "[0.1,0.7,12.3]" }.joinToString(",") + "]}"
        val packed = ForwardCheckpoint8401.pack(raw)
        assertTrue(packed.length < raw.length / 5)
        assertEquals(raw, ForwardCheckpoint8401.unpack(packed))
        assertEquals(raw, ForwardCheckpoint8401.unpack(raw))
        assertThrows(Exception::class.java) { ForwardCheckpoint8401.unpack("gzip8401:bad") }
    }

    @Test fun blockedVerdictsAreNotReportedAsMissingIntentOrDeadDiscovery() {
        assertEquals("FDG_BLOCKED_ALL", ToolkitSignalSheet.preExecutionStatus8401(100, 10, 0, 0, 50))
        assertEquals("FDG_BLOCKED_ALL", ToolkitSignalSheet.preExecutionStatus8401(0, 0, 0, 0, 1))
        assertEquals("INTENT_CHOKED", ToolkitSignalSheet.preExecutionStatus8401(10, 5, 0, 1, 2))
        assertEquals("FDG_CHOKED", ToolkitSignalSheet.preExecutionStatus8401(10, 5, 0, 0, 0))
        assertNull(ToolkitSignalSheet.preExecutionStatus8401(10, 5, 1, 1, 2))
    }

    @Test fun bothRealShadowProofCallersUseTheFastLaneBarAndLosingCohortsStayHeld() {
        val owner = EvidenceShadow8344
        val book = field(owner, "closes").get(owner) as ShadowCohortBook8397
        owner.resetForTests8345()
        try {
            for (lane in listOf("MOONSHOT", "QUALITY", "CORE")) repeat(15) {
                book.append(listOf(lane), doubleArrayOf(1000.0 + it, if (lane == "CORE") -10.0 else 10.0,
                    1.0, ShadowPersist8355.TRADE_CONTRACT_8394.toDouble(), 1000.0 + it), "$lane|$it")
            }
            // superseded by V5.0.8418 — the 15-close fast bar is retired (EvidenceHold8336.minN8399 = 30 for every lane): 15
            // shadow closes no longer prove MOONSHOT (was assertTrue for both, and "MOONSHOT n15/15" below).
            assertFalse(owner.proof8344("MOONSHOT", 0).proven)
            assertFalse(owner.advisoryProof8393("MOONSHOT", 0).proven)
            assertFalse(owner.proof8344("QUALITY", 0).proven)
            assertFalse(owner.advisoryProof8393("QUALITY", 0).proven)
            assertFalse(owner.proof8344("CORE", 0).proven)
            assertFalse(owner.advisoryProof8393("CORE", 0).proven)
            assertTrue(owner.recoveryLine8345(0, 2000).contains("MOONSHOT n15/30"))
        } finally { owner.resetForTests8345() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun acknowledgedFirstBatchCannotStarveLaterTerminals() {
        val owner = ForwardReturnLabeler7731
        val outbox = field(owner, "terminalOutbox8395").get(owner) as MutableMap<String, String>
        val ack = field(owner, "deliveredTerminals8395").get(owner) as MutableSet<String>
        val saved = HashMap(outbox); val savedAck = HashSet(ack)
        try {
            outbox.clear(); ack.clear()
            repeat(160) { outbox["terminal|$it"] = "{}" }
            ack.addAll(outbox.keys.take(64))
            val first = call(owner, "terminalBatch8401") as List<Map.Entry<String, String>>
            assertEquals(64, first.size); assertTrue(first.none { it.key in ack })
            ack.addAll(first.map { it.key })
            val second = call(owner, "terminalBatch8401") as List<Map.Entry<String, String>>
            assertEquals(32, second.size); assertTrue(second.none { it.key in ack })
        } finally { outbox.clear(); outbox.putAll(saved); ack.clear(); ack.addAll(savedAck) }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun failedAcknowledgementCommitRetainsOutboxAndSuccessfulRetryRemovesOldObservation() {
        val owner = ForwardReturnLabeler7731
        val outbox = field(owner, "terminalOutbox8395").get(owner) as MutableMap<String, String>
        val ack = field(owner, "deliveredTerminals8395").get(owner) as MutableSet<String>
        val prefsField = field(owner, "prefs"); val oldPrefs = prefsField.get(owner)
        val saved = HashMap(outbox); val savedAck = HashSet(ack)
        val prefs = mock(android.content.SharedPreferences::class.java)
        val editor = mock(android.content.SharedPreferences.Editor::class.java)
        val id = "terminal8395|mint|MOONSHOT|1000"
        val row = MutableList(17) { "0" }.also { it[0] = "mint"; it[4] = "MOONSHOT"; it[12] = "1000" }
        val separator = field(owner, "FIELD_SEP_7735").get(owner) as Char
        `when`(prefs.getString(anyString(), isNull())).thenReturn(row.joinToString(separator.toString()))
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false, true)
        try {
            prefsField.set(owner, prefs); outbox.clear(); ack.clear(); outbox[id] = "{}"; ack += id
            call(owner, "cleanupTerminals8401")
            assertTrue(id in outbox); assertTrue(id in ack)
            call(owner, "cleanupTerminals8401")
            assertFalse(id in outbox); assertFalse(id in ack)
            verify(editor, times(2)).putString("pending_8350", "")
            // V5.0.8409 — the acknowledged row leaves only after the checkpoint commit succeeded (its own transaction), so the
            // failed first attempt never reaches the row removal; the retry removes it once.
            verify(editor, times(1)).remove(id)
        } finally { prefsField.set(owner, oldPrefs); outbox.clear(); outbox.putAll(saved); ack.clear(); ack.addAll(savedAck) }
    }

    @Test fun coldReadWith13500KeysDoesNotHoldTheOutcomeAppendMonitor() {
        val cold = mock(ShadowColdStore8397::class.java)
        `when`(cold.keys()).thenReturn((0 until 13_500).map { "cold-$it" }.toSet())
        `when`(cold.appendReceipt(anyString(), anyMap(), anyString())).thenReturn(ShadowColdStore8397.Receipt(true, ""))
        val book = ShadowCohortBook8397(4)
        book.attach(cold)
        book.append(listOf("hot"), doubleArrayOf(1.0, 4.0, 8394.0, 1.0), "first")
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        `when`(cold.read("cold-1")).thenAnswer {
            assertFalse(Thread.holdsLock(book)); started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS)); "1,5,8394,1"
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reader = pool.submit<List<DoubleArray>?> { book["cold-1"] }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            pool.submit { book.append(listOf("hot"), doubleArrayOf(2.0, -2.0, 8394.0, 2.0), "second") }.get(2, TimeUnit.SECONDS)
            release.countDown(); assertNotNull(reader.get(2, TimeUnit.SECONDS))
            assertEquals(2, book["hot"]!!.size)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun exactForwardCheckpointIncludesOlderPredictionsBeyondOrdinaryThousandAndRestoresFrozenValues() {
        val owner = Cortex7885
        val pending = field(owner, "pending").get(owner) as MutableMap<String, Any>
        val saved = HashMap(pending)
        val now = System.currentTimeMillis()
        val constructor = Class.forName("${owner.javaClass.name}\$Pending").declaredConstructors
            .single { it.parameterCount == 7 }.apply { isAccessible = true }
        val ids = LinkedHashSet<String>()
        try {
            pending.clear()
            repeat(1100) { i ->
                val at = now - 2000 + i; val key = "pair8401-$i|MOONSHOT"
                val a = Cortex7885.Assessment("MOONSHOT", true, doubleArrayOf(i.toDouble()), listOf("FROZEN_TEST"),
                    listOf(doubleArrayOf(0.0)), "TEST", CortexLedger7885.Fused(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()),
                    1.0, CortexScoreboard7885.Bucket.STRONG, 0, false, at)
                pending[key] = constructor.newInstance(a, false, at, null, "TEST", "SETUP", emptyList<String>())
                ids += "$key|$at"
            }
            val encode = synchronized(owner) { call(owner, "pendingSnapshot8076", ids) as () -> String }
            pending.clear()
            val raw = encode()
            assertEquals(1100, JSONObject(raw).getJSONArray("p").length())
            assertEquals(0, JSONObject(raw).getJSONArray("unpaired8401").length())
            synchronized(owner) { call(owner, "restorePending", raw, now, true) }
            assertEquals(1100, pending.size)
            val first = pending["pair8401-0|MOONSHOT"]!!
            val assessment = field(first, "a").get(first) as Cortex7885.Assessment
            assertEquals(0.0, assessment.raws.single(), 0.0)
            assertEquals(listOf("FROZEN_TEST"), assessment.ids)
        } finally { pending.clear(); pending.putAll(saved) }
    }
}
