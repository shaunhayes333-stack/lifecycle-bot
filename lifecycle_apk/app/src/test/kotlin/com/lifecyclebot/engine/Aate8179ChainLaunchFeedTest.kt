package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.ChainLaunchFeed8179.Target8179
import io.github.novacrypto.base58.Base58
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

    // ── (7) GeckoTerminal new_pools poller ──

    private fun geckoPool(addr: String, name: String, dex: String, base: String, quote: String, created: String?, reserve: String) = JSONObject()
        .put("id", "solana_$addr").put("type", "pool")
        .put("attributes", JSONObject().put("base_token_price_usd", "0.0000412").put("address", addr).put("name", name)
            .apply { if (created != null) put("pool_created_at", created) }
            .put("fdv_usd", "41200.5").put("market_cap_usd", JSONObject.NULL).put("reserve_in_usd", reserve)
            .put("price_change_percentage", JSONObject().put("m5", "12.4").put("h1", "12.4"))
            .put("transactions", JSONObject().put("m5", JSONObject().put("buys", 14).put("sells", 3)))
            .put("volume_usd", JSONObject().put("m5", "2210.4").put("h1", "2210.4")))
        .put("relationships", JSONObject()
            .put("base_token", JSONObject().put("data", JSONObject().put("id", "solana_$base").put("type", "token")))
            .put("quote_token", JSONObject().put("data", JSONObject().put("id", "solana_$quote").put("type", "token")))
            .put("dex", JSONObject().put("data", JSONObject().put("id", dex).put("type", "dex"))))

    private val MINT_B = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"
    private val MINT_C = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"

    private fun geckoBody(): String = JSONObject().put("data", JSONArray()
        .put(geckoPool("Pool1111111111111111111111111111111111111111".take(43), "NEW / SOL", "meteora-damm-v2", NEW_MINT, f.WSOL_8179, "2026-10-17T09:41:12Z", "18234.77"))
        .put(geckoPool("Pool2222222222222222222222222222222222222222".take(43), "BONK / SOL", "raydium-launchlab", MINT_B, f.WSOL_8179, "2026-10-17T09:40:58Z", "6100.1"))
        .put(geckoPool("Pool3333333333333333333333333333333333333333".take(43), "SOL / USDC", "orca", f.WSOL_8179, f.USDC_8179, "2026-10-17T09:40:40Z", "250000"))
        .put(geckoPool("Pool4444444444444444444444444444444444444444".take(43), "CURVE / SOL", "pump-fun", MINT_C, f.WSOL_8179, "2026-10-17T09:40:30Z", "4100"))
        .put(geckoPool("Pool5555555555555555555555555555555555555555".take(43), "NOTIME / USDC", "orca", MINT_C, f.USDC_8179, null, "900"))
        .put(geckoPool("Pool6666666666666666666666666666666666666666".take(43), "JUP / USDT", "orca", MINT_C, f.USDT_8179, "2026-10-17T09:39:00Z", "5000"))
    ).put("included", JSONArray()).toString()

    @Test fun geckoNewPoolsParseWithQuoteAndCurveExclusions() {
        val skipped = java.util.concurrent.atomic.AtomicLong(0)
        val out = f.parseGeckoNewPools8179(geckoBody(), skipped)
        assertEquals(listOf(NEW_MINT, MINT_B, MINT_C), out.map { it.mint })
        assertEquals(3L, skipped.get())   // SOL-as-base, pump.fun curve (PumpPortal owns it), no creation time
        val damm = out[0]
        assertEquals("GECKO_NEW_POOL_METEORA_DAMM_V2", damm.source)
        assertEquals(ChainLaunchFeed8179.Venue8179.METEORA, damm.venue)
        assertEquals(f.WSOL_8179, damm.quoteMint)
        assertEquals(java.time.Instant.parse("2026-10-17T09:41:12Z").toEpochMilli(), damm.blockTimeMs)
        assertEquals(18234.77, f.quoteDepthUsd8179(damm, 150.0), 1e-9)
        // A bonding curve's reserve_in_usd (unsold inventory) is no sellable depth: unknown.
        assertTrue(out[1].curve)
        assertTrue(f.quoteDepthUsd8179(out[1], 150.0).isNaN())
        assertEquals("GECKO_NEW_POOL_RAYDIUM_LAUNCHLAB", out[1].source)
        assertEquals(ChainLaunchFeed8179.Venue8179.OTHER, out[2].venue)
        assertEquals(f.USDT_8179, out[2].quoteMint)
        assertTrue(f.parseGeckoNewPools8179("{\"errors\":[{\"status\":\"429\"}]}").isEmpty())
        assertTrue(f.parseGeckoNewPools8179("<html>").isEmpty())
        assertEquals("", f.geckoMint8179("eth_0xabc"))
    }

    @Test fun geckoPoolTimeIsNeverAMintBirth() {
        val out = f.parseGeckoNewPools8179(geckoBody())
        assertTrue(out.none { it.mintInitInTx })
        // Recorded as a pool floor: a lower bound of age, never KNOWN; an old token stays old.
        val now = System.currentTimeMillis()
        val r = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(poolFloorMs = now - 60_000L), now)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN, r.kind)
        assertEquals(AdmissionBirth8144.Window8144.UNKNOWN_DEFER, AdmissionBirth8144.window8144(r, 5 * 60_000L))
        val old = AdmissionBirth8144.read8144(AdmissionBirth8144.Witness8144(poolFloorMs = now - 60_000L, oldestSignatureMs = now - 3 * 86_400_000L), now)
        assertEquals(AdmissionBirth8144.Kind8144.KNOWN, old.kind)
        assertEquals(AdmissionBirth8144.Window8144.PAST_WINDOW, AdmissionBirth8144.window8144(old, 5 * 60_000L))
    }

    @Test fun sharedDedupPeekDoesNotConsumeASlot() {
        val s = ChainLaunchFeed8179.BoundedSeen8179(2)
        assertFalse(s.contains8179(NEW_MINT))
        assertEquals(0, s.size8179())
        assertTrue(s.firstSeen8179(NEW_MINT))   // a chain-read launch marks it ...
        assertTrue(s.contains8179(NEW_MINT))    // ... and the Gecko poll then skips the same mint
    }
}
