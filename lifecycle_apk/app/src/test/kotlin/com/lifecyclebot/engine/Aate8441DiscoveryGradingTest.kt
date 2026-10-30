package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075
import com.lifecyclebot.network.HostCircuitInterceptor
import com.lifecyclebot.network.KeylessRpc8359
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8441 — the 8439 report's discovery and grading chokes: the app's own Helius budget locked Helius out of the shared
 * reads; publicnode's "indexed requests require a personal token" sat in every ladder and was written down as NO_SUPPLY;
 * the brief now grades Cortex label coverage, discovery / supply and entry quality.
 */
class Aate8441DiscoveryGradingTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theBudgetsOwnRefusalNeverLocksHeliusOut() {
        val m = src("network/HeliusCreditMeterInterceptor7881.kt")
        assertEquals(2, Regex("\\.header\\(HostCircuitInterceptor\\.SYNTHETIC_HEADER_6969, \"1\"\\)").findAll(m).count())
        val hc = src("network/HostCircuitInterceptor.kt")
        val proceed = hc.indexOf("chain.proceed(wireReq)")
        val guard = hc.indexOf("if (isSyntheticBlock(response)) {")
        assertTrue("an inner synthetic reply returns before anything is recorded", guard in proceed until hc.indexOf("noteScopedOutcome8179(provider, response.code, now)"))
    }

    @Test fun aNodeThatCannotServeAMethodLeavesThatLadder() {
        assertTrue(KeylessRpc8359.capabilityRefusal8441(-32602, "Indexed requests require a personal token. Get one at: https://www.allnodes.com"))
        assertTrue(KeylessRpc8359.capabilityRefusal8441(-32601, "Method not found"))
        assertFalse("rate limit is not a capability refusal", KeylessRpc8359.capabilityRefusal8441(-32429, "rate limited"))
        assertFalse("an account the node cannot find yet", KeylessRpc8359.capabilityRefusal8441(-32602, "Invalid param: could not find account"))
        KeylessRpc8359.resetForTests8359()
        val pn = "https://solana-rpc.publicnode.com"
        val mb = "https://api.mainnet-beta.solana.com"
        assertTrue(KeylessRpc8359.noteRpcError8441(pn, "getSignaturesForAddress", -32602, "Indexed requests require a personal token"))
        val walk = KeylessRpc8359.marketLadder8359(listOf(pn, mb), "getSignaturesForAddress")
        assertFalse(walk.contains(pn))
        assertTrue("other methods keep the node", KeylessRpc8359.marketLadder8359(listOf(pn, mb), "getAccountInfo").contains(pn))
        assertTrue(KeylessRpc8359.statusLine8359().contains("unsupported8441{solana-rpc.publicnode.com|getSignaturesForAddress}"))
        assertFalse("helius is never dropped", KeylessRpc8359.noteRpcError8441("https://mainnet.helius-rpc.com/?api-key=x", "getTokenSupply", -32601, "Method not found"))
        KeylessRpc8359.resetForTests8359()
    }

    @Test fun aCapabilityRefusalIsNotNoSupply() {
        assertEquals("TRANSPORT", OnChainSupplyAuthority7075.rpcErrorClass7809(-32602, "Indexed requests require a personal token"))
        assertEquals("NO_SUPPLY", OnChainSupplyAuthority7075.rpcErrorClass7809(-32602, "Invalid param: not a Token mint"))
        assertEquals("NOT_FOUND_YET", OnChainSupplyAuthority7075.rpcErrorClass7809(-32602, "Invalid param: could not find account"))
        assertEquals(5, OnChainSupplyAuthority7075.counts8441().size)
        assertTrue(src("engine/truth/TokenBirthHydrator7441.kt").contains("KeylessRpc8359.noteRpcError8441(rpc, \"getSignaturesForAddress\""))
    }

    @Test fun theBriefGradesLabelsDiscoveryAndEntries() {
        assertTrue("8439: 1254 of 9657 priced", DiagnosisBrief8438.labelsStarved8441(9657, 1254))
        assertFalse(DiagnosisBrief8438.labelsStarved8441(9657, 6000))
        assertFalse("too few to judge", DiagnosisBrief8438.labelsStarved8441(100, 0))
        assertTrue("8439: 767 of 5622", DiagnosisBrief8438.supplyStarved8441(5622, 767, 11669))
        assertTrue("locks outnumber asks", DiagnosisBrief8438.supplyStarved8441(300, 290, 900))
        assertFalse(DiagnosisBrief8438.supplyStarved8441(5000, 4000, 100))
        assertEquals(4, Cortex7885.grading8441().size)
        assertEquals(4, com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.labelMarks8441().size)
        val b = src("engine/DiagnosisBrief8438.kt")
        assertTrue(b.contains("[FAULT labels unpriced]"))
        assertTrue(b.contains("[FAULT supply starved]"))
        assertTrue(b.contains("entry quality "))
    }

    @Test fun aLostLabelKeepsItsEvidenceStatus() {
        assertEquals("DEADLINE_UNKNOWN_BASIS", Cortex7885.lostKey8441("OBSERVATION_DEADLINE_OBSERVED_ONLY_UNKNOWN_BASIS"))
        assertEquals("DEADLINE_VALID_8386", Cortex7885.lostKey8441("OBSERVATION_DEADLINE_VALID_8386"))
        assertEquals("STAGE", Cortex7885.lostKey8441("STAGE"))
    }
}
