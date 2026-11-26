package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.network.PumpCurveKeys7269
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.9032 §A_PUMP_FUN_COIN'S_LP_IS_NOT_A_QUESTION — owner, 26 Nov 21:56: "fix the lp rule on pumpfun coins. only mayhem
 * coins have weird liq. open it up please." The 26 Nov 21:03 report (5.0.9030, 13 min): the buy door's unread proofs were
 * LP_OWNERSHIP_PROOF_PENDING_8522 = 2,967 of 4,988 (holders 561, RugCheck 479, liquidity 210); three in four of the coins
 * it decides on are pump.fun coins. 9007 / 9021 proved a pump.fun coin's LP only while its curve had a reading inside six
 * hours that held the exit floor of real SOL, and only before graduation; after graduation it waited on a provider's figure
 * or a pool read, and a provider report of an unlocked pool refused it.
 *
 * The rule now, for the LP question only: a pump.fun coin that is not Mayhem has no liquidity a dev can pull. On its curve
 * the pump.fun program holds it; at graduation the program moves it to the canonical PumpSwap pool and burns the LP. So the
 * LP rule is PROVEN for a coin that (a) ends in "pump", (b) the pump.fun program itself is known to have made — its curve
 * was seen on the program's own stream or read from its account, it is recorded graduated from a proven source, or its
 * bonding-curve account (the program's own address for that mint, which only the program can create) is read here and
 * exists under the program — and (c) is not Mayhem by its chain supply. (b) is what keeps a mint that merely ends in
 * "pump" (four characters anyone can grind) out: it has no such account. A coin not yet known is read at once, one small
 * account read, and the answer resumes a ticket waiting on it.
 *
 * Unchanged: Mayhem coins are still refused by MayhemMode7943; the curve's exit depth, pool depth, holders, RugCheck, the
 * mint and freeze authorities and every other door still decide. The 70 % rule for coins that are not pump.fun coins is
 * unchanged.
 */
object PumpFunCoin9032 {
    const val REASON_9032 = "PUMP_FUN_LP_BY_CONSTRUCTION_9032"
    private const val PUMP_PROGRAM_9032 = com.lifecyclebot.network.SolanaSwapEvidence8506.PUMP
    internal const val ABSENT_RETRY_MS_9032 = 10L * 60_000L
    internal const val ASK_GAP_MS_9032 = 5_000L
    internal const val MAX_PROVEN_9032 = 12_000

    private val proven: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val absent = ConcurrentHashMap<String, Long>()
    private val lastAsk = ConcurrentHashMap<String, Long>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val asked = AtomicLong(0L)
    private val provenByRead = AtomicLong(0L)
    private val absentByRead = AtomicLong(0L)
    private val rpcFailed = AtomicLong(0L)
    private val served = AtomicLong(0L)
    private val mayhem = AtomicLong(0L)

    /** Pure: the LP rule is answered by construction for a "pump" mint the program made that is not Mayhem by chain supply. */
    fun byConstruction9032(pumpSuffix: Boolean, programKnows: Boolean, chainSupply: Double): Boolean =
        pumpSuffix && programKnows && !(chainSupply > 0.0 && com.lifecyclebot.engine.MayhemMode7943.mayhemSupply(chainSupply))

    /** Pure: what a read of the mint's bonding-curve address says — true the program's account, false none / another owner. */
    fun curveAccountIsPumps9032(accountPresent: Boolean, owner: String): Boolean = accountPresent && owner == PUMP_PROGRAM_9032

    private fun remember(m: String) {
        if (proven.size >= MAX_PROVEN_9032) proven.clear()   // re-proven from the same sources on the next look
        proven.add(m)
    }

    /** Is [mint] a coin the pump.fun program made, by the program's own evidence? Never by the suffix alone. */
    fun programKnows9032(mint: String): Boolean {
        val m = mint.trim()
        if (!PumpCurveKeys7269.isPumpMint8658(m)) return false
        if (proven.contains(m)) return true
        val known = try {
            CurveReserves7951.known7951(m) || PumpCurveKeys7269.keyFor(m) != null || PumpCurveKeys7269.isGraduated7392(m)
        } catch (_: Throwable) { false }
        if (known) remember(m)
        return known
    }

    /** The LP rule's answer for [mint]: true = PROVEN by construction. An unknown "pump" mint is read (async) and answers false now. */
    fun lpByConstruction9032(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val m = mint.trim()
        if (!PumpCurveKeys7269.isPumpMint8658(m)) return false
        if (!programKnows9032(m)) { request9032(m, nowMs); return false }
        val supply = try { OnChainSupplyAuthority7075.supplyOf7075(m) } catch (_: Throwable) { 0.0 }
        val ok = byConstruction9032(true, true, supply)
        if (ok) served.incrementAndGet() else mayhem.incrementAndGet()
        return ok
    }

    /** Read [mint]'s bonding-curve address once (single flight, at most every [ASK_GAP_MS_9032]; an absent one again after [ABSENT_RETRY_MS_9032]). */
    private fun request9032(mint: String, nowMs: Long = System.currentTimeMillis()) {
        val m = mint.trim()
        if (!PumpCurveKeys7269.isPumpMint8658(m) || proven.contains(m)) return
        absent[m]?.let { if (nowMs - it in 0L until ABSENT_RETRY_MS_9032) return }
        lastAsk[m]?.let { if (nowMs - it in 0L until ASK_GAP_MS_9032) return }
        val curve = try { PumpCurveKeys7269.canonicalCurveKey7392(m) } catch (_: Throwable) { null } ?: return
        if (!inFlight.add(m)) return
        lastAsk[m] = nowMs
        if (lastAsk.size > 6_000) lastAsk.entries.removeIf { nowMs - it.value > ASK_GAP_MS_9032 }
        if (absent.size > 6_000) absent.entries.removeIf { nowMs - it.value > ABSENT_RETRY_MS_9032 }
        try { exec.execute { try { read(m, curve) } catch (_: Throwable) {} finally { inFlight.remove(m) } } }
        catch (_: Throwable) { inFlight.remove(m) }
    }

    private fun read(m: String, curve: String) {
        asked.incrementAndGet()
        val res = com.lifecyclebot.network.KeylessRpc8359.post8359(http, "getAccountInfo", JSONArray().put(curve).put(
            JSONObject().put("encoding", "base64").put("commitment", "confirmed").put("dataSlice", JSONObject().put("offset", 0).put("length", 0))),
            "9032", maxRungs = 3)
        if (res == null || !res.has("value")) { rpcFailed.incrementAndGet(); return }
        val v = res.optJSONObject("value")
        if (curveAccountIsPumps9032(v != null, v?.optString("owner").orEmpty())) {
            remember(m)
            absent.remove(m)
            provenByRead.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PUMP_FUN_COIN_PROVEN_BY_CURVE_ACCOUNT_9032") } catch (_: Throwable) {}
            // the safety report is re-made with the LP answered, and a ticket held on the proof resumes now
            try { com.lifecyclebot.engine.SafetyRefreshQueue.request(m) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.TicketRetry8327.onProofLanded8330(m) } catch (_: Throwable) {}
        } else {
            absent[m] = System.currentTimeMillis()
            absentByRead.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PUMP_SUFFIX_NOT_A_PUMP_FUN_COIN_9032") } catch (_: Throwable) {}
        }
    }

    private val exec: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            1, 2, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            { r -> Thread(r, "pump-coin-9032").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    }

    fun line9032(): String = "pumpFunLp9032[served=${served.get()} mayhemNotOpened=${mayhem.get()} known=${proven.size} asked=${asked.get()} " +
        "provenByRead=${provenByRead.get()} notPumpFun=${absentByRead.get()} rpcFailed=${rpcFailed.get()} rule=a coin ending in pump that the pump.fun " +
        "program made (its curve seen on the program's stream, read from its account, recorded graduated, or its bonding-curve account read here) " +
        "and that is not Mayhem by chain supply has its LP rule PROVEN; the suffix alone proves nothing]"

    internal fun rememberForTest9032(mint: String) { remember(mint.trim()) }
    internal fun resetForTest9032() {
        proven.clear(); absent.clear(); lastAsk.clear(); inFlight.clear()
        asked.set(0L); provenByRead.set(0L); absentByRead.set(0L); rpcFailed.set(0L); served.set(0L); mayhem.set(0L)
    }
}
