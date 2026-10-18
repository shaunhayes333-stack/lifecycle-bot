package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8169 §A_LAUNCH_IS_TIMED_FROM_WHAT_WE_ALREADY_HOLD.
 *
 * Device 5.0.8168 (9 min, LIVE): PROJECT_SNIPER was called natively 6,737 times and refused 4,749 of them as
 * NATIVE_TOO_OLD (TOO_OLD_8144: 1,321,306 s > 180 s — a fifteen-day-old token) after the full native bridge had been
 * built for it, its election record opened and its refusal labelled and graded; the launch tape recovered a birth for
 * 8 traded mints and read 59 trade events as RECOVERY_BIRTH_UNKNOWN_8168; the sniper's birth read deferred 961 times as
 * UNKNOWN_DEFER. Causes read in code:
 *  - The sniper's age is a cheap read (CanonicalTokenBirthTime7440 + AdmissionBirth8144), but it ran only INSIDE the
 *    sniper's native assessment, after SpecialistBrainBridge7542 had already put the sniper in scope for every sub-$1M
 *    token. Now [sniperScoped8169] reads the same age first: a token proven past the window leaves the sniper's native
 *    scope (counted), and is handed on by the sniper's own TOO_OLD_8144 hand-off, unchanged.
 *  - The launch tape's birth recovery read only the create frame / pump.fun coin record and the curve key's create
 *    time; the mint's oldest signature (its history read to the end) and its own bonding-curve pair — direct create
 *    witnesses already in memory — were never asked. [launchBirth8169] reads every direct witness and names the source.
 *  - DexScreener's pairCreatedAt from the sweep's enrichment rows was parsed and dropped; a pump mint's bonding-curve
 *    pair IS its create event, and any other pool proves the token existed then ([pairProof8169]).
 *
 * Nothing is estimated: a lower bound stays a lower bound (it can prove a token OLD, never make it young), and an
 * unknown birth still defers. Pure helpers first, then the bounded runtime half.
 */
object LaunchBirth8169 {

    enum class PairProof8169 { BIRTH, FLOOR, NONE }

    data class Birth8169(val birthMs: Long, val source: String)

    /** Pure: pump.fun's vanity suffix on a Solana mint (32..44 chars ending in "pump"). */
    fun pumpMint8169(mint: String): Boolean {
        val m = mint.trim()
        return m.length in 32..44 && m.endsWith("pump")
    }

    /**
     * Pure: what a pair created at [createdMs] proves about [mint]'s birth. The pump.fun bonding-curve pair of a pump mint
     * (DexScreener dexId "pumpfun") is created with the coin: BIRTH. Any other pool (PumpSwap after graduation, Raydium,
     * Meteora ...) proves only that the token existed then: FLOOR. An implausible / future time proves nothing.
     */
    fun pairProof8169(mint: String, dexId: String, createdMs: Long, nowMs: Long): PairProof8169 {
        if (mint.trim().length !in 32..44 || !AdmissionBirth8144.plausible8144(createdMs, nowMs)) return PairProof8169.NONE
        return if (pumpMint8169(mint) && dexId.trim().equals("pumpfun", ignoreCase = true)) PairProof8169.BIRTH
        else PairProof8169.FLOOR
    }

    /** Pure: the earliest plausible direct create witness, named; on a tie the first listed is credited. Null = none. */
    fun earliestDirect8169(witnesses: List<Pair<String, Long?>>, nowMs: Long): Birth8169? {
        var best: Birth8169? = null
        for ((source, ms) in witnesses) {
            if (ms == null || !AdmissionBirth8144.plausible8144(ms, nowMs)) continue
            if (best == null || ms < best.birthMs) best = Birth8169(ms, source)
        }
        return best
    }

    /**
     * Pure: the sniper's launch window for one token. [canonicalAgeMs] is CanonicalTokenBirthTime7440.launchAgeMs7767;
     * without it the AdmissionBirth8144 read decides as in ProjectSniperAI.assessTarget (a known age, or a proven lower
     * bound already past the window; anything else is unknown). Compared in whole seconds, exactly as assessTarget does.
     */
    fun sniperWindow8169(canonicalAgeMs: Long?, read: AdmissionBirth8144.Read8144, windowSecs: Int): AdmissionBirth8144.Window8144 {
        val ageMs = canonicalAgeMs ?: when (AdmissionBirth8144.window8144(read, windowSecs * 1000L)) {
            AdmissionBirth8144.Window8144.UNKNOWN_DEFER -> null
            else -> read.minAgeMs
        } ?: return AdmissionBirth8144.Window8144.UNKNOWN_DEFER
        return if (ageMs / 1000L > windowSecs) AdmissionBirth8144.Window8144.PAST_WINDOW else AdmissionBirth8144.Window8144.IN_WINDOW
    }

    /** Pure: [scope] without PROJECT_SNIPER when the window is proven past; every other lane and verdict unchanged. */
    fun scopeFor8169(scope: Set<String>, window: AdmissionBirth8144.Window8144): Set<String> =
        if (window == AdmissionBirth8144.Window8144.PAST_WINDOW && SNIPER_8169 in scope) scope - SNIPER_8169 else scope

