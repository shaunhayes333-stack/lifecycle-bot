package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.LiquidityTruth8037
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.OnChainHolderConcentration7379
import com.lifecyclebot.engine.truth.OnChainHolderConcentration7379.Holder8418
import com.lifecyclebot.engine.truth.OnChainHolderConcentration7379.Row8418
import com.lifecyclebot.engine.truth.TokenBirthHydrator7441
import com.lifecyclebot.engine.truth.TokenBirthHydrator7441.MintInit8418
import com.lifecyclebot.engine.truth.TokenBirthHydrator7441.SigRow8144
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** V5.0.8418 — holder evidence, mint-creation proof, newest-not-largest discovery metrics, pool/curve depth only. */
class Aate8418SafetyEvidenceTest {

    @After fun resetSol() { WalletManager.lastKnownSolPrice = 0.0 }

    private val wallet = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"      // on curve (a normal key)
    private val wallet2 = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"     // on curve
    private val pda = "4wTV1YmiEkRvAtNtsSGPtUrqRYQMe5SKy2uB4Jjaxnjf"        // off curve (a PDA)
    private val pumpProgram = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"

    // ── item 2: holders ──

    @Test fun program_owned_balances_are_classified_not_dropped() {
        val h = OnChainHolderConcentration7379
        assertEquals(Holder8418.WALLET, h.classify8418(wallet, null, false))
        assertEquals(Holder8418.VENUE, h.classify8418(pda, pumpProgram, true))                    // the curve PDA
        assertEquals(Holder8418.VENUE, h.classify8418("5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1", null, false))  // Raydium v4 authority
        assertEquals(Holder8418.UNCLASSIFIED_PROGRAM, h.classify8418(pda, "LockerProgram111111111111111111111111111111", true))
        assertEquals(Holder8418.UNCLASSIFIED_PROGRAM, h.classify8418(pda, "", true))                // PDA with no account of its own
        assertEquals(Holder8418.OWNER_UNREAD, h.classify8418(pda, null, false))                     // owner's program not read
        assertEquals(Holder8418.OWNER_UNREAD, h.classify8418("", null, false))
        assertEquals(Holder8418.BURN, h.classify8418("1nc1nerator11111111111111111111111111111111", null, false))
    }

    @Test fun unclassified_program_balance_counts_toward_top10() {
        val h = OnChainHolderConcentration7379
        val supply = BigInteger.valueOf(1_000L)
        val rows = listOf(
            Row8418("curve", BigInteger.valueOf(700L), Holder8418.VENUE),
            Row8418("lock", BigInteger.valueOf(200L), Holder8418.UNCLASSIFIED_PROGRAM),
            Row8418(wallet, BigInteger.valueOf(50L), Holder8418.WALLET),
            Row8418(wallet2, BigInteger.valueOf(50L), Holder8418.WALLET),
        )
        val v = h.verdict8418(supply, rows, listTruncated = false)!!
        // 7379 dropped the 200 held by the program and read 10%; it is 30%.
        assertEquals(30.0, v.pct, 1e-9)
        assertTrue(v.complete)
        assertEquals(1, v.unclassified)
        assertEquals(1, v.venueExcluded)
        assertEquals(30.0, h.decisive8418(v)!!, 1e-9)
    }

    @Test fun incomplete_account_evidence_is_unknown_never_clean() {
        val h = OnChainHolderConcentration7379
        val supply = BigInteger.valueOf(1_000L)
        val unread = listOf(
            Row8418(wallet, BigInteger.valueOf(100L), Holder8418.WALLET),
            Row8418("", BigInteger.valueOf(500L), Holder8418.OWNER_UNREAD),
        )
        val v = h.verdict8418(supply, unread, listTruncated = false)!!
        assertFalse(v.complete)
        assertNull("an unread owner makes a 10% figure UNKNOWN", h.decisive8418(v))
        // a truncated largest-accounts list that does not reach ten holders is a lower bound
        val truncated = h.verdict8418(supply, listOf(Row8418(wallet, BigInteger.valueOf(300L), Holder8418.WALLET)), listTruncated = true)!!
        assertFalse(truncated.complete)
        assertNull(h.decisive8418(truncated))
        // a lower bound that already crosses the hard-block line is decisive
        val heavy = h.verdict8418(supply, listOf(Row8418(wallet, BigInteger.valueOf(850L), Holder8418.WALLET),
            Row8418("", BigInteger.ONE, Holder8418.OWNER_UNREAD)), listTruncated = true)!!
        assertEquals(85.0, h.decisive8418(heavy)!!, 1e-9)
        // nothing countable (only venues / burn) is UNKNOWN, not 0%
        assertNull(h.verdict8418(supply, listOf(Row8418("curve", BigInteger.valueOf(900L), Holder8418.VENUE)), false))
        assertNull(h.decisive8418(null))
        assertTrue(OnChainHolderConcentration7379.VENUE_PROGRAMS_8418.contains(pumpProgram))
        assertTrue(OnChainHolderConcentration7379.VENUE_AUTHORITIES_8418.contains("GpMZbSM2GgvTKHJirzeGfMFoaZ8UR2X7F4v8vHTvxFbL"))
        assertTrue(OnChainHolderConcentration7379.BURN_OWNERS_8418.contains("1nc1nerator11111111111111111111111111111111"))
        assertEquals(80.0, OnChainHolderConcentration7379.LOWER_BOUND_DECISIVE_PCT_8418, 1e-9)
    }

