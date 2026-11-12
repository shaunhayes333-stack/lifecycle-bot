package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.network.MeteoraPools8703
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8703 — the three things 8699-8702 measured and left open: a LIVE admit waits for the buy door's proof as a candidate
 * (8689: 30 tickets, 5 buys), LIVE capital is steered by the origin cohort's own record, and Meteora is read from Meteora.
 */
class Aate8703WholeNetworkAndTicketsTest {
    @Test fun theBuyDoorsProofIsReadBeforeATicketIsSpent() {
        val now = 10_000_000L
        // V5.0.8705 — the wait is the door's own verdict, probed; not a copy of some of its tests.
        assertEquals("LIVE_CRITICAL_PROOF_PENDING_8373", PreTradeHardGate.proofWaitOf8705(
            PreTradeHardGate.Verdict(false, "DEFER_SAFETY_PROOF", "LIVE_CRITICAL_PROOF_PENDING_8373|MINT_AUTHORITY_UNKNOWN|HOLDER_DATA_UNKNOWN")))
        assertEquals("PROOF_PENDING", PreTradeHardGate.proofWaitOf8705(PreTradeHardGate.Verdict(false, "DEFER_SAFETY_PROOF", "")))
        assertNull("an allow is not a wait", PreTradeHardGate.proofWaitOf8705(PreTradeHardGate.Verdict(true, "ALLOW", "site=x")))
        assertNull("a hard block is the door's refusal, not a wait", PreTradeHardGate.proofWaitOf8705(PreTradeHardGate.Verdict(false, "SAFETY_HARD_BLOCK", "rug")))
        assertNull(PreTradeHardGate.proofWaitOf8705(null))
        val door = src("engine/PreTradeHardGate.kt")
        assertTrue(door.contains("val v = try { requireLiveBuyAllowed(ts, \"ADMISSION_PROBE_8705\") } catch (_: Throwable) { null } finally { probe8705.remove() }"))
        assertTrue("a probe writes no log, counter or priority note",
            door.contains("if (probing8705()) return Verdict(false, \"DEFER_SAFETY_PROOF\", \"\$reason|\$detail\")") &&
                door.contains("if (probing8705()) return Verdict(false, reason, detail)"))
        assertFalse(PreTradeHardGate.proofAgeing8703(now - 60_000L, now))
        assertTrue(PreTradeHardGate.proofAgeing8703(now - 60_001L, now))
        assertFalse("never checked is a wait, not ageing", PreTradeHardGate.proofAgeing8703(0L, now))
    }

