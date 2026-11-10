package com.lifecyclebot.network

import com.lifecyclebot.engine.market.KineticFlow8506
import com.lifecyclebot.engine.truth.TradeTape8100
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal object ValidatorMarket8506 {
    @Volatile var onConfirmed: ((KineticFlow8506.Swap) -> Unit)? = null
    private val pools = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1024
    }
    fun notePool(launch: ChainLaunchFeed8179.Launch8179) {
        if (launch.chainProven && HeliusSolanaScope7819.isSolanaMint7819(launch.pool) && launch.quoteMint == SolanaSwapEvidence8506.WSOL)
            synchronized(pools) { pools[launch.pool] = launch.mint }
    }
    private val ray = ChainLaunchFeed8179.TARGETS_8179.filter { it.venue == ChainLaunchFeed8179.Venue8179.RAYDIUM }.map { it.programId }.toSet()
    fun launches(tx: JSONObject, logs: List<String>, sig: String): List<ChainLaunchFeed8179.Launch8179> {
        val owners = SolanaSwapEvidence8506.ownedLogs(logs)
        val launches = ArrayList<ChainLaunchFeed8179.Launch8179>()
        for (t in ChainLaunchFeed8179.TARGETS_8179) {
            if (t.programId !in owners) continue
            val ix = ChainLaunchFeed8179.creationInstruction8179(t, logs) ?: continue
            ChainLaunchFeed8179.extract8179(t, ix, sig, tx)?.let { launches.add(it) }
        }
        val creates = owners[SolanaSwapEvidence8506.PUMP].orEmpty().any { it == "Program log: Instruction: Create" || it == "Program log: Instruction: CreateV2" }
        if (creates) {
            val initialized = ChainLaunchFeed8179.initializedMints8179(tx)
            for (ix in SolanaSwapEvidence8506.instructions(tx).filter { it.optString("programId") == SolanaSwapEvidence8506.PUMP }) {
                // Confirm the same Pump create instruction names the freshly initialized mint.
                val mint = ix.optJSONArray("accounts")?.optString(0).orEmpty()
                if (mint !in initialized || !HeliusSolanaScope7819.isSolanaMint7819(mint) || !ix.optBoolean("pumpCreate8506")) continue
                launches.add(ChainLaunchFeed8179.Launch8179(mint, ChainLaunchFeed8179.WSOL_8179, "",
                    ChainLaunchFeed8179.Venue8179.OTHER, "PUMP_CREATE", "Create", sig, tx.optLong("slot"),
                    tx.optLong("blockTime") * 1000L, Double.NaN, true, true, sourceOverride = "CHAIN_LAUNCH_PUMPFUN"))
            }
        }
        return launches.distinctBy { it.mint }
    }

    fun enhanced(result: JSONObject, params: JSONObject) {
        val wrap = result.optJSONObject("transaction") ?: return
        val tx = wrap.optJSONObject("transaction") ?: return
        val meta = wrap.optJSONObject("meta") ?: return
        val logs = meta.optJSONArray("logMessages") ?: return
        val slot = result.optLong("slot", params.optJSONObject("context")?.optLong("slot") ?: 0L)
        val json = JSONObject().put("transaction", tx).put("meta", meta).put("slot", slot)
            .put("blockTime", result.optLong("blockTime", wrap.optLong("blockTime")))
        swaps(json, (0 until logs.length()).map { logs.optString(it) }, result.optString("signature"), System.currentTimeMillis(), "helius")
    }

    fun swaps(tx: JSONObject, logs: List<String>, sig: String, now: Long, source: String = "yellowstone", preview: ((String) -> Unit)? = null) {
        if (sig.isBlank() || tx.optJSONObject("meta") == null || !tx.getJSONObject("meta").isNull("err")) return
        val keys = SolanaSwapEvidence8506.objects(tx.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("accountKeys"))
        val payer = keys.firstOrNull()?.optString("pubkey").orEmpty()
        val signers = keys.filter { it.optBoolean("signer") }.map { it.optString("pubkey") }
        val allIx = SolanaSwapEvidence8506.instructions(tx)
        val atMs = tx.optLong("blockTime") * 1000L
        if (preview == null && tx.optLong("slot") > 0 && (atMs == 0L || now - atMs in 0L..15_000L)) for (ix in allIx) {
            val parsed = ix.optJSONObject("parsed") ?: continue
            val i = parsed.optJSONObject("info") ?: continue
            if (ix.optString("programId") == SolanaSwapEvidence8506.SYSTEM && parsed.optString("type") == "transfer" &&
                i.optString("source") in signers && i.optLong("lamports") > 0L && !JitoTipAccounts8506.isTipRecipient8506(i.optString("destination")))
                KineticFlow8506.noteFunding(i.optString("source"), i.optString("destination"), atMs.takeIf { it > 0 } ?: now, tx.optLong("slot"))
        }
        val owned = SolanaSwapEvidence8506.ownedLogs(logs)
        val slot = tx.optLong("slot")
        val prints = ArrayList<Pair<KineticFlow8506.Swap, String>>()
        for (line in owned[SolanaSwapEvidence8506.PUMP].orEmpty()) {
            val trade = ChainLaunchFeed8179.decodeTradeEventLine8179(line) ?: continue
            if (!PumpCurveKeys7269.isPumpMint8658(trade.mint)) continue
            val time = trade.timestampSec * 1000L
            if (time <= 0 || now - time !in 0L..15_000L || trade.sol <= 0 || trade.tokens <= 0) continue
            if (preview == null && trade.virtualSol > 0 && trade.virtualTokens > 0) com.lifecyclebot.engine.truth.CurveReserves7951.noteAccount7951(
                trade.mint, trade.virtualSol, trade.virtualTokens, trade.realSol, time)
            prints.add(KineticFlow8506.Swap(trade.mint, "CURVE", sig, trade.user, trade.isBuy,
                trade.sol, trade.tokens, time, slot, sourceTime = true) to TradeTape8100.VENUE_CURVE_8285)
        }
        for (e in PumpSwapTape8307.swapsInLogs8307(owned[ChainLaunchFeed8179.PUMPSWAP_AMM_8179].orEmpty())) {
            val delta = SolanaSwapEvidence8506.deltas(tx, e.user)
            val matched = delta.filter { (m, q) -> m != ChainLaunchFeed8179.WSOL_8179 && (q > 0) == e.isBuy &&
                abs(abs(q) - e.tokens) <= e.tokens * 0.000001 }.keys.singleOrNull()
            val mint = PumpSwapTape8307.mintForPool8307(e.pool) ?: matched ?: continue
            if (matched != null && matched != mint) continue
            val wrap = delta[ChainLaunchFeed8179.WSOL_8179]
            val vaultMints = SolanaSwapEvidence8506.balances(tx, "postTokenBalances", e.pool).keys
            if (!(wrap != null && (wrap < 0) == e.isBuy) && !(ChainLaunchFeed8179.WSOL_8179 in vaultMints && mint in vaultMints)) continue
            if (preview == null && matched != null) PumpSwapTape8307.notePoolMint8307(e.pool, mint)
            val ms = e.tsSec * 1000L
            if (ms <= 0 || now - ms !in 0L..15_000L) continue
            prints.add(KineticFlow8506.Swap(mint, "PUMPSWAP", sig, e.user, e.isBuy, e.sol, e.tokens, ms, slot, sourceTime = true) to e.pool)
        }
        val raySwap = owned.any { (program, lines) -> program in ray && lines.any { it in raySwapInstructions || isRayV4Swap(program, it) } }
        val top = SolanaSwapEvidence8506.objects(tx.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("instructions"))
        val singleRay = top.count { it.optString("programId") in ray } == 1 &&
            top.all { it.optString("programId") in ray || it.optString("programId") in rayCompanions }
        if (raySwap && singleRay && prints.isEmpty()) SolanaSwapEvidence8506.rayLeg(tx, signers, sig, now)?.let { leg ->
            val present = keys.map { it.optString("pubkey") }
            val pool = synchronized(pools) { present.singleOrNull { pools[it] == leg.mint } }.orEmpty()
            prints.add(leg to pool)
        }
        val unique = prints.map { it.first.mint to it.first.buyer }.distinct().size == 1
        val ixs = if (unique) allIx else emptyList()
        for ((event, pool) in prints) {
            if (event.sol <= 0 || event.qty <= 0 || now - event.atMs !in 0L..15_000L) continue
            if (preview != null) { preview(event.mint); continue }
            if (!event.sourceTime) continue
            val signed = unique && event.buyer in signers && payer in signers
            val s = event.copy(sponsor = if (signed && payer != event.buyer) payer else "",
                pool = pool, tip = if (signed) SolanaSwapEvidence8506.buyerTip(ixs, event.buyer, payer) else 0L)
            if (source != "helius" || s.venue == "RAYDIUM") TradeTape8100.note8285(s.mint, s.sol / s.qty, s.sol, s.qty, s.isBuy, source,
                pool = pool, signature = sig, eventMs = if (s.sourceTime) s.atMs else 0L, recvMs = now)
            KineticFlow8506.observe(s, now)
            if (source == "yellowstone" && s.sourceTime && now - s.atMs in 0L..2_000L) onConfirmed?.invoke(s)
        }
    }
    private val rayCompanions = setOf(SolanaSwapEvidence8506.SYSTEM, "ComputeBudget111111111111111111111111111111", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
        "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    private val raySwapInstructions = setOf("Swap", "SwapBaseInput", "SwapBaseOutput", "SwapV2", "BuyExactIn", "SellExactIn")
        .map { "Program log: Instruction: " + it }.toSet()
    private fun isRayV4Swap(program: String, log: String): Boolean {
        if (program != ChainLaunchFeed8179.RAYDIUM_AMM_V4_8179 || !log.startsWith("Program log: ray_log: ")) return false
        val raw = runCatching { java.util.Base64.getDecoder().decode(log.substringAfter("ray_log: ")) }.getOrNull() ?: return false
        return raw.size == 57 && raw[0].toInt() in 3..4
    }
}
