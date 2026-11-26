package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.TopMemory9031
import com.lifecyclebot.engine.truth.keysSnapCurrent9031
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9031 — the 26 Nov 21:03 report (5.0.9030): the running-code sampler, the shadow key set no longer copied per read,
 * close commits paced like every other whole-graph encode, the dead-runner rule's remembered top, and the pump.fun curve
 * route in the same attempt when Jupiter has no quote.
 */
class Aate9031RunningCodeTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun el(c: String, m: String) = StackTraceElement(c, m, "SourceFile", 1)

    @Test fun aRunningStackIsReducedToItsThreeNamesAndAWaitIsNotCounted() {
        val busy = arrayOf(el("java.lang.StringBuilder", "append"), el("com.lifecyclebot.engine.cortex.CortexLedger7885", "appendJson9028"),
            el("com.lifecyclebot.engine.cortex.Cortex7885", "persistBlocking8094"), el("java.lang.Thread", "run"))
        val f = HotFrames9031.fold9031(busy)!!
        assertEquals("CortexLedger7885.appendJson9028", f.self)
        assertEquals("Cortex7885.persistBlocking8094", f.root)
        assertEquals("StringBuilder.append", f.leaf)
        // app code on top: the library column says so rather than repeating the method
        assertEquals(HotFrames9031.APP_CODE_9031, HotFrames9031.fold9031(arrayOf(el("com.lifecyclebot.engine.FlatChart8027", "refusal8027")))!!.leaf)
        // a stack with no app frame still counts under its library call
        assertEquals(HotFrames9031.NO_APP_FRAME_9031, HotFrames9031.fold9031(arrayOf(el("okio.Buffer", "write")))!!.self)
        // waits: a socket read, a parked worker, the main looper's poll — RUNNABLE on ART, not work
        assertNull(HotFrames9031.fold9031(arrayOf(el("libcore.io.Linux", "recvfrom"), el("com.lifecyclebot.network.X", "read"))))
        assertNull(HotFrames9031.fold9031(arrayOf(el("android.os.MessageQueue", "nativePollOnce"))))
        assertNull(HotFrames9031.fold9031(arrayOf(el("com.android.org.conscrypt.NativeCrypto", "SSL_read"))))
        assertNull(HotFrames9031.fold9031(arrayOf(el("sun.misc.Unsafe", "park"))))
        assertNull(HotFrames9031.fold9031(emptyArray()))
        assertFalse(HotFrames9031.idleLeaf9031("libcore.io.Linux", "pread"))
        assertEquals("DefaultDispatcher-worker-#", HotFrames9031.family9031("DefaultDispatcher-worker-12"))
    }

    @Test fun theBlockPrintsSharesOfTheRunningStacksAndTheSamplerPacesItself() {
        HotFrames9031.resetForTest9031()
        val a = arrayOf(el("org.json.JSONObject", "put"), el("com.lifecyclebot.engine.truth.EdgeModel8537", "seed"))
        val b = arrayOf(el("com.lifecyclebot.engine.cortex.Cortex7885", "assess"))
        repeat(3) { assertTrue(HotFrames9031.note9031(a, "AATE-Entry-3")) }
        assertTrue(HotFrames9031.note9031(b, "DefaultDispatcher-worker-7"))
        assertFalse(HotFrames9031.note9031(arrayOf(el("java.lang.Object", "wait")), "idle"))
        val text = HotFrames9031.block9031()
        assertTrue(text, text.contains("runningStacks=4"))
        assertTrue(text, text.contains("EdgeModel8537.seed 75.0%"))
        assertTrue(text, text.contains("JSONObject.put 75.0%"))
        assertTrue(text, text.contains("AATE-Entry-# 75.0%"))
        assertEquals("Cortex7885.assess", HotFrames9031.short9031("com.lifecyclebot.engine.cortex.Cortex7885", "assess"))
        assertEquals(HotFrames9031.Fold9031("a", "b", "c"), HotFrames9031.Fold9031("a", "b", "c"))
        assertTrue(HotFrames9031.MAX_STACKS_9031 in 1..64 && HotFrames9031.MAX_KEYS_9031 >= 500 && HotFrames9031.COST_MULT_9031 >= 20L)
        assertEquals(HotFrames9031.PERIOD_MS_9031, HotFrames9031.gapAfter9031(3L))
        assertEquals(2_000L, HotFrames9031.gapAfter9031(100L))        // 20 x the sample's cost: under 5 % of a core
        // superseded by V5.0.9034 — the cap was 10 s, which broke the 5 % promise for a sample costing over 0.5 s
        assertEquals(HotFrames9031.GAP_CAP_MS_9034, HotFrames9031.gapAfter9031(60_000L))
        assertTrue(src("AATEApp.kt").contains("HotFrames9031.start9031()"))
        assertTrue(src("engine/HeapLog8618.kt").contains("HotFrames9031.block9031()"))
        HotFrames9031.resetForTest9031()
    }

    @Test fun theShadowKeySetIsCopiedOnlyWhenItGrows() {
        assertTrue(keysSnapCurrent9031(7824, 7824))
        assertFalse(keysSnapCurrent9031(7824, 7825))
        val s = src("engine/truth/ShadowColdStore8397.kt")
        assertTrue(s.contains("keysSnap9031?.let { if (keysSnapCurrent9031(it.size, keyIndex.size)) return it }"))
        assertTrue(s.contains("return (keyIndex + hot.keys).also { keysSnap9031 = it }"))
        assertTrue(s.contains("store = cold; hot.clear(); preAttachReceipts.clear(); keysSnap9031 = null"))
        assertTrue(s.contains("keyIndex.clear(); keysSnap9031 = null }"))
        assertTrue(s.contains("if (store == null) return hot.keys.toSet()"))
    }

    @Test fun aCloseCommitWaitsItsGapAfterAWholeGraphEncodeAndAHealthyPhoneIsNotPaced() {
        assertEquals(0L, Cortex7885.closeCommitGapMs9031(900L))
        assertEquals(Cortex7885.CLOSE_PACE_MIN_COST_MS_9031 * Cortex7885.CLOSE_PACE_MULT_9031, Cortex7885.closeCommitGapMs9031(Cortex7885.CLOSE_PACE_MIN_COST_MS_9031))
        assertFalse(Cortex7885.closeCommitPaced9031(900L, 0L))
        // 26 Nov: encode 50,448 ms + db 2,219 ms
        assertEquals(210_668L, Cortex7885.closeCommitGapMs9031(52_667L))
        assertTrue(Cortex7885.closeCommitPaced9031(52_667L, 60_000L))
        assertFalse(Cortex7885.closeCommitPaced9031(52_667L, 210_668L))
        assertEquals(Cortex7885.CLOSE_PACE_CAP_MS_9031, Cortex7885.closeCommitGapMs9031(600_000L))
        assertFalse("a clock that moved back is not a reason to wait", Cortex7885.closeCommitPaced9031(52_667L, -5L))
        val c = src("engine/cortex/Cortex7885.kt")
        val persistFn = c.substringAfter("private fun persistBlocking8094(force8652: Boolean = false) { synchronized(persistLock8325) {")
        val batchAt = persistFn.indexOf("if (uncommittedCloseGrades8198.isNotEmpty() && (force8652 || !closeCommitPacedNow9031())) { commitWaitingCloses9031(); return }")
        assertTrue(batchAt >= 0 && batchAt < persistFn.indexOf("if (uncommittedCloseGrades8198.isNotEmpty()) return"))
        val batch = c.substringAfter("private fun commitWaitingCloses9031() {").substringBefore("\n    }\n")
        assertTrue(batch.contains("LearningPersistence.commitOnce8090(\"CORTEX_CLOSE_BATCH_9031|"))
        assertTrue(batch.contains("snapshotEncoder8386(periodic = false)"))
        // the ids are read before the snapshot is captured, and marked only after the transaction answers committed
        assertTrue(batch.indexOf("uncommittedCloseGrades8198.toList()") < batch.indexOf("snapshotEncoder8386(periodic = false)"))
        assertTrue(batch.indexOf("Commit8090.RETRY) { inc(\"CORTEX_CLOSE_BATCH_RETRY_9031\"); return }") < batch.indexOf("closeReceipts8198.add(id)"))
        val close = c.substringAfter("fun onCanonicalClose(").substringBefore("// ── persistence ──")
        assertTrue(close.contains("val durable = try { !paced9031 && durableCloseCommit8198(closeReceipt8198(env.tradeId)) } catch (_: Throwable) { false }"))
        assertTrue(close.contains("if (batchCommitted9031.remove(tradeId)) {"))
        assertTrue(c.contains("paced9031=\$closeCommitsPaced9031 batches9031=\$closeBatches9031/\$closesInBatches9031}]"))
    }

    @Test fun aCoinsTopIsRememberedFromTwoMinutesAndBothSidesMustCallItDead() {
        // GFmaQA: $23k -> $301k in the morning, $6.5k at night
        var t: TopMemory9031.Top9031? = null
        t = TopMemory9031.step9031(t, 2.3e-5, 23_182.0, 100L)
        t = TopMemory9031.step9031(t, 1.46e-4, 146_485.0, 107L)
        t = TopMemory9031.step9031(t, 3.01e-4, 301_479.0, 126L)
        t = TopMemory9031.step9031(t, 4.3e-5, 42_995.0, 158L)
        assertEquals(1.46e-4, t.p2, 1e-12)             // the second-highest minute, not the single highest print
        assertEquals(146_485.0, t.c2, 1e-6)
        assertTrue(TopMemory9031.deadByMemory9031(t, 6.5e-6, 6_518.0, FlatChart8027.DEAD_RATIO))
        assertFalse("4x under its top is not dead (Grodia)", TopMemory9031.deadByMemory9031(t, 3.7e-5, 37_000.0, FlatChart8027.DEAD_RATIO))
        // one rogue print cannot call a coin dead
        var r: TopMemory9031.Top9031? = TopMemory9031.step9031(null, 1.0e-5, 10_000.0, 1L)
        r = TopMemory9031.step9031(r, 9.0e-1, 900_000_000.0, 2L)
        r = TopMemory9031.step9031(r, 1.1e-5, 11_000.0, 3L)
        assertFalse(TopMemory9031.deadByMemory9031(r, 1.1e-5, 11_000.0, FlatChart8027.DEAD_RATIO))
        // a second print in the same minute raises the high, it does not confirm it
        var m: TopMemory9031.Top9031? = TopMemory9031.step9031(null, 5.0, 5_000.0, 7L)
        m = TopMemory9031.step9031(m, 9.0, 9_000.0, 7L)
        assertEquals(0.0, m.p2, 0.0)
        assertFalse(TopMemory9031.deadByMemory9031(m, 1.0, 1_000.0, FlatChart8027.DEAD_RATIO))
        // a wrong-unit price on its own proves nothing: the cap side must agree
        val oneSide = TopMemory9031.Top9031(5L, 100.0, 90.0, 12_000.0, 11_000.0)
        assertFalse(TopMemory9031.deadByMemory9031(oneSide, 1.0, 10_000.0, FlatChart8027.DEAD_RATIO))
        assertFalse(TopMemory9031.deadByMemory9031(null, 1.0, 1.0, FlatChart8027.DEAD_RATIO))
        // round trip, and the store through its own door
        val back = TopMemory9031.decode9031(TopMemory9031.encode9031(mapOf("MintA" to t, "MintB" to oneSide)))
        assertEquals(2, back.size); assertEquals(t.p2, back.getValue("MintA").p2, 0.0); assertEquals(126L, back.getValue("MintA").minute)
        assertTrue(TopMemory9031.decode9031("bad;row,1,2").isEmpty())
        TopMemory9031.resetForTest9031()
        TopMemory9031.note9031("Coin9031", 2.3e-5, 23_182.0, 100L * 60_000L)
        TopMemory9031.note9031("Coin9031", 1.46e-4, 146_485.0, 107L * 60_000L)
        TopMemory9031.note9031("Coin9031", 3.01e-4, 301_479.0, 126L * 60_000L)
        assertTrue(TopMemory9031.dead9031("Coin9031", 6.5e-6, 6_518.0, FlatChart8027.DEAD_RATIO))
        assertFalse(TopMemory9031.dead9031("Unknown9031", 6.5e-6, 6_518.0, FlatChart8027.DEAD_RATIO))
        assertTrue(TopMemory9031.line9031().contains("deadByMemory=1"))
        TopMemory9031.resetForTest9031()
        val flat = src("engine/FlatChart8027.kt")
        assertTrue(flat.contains("deadByMemory9031(ts) -> { deadRefused.incrementAndGet(); \"DEAD_RUNNER_8027\" }"))
        assertTrue(flat.contains("if (bars.isEmpty()) return if (deadByMemory9031(ts, count = false)) \"DEAD_RUNNER_8027\" else null"))
        assertTrue(src("engine/truth/LearnerColumns8798.kt").contains("TopMemory9031.note9031(ts.mint, ts.lastPrice,"))
        assertTrue(TopMemory9031.MAX_9031 >= 1_000 && TopMemory9031.SAVE_GAP_MS_9031 >= 60_000L)
    }

    @Test fun aCurveCoinWithNoJupiterQuoteTakesTheCurveRouteInTheSameAttempt() {
        val ex = src("engine/Executor.kt")
        val helper = ex.substringAfter("private fun pumpAfterNoQuote9031(").substringBefore("\n    }\n")
        val curve = helper.indexOf("if (!onActivePumpCurve8693(ts.mint)) return null")
        val failing = helper.indexOf("LaunchCohort8173.pumpFallback8173(ts.mint)")
        val plan = helper.indexOf("recalcBuyPlanForProcessor(ts = ts, wallet = wallet, processor = \"PUMPPORTAL_BUY\"")
        val mark = helper.indexOf("EntryEconomics8329.markRefusal8329(ts, intent8198, plan.solAmount) != null")
        val send = helper.indexOf("tryPumpPortalBuy(ts = ts, wallet = wallet, solAmount = plan.solAmount")
        assertTrue(curve >= 0 && curve < failing && failing < plan && plan < mark && mark < send)
        // the no-quote branch: Raydium, then the curve route, then the terminal it always had
        val leg = ex.substringAfter("private fun prepareJupiterBuy8090(")
        val ray = leg.indexOf("if (raydiumBuy7325 != null) return JupiterBuyPreparation8090(directFill = raydiumBuy7325)\n            // V5.0.9031")
        val pump = leg.indexOf("pumpAfterNoQuote9031(ts, wallet, effectiveSol, c, tradeKey, intent8198, submission8397)?.let { return JupiterBuyPreparation8090(directFill = it, pumpFill9031 = true) }")
        val terminal = leg.indexOf("val terminal = \"NO_QUOTE:JUPITER_QUOTE_EXHAUSTED\"")
        assertTrue(ray >= 0 && ray < pump && pump < terminal)
        assertTrue(leg.contains("if (pumpNotTried9031.get() == true) {"))
        // the pinned order of the live buy is untouched
        assertTrue(ex.contains("prepareJupiterBuy8090(ts, wallet, sol, effectiveSol, tradeKey, c, ::buyTerminalFail, sealedIntent7835, submission8397)"))
        assertTrue(ex.contains("pumpNotTried9031.set(pumpBuyPlan == null)"))
        assertTrue(ex.contains("val directVenue7325 = if (pumpFirstResult != null || prepared8090?.pumpFill9031 == true) pumpVenue else \"raydium\""))
    }

    @Test fun theBuildIsRecorded() {
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §216 — V5.0.9031: the code that runs is named; a close commit waits its turn; the top is remembered"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9031*'"))
    }
}
