package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.ChainLaunchFeed8179.Target8179
import io.github.novacrypto.base58.Base58
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8179 — chain-native launch discovery (Raydium / Meteora / PumpSwap pool creation on Helius logsSubscribe) and the
 * pump.fun TradeEvent read from Helius logs. Pure parts only: the log filter attributes each instruction log to its own
 * program, mints come from realistic jsonParsed transactions, SOL/USDC/USDT are never the new token, every bound holds.
 */
class Aate8179ChainLaunchFeedTest {
    private val f = ChainLaunchFeed8179
    private fun t(key: String): Target8179 = f.TARGETS_8179.first { it.key == key }

    private val NEW_MINT = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val LP_MINT = "4xLpMintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".take(43) + "B"
    private val PAYER = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val RAY_AUTH = "5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1"
    private val POOL = "58oQChx4yWmvKdwLLZzBi4ChoCc2fqCUWBkwMihLYQo2"

    private fun invoke(p: String, d: Int) = "Program $p invoke [$d]"
    private fun ok(p: String) = "Program $p success"

    // ── (1) log filter per program ──

    @Test fun everyTargetMatchesItsOwnCreationLog() {
        val cases = mapOf(
            "RAY_V4" to "Program log: initialize2: InitializeInstruction2 { nonce: 254, open_time: 0, init_pc_amount: 79000000000, init_coin_amount: 206900000000000 }",
            "RAY_CPMM" to "Program log: Instruction: Initialize",
            "RAY_LAUNCHLAB" to "Program log: Instruction: InitializeV2",
            "MET_DLMM" to "Program log: Instruction: InitializeCustomizablePermissionlessLbPair2",
            "MET_DAMM_V1" to "Program log: Instruction: InitializePermissionlessConstantProductPoolWithConfig2",
            "MET_DAMM_V2" to "Program log: Instruction: InitializeCustomizablePool",
            "MET_DBC" to "Program log: Instruction: InitializeVirtualPoolWithToken2022",
            "PUMPSWAP" to "Program log: Instruction: CreatePool",
        )
        assertEquals(8, f.TARGETS_8179.size)
        for ((key, line) in cases) {
            val tg = t(key)
            val logs = listOf(
                invoke("ComputeBudget111111111111111111111111111111", 1), ok("ComputeBudget111111111111111111111111111111"),
                invoke(tg.programId, 1), line,
                invoke("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", 2), "Program log: Instruction: InitializeAccount3",
                ok("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"),
                "Program ${tg.programId} consumed 120000 of 400000 compute units", ok(tg.programId),
            )
            assertNotNull(key, f.creationInstruction8179(tg, logs))
        }
        assertEquals("initialize2", f.creationInstruction8179(t("RAY_V4"), listOf(invoke(f.RAYDIUM_AMM_V4_8179, 1), cases.getValue("RAY_V4"))))
    }

    @Test fun swapsAreFilteredOut() {
        for (tg in f.TARGETS_8179) {
            val logs = listOf(invoke(tg.programId, 1), "Program log: Instruction: Swap", "Program log: Instruction: SwapBaseInput",
                "Program log: ray_log: A0Bxxxx", "Program data: vdt/007mYe4AAAA", ok(tg.programId))
            assertNull(tg.key, f.creationInstruction8179(tg, logs))
        }
    }

    @Test fun anotherProgramsInitializeLogIsNeverAttributedToTheTarget() {
        val cpmm = t("RAY_CPMM")
        val other = "Ex1stingAnchorProgram1111111111111111111111"
        // The CPMM program swaps; an unrelated Anchor program in the same transaction logs "Instruction: Initialize".
        val logs = listOf(
            invoke(other, 1), "Program log: Instruction: Initialize", ok(other),
            invoke(cpmm.programId, 1), "Program log: Instruction: SwapBaseInput",
            invoke(other, 2), "Program log: Instruction: Initialize", ok(other),
            ok(cpmm.programId),
        )
        assertNull(f.creationInstruction8179(cpmm, logs))
        // "InitializeV2" is not "Initialize" for a target that lists only the exact name.
        assertNull(f.creationInstruction8179(t("PUMPSWAP"), listOf(invoke(f.PUMPSWAP_AMM_8179, 1), "Program log: Instruction: CreatePoolX")))
    }