    // ── item 4: mint creation proof ──

    private fun ix(program: String, type: String, mint: String): JSONObject =
        JSONObject().put("program", program).put("parsed", JSONObject().put("type", type).put("info", JSONObject().put("mint", mint)))

    private fun tx(top: List<JSONObject>, inner: List<JSONObject> = emptyList(), err: Boolean = false): JSONObject {
        val meta = JSONObject().put("err", if (err) JSONObject().put("InstructionError", 1) else JSONObject.NULL)
        if (inner.isNotEmpty()) meta.put("innerInstructions", JSONArray().put(JSONObject().put("index", 0).put("instructions", JSONArray(inner))))
        return JSONObject().put("blockTime", 1_760_000_000L)
            .put("transaction", JSONObject().put("message", JSONObject().put("instructions", JSONArray(top))))
            .put("meta", meta)
    }

    @Test fun only_an_initialize_mint_for_this_mint_proves_creation() {
        val h = TokenBirthHydrator7441
        val m = "MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        assertEquals(MintInit8418.PROVEN, h.mintInitProof8418(tx(listOf(ix("spl-token", "initializeMint2", m))), m))
        assertEquals(MintInit8418.PROVEN, h.mintInitProof8418(tx(listOf(ix("system", "createAccount", m)), inner = listOf(ix("spl-token-2022", "initializeMint", m))), m))
        // the oldest row a truncated history returns: a transfer, or another mint's init — not proof
        assertEquals(MintInit8418.NOT_IN_TRANSACTION, h.mintInitProof8418(tx(listOf(ix("spl-token", "transferChecked", m))), m))
        assertEquals(MintInit8418.NOT_IN_TRANSACTION, h.mintInitProof8418(tx(listOf(ix("spl-token", "initializeMint2", "OtherMint1111111111111111111111111111111111"))), m))
        assertEquals(MintInit8418.NOT_IN_TRANSACTION, h.mintInitProof8418(tx(listOf(ix("spl-memo", "initializeMint2", m))), m))
        assertEquals(MintInit8418.NOT_IN_TRANSACTION, h.mintInitProof8418(tx(listOf(ix("spl-token", "initializeMint2", m)), err = true), m))
        assertEquals(MintInit8418.TRANSACTION_UNAVAILABLE, h.mintInitProof8418(null, m))
    }

    @Test fun page_scan_names_the_oldest_successful_signature() {
        val h = TokenBirthHydrator7441
        val scan = h.scanPage8144(listOf(SigRow8144("NEW", 2_000L, 9L, false), SigRow8144("OLD", 1_000L, 8L, false), SigRow8144("BAD", 900L, 7L, true)), 1_000)
        assertEquals("OLD", scan.oldestOkSignature8418)
        assertEquals("BAD", scan.cursor)
        assertEquals("", h.scanPage8144(listOf(SigRow8144("F", 1L, 1L, true)), 1_000).oldestOkSignature8418)
    }

    // ── item 5: newest replaces largest ──

    @Test fun a_newer_lower_observation_replaces_the_historical_peak() {
        assertTrue(TokenMergeQueue.newerMetric8418(50_000.0, 20_000.0))
        assertTrue(TokenMergeQueue.newerMetric8418(20_000.0, 50_000.0))
        assertFalse(TokenMergeQueue.newerMetric8418(20_000.0, 0.0))
        assertFalse(TokenMergeQueue.newerMetric8418(20_000.0, Double.NaN))
        assertFalse(TokenMergeQueue.newerMetric8418(20_000.0, 20_000.0))
        assertEquals(1_200.0, LiquidityTruth8037.latest8418(1_200.0, 9_000.0), 1e-9)
        assertEquals(9_000.0, LiquidityTruth8037.latest8418(0.0, 9_000.0), 1e-9)
        assertEquals(0.0, LiquidityTruth8037.latest8418(Double.NaN, -1.0), 1e-9)
        assertTrue(LiquidityTruth8037.intakeWrites8418(9_000.0, 1_200.0, capDerived = false, chainHeld = false))
        assertFalse(LiquidityTruth8037.intakeWrites8418(9_000.0, 1_200.0, capDerived = true, chainHeld = false))
        assertFalse(LiquidityTruth8037.intakeWrites8418(9_000.0, 1_200.0, capDerived = false, chainHeld = true))
        assertFalse(LiquidityTruth8037.intakeWrites8418(9_000.0, 0.0, capDerived = false, chainHeld = false))
    }