    /** Pure: the key a birthless mint is counted under — its feed (first word of the scanner tag) and pump suffix. */
    fun unknownKey8169(mint: String, source: String): String {
        val feed = source.trim().uppercase().split(' ', ',', '|', ';').firstOrNull { it.isNotBlank() }?.take(24) ?: "NO_SOURCE"
        return "${feed}${if (pumpMint8169(mint)) "/pump" else "/other"}"
    }

    /** Pure: "KEY=n,..." for the [top] most frequent values, or "-". */
    fun histogram8169(values: Collection<String>, top: Int): String =
        values.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(top).joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }

    // ── runtime ──

    private const val SNIPER_8169 = "PROJECT_SNIPER"
    private val scopeCounts8169 = ConcurrentHashMap<String, AtomicLong>()

    private fun countScope8169(key: String) {
        scopeCounts8169.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SNIPER_SCOPE_8169_$key") } catch (_: Throwable) {}
    }

    /**
     * LaunchTape7921.onTrade (a trade for a mint with no launch record): the earliest DIRECT create witness held in memory
     * — create frame, pump.fun coin record, curve-key create time, the mint's own bonding-curve pair, its oldest signature
     * (history read to the end). Pool creation of a later pool and first sight are never a launch.
     */
    /**
     * V5.0.8207 — EdgeOptimizer.filterTrade(isFresh): "fresh" means a DIRECT canonical birth witness ([launchBirth8169]) puts
     * the launch inside LaunchChase7280.FRESH_WINDOW_MS. Watchlist discovery / first sight is never freshness: no witness,
     * not fresh. Pure read of the witnesses already in memory; no provider call.
     */
    fun verifiedFresh8207(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val b = try { launchBirth8169(mint, nowMs) } catch (_: Throwable) { null } ?: return false
        val age = nowMs - b.birthMs
        return age in 0L..LaunchChase7280.FRESH_WINDOW_MS
    }

    fun launchBirth8169(mint: String, nowMs: Long): Birth8169? {
        val m = mint.trim()
        if (m.isBlank()) return null
        return earliestDirect8169(listOf(
            "CREATE_FRAME" to (try { AdmissionBirth8144.createFrameMs8169(m) } catch (_: Throwable) { null }),
            "PUMP_COIN_RECORD" to (try { AdmissionBirth8144.pumpRecordMs8169(m) } catch (_: Throwable) { null }),
            "CURVE_CREATE" to (try { com.lifecyclebot.network.PumpCurveKeys7269.createdAtMs7280(m) } catch (_: Throwable) { null }),
            "CURVE_PAIR" to (try { AdmissionBirth8144.curvePairMs8169(m) } catch (_: Throwable) { null }),
            "OLDEST_SIGNATURE" to (try { AdmissionBirth8144.oldestSignatureMs8169(m) } catch (_: Throwable) { null }),
            "CHAIN_MINT_INIT" to (try { AdmissionBirth8144.chainMintInitMs8179(m) } catch (_: Throwable) { null }),   // V5.0.8179
        ), nowMs)
    }

    /**
     * SpecialistBrainBridge7542.evaluate: [scope] with PROJECT_SNIPER removed when [ts] is proven past the sniper's
     * window — the same age assessTarget reads (canonical, else AdmissionBirth8144), read without its counters — and the
     * sniper's own TOO_OLD hand-off emitted for it. In window or unknown: scope unchanged, assessTarget decides.
     */
    fun sniperScoped8169(ts: TokenState, scope: Set<String>, nowMs: Long = System.currentTimeMillis()): Set<String> {
        if (SNIPER_8169 !in scope) return scope
        return try {
            val windowSecs = com.lifecyclebot.v3.scoring.ProjectSniperAI.SNIPER_WINDOW_SECS_8169
            val canonical = CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs)
            val read = if (canonical == null) AdmissionBirth8144.readFor8144(ts, nowMs)
            else AdmissionBirth8144.Read8144(AdmissionBirth8144.Kind8144.KNOWN, nowMs - canonical, canonical, "CANONICAL")
            val window = sniperWindow8169(canonical, read, windowSecs)
            countScope8169(window.name)
            // V5.0.8190 — an in-window pump launch is put on the trade stream, so its first-minute flow is observed.
            if (window == AdmissionBirth8144.Window8144.IN_WINDOW && pumpMint8169(ts.mint)) try { com.lifecyclebot.network.PumpFunWS.watchLaunch8190(ts.mint) } catch (_: Throwable) {}
            if (window == AdmissionBirth8144.Window8144.PAST_WINDOW) {
                val ageSecs = ((canonical ?: read.minAgeMs) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                try { com.lifecyclebot.v3.scoring.ProjectSniperAI.tooOldHandOff8169(ts, ageSecs) } catch (_: Throwable) {}
            }
            scopeFor8169(scope, window)
        } catch (_: Throwable) { scope }
    }

    /** "sniperScope8169[pastWindowSkipped=.. inWindow=.. unknown=..]" — the native assessments the pre-filter saved. */
    fun sniperScopeLine8169(): String {
        val c = { k: AdmissionBirth8144.Window8144 -> scopeCounts8169[k.name]?.get() ?: 0L }
        return "sniperScope8169[pastWindowSkipped=${c(AdmissionBirth8144.Window8144.PAST_WINDOW)} " +
            "inWindow=${c(AdmissionBirth8144.Window8144.IN_WINDOW)} unknown=${c(AdmissionBirth8144.Window8144.UNKNOWN_DEFER)}]"
    }
}
