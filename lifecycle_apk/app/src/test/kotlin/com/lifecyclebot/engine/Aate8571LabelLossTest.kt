package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LabelEvidence8386
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8571 — the outcome-loss audit: every point where a decided coin's forward outcome was dropped. */
class Aate8571LabelLossTest {
    private val now = 1_800_000_000_000L
    private fun labeler() = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()

    @Test fun the_stop_paths_chain_feeds_are_chain_witnesses_for_labels_too() {
        for (s in listOf("PUMP_PORTAL_TRADE_WS_7278", "PUMP_CURVE_RPC_KEYLESS_8359", "PUMP_CURVE_RPC", "POOL_VAULT_WS_8418_RAYDIUM",
                "POOL_STATE_WS_8418_ORCA", "PUMPSWAP_VAULT_WS_8018", ForwardReturnLabeler7731.VAULT_MARK_SOURCE_8571)) {
            assertTrue(s, LabelEvidence8386.independentSource8418(s))
            assertTrue(s, StopFeed8327.chainMark8327(s) || s == ForwardReturnLabeler7731.VAULT_MARK_SOURCE_8571)
        }
        // quotes and APIs are still never prints
        assertFalse(LabelEvidence8386.independentSource8418("JUPITER_SWAP"))
        assertFalse(LabelEvidence8386.independentSource8418("DEXSCREENER"))
        assertFalse(LabelEvidence8386.independentSource8418(ForwardReturnLabeler7731.CROSS_ASSET_MARK_SOURCE_8571))
        // one list: the stop path reads the label evidence's feeds
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/StopFeed8327.kt").readText()
            .contains("CHAIN_SOURCES_8327 = com.lifecyclebot.engine.truth.LabelEvidence8386.CHAIN_FEEDS_8571"))
    }

    @Test fun a_10x_runner_on_two_pumpportal_prints_is_bookable() {
        val e = LabelMark8386("M", "", 1e-6, now - 60_000, now, "PUMP_PORTAL_TRADE_WS_7278", decimals = 6)
        val o = LabelMark8386("M", "", 2e-5, now, now, "PUMP_PORTAL_TRADE_WS_7278", decimals = 6)
        assertEquals("VALID_8386", LabelEvidence8386.status(e, o))
    }

    @Test fun the_edge_snapshot_survives_the_checkpoint_with_unobserved_features_kept_unobserved() {
        val raw = EdgeModel8537.Raw("SHITCOIN", "PUMP_PORTAL", 12_000.0, 40_000.0, 3.5, 25.0, 0.9, 8.0, 62.0, 4.0, Double.NaN,
            2.2, 18.0, "IGNITION", 55.0, 3.1, holderVel = 1.5, txVel = Double.NaN, curve = 0.42, turnover = 0.8, flags = 5)
        val snap = EdgeModel8537.Snap(raw, EdgeModel8537.vectorOf(raw), Double.NaN, "COLD", now, "MintA", true)
        val json = EdgeModel8537.encodeSnap8571(snap).toString()   // JSON has no NaN: must not throw
        val back = EdgeModel8537.decodeSnap8571(org.json.JSONObject(json))
        assertNotNull(back)
        assertEquals(raw.toString(), back!!.raw.toString())
        assertArrayEquals(snap.x, back.x, 0.0)
        assertTrue(back.pred.isNaN())
        assertEquals("COLD", back.modelKey); assertEquals(now, back.atMs); assertEquals("MintA", back.mint); assertTrue(back.admitted)
        assertEquals(EdgeModel8537.MODEL_EPOCH_8642, back.modelEpoch)
        val old = org.json.JSONObject(json).apply { remove("modelEpoch") }
        assertEquals("legacy frozen predictions cannot earn new out-of-sample authority", 0,
            EdgeModel8537.decodeSnap8571(old)!!.modelEpoch)
        assertNull(EdgeModel8537.decodeSnap8571(null))
        assertNull(EdgeModel8537.decodeSnap8571(org.json.JSONObject("{}")))
        val lab = labeler()
        assertTrue(lab.contains(".put(\"edge8571\", o.edge8537?.let { EdgeModel8537.encodeSnap8571(it) })"))
        assertTrue(lab.contains("o.edge8537 = EdgeModel8537.decodeSnap8571(e.optJSONObject(\"edge8571\"))"))
    }

    private fun m(px: Double, ageMs: Long, source: String, independent: Boolean = false) =
        LabelMark8386("M", "", px, now - ageMs, now, source, decimals = 6, independentBasis = independent)

    @Test fun a_fresh_chain_mark_is_the_decision_basis_and_replaces_a_contradicted_price() {
        val state = m(1.0, 2_000, "TOKEN_STATE")
        val chain = m(1.05, 1_000, "CURVE_ACCOUNT", independent = true)
        val never: (LabelMark8386) -> Boolean = { false }
        assertSame(chain, ForwardReturnLabeler7731.preferChainEntry8571(state, chain, now, never))
        // an older chain mark does not outrank an uncontradicted selection
        val olderChain = m(1.05, 20_000, "CURVE_ACCOUNT", independent = true)
        assertSame(state, ForwardReturnLabeler7731.preferChainEntry8571(state, olderChain, now, never))
        // ...but it replaces a price the curve contradicts (was: the decision got no observation at all)
        val stale = m(3.0, 2_000, "TOKEN_STATE")
        val contradicts: (LabelMark8386) -> Boolean = { it.usd > 2.0 }
        assertSame(olderChain, ForwardReturnLabeler7731.preferChainEntry8571(stale, olderChain, now, contradicts))
        // no chain mark: a contradicted price still has no buyable basis
        assertNull(ForwardReturnLabeler7731.preferChainEntry8571(stale, null, now, contradicts))
        assertNull(ForwardReturnLabeler7731.preferChainEntry8571(stale, m(1.0, 1_000, "OFFWATCH"), now, contradicts))
        // a contradicted chain mark is never preferred
        assertSame(state, ForwardReturnLabeler7731.preferChainEntry8571(state, m(5.0, 500, "CURVE_ACCOUNT", true), now, contradicts))
    }

    @Test fun vault_reads_and_cross_asset_feeds_reach_the_label_at_their_own_time() {
        val lab = labeler()
        val typed = lab.substringAfter("internal fun typedMark8386(").substringBefore("private fun markFor(")
        assertTrue(typed.contains("OnChainDepth8038.freshRead8198(mint, MARK_MAX_AGE_MS_7731, nowMs)"))
        assertTrue(typed.contains("vr.atMs, nowMs, VAULT_MARK_SOURCE_8571"))
        assertTrue(typed.contains("CrossAssetCortex7931.markAt8571(mint, nowMs)"))
        assertTrue(ForwardReturnLabeler7731.liveMark8454(ForwardReturnLabeler7731.CROSS_ASSET_MARK_SOURCE_8571, false))
    }

    @Test fun an_hour_target_lost_at_the_hour_is_counted_lost() {
        val lab = labeler()
        val fn = lab.substringAfter("private fun unavailable8395(").substringBefore("private val labelLostDowntime8505")
        assertTrue(fn.contains("val hourTargetLost8571 = o.targetHorizon8521 > H60_MS_7731 && !o.done240"))
        assertTrue(fn.contains("val released8505 = pending.remove(key,o) && (!o.done60 || hourTargetLost8571)"))
        assertTrue(lab.contains("hourTarget8571[lost=\${hourTargetLost8571Count.get()} interimMissed=\${interimMissed8571.get()}]"))
    }

    @Test fun vault_reads_are_asked_at_batch_start_with_the_label_freshness() {
        val lab = labeler()
        val fn = lab.substringAfter("private fun fetchOffWatchMarks7737(").substringBefore("val dexBatch8418 =")
        assertTrue(fn.contains("OnChainDepth8038.requestMints8543(offCurve8418, CHAIN_MARK_MAX_AGE_MS_8540)"))
        val depth = File("src/main/kotlin/com/lifecyclebot/engine/truth/OnChainDepth8038.kt").readText()
        assertTrue(depth.contains("requestMint8540(m, askMaxAge8572.remove(m) ?: FRESH_MS)"))
    }

    @Test fun a_decision_with_no_token_row_is_counted_not_silent() {
        assertTrue(labeler().contains("skippedNoState8572.incrementAndGet()"))
    }
}
