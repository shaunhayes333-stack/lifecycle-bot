package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.RetentionPolicy8136.Pressure
import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8309 — 5.0.8306 ran at 473/512 MB (peak 100%, HARD) with 406 MB unattributed; hard trims freed nothing and the
 * census listed hiveBook / insiderTracker / chartLibrary as untrimmed or unreached. Per-mint residue (caches the evaluation
 * path fills for every mint and nothing removes) is bounded and swept for non-live mints only, every registered store
 * either trims or is exempt by a named kind, trims report the bytes each store released, and the census attributes the
 * new stores. Learning books are counted, never trimmed.
 */
class Aate8309MemoryResidueTest {
    private fun mint(i: Int, tag: String) = "$tag$i".padEnd(44, 'M')
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── the residue sweep ──

    @Test fun aSweepDropsOnlyMintsThatAreNoLongerLive() {
        val m = ConcurrentHashMap<String, Int>()
        for (i in 0 until 1_000) m[mint(i, "a")] = i
        m[mint(1, "live") + "|MOONSHOT"] = 1      // "mint|suffix" keys resolve to their mint
        m[mint(2, "doubt")] = 2
        val live = setOf(mint(5, "a"), mint(6, "a"), mint(1, "live"))
        val removed = MintResidue8309.sweepMap8309(m) { k -> if (k == mint(2, "doubt")) throw IllegalStateException("x") else k in live }
        assertEquals(1_000 - 2, removed)
        assertEquals(setOf(mint(5, "a"), mint(6, "a"), mint(1, "live") + "|MOONSHOT", mint(2, "doubt")), m.keys.toSet())
        assertEquals(mint(1, "live"), MintResidue8309.mintOf8309(mint(1, "live") + "|MOONSHOT"))
    }

    @Test fun theWriteCapHoldsPastTenTimesTheCapAndNeverDropsALiveMint() {
        val m = ConcurrentHashMap<String, Int>()
        val keep = mint(0, "k")
        m[keep] = 0
        for (i in 0 until 5_000) { m[mint(i, "w")] = i; MintResidue8309.capMap8309(m, 500) { it == keep } }
        assertTrue("size ${m.size}", m.size <= 500)
        assertTrue(keep in m)
        assertEquals(0, MintResidue8309.capMap8309(ConcurrentHashMap<String, Int>().apply { put("x", 1) }, 500))
    }

    @Test fun aRegisteredCacheIsCountedEstimatedAndSweptByName() {
        val m = ConcurrentHashMap<String, String>()
        for (i in 0 until 300) m[mint(i, "r")] = "v"
        MintResidue8309.register8309("test8309", 100L, { m })
        assertEquals(300, MintResidue8309.countOf8309("test8309"))
        assertEquals(30_000L, MintResidue8309.bytesOf8309("test8309"))
        assertTrue(MintResidue8309.bytes8309() >= 30_000L)
        assertEquals(299, MintResidue8309.sweepOne8309("test8309") { it == mint(7, "r") })
        assertEquals(setOf(mint(7, "r")), m.keys.toSet())
        assertEquals(0, MintResidue8309.sweepOne8309("no-such-store") { false })
        assertEquals(-1L, MintResidue8309.bytesOf8309("no-such-store"))
        assertTrue(MintResidue8309.line8309().startsWith("residue8309[keys="))
        MintResidue8309.register8309("test8309", 0L, { null })   // leave the shared registry with an empty test store
    }

    @Test fun thePeriodicSweepRunsEveryTwoMinutes() {
        assertEquals(120_000L, MintResidue8309.SWEEP_PERIOD_MS_8309)
        assertTrue(MintResidue8309.sweepDue8309(0L, 5L))
        assertFalse(MintResidue8309.sweepDue8309(1_000L, 120_999L))
        assertTrue(MintResidue8309.sweepDue8309(1_000L, 121_000L))
    }

    // ── the largest residue: one 200-candle chart copy per mint ever full-scanned ──

