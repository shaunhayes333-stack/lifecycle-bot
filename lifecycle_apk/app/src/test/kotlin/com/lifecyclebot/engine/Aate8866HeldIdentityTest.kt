package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.perps.CryptoAltTrader
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.ui.ReportCopyDialog8198
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8866 — a held crypto position is its trader's to mark and to sell. 5.0.8864, live: CRIME, ADCY and Tomás sat at
 * -44 %, -75 % and -20 % for hours under a -10 % floor. The trader keyed them "solana|<mint>", the ledger "<mint>", and
 * every comparison between the two was a string equality; the meme risk clock latched their stops and sent them to an
 * Executor that had been told not to see them (3,069 refusals).
 */
class Aate8866HeldIdentityTest {
    private val mint = "96WnzbBGRm3RadEMZcn6VfGQKTk7AaNYrUmTXNx9pump"
    private val other = "PKShUAph3RadEMZcn6VfGQKTk7AaNYrUmTXNx9pump11"
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun ledger(mintKey: String, lane: String = "CRYPTO_ALT", id: String = "ALT:8866") = CanonicalPositionAuthority6441.Position(
        id, "live", mintKey, "CRIME", lane, "run8866", 100L,
        0.0441, BigInteger("29647531554"), BigInteger("29647531554"), 0.0, 0.0, 0.0, 0.0, 6,
        lifecycle = CanonicalPositionAuthority6441.Lifecycle.OPEN,
        lastMutationMs = 1_000L, quarantineReason = "", entryPriceUsd = 1.108e-4, assetClass = AssetClass.CRYPTO_ALT,
    )

    private fun own(key: String) = CryptoAltTrader.AltPosition(
        id = "ALT:8866", market = PerpsMarket.DYN, dynSymbol = "CRIME", dynMint = mint,
        direction = PerpsDirection.LONG, isSpot = true, isPaper = false, canonicalAssetKey = key,
        markAssetKey = key, markUpdatedAtMs = 900L,
        entryPrice = 1.1e-4, currentPrice = 1.2e-4, sizeSol = 0.0441, leverage = 1.0,
        takeProfitPrice = 2.2e-4, stopLossPrice = 0.9e-4, aiScore = 71, aiConfidence = 80,
        reasons = listOf("STRATEGY7803=MOMENTUM"), openTime = 101L, highestPnlPct = 12.0,
    )

    @Test fun oneSolanaMintInEitherSpellingIsOneAsset() {
        assertEquals(mint, DynamicAltTokenRegistry.solanaMintOf8866(mint))
        assertEquals(mint, DynamicAltTokenRegistry.solanaMintOf8866("solana|$mint"))
        assertNull(DynamicAltTokenRegistry.solanaMintOf8866("base|0xc2daf5c7dd14ceb2203f09741aaaaaaaaaaaaaaa"))
        assertNull("an EVM address is not base58", DynamicAltTokenRegistry.solanaMintOf8866("0xc2daf5c7dd14ceb2203f09741aaaaaaaaaaaaaaa"))
        for (placeholder in listOf("cg:bitcoin", "static:BTC", "perps:SOL", "unresolved:DYN", "short", ""))
            assertNull(placeholder, DynamicAltTokenRegistry.solanaMintOf8866(placeholder))
        assertTrue(DynamicAltTokenRegistry.sameAsset8866("solana|$mint", mint))
        assertTrue(DynamicAltTokenRegistry.sameAsset8866(mint, "solana|$mint"))
        assertTrue(DynamicAltTokenRegistry.sameAsset8866(mint, mint))
        assertTrue(DynamicAltTokenRegistry.sameAsset8866("cg:x", "cg:x"))
        assertFalse(DynamicAltTokenRegistry.sameAsset8866("solana|$mint", other))
        assertFalse(DynamicAltTokenRegistry.sameAsset8866("", mint))
        assertFalse(DynamicAltTokenRegistry.sameAsset8866("cg:x", "cg:y"))
        assertFalse("only a Solana mint has two spellings", DynamicAltTokenRegistry.sameAsset8866("base|0xabc", "0xabc"))
        assertEquals("solana" to mint, DynamicAltTokenRegistry.heldChainAddress8866(mint, null))
        assertEquals("solana" to mint, DynamicAltTokenRegistry.heldChainAddress8866("solana|$mint", null))
        assertEquals("base" to "0xc2daf5c7dd14ceb2203f09741aaaaaaaaaaaaaaa",
            DynamicAltTokenRegistry.heldChainAddress8866("base|0xc2daf5c7dd14ceb2203f09741aaaaaaaaaaaaaaa", null))
        assertNull(DynamicAltTokenRegistry.heldChainAddress8866("cg:bitcoin", null))
        assertNull(DynamicAltTokenRegistry.heldChainAddress8866("unknown|$mint", null))
        assertEquals("solana" to mint, DynamicAltTokenRegistry.heldChainAddress8866("perps:SOL", mint))
    }