    @Test fun pumpSwapCreatePoolViaMigrationCpiMatches() {
        val pump = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
        val logs = listOf(
            invoke(pump, 1), "Program log: Instruction: Migrate",
            invoke(f.PUMPSWAP_AMM_8179, 2), "Program log: Instruction: CreatePool", ok(f.PUMPSWAP_AMM_8179),
            ok(pump),
        )
        assertEquals("CreatePool", f.creationInstruction8179(t("PUMPSWAP"), logs))
    }

    // ── (2) mint extraction from jsonParsed transactions ──

    private fun bal(idx: Int, mint: String, owner: String, ui: String, dec: Int = 9) = JSONObject()
        .put("accountIndex", idx).put("mint", mint).put("owner", owner)
        .put("programId", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
        .put("uiTokenAmount", JSONObject().put("uiAmountString", ui).put("uiAmount", ui.toDouble()).put("decimals", dec).put("amount", "0"))

    private fun initMintIx(mint: String, program: String = "spl-token", type: String = "initializeMint") = JSONObject()
        .put("program", program).put("programId", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA").put("stackHeight", 2)
        .put("parsed", JSONObject().put("type", type).put("info", JSONObject().put("mint", mint).put("decimals", 6).put("mintAuthority", PAYER)))

    private fun tx(instructions: JSONArray, inner: JSONArray, post: JSONArray, err: Any? = null, blockTime: Long = 1_760_000_000L): JSONObject {
        val keys = JSONArray().put(JSONObject().put("pubkey", PAYER).put("signer", true).put("writable", true).put("source", "transaction"))
        return JSONObject()
            .put("slot", 371_234_567L).put("blockTime", blockTime)
            .put("meta", JSONObject().put("err", err ?: JSONObject.NULL).put("fee", 5000)
                .put("preTokenBalances", JSONArray()).put("postTokenBalances", post)
                .put("innerInstructions", JSONArray().put(JSONObject().put("index", 1).put("instructions", inner)))
                .put("logMessages", JSONArray()))
            .put("transaction", JSONObject().put("signatures", JSONArray().put("sig")).put("message",
                JSONObject().put("accountKeys", keys).put("instructions", instructions)))
            .put("version", 0)
    }

    private fun rayV4Tx(err: Any? = null): JSONObject {
        val acc = JSONArray()
        val names = listOf(
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", "11111111111111111111111111111111",
            "SysvarRent111111111111111111111111111111111", POOL, RAY_AUTH, "OpenOrders111111111111111111111111111111111",
            LP_MINT, NEW_MINT, f.WSOL_8179, "CoinVau1t111111111111111111111111111111111", "PcVau1t1111111111111111111111111111111111111".take(43),
            "WithdrawQueue11111111111111111111111111111", "TargetOrders111111111111111111111111111111", "TempLp11111111111111111111111111111111111111".take(43),
            "srmqPvymJeFKQ4zGQed1GFppgkRHL9kaELCbyksJtPX", "Market1111111111111111111111111111111111111", PAYER,
            "UserCoin11111111111111111111111111111111111", "UserPc1111111111111111111111111111111111111", "UserLp1111111111111111111111111111111111111",
        )
        names.forEach { acc.put(it) }
        val ixs = JSONArray()
            .put(JSONObject().put("programId", "ComputeBudget111111111111111111111111111111").put("accounts", JSONArray()).put("data", "3DTZbgwsozUF"))
            .put(JSONObject().put("programId", f.RAYDIUM_AMM_V4_8179).put("accounts", acc).put("data", "4YRBY9vtgBSbRd8HiAVtqEiG"))
        val inner = JSONArray().put(initMintIx(LP_MINT))
        val post = JSONArray()
            .put(bal(10, NEW_MINT, RAY_AUTH, "206900000.0", 6))
            .put(bal(11, f.WSOL_8179, RAY_AUTH, "79.0"))
            .put(bal(20, LP_MINT, PAYER, "4042.6", 9))
            .put(bal(18, NEW_MINT, PAYER, "793100000.0", 6))
        return tx(ixs, inner, post, err)
    }

    @Test fun raydiumV4PoolYieldsTheNewMintNotTheLpMint() {
        val l = f.extract8179(t("RAY_V4"), "initialize2", "sigRay", rayV4Tx())!!
        assertEquals(NEW_MINT, l.mint)
        assertEquals(f.WSOL_8179, l.quoteMint)
        assertEquals(POOL, l.pool)
        assertEquals(79.0, l.quoteReserve, 1e-9)
        assertEquals("CHAIN_LAUNCH_RAYDIUM", l.source)
        assertEquals(1_760_000_000_000L, l.blockTimeMs)
        assertEquals(371_234_567L, l.slot)
        // Only the LP mint was initialised here: the token itself is older than its pool -> no direct birth.
        assertFalse(l.mintInitInTx)
        assertEquals(79.0 * 150.0, f.quoteDepthUsd8179(l, 150.0), 1e-6)
    }

    @Test fun failedTransactionIsNoLaunch() {
        assertNull(f.extract8179(t("RAY_V4"), "initialize2", "sigRay", rayV4Tx(err = JSONObject().put("InstructionError", JSONArray().put(1).put("Custom")))))
    }

    @Test fun dbcPoolCreatesItsMintInTheSameTransaction() {
        val dbc = t("MET_DBC")
        val config = "DbcConfig1111111111111111111111111111111111"
        val auth = "FhVo3mqL8PW5pH5U2CN4XE33DokiyZnUwuGpH2hmHLuM"
        val pool = "DbcPoo1111111111111111111111111111111111111"
        val acc = JSONArray().put(config).put(auth).put(PAYER).put(NEW_MINT).put(f.WSOL_8179).put(pool)
            .put("BaseVau1t11111111111111111111111111111111111".take(43)).put("QuoteVau1t1111111111111111111111111111111111".take(43))
        val ixs = JSONArray().put(JSONObject().put("programId", dbc.programId).put("accounts", acc).put("data", "x"))
        val inner = JSONArray().put(initMintIx(NEW_MINT, "spl-token-2022", "initializeMint2"))
        val post = JSONArray().put(bal(6, NEW_MINT, auth, "1000000000.0", 6)).put(bal(7, f.WSOL_8179, auth, "0", 9))
        val l = f.extract8179(dbc, "InitializeVirtualPoolWithToken2022", "sigDbc", tx(ixs, inner, post))!!
        assertEquals(NEW_MINT, l.mint)
        assertEquals(pool, l.pool)
        assertTrue(l.mintInitInTx)
        assertTrue(l.curve)
        assertEquals("CHAIN_LAUNCH_METEORA", l.source)
        // A bonding curve's real reserve is zero at birth: depth is unknown, never zero-as-measured, never estimated.
        assertTrue(f.quoteDepthUsd8179(l, 150.0).isNaN())
    }

    @Test fun unknownLayoutFallsBackToTheVaultPair() {
        val damm = t("MET_DAMM_V2")
        val auth = "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC"
        val nft = "PositionNft111111111111111111111111111111111".take(43)
        val acc = JSONArray().put(PAYER).put(nft).put("x1").put(PAYER).put("cfg").put(auth).put("pool").put("pos").put(NEW_MINT).put(f.USDC_8179)
        val ixs = JSONArray().put(JSONObject().put("programId", damm.programId).put("accounts", acc).put("data", "x"))
        val post = JSONArray()
            .put(bal(2, nft, PAYER, "1", 0))
            .put(bal(10, NEW_MINT, auth, "5000000.0", 6))
            .put(bal(11, f.USDC_8179, auth, "2500.0", 6))
        val l = f.extract8179(damm, "InitializePoolWithDynamicConfig", "sigDamm", tx(ixs, JSONArray(), post))!!
        assertEquals(NEW_MINT, l.mint)
        assertEquals(f.USDC_8179, l.quoteMint)
        assertEquals("", l.pool)
        assertEquals(2500.0, f.quoteDepthUsd8179(l, 0.0), 1e-9)
    }

    @Test fun layoutPointingAtNonMintsIsRejectedAndTheVaultPairDecides() {
        val ps = t("PUMPSWAP")
        val poolOwner = "PoolPda111111111111111111111111111111111111"
        // Accounts shifted by one (an IDL change): index 3/4 no longer hold mints -> the layout is not trusted.
        val acc = JSONArray().put("pool").put("cfg").put(PAYER).put("notAMint").put(NEW_MINT).put(f.WSOL_8179)
        val ixs = JSONArray().put(JSONObject().put("programId", ps.programId).put("accounts", acc).put("data", "x"))
        val post = JSONArray().put(bal(9, NEW_MINT, poolOwner, "206900000", 6)).put(bal(10, f.WSOL_8179, poolOwner, "84.99"))
        val l = f.extract8179(ps, "CreatePool", "sigPs", tx(ixs, JSONArray(), post))!!
        assertEquals(NEW_MINT, l.mint)
        assertEquals("", l.pool)
        assertEquals(84.99, l.quoteReserve, 1e-9)
    }

    // ── (3) quote-mint exclusion ──

    @Test fun quoteMintsAreNeverTheNewToken() {
        assertNull(f.splitQuote8179(f.WSOL_8179, f.USDC_8179))
        assertNull(f.splitQuote8179(NEW_MINT, LP_MINT))
        assertNull(f.splitQuote8179(NEW_MINT, NEW_MINT))
        assertEquals(NEW_MINT to f.USDT_8179, f.splitQuote8179(f.USDT_8179, NEW_MINT))
        assertEquals(NEW_MINT to f.WSOL_8179, f.splitQuote8179(NEW_MINT, f.WSOL_8179))
        // A SOL-USDC pool creation is no launch.
        val ps = t("PUMPSWAP")
        val acc = JSONArray().put("pool").put("cfg").put(PAYER).put(f.WSOL_8179).put(f.USDC_8179)
        val ixs = JSONArray().put(JSONObject().put("programId", ps.programId).put("accounts", acc).put("data", "x"))
        val post = JSONArray().put(bal(9, f.WSOL_8179, "PoolPda", "10")).put(bal(10, f.USDC_8179, "PoolPda", "1500", 6))
        assertNull(f.extract8179(ps, "CreatePool", "s", tx(ixs, JSONArray(), post)))
        // The fee payer's own two balances are never read as the pool's vaults.
        assertNull(f.vaultPairMint8179(listOf(NEW_MINT to PAYER, f.WSOL_8179 to PAYER), PAYER))
    }

    // ── (4) dedup and rate bounds ──

    @Test fun boundedSeenSetEvictsTheOldest() {
        val s = ChainLaunchFeed8179.BoundedSeen8179(3)
        assertTrue(s.firstSeen8179("a")); assertTrue(s.firstSeen8179("b")); assertTrue(s.firstSeen8179("c"))
        assertFalse(s.firstSeen8179("a"))
        assertTrue(s.firstSeen8179("d"))   // evicts b (a was touched)
        assertEquals(3, s.size8179())
        assertTrue(s.firstSeen8179("b"))
    }

    @Test fun discoveriesPerMinuteAreCapped() {
        val r = ChainLaunchFeed8179.RateWindow8179(ChainLaunchFeed8179.MAX_DISCOVERIES_PER_MIN, 60_000L)
        val t0 = 1_000_000L
        repeat(ChainLaunchFeed8179.MAX_DISCOVERIES_PER_MIN) { assertTrue(r.tryAcquire8179(t0 + it)) }
        assertFalse(r.tryAcquire8179(t0 + 30_000L))
        assertTrue(r.tryAcquire8179(t0 + 60_000L))
    }

    @Test fun firehoseStreamIsParkedWithEscalation() {
        val g = ChainLaunchFeed8179.StreamGovernor8179(ChainLaunchFeed8179.GOVERNOR_MAX_PER_WINDOW, ChainLaunchFeed8179.GOVERNOR_WINDOW_MS)
        val t0 = 5_000_000L
        repeat(ChainLaunchFeed8179.GOVERNOR_MAX_PER_WINDOW) { assertFalse(g.note8179(t0 + it)) }
        assertTrue(g.note8179(t0 + 100))
        assertFalse(g.note8179(t0 + ChainLaunchFeed8179.GOVERNOR_WINDOW_MS + 1))   // a new window starts clean
        assertEquals(30L * 60_000L, f.parkMs8179(1))
        assertEquals(60L * 60_000L, f.parkMs8179(2))
        assertEquals(4L * 60L * 60_000L, f.parkMs8179(4))
        assertEquals(4L * 60L * 60_000L, f.parkMs8179(9))
        assertEquals(48, ChainLaunchFeed8179.MAX_FETCH_QUEUE)
    }

    // ── (5) pump.fun TradeEvent ──

    private fun u64(v: Long) = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun tradeEventBytes(withReal: Boolean, disc0: Int = 189): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(disc0.toByte(), 219.toByte(), 127, 211.toByte(), 78, 230.toByte(), 97, 238.toByte()))
        out.write(Base58.base58Decode(NEW_MINT))
        out.write(u64(1_500_000_000L))           // 1.5 SOL
        out.write(u64(52_000_000_000_000L))      // 52,000,000 tokens (6 dp)
        out.write(byteArrayOf(1))                // buy
        out.write(Base58.base58Decode(PAYER))
        out.write(u64(1_760_000_123L))           // timestamp
        out.write(u64(41_500_000_000L))          // vSol 41.5
        out.write(u64(775_000_000_000_000L))     // vTok 775,000,000
        if (withReal) {
            out.write(u64(11_500_000_000L))      // real SOL 11.5
            out.write(u64(495_000_000_000_000L))
            out.write(ByteArray(32))             // fee recipient (ignored)
        }
        return out.toByteArray()
    }

