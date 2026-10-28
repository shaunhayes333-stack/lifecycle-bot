package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.Executors

/** Exercises the production open, mark, exit, close and live hold reader. No invented close records. */
class Aate8417EvidenceLifecycleTest {
    private var epoch = 0L
    private var enabled = true
    private var wasPaper = true
    private var t0 = 0L

    @Before fun setup() {
        epoch = LearningEpoch8164.epochMs8164()
        enabled = EvidenceHold8336.enabled8336
        wasPaper = RuntimeModeAuthority.isPaper()
        t0 = maxOf(System.currentTimeMillis(), EvidenceHold8336.startMs8336, epoch) + 1_000L
        EvidenceShadow8344.resetForTests8345()
        LearningEpoch8164.setForTest8164(t0 - 1)
        EvidenceHold8336.enabled8336 = true
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
    }

    @After fun cleanup() {
        EvidenceShadow8344.resetForTests8345()
        LearningEpoch8164.setForTest8164(epoch)
        EvidenceHold8336.enabled8336 = enabled
        RuntimeModeAuthority.publishConfig(paperMode = wasPaper, autoTrade = false)
    }

    private fun landedMint(prefix: String, lanes: List<String> = listOf("MOONSHOT")): String =
        (0..10_000).asSequence().map { "$prefix-$it" }.first { mint ->
            val rate = ShadowAlign8366.landFor8366(mint, t0).rate
            lanes.all { lane -> ShadowAlign8366.lands8366("$mint|$lane|$t0|EVIDENCE", rate) }
        }

    private fun token(mint: String, at: Long = t0) = TokenState(mint = mint, symbol = "TEST").also {
        it.lastPrice = 1.0; it.lastPriceUpdate = at
        it.lastLiquidityUsd = 1_000_000.0; it.lastMcap = 1_000_000.0
    }

    @Suppress("UNCHECKED_CAST")
    private fun open(): Map<String, Any> = EvidenceShadow8344::class.java.getDeclaredField("open")
        .apply { isAccessible = true }.get(EvidenceShadow8344) as Map<String, Any>

    private fun field(p: Any, name: String): Any? = p.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(p)

    @Test fun lanesRehearseTheSameCoinAndDuplicateRequestsCannotReplaceTheirEntries() {
        val ts = token(landedMint("shared-8417", listOf("MOONSHOT", "QUALITY")))
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((1..40).map { java.util.concurrent.Callable {
                EvidenceShadow8344.open8344(ts, if (it % 2 == 0) "MOONSHOT" else "QUALITY", t0)
            } }).forEach { it.get() }
        } finally { pool.shutdownNow() }
        assertEquals(setOf("MOONSHOT|${ts.mint}", "QUALITY|${ts.mint}"), open().keys)
        val original = open().toMap()
        ts.lastPrice = 2.0
        EvidenceShadow8344.open8344(ts, "MOONSHOT", t0 + 1_000)
        original.forEach { (key, position) -> assertSame(position, open()[key]) }
        assertEquals(EvidenceShadow8344.episodeKey8417(ts.mint, "SHITCOIN_EXPRESS"),
            EvidenceShadow8344.episodeKey8417(ts.mint, "EXPRESS"))
    }

    @Test fun actualClosedEpisodesReleaseOnlyTheLaneThatEarnedProof() {
        assertNotNull(EvidenceHold8336.refusal8336("MOONSHOT", false, t0))
        val tokens = (1..40).associate { i -> token(landedMint("proof-8417-$i")).let { it.mint to it } }
        tokens.values.forEach { EvidenceShadow8344.open8344(it, "MOONSHOT", t0) }
        assertEquals(40, open().size)
        val exitAt = t0 + 7 * 3_600_000L
        tokens.values.forEach { it.lastPrice = 1.3; it.lastPriceUpdate = exitAt }
        EvidenceShadow8344.tick8344(tokens, exitAt)
        assertEquals("a trigger is not a fill", 0, EvidenceShadow8344.proof8344("MOONSHOT", t0).n)
        val fillAt = exitAt + ShadowAlign8366.sellLatencyMs8366(false) + 1
        tokens.values.forEach { it.lastPriceUpdate = fillAt }
        EvidenceShadow8344.tick8344(tokens, fillAt)
        val proof = EvidenceShadow8344.proof8344("MOONSHOT", t0)
        assertEquals(40, proof.n)
        assertTrue(proof.toString(), proof.proven)
        assertNull(EvidenceHold8336.refusal8336("MOONSHOT", false, fillAt))
        assertNotNull(EvidenceHold8336.refusal8336("QUALITY", false, fillAt))
        assertTrue(open().isEmpty())
        EvidenceShadow8344.tick8344(tokens, fillAt + 1)
        assertEquals("closed episodes contribute once", proof, EvidenceShadow8344.proof8344("MOONSHOT", t0))
    }

    @Test fun lossesAndUnobservedExitsCannotUnlockLive() {
        val ts = token(landedMint("loss-8417"))
        EvidenceShadow8344.open8344(ts, "MOONSHOT", t0)
        ts.lastPrice = 0.4; ts.lastPriceUpdate = t0 + 1_000
        EvidenceShadow8344.tick8344(mapOf(ts.mint to ts), ts.lastPriceUpdate)
        val pending = open().values.single()
        assertEquals("STOP", field(pending, "exitWhy8366"))
        assertEquals("trigger remains dirty until durable", true, field(pending, "dirty8355"))
        val fillAt = t0 + 1_001 + ShadowAlign8366.sellLatencyMs8366(false)
        EvidenceShadow8344.tick8344(mapOf(ts.mint to ts), fillAt)
        assertEquals("pre-trigger mark cannot fill a pending sell", 0, EvidenceShadow8344.proof8344("MOONSHOT", t0).n)
        ts.lastPriceUpdate = fillAt
        EvidenceShadow8344.tick8344(mapOf(ts.mint to ts), fillAt)
        val proof = EvidenceShadow8344.proof8344("MOONSHOT", t0)
        assertEquals(1, proof.n); assertTrue(proof.meanPct < 0)
        assertNotNull(EvidenceHold8336.refusal8336("MOONSHOT", false, fillAt))
    }
}
