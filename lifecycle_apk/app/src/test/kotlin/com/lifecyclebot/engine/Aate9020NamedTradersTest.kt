package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchFacts8840
import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.engine.truth.NamedTraders9020
import com.lifecyclebot.engine.truth.SignalSourceProof7291
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9020 — the owner's named traders (the 50 most profitable pump.fun wallets he supplied): followed whole on the
 * stream, copied as their own graded source, and not counted as the unknown whale the holder limits judge.
 */
class Aate9020NamedTradersTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val named = NamedTraders9020.WALLETS_9020
    private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    @Test fun theListIsFiftyDistinctSolanaKeys() {
        assertEquals(50, named.size); assertEquals(50, named.toSet().size)
        for (a in named) {
            assertTrue(a, a.length in 32..44 && a.all { it in alphabet })
            var n = java.math.BigInteger.ZERO
            for (c in a) n = n.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(alphabet.indexOf(c).toLong()))
            val bytes = (n.bitLength() + 7) / 8 + a.takeWhile { it == '1' }.length
            assertEquals("$a is a 32-byte key", 32, bytes)
            assertTrue(NamedTraders9020.isNamed9020(a)); assertTrue(NamedTraders9020.isNamedKey9020(LaunchFacts8840.walletKey8853(a)))
        }
        assertEquals("GLSCB2DeQAKcSD79nSSXm4F4qAheWDuJswiT8xXUZ6TN", named.first())
        assertFalse(NamedTraders9020.isNamed9020(ExpertWallets7962.OWNER_WALLET_7962))
        assertFalse(NamedTraders9020.isNamedKey9020(LaunchFacts8840.walletKey8853("W1")))
        assertEquals("TOP_NAMED_GLSC_#1", NamedTraders9020.label9020(named[0]))
        assertTrue("the label reads as a top trader", src("engine/ExpertWallets7962.kt").contains("label.startsWith(\"TopTrader_\") || label.startsWith(\"TOP\") -> Tier7962.TOP"))
    }

    @Test fun everyNamedWalletIsWatchedWhateverTheCopyBooksWidth() {
        val mined = (1..26).map { "MINED$it" }
        // the copy book's record cut 26 asked wallets to 3 (5.0.9016); the named list is not inside that width
        val out = NamedTraders9020.streamList9020(mined + named) { n -> assertEquals(26, n); 3 }
        assertEquals(53, out.size); assertTrue(out.containsAll(named)); assertEquals(mined.take(3), out.take(3))
        assertEquals("no named wallet asked for: the old behaviour", mined.take(3), NamedTraders9020.streamList9020(mined) { 3 })
        assertEquals(named, NamedTraders9020.streamList9020(named) { 0 })
        val ws = src("network/HeliusEnhancedWS.kt")
        assertTrue(ws.contains("NamedTraders9020.streamList9020(") && ws.contains("ExpertWallets7962.prioritise7962(requested)"))
        val cad = src("engine/HeliusCadence8361.kt").substringAfter("fun watchList8361(").substringBefore("fun copyWatched8361(")
        assertTrue(cad.contains("val mined = copyActive.filter { it !in board && it !in named9020 }"))
        assertTrue(cad.contains("copyActive.filter { it in board }.take(ExpertWallets7962.BOARD_SIZE_8713) + named9020).distinct()"))
        val ew = src("engine/ExpertWallets7962.kt")
        assertTrue(ew.contains("if (experts.size >= MAX_EXPERTS && !com.lifecyclebot.engine.truth.NamedTraders9020.isNamed9020(w.address)) continue"))
        assertTrue("fifty busy wallets keep fewer legs each", NamedTraders9020.MAX_LEGS_9020 <= 400 && ew.contains("NamedTraders9020.MAX_LEGS_9020 else MAX_LEGS"))
    }

    @Test fun theListJoinsTheCopyListOnceAndARemovedWalletStaysRemoved() {
        val (add, relabel) = NamedTraders9020.seedPlan9020(mapOf(named[0] to "SMART_MONEY_x3", named[1] to NamedTraders9020.label9020(named[1]), "OTHER" to "TopTrader_x"), null)!!
        assertEquals(48, add.size); assertFalse(named[0] in add); assertEquals(listOf(named[0]), relabel)
        assertNull("seeded already: nothing is re-added", NamedTraders9020.seedPlan9020(emptyMap(), NamedTraders9020.LIST_ID_9020))
        assertEquals("a new list seeds again", 50, NamedTraders9020.seedPlan9020(emptyMap(), "an-older-list")!!.first.size)
        val cte = src("engine/CopyTradeEngine.kt")
        assertTrue(cte.contains("try { seedNamed9020() } catch (_: Throwable) {}"))
        assertTrue(cte.contains("prefs.edit().putString(\"named_list_9020\", named.LIST_ID_9020).apply()"))
        assertTrue("the board sync only ever drops board wallets", cte.contains("tracked.filter { isBoardWallet8713(it) && it.address !in listed && it.totalPnlSol <= 0.0 }"))
        assertFalse(NamedTraders9020.LABEL_PREFIX_9020.startsWith(CopyTradeEngine.BOARD_LABEL_8713))
        assertTrue(CopyTradeEngine.isNamedWallet9020(CopyTradeEngine.CopyWallet(named[3], "x", true, 0, 0, 0, 0.0, 0L, 0L)))
        assertTrue(src("engine/SmartMoneyDiscovery7277.kt").contains("!CopyTradeEngine.isBoardWallet8713(it) && !CopyTradeEngine.isNamedWallet9020(it)"))
    }

    @Test fun aNamedBuyIsACopySignalGradedOnItsOwnRecord() {
        assertEquals(SignalSourceProof7291.Source.NAMED, NamedTraders9020.sourceFor9020(named[7]))
        assertEquals(SignalSourceProof7291.Source.COPY, NamedTraders9020.sourceFor9020("SomeMinedWallet1111111111111111111111111111"))
        fun labelled(): Int = Regex("NAMED\\[.*?labeled7731\\[n=(\\d+)").find(SignalSourceProof7291.statusLine())!!.groupValues[1].toInt()
        val before = labelled()
        val mint = "Aate9020NamedMint11111111111111111111111111"
        SignalSourceProof7291.stamp(SignalSourceProof7291.Source.NAMED, mint)
        SignalSourceProof7291.stamp(SignalSourceProof7291.Source.COPY, mint)   // a mined wallet follows: the coin stays the named list's
        SignalSourceProof7291.onForwardLabel7731(mint, 0.25)
        assertEquals(before + 1, labelled())
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("com.lifecyclebot.engine.truth.NamedTraders9020.sourceFor9020(wallet), mint)"))
        assertTrue("the copy route itself is unchanged: candidate, V3, FDG, sizing", bs.contains("com.lifecyclebot.engine.InsiderCopyEngine.copyBuyFromSmartMoney7277("))
        assertTrue("the stream's width still follows the mined book", src("engine/truth/HeliusCreditEconomy7881.kt").contains("SignalSourceProof7291.economicValue7881(SignalSourceProof7291.Source.COPY)"))
    }

    @Test fun namedBuyersAreCountedPerCoinUntilTheySell() {
        NamedTraders9020.resetForTest9020()
        val mint = "Aate9020BuyersMint1111111111111111111111111"; val t = 1_000_000_000L
        NamedTraders9020.noteBuy9020(mint, named[0], t); NamedTraders9020.noteBuy9020(mint, named[1], t + 60_000L)
        NamedTraders9020.noteBuy9020(mint, "NotOnTheList11111111111111111111111111111111", t)
        assertEquals(2, NamedTraders9020.buyers9020(mint, t + 120_000L))
        NamedTraders9020.noteSell9020(mint, named[0])
        assertEquals(1, NamedTraders9020.buyers9020(mint, t + 120_000L))
        assertEquals("a buy older than 30 minutes no longer counts", 0, NamedTraders9020.buyers9020(mint, t + 60_000L + NamedTraders9020.BUYER_WINDOW_MS_9020 + 1L))
        assertTrue(NamedTraders9020.statusLine9020(t + 120_000L).contains("buysSeen=2 sellsSeen=1"))
        val cte = src("engine/CopyTradeEngine.kt")
        assertTrue(cte.contains("NamedTraders9020.noteBuy9020(mint, buyerWallet, now)") && cte.contains("NamedTraders9020.noteSell9020(mint, buyerWallet)"))
        NamedTraders9020.resetForTest9020()
    }

    @Test fun theHolderLimitsJudgeTheWalletsThatAreNotOnTheList() {
        val f = LaunchFacts8840.Fold8840("DEV", "SIG0"); val p = 4e-8; var t = 9_000_000L   // 1 SOL = 2.5 % of supply
        fun buy(w: String, sol: Double) { f.add(t, p, sol, true, w, "S$t"); t += 3_000L }
        buy("DEV", 0.2)
        buy(named[0], 3.0)                       // a named trader holds 7.5 %
        buy("UNKNOWN_A", 1.6)                    // the largest unknown wallet holds 4.0 %
        for (i in 1..12) buy("CROWD$i", 0.4)     // twelve wallets at 1.0 % each
        buy(named[1], 0.04)                      // a named trader holding 0.1 %: outside the ten largest
        val x = f.snapshot()
        assertEquals(7.5, x.top1Pct, 1e-6); assertEquals(4.0, x.top1UnnamedPct, 1e-6); assertEquals(7.5, x.namedTop10Pct, 1e-6)
        val g = PreTradeHardGate
        assertEquals("TOP_HOLDER_CONCENTRATION", g.holderBlock9019(20.0, x.top1Pct))
        assertNull("the largest wallet is a named trader: the limit reads the next one", g.holderBlock9019(20.0, g.singleJudged9020(x.top1Pct, x.top1UnnamedPct)))
        assertEquals("an unknown wallet over the limit is still refused", "TOP_HOLDER_CONCENTRATION", g.holderBlock9019(20.0, g.singleJudged9020(9.0, 6.0)))
        assertEquals("no named reading: the plain largest wallet", 6.0, g.singleJudged9020(6.0, Double.NaN), 0.0)
        assertEquals(44.5, g.top10Judged9020(52.0, 7.5), 1e-9); assertEquals(52.0, g.top10Judged9020(52.0, Double.NaN), 0.0)
        assertEquals(0.0, g.top10Judged9020(5.0, 7.5), 0.0)
        assertNull("ten wallets at 52 % of which a named trader holds 7.5 %", g.holderBlock9019(g.top10Judged9020(52.0, 7.5), 4.0))
        assertEquals("unknown wallets alone over the spread limit", "TOP_HOLDER_CONCENTRATION", g.holderBlock9019(g.top10Judged9020(60.0, 7.5), 4.0))
        // a launch with no named holder reads exactly as before
        val h = LaunchFacts8840.Fold8840("DEV", "SIG0"); h.add(1_000L, p, 2.4, true, "W1", "A"); h.add(4_000L, p, 0.4, true, "W2", "B")
        val y = h.snapshot()
        assertEquals(y.top1Pct, y.top1UnnamedPct, 1e-9); assertEquals(0.0, y.namedTop10Pct, 0.0)
        val gate = src("engine/PreTradeHardGate.kt")
        assertTrue(gate.contains("holderBlockFor9020(holderProof8960(ts, ts.safety, nowMs).adversePct, ts.mint)"))
        assertTrue(gate.contains("if (holderBlock9019(top10J9020, single9019) != null) {"))
        assertEquals("the thresholds are the owner's, unchanged", 5.0, g.SINGLE_HOLDER_FATAL_PCT_9019, 0.0)
    }

    @Test fun theDatasetCarriesWhatGradesTheRule() {
        val added = listOf("top1UnnamedPct", "namedTop10Pct", "namedBuyers30m")
        assertEquals(added, LearnerColumns8798.NAMES.takeLast(added.size))
        assertEquals("poolLabel", LearnerColumns8798.NAMES[LearnerColumns8798.SIZE - added.size - 1])
        val lc = src("engine/truth/LearnerColumns8798.kt")
        for (c in added) assertTrue(c, lc.contains("put(\"$c\""))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("NamedTraders9020.statusLine9020()"))
        assertTrue(NamedTraders9020.statusLine9020().startsWith("namedTraders9020[list=${NamedTraders9020.LIST_ID_9020} wallets=50 "))
        NamedTraders9020.noteExemption9020(); NamedTraders9020.noteSeeded9020(50)
        assertTrue(NamedTraders9020.statusLine9020().contains("seededThisRun=+50"))
        NamedTraders9020.resetForTest9020()
    }
}