    @Test fun tradeEventDecodesFromAProgramDataLine() {
        val line = "Program data: " + java.util.Base64.getEncoder().encodeToString(tradeEventBytes(withReal = true))
        val ev = f.decodeTradeEventLine8179(line)!!
        assertEquals(NEW_MINT, ev.mint)
        assertEquals(PAYER, ev.user)
        assertTrue(ev.isBuy)
        assertEquals(1.5, ev.sol, 1e-12)
        assertEquals(52_000_000.0, ev.tokens, 1e-6)
        assertEquals(1_760_000_123L, ev.timestampSec)
        assertEquals(41.5, ev.virtualSol, 1e-12)
        assertEquals(775_000_000.0, ev.virtualTokens, 1e-6)
        assertEquals(11.5, ev.realSol, 1e-12)
    }

    @Test fun olderShortTradeEventHasNoRealReserves() {
        val ev = f.decodeTradeEvent8179(tradeEventBytes(withReal = false))!!
        assertTrue(ev.realSol.isNaN())
        assertEquals(41.5, ev.virtualSol, 1e-12)
    }

    @Test fun otherEventsAndJunkAreNotTrades() {
        assertNull(f.decodeTradeEvent8179(tradeEventBytes(withReal = true, disc0 = 27)))   // CreateEvent's first byte
        assertNull(f.decodeTradeEvent8179(ByteArray(100)))
        assertNull(f.decodeTradeEventLine8179("Program log: Instruction: Buy"))
        assertNull(f.decodeTradeEventLine8179("Program data: !!!not-base64!!!"))
    }