    @Test fun intake_lowers_a_drained_tick_and_never_writes_a_cap_derived_depth() {
        val ts = TokenState(mint = "Drain8418AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", symbol = "DRN")
        ts.lastLiquidityUsd = 9_000.0
        assertTrue(HydrationReeval8343.intakeLiquidity8345(ts, 1_200.0))
        assertEquals(1_200.0, ts.lastLiquidityUsd, 1e-9)
        val cap = LiquidityTruth8037.capDerived8418(ts.mint, 2_600.0)
        assertEquals(2_600.0, cap, 1e-9)
        assertTrue(LiquidityTruth8037.isCapDerived8418(ts.mint, 2_600.0))
        assertFalse(HydrationReeval8343.intakeLiquidity8345(ts, cap))
        assertEquals(1_200.0, ts.lastLiquidityUsd, 1e-9)
        val fresh = TokenState(mint = "Fresh8418AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", symbol = "FRS")
        LiquidityTruth8037.capDerived8418(fresh.mint, 3_100.0)
        assertFalse(HydrationReeval8343.intakeLiquidity8345(fresh, 3_100.0))
        assertEquals("an estimate stays out of the field safety reads", 0.0, fresh.lastLiquidityUsd, 1e-9)
    }

    // ── item 6: pool / curve evidence only ──

    @Test fun hydrate_no_longer_writes_a_cap_derived_curve_depth() {
        WalletManager.lastKnownSolPrice = 150.0
        val ts = TokenState(mint = "Cap8418AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", symbol = "CAP")
        ts.lastPrice = 0.00001                   // price x 1e9 = $10,000 cap: the old path derived a depth from it
        assertNotNull(LiquidityTruth8037.curveDepthUsd8164(LiquidityTruth8037.curveCapUsd8317(ts.mint, Double.NaN, ts.lastPrice), 150.0))
        assertEquals(0.0, LiquidityTruth8037.hydrate8037(ts), 1e-9)
        assertEquals("unknown depth stays unknown", 0.0, ts.lastLiquidityUsd, 1e-9)
        assertTrue(LiquidityTruth8037.statusLine().contains("curveCapEstimateNotWritten8418="))
    }

    @Test fun clmm_dlmm_and_damm_v2_vaults_are_read_as_depth_only() {
        val d = OnChainDepth8038
        assertEquals(137 to 169, d.vaultOffsets8038(OnChainDepth8038.RAYDIUM_CLMM_8418))
        assertEquals(152 to 184, d.vaultOffsets8038(OnChainDepth8038.METEORA_DLMM_8418))
        assertEquals(232 to 264, d.vaultOffsets8038(OnChainDepth8038.METEORA_DAMM_V2_8418))
        assertNull("DAMM v1 depth is UNKNOWN, not estimated", d.vaultOffsets8038(OnChainDepth8038.METEORA_DAMM_V1_8418))
        val data = ByteArray(300)
        for (i in 152 until 184) data[i] = 1
        for (i in 184 until 216) data[i] = 2
        val (x, y) = d.vaultKeys8038(OnChainDepth8038.METEORA_DLMM_8418, data)!!
        assertFalse(x == y)
        assertNull(d.vaultKeys8038(OnChainDepth8038.METEORA_DLMM_8418, ByteArray(200)))
        assertTrue(d.concentrated8418(OnChainDepth8038.RAYDIUM_CLMM_8418))
        assertTrue(d.concentrated8418(OnChainDepth8038.METEORA_DLMM_8418))
        assertTrue(d.concentrated8418(OnChainDepth8038.METEORA_DAMM_V2_8418))
        assertFalse(d.concentrated8418(OnChainDepth8038.PUMP_AMM))
        assertFalse(d.concentrated8418(OnChainDepth8038.RAYDIUM_CPMM))
        // the DAMM v2 program id is the one damm-v2's lib.rs declares, in both the depth reader and the holder venues
        assertEquals("cpamdpZCGKUy5JxQXB4dcpGPiikHawvSWAd6mEn1sGG", OnChainDepth8038.METEORA_DAMM_V2_8418)
        assertTrue(OnChainHolderConcentration7379.VENUE_PROGRAMS_8418.contains(OnChainDepth8038.METEORA_DAMM_V2_8418))
        assertTrue(OnChainHolderConcentration7379.VENUE_AUTHORITIES_8418.containsAll(listOf(
            "WLHv2UAZm6z4KyaaELi5pjdbJh6RESMva1Rnn8pJVVh", "FhVo3mqL8PW5pH5U2CN4XE33DokiyZnUwuGpH2hmHLuM", "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC")))
        assertEquals("MeteoraDAMMv1", d.venueName8418(OnChainDepth8038.METEORA_DAMM_V1_8418))
        assertEquals("OTHER", d.venueName8418("x"))
        assertFalse(d.depthOnlyFresh8418("NoRead8418", 90_000L))
        assertTrue(d.statusLine().contains("unknownDepth=MeteoraDAMMv1"))
    }
}
