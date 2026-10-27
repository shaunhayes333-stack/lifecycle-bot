package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexOutcomeBook8372.Terminal8372
import org.junit.Assert.*
import org.junit.Test

class Aate8402LearningDeliveryTest {
    @Test fun actualShadowUnavailableReceiptsUseTheUnavailableCadence() {
        val unavailable = List(40) { Terminal8372("episode-$it", "", "", false) }
        assertFalse(Cortex7885.shadowFlushDue8402(unavailable, 0, 15_000))
        assertTrue(Cortex7885.shadowFlushDue8402(unavailable, 0, 120_000))
        assertTrue(Cortex7885.shadowFlushDue8402(unavailable, 216, 1_000))
        assertTrue(Cortex7885.shadowFlushDue8402(unavailable + Terminal8372("learned", "", "learned", true), 0, 15_000))
        // A shadow that contributed nothing new does not become learned just because its forward alias trained earlier.
        assertFalse(Cortex7885.shadowFlushDue8402(listOf(Terminal8372("s", "f", "f", false)), 0, 15_000))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun restoredOlderEpisodeParticipatesInCoverageWithoutDoubleCounting() {
        val owner = Cortex7885
        fun field(name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val pending = field("pending").get(owner) as MutableMap<String, Any>
        val paired = field("pairedPending8401").get(owner) as MutableMap<String, Any>
        val key = "coverage8402|MOONSHOT"
        val at = System.currentTimeMillis() - 12345
        val ctor = Class.forName("${owner.javaClass.name}\$Pending").declaredConstructors
            .single { it.parameterCount == 7 }.apply { isAccessible = true }
        fun prediction(time: Long): Any {
            val a = Cortex7885.Assessment("MOONSHOT", true, doubleArrayOf(1.0), listOf("TEST"),
                listOf(doubleArrayOf(0.0)), "TEST",
                com.lifecyclebot.engine.cortex.CortexLedger7885.Fused(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()),
                1.0, com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket.STRONG, 0, false, time)
            return ctor.newInstance(a, true, time, null, "TEST", "8402_ONLY", emptyList<String>()).also {
                it.javaClass.getDeclaredField("decisionReason8196").apply { isAccessible = true }.set(it, "FDG_ALLOW")
            }
        }
        try {
            pending[key] = prediction(at + 1)
            paired["$key|$at"] = prediction(at)
            paired["$key|${at + 1}"] = pending.getValue(key)
            val method = owner.javaClass.getDeclaredMethod("authorityRows8392", String::class.java, String::class.java)
                .apply { isAccessible = true }
            val rows = synchronized(owner) { method.invoke(owner, "MOONSHOT", "8402_ONLY") } as List<*>
            assertEquals("both frozen episodes count once", 2, rows.size)
        } finally {
            pending.remove(key); paired.remove("$key|$at"); paired.remove("$key|${at + 1}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun priceIdentityRestoresWithoutInventingAPriceOrOverwritingNewerMapping() {
        val owner = com.lifecyclebot.engine.cortex.CrossAssetCortex7931
        val field = owner.javaClass.getDeclaredField("assets").apply { isAccessible = true }
        val assets = field.get(owner) as MutableMap<String, Any>
        val saved = HashMap(assets)
        val raw = "[[\"identity8402\",\"CRYPTO_ALT\",\"UNKNOWN8402\",\"solana|identity8402\",1000,true]]"
        try {
            assets.clear()
            owner.restorePriceIdentities8402(raw)
            assertEquals(org.json.JSONArray(raw).toString(), owner.checkpointPriceIdentities8402(setOf("identity8402")))
            assertEquals("[]", owner.checkpointPriceIdentities8402(setOf("not-retained")))
            assertNull(owner.priceFor("identity8402", 2000))
            owner.restorePriceIdentities8402(raw.replace("1000", "500").replace("UNKNOWN8402", "OLD"))
            assertEquals(org.json.JSONArray(raw).toString(), owner.checkpointPriceIdentities8402(setOf("identity8402")))
            assertThrows(IllegalArgumentException::class.java) {
                owner.restorePriceIdentities8402(org.json.JSONArray(List(2001) { org.json.JSONArray(raw).getJSONArray(0) }).toString())
            }
        } finally { assets.clear(); assets.putAll(saved) }
    }
}