    @Test fun aLivePositionKeepsItsOwnStopTargetAndStrategy() {
        // the ledger's row is keyed by the bare mint, the trader's saved position by the registry identity
        val saved = CryptoAltTrader.restoreCanonicalPosition8521(ledger(mint), own("solana|$mint"))
        assertNotNull("the saved position is this ledger row's", saved)
        val restored = saved!!
        assertEquals(0.9e-4, restored.stopLossPrice, 0.0)
        assertEquals(2.2e-4, restored.takeProfitPrice, 0.0)
        assertEquals(listOf("STRATEGY7803=MOMENTUM"), restored.reasons)
        assertEquals("solana|$mint", restored.canonicalAssetKey)
        assertEquals(1.108e-4, restored.entryPrice, 0.0)
        assertEquals(12.0, restored.highestPnlPct, 0.0)
        assertNull("another coin's saved position supplies nothing", CryptoAltTrader.restoreCanonicalPosition8521(ledger(mint), own("solana|$other")))
        // a position rebuilt from the ledger alone takes the registry's mark for the same mint, and trusts it
        val mark = DynamicAltTokenRegistry.HeldMarkRefresh7251(6.186e-5, "solana|$mint", 5_000L, true)
        val rebuilt = CryptoAltTrader.restoreCanonicalPosition8521(ledger(mint), null, mark)!!
        assertEquals(6.186e-5, rebuilt.currentPrice, 0.0)
        assertEquals(mint, rebuilt.markAssetKey)
        assertTrue(rebuilt.hasTrustedMark(6_000L))
        val wrong = CryptoAltTrader.restoreCanonicalPosition8521(ledger(mint), null, mark.copy(canonicalIdentity = "solana|$other"))!!
        assertEquals(0.0, wrong.currentPrice, 0.0)
        val trader = src("perps/CryptoAltTrader.kt")
        assertTrue("the trader's own position is not projected over",
            trader.contains("DynamicAltTokenRegistry.sameAsset8866(it.canonicalAssetKey, cp.mint) } == true) continue"))
        assertTrue(trader.contains("val persisted = stored.firstOrNull { it.id == cp.positionId && DynamicAltTokenRegistry.sameAsset8866(it.canonicalAssetKey, cp.mint) &&"))
    }

    @Test fun aHeldCoinIsAlwaysARegistryRow() {
        fun row(m: String, at: Long, static: Boolean = false) =
            DynamicAltTokenRegistry.DynToken(mint = m, symbol = "S", name = "S", chainId = "solana", isStatic = static, lastUpdatedMs = at)
        val fresh = (1..5).map { row("fresh$it", 100L + it) }
        val held = row(mint, 1L)
        val quiet = row("quietNotHeld", 2L)
        val kept = DynamicAltTokenRegistry.persistRows8866(fresh + held + quiet, setOf(mint.lowercase()), 3)
        assertEquals("the three newest and the held row the cap cut", listOf("fresh5", "fresh4", "fresh3", mint), kept.map { it.mint })
        assertEquals(3, DynamicAltTokenRegistry.persistRows8866(fresh + held + quiet, emptySet(), 3).size)
        assertEquals("under the cap everything is written", 7, DynamicAltTokenRegistry.persistRows8866(fresh + held + quiet, setOf(mint.lowercase()), 50).size)
        assertTrue(DynamicAltTokenRegistry.isHeldRow8866(held, setOf("solana|$mint".lowercase())))
        assertTrue(DynamicAltTokenRegistry.isHeldRow8866(held, setOf(mint.lowercase())))
        assertFalse(DynamicAltTokenRegistry.isHeldRow8866(quiet, setOf(mint.lowercase())))
        assertFalse(DynamicAltTokenRegistry.isHeldRow8866(held, emptySet()))

        // a row a restart cut from the file is created again from the position's own key, price-less
        val gone = "Aate8866HeLdRowTestMint111111111111111111111"
        assertNull(DynamicAltTokenRegistry.heldToken8866("solana|$gone", gone))
        val created = DynamicAltTokenRegistry.ensureHeld8866("solana|$gone", null, "held8866")
        assertNotNull(created)
        val seeded = created!!
        assertEquals("solana", seeded.chainId)
        assertEquals(gone, seeded.tokenAddress)
        assertEquals(0.0, seeded.price, 0.0)
        assertEquals("held_position_8866", seeded.source)
        assertEquals("solana|$gone", seeded.canonicalIdentity6544)
        assertEquals("the ledger's bare mint finds the same row", "solana|$gone", DynamicAltTokenRegistry.heldToken8866(gone, gone)?.canonicalIdentity6544)
        assertNull("a placeholder names no chain and address", DynamicAltTokenRegistry.ensureHeld8866("cg:nothing-8866", null, "X"))
        // the price-less row takes a mark dated before the row was written; then only a newer observation replaces it
        val now = System.currentTimeMillis()
        assertTrue(DynamicAltTokenRegistry.observeHeldMarkAt8800("solana|$gone", 1.7e-5, now - 5_000L))
        val snap = DynamicAltTokenRegistry.heldMarkSnapshot7251(gone)
        assertTrue(snap.freshObservation)
        assertEquals(1.7e-5, snap.price, 0.0)
        assertEquals(now - 5_000L, snap.observedAtMs)
        assertFalse(DynamicAltTokenRegistry.observeHeldMarkAt8800("solana|$gone", 9.0e-5, now - 20_000L))
        val registry = src("perps/DynamicAltTokenRegistry.kt")
        assertTrue(registry.contains("persistRows8866(registry.values.filter { !it.mint.startsWith(\"cg:\") }, heldLedgerKeys8866(), MAX_PERSIST_ROWS_8033)"))
        assertTrue(src("perps/CryptoAltTrader.kt").contains("DynamicAltTokenRegistry.ensureHeld8866(positionKey, position.dynMint, position.marketSymbol)"))
    }

