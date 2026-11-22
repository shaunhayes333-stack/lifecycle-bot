package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8950 §WHAT_A_DECISION_NEEDS_IS_CONFIRMED_ON_CHAIN
 *
 * Owner, 22 Nov 23:57: "that goes for everything data related. if we need it to make a trade decision, hold, exit appraisal,
 * confirm it on chain." Before, a missing or stale input was mostly WAITED for: the cycle's next pass, a free source's
 * grace, a provider's batch. Now the input itself is read from the chain the moment a LIVE decision, hold or exit lacks it:
 *
 *   PRICE_DEPTH  the price and the depth: the pump.fun curve's reserves, or the pool's own vaults
 *                (OnChainDepth8038.needDepth8047: curve account / pool vault balances);
 *   HISTORY      the run-up, the distance from the peak, the drawdown, the 5 m / 1 h change, the buy / sell split, the stage:
 *                the coin's own swap history from its curve or pool (StageRead8950 -> HeliusSwapCandles7819, no grace);
 *   AUTHORITY    mint and freeze authority (AuthorityProofPrefetch8136: the mint account);
 *   HOLDERS      top-holder concentration over wallet owners (OnChainHolderConcentration7379: the largest token accounts);
 *   LP           the pool's LP lock (PoolLock8895: the pool's own accounts, per pool the coin names);
 *   SAFETY       the safety report around them: a current report and a cycle slot (SafetyProofPriority8310).
 *
 * Each (coin, input) is read at most once per [GAP_MS_8950]; the reads run off the caller's thread. Callers:
 * LiveEdgeGate7877 (every LIVE candidate the admission deferred on data, with the wait's own causes), EvidenceWait8948
 * (a proven coin's wait), StageRead8950 (an UNKNOWN stage), MissingMarkExitVeto6835 (an exit appraisal with no mark) and
 * SellReconciler (a held position with no price). Nothing is decided here: the same admission, door, FDG and exits read
 * what the chain answered. [statusLine8950] counts asks by use and input.
 */
object ChainConfirm8950 {
    private const val GAP_MS_8950 = 20_000L
    private const val CAP_8950 = 8_000

    enum class Input8950 { PRICE_DEPTH, HISTORY, AUTHORITY, HOLDERS, LP, SAFETY }

    private val lastAsk = ConcurrentHashMap<String, Long>()   // "mint|input" -> last asked
    private val asks = ConcurrentHashMap<String, AtomicLong>()  // "use:input" -> count
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "chain-confirm-8950").apply { isDaemon = true } }

    /** Pure: the chain inputs that answer the admission / door / exit names in [missing] (missing inputs and defer causes). */
    fun inputsOf8950(missing: Collection<String>): Set<Input8950> {
        val out = LinkedHashSet<Input8950>()
        for (raw in missing) {
            val m = raw.substringBefore(':').trim().uppercase()
            when {
                m.isEmpty() || m == "FALLING_HOUR" || m == "UNNAMED" -> {}
                m in PRICE_8950 || m.startsWith("CRITICAL_LIQ") || m.startsWith("LIQ") -> out += Input8950.PRICE_DEPTH
                m in HISTORY_8950 || m.startsWith("ENTRY_WAIT_FLOW") || m.startsWith("ENTRY_WAIT_STAGE") -> out += Input8950.HISTORY
                m == "MOVE" || m == "MOVE_UNREAD" -> { out += Input8950.PRICE_DEPTH; out += Input8950.HISTORY }
                m.contains("AUTHORITY") || m.contains("FREEZE") -> out += Input8950.AUTHORITY
                m.contains("HOLDER") -> { out += Input8950.HOLDERS; out += Input8950.SAFETY }
                m.startsWith("LP") || m.contains("LP_OWNERSHIP") || m.contains("POOL_PROOF") -> out += Input8950.LP
                m.startsWith("PROOF") || m.startsWith("BUY_DOOR_PROOF") || m.contains("SAFETY") || m.contains("RUGCHECK") || m.contains("CRITICAL_PROOF") ->
                    { out += Input8950.SAFETY; out += Input8950.AUTHORITY; out += Input8950.HOLDERS; out += Input8950.LP }
            }
        }
        return out
    }
    private val PRICE_8950 = setOf("PRICE", "PRICE_STALE", "ECONOMICS", "COST_UNKNOWN", "LIQUIDITY", "DEPTH", "CRITICAL_LIQUIDITY", "MARK", "NO_MARK")
    private val HISTORY_8950 = setOf("SHAPE", "CHG", "BP", "SWAPS", "TAPE", "TAPE8100", "CORE", "BARS", "CANDLES", "HISTORY",
        "MOVE_HISTORY_SPARSE", "STAGE", "STAGE_UNRESOLVED", "FLOW_UNOBSERVED", "VOLACCEL", "OPP", "LAUNCHTAPE")

    /** Pure: may [input] be asked for a coin last asked at [lastMs] (0 = never)? */
    internal fun due8950(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || nowMs - lastMs >= GAP_MS_8950

    private fun live8950(): Boolean = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }

    /** [use] (DECISION / EVIDENCE / STAGE / HOLD / EXIT) of [ts] lacks [missing]: read each from the chain now (LIVE). */
    fun confirm8950(ts: TokenState, missing: Collection<String>, use: String, nowMs: Long = System.currentTimeMillis()) {
        if (ts.mint.length !in 32..44 || !live8950()) return
        for (input in inputsOf8950(missing)) ask8950(ts, input, use, nowMs)
    }

    /** A held position / exit known by its mint only: its token state, then [confirm8950]. */
    fun confirmMint8950(mint: String, missing: Collection<String>, use: String, nowMs: Long = System.currentTimeMillis()) {
        val ts = try { com.lifecyclebot.engine.HandoffRoute8180.tokenFor8180(mint) } catch (_: Throwable) { null } ?: return
        confirm8950(ts, missing, use, nowMs)
    }

    private fun ask8950(ts: TokenState, input: Input8950, use: String, nowMs: Long) {
        val key = "${ts.mint}|${input.name}"
        if (!due8950(lastAsk[key] ?: 0L, nowMs)) return
        if (lastAsk.size > CAP_8950) lastAsk.entries.removeIf { nowMs - it.value > GAP_MS_8950 }
        lastAsk[key] = nowMs
        asks.computeIfAbsent("${use.take(12)}:${input.name}") { AtomicLong(0L) }.incrementAndGet()
        val mint = ts.mint
        try {
            io.execute {
                try {
                    when (input) {
                        Input8950.PRICE_DEPTH -> OnChainDepth8038.needDepth8047(ts, System.currentTimeMillis())
                        Input8950.HISTORY -> StageRead8950.askChain8950(ts, System.currentTimeMillis())
                        Input8950.AUTHORITY -> AuthorityProofPrefetch8136.prefetchIfLive8136(mint)
                        Input8950.HOLDERS -> OnChainHolderConcentration7379.observation8522(mint, maxAgeMs = 60_000L)
                        Input8950.LP -> poolsOf8950(ts).forEach { PoolLock8895.request8895(mint, it) }
                        Input8950.SAFETY -> com.lifecyclebot.engine.SafetyProofPriority8310.noteDeferred8310(mint, "CHAIN_CONFIRM_8950")
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun poolsOf8950(ts: TokenState): List<String> =
        listOf(ts.lastPricePoolAddr, ts.pairAddress, ts.tokenMap.poolAddress, ts.tokenMap.pairAddress)
            .map { it.trim() }.filter { it.length in 32..44 && it != ts.mint }.distinct()

    /** Brief: asks by use and input. */
    fun statusLine8950(): String =
        "chainConfirm8950[" + asks.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } +
            " rule=a LIVE decision, hold or exit missing an input reads it from the chain now (price/depth: curve or pool vaults; " +
            "history/stage: the coin's swap history; authority; holders; LP lock; safety report), each (coin, input) at most every " +
            "${GAP_MS_8950 / 1000}s; nothing decided here]"
}