    // ── (6) chain birth ──

    @Test fun mintInitialisedOnChainIsADirectBirthAndAPoolAloneIsNot() {
        val now = System.currentTimeMillis()
        val r = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(chainMintInitMs = now - 40_000L), now)
        assertEquals(AdmissionBirth8144.Kind8144.KNOWN, r.kind)
        assertEquals("CHAIN_MINT_INIT", r.source)
        val floor = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(poolFloorMs = now - 40_000L), now)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, floor.kind)
        val mint = "ChainBirthMint8179xxxxxxxxxxxxxxxxxxxxxxxxx".take(43)
        AdmissionBirth8144.noteChainMintInit8179(mint, now - 30_000L)
        assertEquals(now - 30_000L, AdmissionBirth8144.chainMintInitMs8179(mint))
        AdmissionBirth8144.noteChainMintInit8179(mint, now - 10_000L)   // earliest kept
        assertEquals(now - 30_000L, AdmissionBirth8144.chainMintInitMs8179(mint))
    }

    // ── (7) Codex new-token poller ──

    private val MINT_B = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"
    private val MINT_C = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"
    private val PUMP_MINT = "9BB6NFEcjBCtnNLFko2FqVQBq8HHM13kCyYcdQbgpump"

    private fun codexResult(addr: String, created: Long, liq: String, lp: JSONObject?, exchange: String?) = JSONObject()
        .put("createdAt", created).put("liquidity", liq).put("volume24", "1830.2")
        .put("token", JSONObject().put("address", addr).put("name", "Token").put("symbol", "TKN").apply { if (lp != null) put("launchpad", lp) })
        .put("exchanges", JSONArray().apply { if (exchange != null) put(JSONObject().put("name", exchange)) })

    private fun lp(name: String, completed: Boolean, migrated: Boolean) = JSONObject()
        .put("launchpadName", name).put("completed", completed).put("migrated", migrated).put("graduationPercent", if (completed) 100.0 else 12.5)

    private fun codexBody(): String = JSONObject().put("data", JSONObject().put("filterTokens", JSONObject().put("results", JSONArray()
        .put(codexResult(NEW_MINT, 1_760_000_100L, "15234.5", lp("Meteora DBC", false, false), "Meteora DBC"))
        .put(codexResult(MINT_B, 1_760_000_090L, "48211.0", lp("LaunchLab", true, true), "Raydium CPMM"))
        .put(codexResult(PUMP_MINT, 1_760_000_080L, "5000", lp("Pump.fun", false, false), "Pump.fun"))
        .put(codexResult(f.WSOL_8179, 1_760_000_070L, "1", null, "Raydium"))
        .put(codexResult(MINT_C, 0L, "900", null, "Orca"))
        .put(codexResult("0xdeadbeef", 1_760_000_060L, "1", null, "Uniswap"))
    ))).toString()

    @Test fun codexNewTokensParseWithExclusionsAndVenueLabels() {
        val skipped = java.util.concurrent.atomic.AtomicLong(0)
        val out = f.parseCodexNewTokens8179(codexBody(), skipped)
        assertEquals(listOf(NEW_MINT, MINT_B), out.map { it.mint })
        assertEquals(4L, skipped.get())   // pump.fun (PumpPortal owns it), SOL itself, no creation time, not a Solana address
        val dbc = out[0]
        assertEquals("CODEX_NEW_METEORA_DBC", dbc.source)
        assertEquals(ChainLaunchFeed8179.Venue8179.METEORA, dbc.venue)
        assertTrue(dbc.curve)
        assertTrue(f.quoteDepthUsd8179(dbc, 150.0).isNaN())         // still on its curve: no sellable depth figure
        val grad = out[1]
        assertEquals("CODEX_NEW_RAYDIUM_CPMM", grad.source)          // migrated: named by the pool it trades on
        assertEquals(ChainLaunchFeed8179.Venue8179.RAYDIUM, grad.venue)
        assertEquals(48211.0, f.quoteDepthUsd8179(grad, 150.0), 1e-9)
        assertEquals(1_760_000_090_000L, grad.blockTimeMs)
        // A minimal answer (no launchpad / exchange fields) still parses.
        val minimal = JSONObject().put("data", JSONObject().put("filterTokens", JSONObject().put("results", JSONArray()
            .put(JSONObject().put("createdAt", 1_760_000_100L).put("liquidity", "800").put("token", JSONObject().put("address", MINT_C)))))).toString()
        assertEquals("CODEX_NEW_TOKEN", f.parseCodexNewTokens8179(minimal).single().source)
    }

    @Test fun codexQueryIsConservativeAndARejectionIsDetected() {
        val full = JSONObject(f.codexQuery8179(minimal = false))
        val min = JSONObject(f.codexQuery8179(minimal = true))
        assertEquals(ChainLaunchFeed8179.CODEX_SOLANA_8179, min.getJSONObject("variables").getInt("n"))
        assertTrue(min.getString("query").contains("filterTokens(filters: { network: [\$n] }, rankings: [{ attribute: createdAt, direction: DESC }]"))
        assertFalse(min.getString("query").contains("launchpad"))
        assertTrue(full.getString("query").contains("launchpad { launchpadName completed migrated graduationPercent }"))
        assertTrue(f.codexRejected8179("{\"errors\":[{\"message\":\"Cannot query field \\\"launchpad\\\"\"}],\"data\":null}"))
        assertFalse(f.codexRejected8179(codexBody()))
        assertFalse(f.codexRejected8179("<html>"))
        assertTrue(f.parseCodexNewTokens8179("<html>").isEmpty())
        assertEquals(10, ChainLaunchFeed8179.CODEX_MAX_PER_MIN)
    }

    @Test fun codexCreatedAtIsOnlyABirthHint() {
        val out = f.parseCodexNewTokens8179(codexBody())
        assertTrue(out.all { !it.chainProven && !it.mintInitInTx })
        // Chain reads stay proven; an indexer hint never becomes a witness (only the hydrator's oldest signature does).
        assertTrue(f.extract8179(t("RAY_V4"), "initialize2", "s", rayV4Tx())!!.chainProven)
        val now = System.currentTimeMillis()
        assertEquals(AdmissionBirth8144.Kind8144.UNKNOWN, AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(), now).kind)
    }

    @Test fun sharedDedupPeekDoesNotConsumeASlot() {
        val s = ChainLaunchFeed8179.BoundedSeen8179(2)
        assertFalse(s.contains8179(NEW_MINT))
        assertEquals(0, s.size8179())
        assertTrue(s.firstSeen8179(NEW_MINT))   // a chain-read launch marks it ...
        assertTrue(s.contains8179(NEW_MINT))    // ... and the Codex poll then skips the same mint
    }

    // ── (8) local circuit: one endpoint never blocks its siblings ──

    private fun get(url: String) = okhttp3.Request.Builder().url(url).build()

    private fun chain(req: okhttp3.Request, code: Int, calls: java.util.concurrent.atomic.AtomicInteger): okhttp3.Interceptor.Chain =
        java.lang.reflect.Proxy.newProxyInstance(okhttp3.Interceptor.Chain::class.java.classLoader, arrayOf<Class<*>>(okhttp3.Interceptor.Chain::class.java)) { _, m, args ->
            when (m.name) {
                "request" -> req
                "proceed" -> {
                    calls.incrementAndGet()
                    okhttp3.Response.Builder().request(args!![0] as okhttp3.Request).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("t")
                        .body("[]".toResponseBody(null)).build()
                }
                else -> null
            }
        } as okhttp3.Interceptor.Chain

    @Test fun endpointClassesAreBoundedAndScoped() {
        val h = com.lifecyclebot.network.HostCircuitInterceptor
        assertEquals("tokens/toptraded/24h", h.pathClass8179("/tokens/v2/toptraded/24h"))
        assertEquals("tokens/recent", h.pathClass8179("/tokens/v2/recent"))
        assertEquals("tokens/solana", h.pathClass8179("/tokens/v1/solana/$NEW_MINT,$MINT_B"))
        assertEquals("latest/dex/pairs", h.pathClass8179("/latest/dex/pairs/solana/$POOL"))
        assertEquals("jupiter_tokens@tokens/recent", h.scopedLabel8179("jupiter_tokens", "/tokens/v2/recent"))
        assertEquals("dexscreener@tokens/solana", h.scopedLabel8179("dexscreener", "/tokens/v1/solana/$NEW_MINT"))
        assertEquals("jupiter", h.scopedLabel8179("jupiter", "/swap/v1/quote"))          // execution label untouched
        assertEquals("dexscreener_chart", h.scopedLabel8179("dexscreener_chart", "/tokens/v1/solana/x"))
        assertEquals("birdeye", h.scopedLabel8179("birdeye", "/defi/price"))
        assertTrue(h.scoped8179("jupiter_tokens_keyed@tokens/search"))
        assertFalse(h.scoped8179("jupiter"))
        // The 8125 labels are unchanged; scoping is a second step.
        assertEquals("jupiter_tokens", h.labelFor8125("lite-api.jup.ag", "/tokens/v2/recent", null))
        // Half-open: a cooling endpoint re-tests one minute after its last failure or probe.
        assertTrue(h.halfOpenProbe8179(0L, 1_000L))
        assertFalse(h.halfOpenProbe8179(1_000L, 30_000L))
        assertTrue(h.halfOpenProbe8179(1_000L, 1_000L + h.SCOPED_PROBE_REST_MS_8179))
    }

    @Test fun aRefusedJupiterListNoLongerBlocksTheOtherLists() {
        val h = com.lifecyclebot.network.HostCircuitInterceptor
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val refused = h.intercept(chain(get("https://lite-api.jup.ag/tokens/v2/toporganicscore8179/24h"), 403, calls))
        assertEquals(403, refused.code)
        val again = h.intercept(chain(get("https://lite-api.jup.ag/tokens/v2/toporganicscore8179/24h"), 200, calls))
        assertTrue(h.isSyntheticBlock(again))                  // the refusing list rests (no probe inside a minute)
        val sibling = h.intercept(chain(get("https://lite-api.jup.ag/tokens/v2/recent8179"), 200, calls))
        assertFalse(h.isSyntheticBlock(sibling))               // its siblings still answer
        val dexOther = h.intercept(chain(get("https://api.dexscreener.com/orders8179/v1/solana/$NEW_MINT"), 429, calls))
        assertEquals(429, dexOther.code)
        val dexTokens = h.intercept(chain(get("https://api.dexscreener.com/token-pairs8179/v1/solana/$NEW_MINT"), 200, calls))
        assertFalse(h.isSyntheticBlock(dexTokens))
        assertEquals(4, calls.get())
    }
}