    @Test fun anAdmitWithoutItsProofBecomesANamedDataDefer() {
        val admit = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, "LANE_AUTHORITY_8577", true, "DIP_RECLAIM", 4.2,
            listOf("gross=+7.0 cost=+2.8 net=+4.2", "optionalUnobserved8170=tape:UNKNOWN"), nativePhaseWitness8528 = true)
        val d = CandidateAdmission8136.proofDeferred8703(admit, "SAFETY_REPORT_NOT_FRESH")
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, d.terminal)
        assertEquals("DATA_INCOMPLETE_DEFER_8136", d.reason)
        assertFalse(d.admitted); assertFalse(d.specific); assertFalse(d.nativePhaseWitness8528)
        assertEquals("the setup it would have traded is kept", "DIP_RECLAIM", d.chosenSetup)
        assertTrue(d.trace.contains("missing=proof:SAFETY_REPORT_NOT_FRESH"))
        assertEquals(listOf("BUY_DOOR_PROOF_SAFETY_REPORT_NOT_FRESH"), CandidateAdmission8136.deferCauses8700(d.trace))
        val s = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(s.contains(".let { awaitBuyDoorProof8703(ts, candidate.live, it, nowMs) }.also {"))
        assertTrue(s.contains("if (!live || !d.admitted) return d"))
    }

    @Test fun liveCapitalFollowsTheOriginCohortsOwnRecord() {
        val lane = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(lane.contains("if (originLoses8703(ts, l, nowMs)) return LIVE_ORIGIN_COHORT_LOSES_8703"))
        assertTrue("a coin with a proven edge of its own is still bought", lane.contains("return ownEdge == null"))
        assertTrue(lane.indexOf("if (originLoses8703(ts, l, nowMs))") < lane.indexOf("if (l !in MEME_LANES_8582) return null"))
        assertTrue("the door retires on the one selection-gate rule", lane.contains("if (GateOutcome8418.selectionGateRetired8696(\"CANDIDATE_ADMISSION_8136_\$why\", lane)) return null"))
        assertTrue(com.lifecyclebot.engine.truth.LaneAuthority8577.binding8577("LIVE_ORIGIN_COHORT_LOSES_8703"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("return stat.meanNet60Pct.isFinite() && stat.meanNet60Pct + se < 0.0"))
        assertTrue(src("engine/truth/MarketCohort8701.kt").contains("return if (LiveEdgeGate7877.cohortProvenLosing8703(stat)) cohort else null"))
    }

    @Test fun meteoraPoolsAreReadFromMeteora() {
        val now = 1_800_000_000_000L
        val sol = "So11111111111111111111111111111111111111112"
        val coin = "3nN4GVY7eS9JwWiiTFwzJBMTCv6rD8a2gofADeq6Qm63"
        val other = "HpJsv8sjq5rqJ8yVbEfoJUNj2DMrasP36D3Kj2DDJUXm"
        fun tok(a: String, sym: String) = """{"address":"$a","symbol":"$sym","name":"$sym coin","market_cap":150000.5}"""
        val body = """{"total":4,"pages":1,"current_page":1,"page_size":50,"data":[
            {"address":"POOL1","name":"AAA-SOL","created_at":${now / 1000 - 7200},"tvl":9124.4,"volume":{"30m":100.0,"1h":2500.0},"token_x":${tok(coin, "AAA")},"token_y":${tok(sol, "SOL")},"launchpad":"bags","is_blacklisted":false},
            {"address":"POOL2","name":"SOL-BBB","created_at":${now - 3_600_000L},"tvl":"20000","volume":{"1h":null},"token_x":${tok(sol, "SOL")},"token_y":${tok(other, "BBB")},"launchpad":null,"is_blacklisted":false},
            {"address":"POOL3","name":"AAA-BBB","tvl":5000,"token_x":${tok(coin, "AAA")},"token_y":${tok(other, "BBB")},"is_blacklisted":false},
            {"address":"POOL4","name":"CCC-SOL","tvl":5000,"token_x":${tok(other, "CCC")},"token_y":${tok(sol, "SOL")},"is_blacklisted":true},
            {"address":"POOL5","name":"DDD-SOL","token_x":${tok(other, "DDD")},"token_y":${tok(sol, "SOL")},"is_blacklisted":false}]}"""
        val pools = MeteoraPools8703.parse8703(body, now)
        assertEquals("no quote side, blacklisted and unstated depth are skipped", 2, pools.size)
        assertEquals(coin, pools[0].mint); assertEquals("AAA", pools[0].symbol); assertEquals("bags", pools[0].launchpad)
        assertEquals(9124.4, pools[0].liquidityUsd, 1e-9); assertEquals(2500.0, pools[0].volumeH1Usd, 1e-9)
        assertEquals("created_at in seconds", 2.0, pools[0].ageHours, 1e-6)
        assertEquals(other, pools[1].mint); assertEquals(20_000.0, pools[1].liquidityUsd, 1e-9)
        assertEquals("created_at in milliseconds", 1.0, pools[1].ageHours, 1e-6)
        assertEquals(0.0, pools[1].volumeH1Usd, 0.0); assertEquals("", pools[1].launchpad)
        assertTrue(MeteoraPools8703.parse8703("not json", now).isEmpty())
        assertEquals(3, MeteoraPools8703.REQUESTS_8703.size)
        assertTrue(MeteoraPools8703.REQUESTS_8703.all { it.second.startsWith("https://damm-v2.datapi.meteora.ag/pools?") || it.second.startsWith("https://dlmm.datapi.meteora.ag/pools?") })
        val scanner = src("engine/SolanaMarketScanner.kt")
        assertTrue(scanner.contains("deepScans += \"scanMeteoraPools8703\" to { scanMeteoraPools8703() }"))
        assertTrue("the Gecko page is the fallback", scanner.contains("to { if (meteoraDirectFound8703 == 0) scanMeteoraPoolsViaGecko() }"))
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}