    @Test fun theSmartChartCandleCacheHoldsItsCapAcrossManyMints() {
        for (i in 0 until 1_200) {
            val ts = TokenState(mint = mint(i, "sc"), symbol = "SC$i")
            for (j in 0 until 12) ts.history.addLast(Candle(ts = 1_000L * j, priceUsd = 1.0 + j * 0.01, marketCap = 10_000.0, volumeH1 = 100.0, volume24h = 1_000.0))
            SmartChartScanner.scan(ts)
        }
        val n = SmartChartScanner.residueMap8309().size
        assertTrue("size $n", n in 1..SmartChartScanner.PRICE_CACHE_MINTS_8309)
        assertTrue(SmartChartScanner.residueBytes8309() >= n * 12L * 64L)
        // A sweep with nothing live empties it (these are display copies; a returning mint rebuilds its own).
        assertEquals(n, MintResidue8309.sweepMap8309(SmartChartScanner.residueMap8309()) { false })
        assertEquals(0, SmartChartScanner.residueMap8309().size)
    }

    // ── the registry: exempt kinds, bytes per store, nothing untrimmed ──

    @Test fun exemptStoresAreNotUntrimmedAndTrimsReportBytesPerStore() {
        val r = MemoryRegistry8144()
        val a = ArrayList<Int>().apply { repeat(1_000) { add(it) } }
        r.register8144("a", 100L, { a.size }, { _ -> while (a.size > 100) a.removeAt(a.size - 1) })
        r.register8144("fixedLib", 0L, { 40_000 }, null) { 5_480_000L }
        r.register8144("book", 0L, { -1 }, null) { 2_000_000L }
        r.register8144("plain", 10L, { 5 }, null)
        r.markKind8309("fixedLib", "fixed")
        r.markKind8309("book", "ledger")
        assertEquals(listOf("plain"), r.untrimmed8144())
        assertEquals(listOf("fixedLib(fixed)", "book(ledger)"), r.exempt8309())
        val res = r.trimAll8144(Pressure.HARD)
        val ra = res.first { it.name == "a" }
        assertEquals(100_000L, ra.beforeBytes)
        assertEquals(10_000L, ra.afterBytes)
        assertEquals(90_000L, ra.freedBytes)
        assertTrue(res.first { it.name == "fixedLib" }.exempt)
        assertEquals(2_000_000L, res.first { it.name == "book" }.beforeBytes)
        // Exempt stores are never "unfreed"; a plain store with no trim still is.
        assertEquals(listOf("plain(unreached)"), MemoryRegistry8144.unfreed8144(res, r.census8144()))
        val line = MemoryRegistry8144.trimMbLine8309(res)
        assertTrue(line, line.startsWith("total=0.1MB [a:97->9KB"))
        assertEquals("-", MemoryRegistry8144.trimMbLine8309(emptyList()))
        // Old positional construction still works and carries unknown bytes.
        assertEquals(0L, MemoryRegistry8144.TrimResult8144("x", 3, 1, true).freedBytes)
    }

    @Test fun everyGuardStoreTrimsOrIsExemptAndTheCensusAttributesTheNewStores() {
        assertEquals(emptyList<String>(), MemoryGuard7977.untrimmedStores8309())
        val stores = MemoryGuard7977.registeredStores8144()
        for (s in listOf("res8309:smartChartCandles", "res8309:regimeHistory", "res8309:orderFlow", "res8309:volatilityRegime",
                "res8309:smartMoneyDivergence", "res8309:exitScoreHistory", "res8309:structureClass", "res8309:commonSense",
                "res8309:alphaCache", "res8309:candleSynthBuckets", "res8309:pairHydration", "res8309:regimeTransitions",
                "cortexLedgers8309", "lanePlaybook8309", "cellAllocator8309", "tradeShape8309", "behaviorLearning8309",
                "playbookCache8309", "launchTape8309", "pairFlow8309", "prefsMirror8309", "serviceQueues8309", "hiveBook", "insiderTracker8305", "chartLibrary"))
            assertTrue(s, s in stores)
        assertEquals(stores.size, stores.toSet().size)
        val exempt = MemoryGuard7977.exemptStores8309()
        for (s in listOf("chartLibrary(fixed)", "labeler(ledger)", "cortexLedgers8309(ledger)", "lanePlaybook8309(ledger)",
                "cellAllocator8309(ledger)", "tradeShape8309(ledger)", "behaviorLearning8309(ledger)", "sideEffectQueue(queue)"))
            assertTrue(s, s in exempt)
        // hiveBook and insiderTracker trim (not exempt).
        assertFalse(exempt.any { it.startsWith("hiveBook(") || it.startsWith("insiderTracker8305(") })
        for (n in listOf("smartChartCandles", "orderFlow", "regimeHistory", "pairHydration")) assertTrue(n, n in MintResidue8309.names8309())
        val line = MemoryGuard7977.memLine8309()
        assertTrue(line, line.startsWith("mem8309[residue8309[") && line.contains("lastTrimMb8309=[") && line.contains("exempt8309=["))
        assertTrue(MemoryGuard7977.statusLine7977().contains("mem8309["))
    }

