package com.lifecyclebot.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8471 — owner: "low rug check number means bad token. high number means good" and "trade whatever we can trade safely
 * using the safety tools we have built". 8470: 50 live buy attempts, 0 buys — every one deferred at the safety proof, at
 * least 30 with no RugCheck report (rug=-1): RugCheck answers a fresh mint it has not indexed with 400 "not found", which
 * was retried as a failure and filed as a timeout, a critical pending proof at the live door.
 */
class Aate8471RugcheckTest {
    private fun j(s: String) = JSONObject(s)
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val mint = "Mint8471AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"

    // superseded by V5.0.8472 — 8471 read RugCheck's number as high = good; the owner's live 8471 log showed reports with no
    // findings averaging score_normalised 1.0 and 2+-danger reports 65.5, so the number is a RISK score again (low = clean).
    @Test fun rugchecksNumberIsARiskScoreOnTheOwnersLiveData() {
        assertEquals(99, RugcheckScore8399.safety8399(j("""{"score_normalised":1}""")))     // a clean report in the 8471 log
        assertEquals(35, RugcheckScore8399.safety8399(j("""{"score_normalised":65}""")))    // the 2+-danger reports' average
        assertEquals(0, RugcheckScore8399.safety8399(j("""{"score_normalised":100}""")))
        assertEquals(1.0, RugcheckScore8399.risk8399(j("""{"score_normalised":1}""")), 1e-9)
        assertTrue(RugcheckScore8399.statusLine8471().contains("read=high-is-risk"))
        assertFalse(File("src/main/kotlin/com/lifecyclebot/engine/RugcheckScore8399.kt").readText().contains("read=high-is-good"))
    }

    @Test fun theReportsOwnFindingsCapTheNumber() {
        assertEquals(100, RugcheckScore8399.findingsSafety8471(0, 0))
        assertEquals(75, RugcheckScore8399.findingsSafety8471(0, 2))
        assertEquals(60, RugcheckScore8399.findingsSafety8471(0, 3))
        assertEquals(35, RugcheckScore8399.findingsSafety8471(1, 0))
        assertEquals(20, RugcheckScore8399.findingsSafety8471(2, 5))
        assertEquals(8, RugcheckScore8399.findingsSafety8471(4, 0))
        val report = j("""{"mint":"$mint","score_normalised":5,"risks":[{"name":"Freeze Authority still enabled","level":"danger"},
            {"name":"Mutable metadata","level":"warn"},{"name":"Top 10 holders high ownership","level":"danger"}]}""")
        assertEquals(2 to 1, RugcheckScore8399.findingCounts8471(report))
        assertEquals("two dangers cap a clean-looking number", 20, RugcheckScore8399.reportSafety8418(report, mint))
        assertEquals(20, RugcheckScore8399.safety8399(report))
        assertEquals("a clean report keeps its number", 88,
            RugcheckScore8399.reportSafety8418(j("""{"mint":"$mint","score_normalised":12,"risks":[]}"""), mint))
        assertEquals("rugged is a confirmed rug whatever the number", 0,
            RugcheckScore8399.reportSafety8418(j("""{"mint":"$mint","rugged":true,"score_normalised":0,"risks":[]}"""), mint))
        assertEquals(null, RugcheckScore8399.findingCounts8471(j("""{"score_normalised":50}""")))
        assertTrue(RugcheckScore8399.statusLine8471().startsWith("rugcheck8471[reports="))
    }

    @Test fun aMintRugcheckHasNotIndexedIsNamedAndNotRetried() {
        assertTrue(RugcheckScore8399.notIndexed8471(400, """{"error":"not found"}"""))
        assertTrue(RugcheckScore8399.notIndexed8471(404, null))
        assertFalse("a bad request about something else is not 'not indexed'", RugcheckScore8399.notIndexed8471(400, """{"error":"bad mint"}"""))
        assertFalse(RugcheckScore8399.notIndexed8471(429, "not found"))
        assertFalse(RugcheckScore8399.notIndexed8471(200, "{}"))
        val now = 50_000_000L
        val fresh = "Fresh8471AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        assertFalse(RugcheckScore8399.notIndexedRecently8471(fresh, now))
        RugcheckScore8399.noteNotIndexed8471(fresh, now)
        assertTrue(RugcheckScore8399.notIndexedRecently8471(fresh, now + 30_000L))
        assertFalse("re-asked after a minute", RugcheckScore8399.notIndexedRecently8471(fresh, now + RugcheckScore8399.NOT_INDEXED_REASK_MS_8471))

        val fetch = src("engine/TokenSafetyChecker.kt").substringAfter("private fun fetchRugcheck(").substringBefore("private fun checkMintFreezeViaRpc(")
        assertTrue(fetch.contains("if (RugcheckScore8399.notIndexedRecently8471(mint, startedAt7819)) return null"))
        val loop = fetch.substringAfter("repeat(maxRetries)")
        assertTrue("one ask, no retries", loop.substringAfter("RugcheckScore8399.noteNotIndexed8471(mint, System.currentTimeMillis())").trimStart().startsWith("return null"))
        assertTrue(loop.contains("val body = raw8471.takeIf { code8471 in 200..299 }"))
    }

    @Test fun noRugcheckFindingsTradesOnlyOnCompleteChainProof() {
        val pending = listOf("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW", "LOW_LIQUIDITY_SIZE_REDUCED:liq=900.0 min=1500.0")
        val proven = PreTradeHardGate.chainProvenRugcheckSubstitute8471(pending, mintRevoked = true, topHolderPct = 22.0, executableExit = true)
        assertEquals("RUGCHECK_NO_FINDINGS_CHAIN_PROVEN_8471:PENDING_REVIEW", proven[0])
        assertTrue("no longer a critical proof: a size penalty", PreTradeHardGate.criticalProofPending8373(proven).isEmpty())
        // every proof is required
        for ((m, h, e) in listOf(Triple(false, 22.0, true), Triple(true, -1.0, true), Triple(true, 35.0, true), Triple(true, 22.0, false))) {
            val kept = PreTradeHardGate.chainProvenRugcheckSubstitute8471(pending, m, h, e)
            assertEquals("mint=$m holders=$h exit=$e", pending, kept)
            assertEquals(listOf("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW"), PreTradeHardGate.criticalProofPending8373(kept))
        }
        // other missing proofs still defer
        val withHolders = PreTradeHardGate.chainProvenRugcheckSubstitute8471(pending + "MINT_AUTHORITY_UNKNOWN", true, 22.0, true)
        assertEquals(listOf("MINT_AUTHORITY_UNKNOWN"), PreTradeHardGate.criticalProofPending8373(withHolders))
        // the gate passes its own chain readings, after the freeze / Token-2022 chain re-read and the fatal-holder block
        val gate = src("engine/PreTradeHardGate.kt")
        assertTrue(gate.contains("chainProvenRugcheckSubstitute8471(pendingProofs.distinct(), safety.mintAuthorityDisabled == true,\n            topHolder, tokenMapLiquidity.executable || curveExitSafe8319)"))
        assertTrue(gate.indexOf("FreezeAuthorityHardBlock7238.evaluateLive(ts)") in 0 until gate.indexOf("val pending8471 = chainProvenRugcheckSubstitute8471("))
        assertTrue(gate.indexOf("return block(ts, \"TOP_HOLDER_CONCENTRATION\"") in 0 until gate.indexOf("val pending8471 = chainProvenRugcheckSubstitute8471("))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("RugcheckScore8399.statusLine8471()"))
    }
}