    @Test fun theCanonicalHeldMarkIsTakenOnlyForThisMintInUsdAndFresh() {
        val now = 1_000_000L
        assertEquals(1.735e-5 to now - 5_000L, CryptoAltTrader.canonicalHeldUsd8866(mint, mint, mint, "USD_PER_TOKEN", 1.735e-5, now - 5_000L, now, 60_000L))
        assertNull("older than the bound", CryptoAltTrader.canonicalHeldUsd8866(mint, mint, mint, "USD_PER_TOKEN", 1.735e-5, now - 60_001L, now, 60_000L))
        assertNull("other units", CryptoAltTrader.canonicalHeldUsd8866(mint, mint, mint, "SOL_PER_TOKEN", 1.735e-5, now - 5_000L, now, 60_000L))
        assertNull("another mint's mark", CryptoAltTrader.canonicalHeldUsd8866(other, other, mint, "USD_PER_TOKEN", 1.735e-5, now - 5_000L, now, 60_000L))
        assertNull("another base", CryptoAltTrader.canonicalHeldUsd8866(mint, other, mint, "USD_PER_TOKEN", 1.735e-5, now - 5_000L, now, 60_000L))
        assertNull(CryptoAltTrader.canonicalHeldUsd8866(mint, mint, mint, "USD_PER_TOKEN", Double.NaN, now - 5_000L, now, 60_000L))
        assertNull(CryptoAltTrader.canonicalHeldUsd8866(mint, mint, mint, "USD_PER_TOKEN", 0.0, now - 5_000L, now, 60_000L))
        assertNull("dated after now", CryptoAltTrader.canonicalHeldUsd8866(mint, mint, mint, "USD_PER_TOKEN", 1.0, now + 5L, now, 60_000L))
        assertNull(CryptoAltTrader.canonicalHeldUsd8866("", "", "", "USD_PER_TOKEN", 1.0, now, now, 60_000L))
        val trader = src("perps/CryptoAltTrader.kt")
        assertTrue(trader.contains("val canonical8866 = canonicalHeldMark8866(resolved!!)"))
        assertTrue(trader.contains("CanonicalPriceMarkRegistry6522.heldRiskMark(mint)"))
        assertTrue("the mark's own time, never the tick's", trader.contains("markUpdatedAtMs = if (position.isDynamic) markObservedAt8866 else System.currentTimeMillis(),"))
        assertTrue(trader.contains("DynamicAltTokenRegistry.sameAsset8866(heldMark7251.canonicalIdentity, positionKey)"))
    }