    // ── what must never be dropped ──

    @Test fun liveMintsAreProtectedAndCryptoSymbolsAreNeverSwept() {
        val g = src("engine/MemoryGuard7977.kt")
        val body = g.substringAfter("private fun liveMints8309(now: Long)").substringBefore("/** Registry trim of one residue cache")
        for (k in listOf("m.length < 32", "tokens.containsKey(m)", "m in watch", "m in liveOpen", "m in sealed", "critical8136(m, now)",
                "AdmissionProspect8136.isActive8136(m, now)", "HandoffRetention8185.protects8185(m, now)")) assertTrue(k, body.contains(k))
        // An unreadable watchlist or live projection sweeps nothing.
        assertTrue(body.contains("catch (_: Throwable) { return null }"))
        // The guard sweeps at NORMAL pressure too, under the trim lock.
        assertTrue(g.contains("if (lvl == 0) { escalation8136 = 0; try { periodicResidue8309() } catch (_: Throwable) {}; return }"))
        assertTrue(g.contains("synchronized(trimLock8305) { MintResidue8309.sweep8309(live, now) }"))
    }

    @Test fun insiderTrimDropsExpiredCachesButKeepsTheDeltaBaseline() {
        assertEquals(0, com.lifecyclebot.perps.InsiderWalletTracker.trim8309(true))
        assertTrue(com.lifecyclebot.perps.InsiderWalletTracker.estBytes8309() >= 0L)
        val s = src("perps/InsiderWalletTracker.kt")
        val body = s.substringAfter("fun trim8309(").substringBefore("fun estBytes8309(")
        assertTrue(body.contains("holdingsCache.entries.removeIf { nowMs - it.value.fetchedAt > CACHE_TTL_MS }"))
        assertTrue(body.contains("if (hard) { tokenPriceCache.clear(); tokenSymbolCache.clear() }"))
        assertFalse(body.contains("prevHoldingsSnapshot."))
        val g = src("engine/MemoryGuard7977.kt")
        assertTrue(g.contains("HiveEdge8000.trim8144(if (hard(p)) RetentionPolicy8136.Pressure.CRITICAL else p)"))
    }

    @Test fun learningLedgersAreEstimatedNotTrimmed() {
        val l = CortexLedger7885()
        assertTrue(l.estBytes8309() >= 0L)
        val g = src("engine/MemoryGuard7977.kt")
        for (k in listOf("r.register8144(\"cortexLedgers8309\", 0L, { -1 }, null)", "r.register8144(\"lanePlaybook8309\"",
                "r.register8144(\"behaviorLearning8309\", 0L, { BehaviorLearning.memCount8309() }, null)"))
            assertTrue(k, g.contains(k))
        // The never-called owner cleanups now run.
        for (k in listOf("VolatilityRegimeAI.cleanup()", "OrderFlowImbalanceAI.cleanup()", "SmartMoneyDivergenceAI.cleanup()",
                "DistributionFadeAvoider.cleanup()", "DataPipeline.cleanup()")) assertTrue(k, g.contains(k))
    }
}