    @Test fun theRiskClockLeavesATradersRowToItsTrader() {
        assertFalse("a CRYPTO_ALT row is CryptoAltTrader's to exit", CanonicalPositionAuthority6441.isMemeExecutorExit8866(ledger(mint)))
        val meme = ledger(mint, lane = "SHITCOIN", id = "m8866").copy(assetClass = AssetClass.SOLANA_TOKEN)
        assertTrue(CanonicalPositionAuthority6441.isMemeExecutorExit8866(meme))
        assertFalse(CanonicalPositionAuthority6441.isMemeExecutorExit8866(meme.copy(assetClass = AssetClass.STOCK)))
        assertFalse("a pre-6592 row is read by its id prefix", CanonicalPositionAuthority6441.isMemeExecutorExit8866(meme.copy(positionId = "ALT:legacy")))
        assertTrue(src("engine/truth/CanonicalRiskClock6454.kt").contains("CanonicalPositionAuthority6441.isMemeExecutorExit8866(it)"))
        assertTrue(src("engine/PositionProof8189.kt").contains("com.lifecyclebot.perps.CryptoAltTrader.ownerProof8866(p.positionId, nowMs)"))
        assertEquals("CRYPTO_ALT[eval=2s EVALUATED mark=CANONICAL_HELD/5s pnl=-74.9% plan=LEDGER_RECOVERY]",
            CryptoAltTrader.ownerProofLine8866(2L, "EVALUATED", "CANONICAL_HELD", 5L, -74.93, true))
        assertEquals("CRYPTO_ALT[eval=1s IDENTITY_UNRESOLVED plan=OWN]",
            CryptoAltTrader.ownerProofLine8866(1L, "IDENTITY_UNRESOLVED", "-", null, Double.NaN, false))
        assertEquals("CRYPTO_ALT[never evaluated]", CryptoAltTrader.ownerProofLine8866(null, "-", "-", null, Double.NaN, false))
        assertEquals("CRYPTO_ALT[never evaluated]", CryptoAltTrader.ownerProof8866("ALT:no-such-position-8866"))
    }

    @Test fun aSaleThatWasNotConfirmedIsNotAskedAgainOnTheNextTick() {
        val now = 1_000_000L
        assertTrue("the trader's own live exit waits for the retry time", CryptoAltTrader.liveCloseSpaced8866(paper = false, operator = false, retryAtMs = now + 15_000L, nowMs = now))
        assertFalse("and is asked at it", CryptoAltTrader.liveCloseSpaced8866(false, false, now, now))
        assertFalse("no earlier failure, no wait", CryptoAltTrader.liveCloseSpaced8866(false, false, 0L, now))
        assertFalse("paper settles from its mark", CryptoAltTrader.liveCloseSpaced8866(true, false, now + 15_000L, now))
        assertFalse("the operator's close is never spaced", CryptoAltTrader.liveCloseSpaced8866(false, true, now + 15_000L, now))
        for (r in listOf("USER", "USER_STOP", "USER_REQUEST", "bot_shutdown")) assertTrue(r, CryptoAltTrader.operatorClose8866(r))
        for (r in listOf("TICK_HARD_FLOOR_-75PCT", "HARD_SL", "DESK_PEAK_DRAWDOWN_LOCK_MOONSHOT_peak40_now12_7391")) assertFalse(r, CryptoAltTrader.operatorClose8866(r))
        val trader = src("perps/CryptoAltTrader.kt")
        assertTrue(trader.contains("liveCloseRetryAt8866[pos.id] = System.currentTimeMillis() + HELD_MARK_REFRESH_COOLDOWN_MS_7251"))
        assertTrue("the sale that did not complete is named", trader.contains("noteHeld8866(pos.id, \"CLOSE_NOT_CONFIRMED:\$why8866\""))
    }

    @Test fun aJobTheDecisionWaitsForLeavesTheBackgroundCpuGroup() {
        assertTrue(JobCost8813.YIELDING_NICE_8866 in 1..9)
        assertFalse(JobCost8813.backgroundGroup8866(JobCost8813.YIELDING_NICE_8866))
        assertFalse(JobCost8813.backgroundGroup8866(0))
        assertTrue(JobCost8813.backgroundGroup8866(10))
        assertTrue("Java MIN_PRIORITY is nice 19", JobCost8813.backgroundGroup8866(19))
        val tree = src("engine/truth/TreeRanker8737.kt")
        assertTrue(tree.contains("JobCost8813.measureYielding8866(\"treeParse\")") && tree.contains("JobCost8813.measureYielding8866(\"treeTrain\")"))
        assertTrue(JobCost8813.statusLine8813().contains("yieldingRuns8866="))
    }

    @Test fun theOfferSaysWhatItCostsAndWhatLeaves() {
        val offer = ReportCopyDialog8198.heapMapOffer8866(462L, 512L)
        assertTrue(offer, offer.contains("now 462 of 512 MB"))
        assertTrue(offer, offer.contains("stops the bot"))
        assertTrue(offer, offer.contains("Only class names and sizes are kept"))
        assertTrue(offer, offer.contains("never leaves the app and is deleted"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 57. A held crypto position is its trader's to mark and sell; what holds the heap (8866)"))
    }
}
