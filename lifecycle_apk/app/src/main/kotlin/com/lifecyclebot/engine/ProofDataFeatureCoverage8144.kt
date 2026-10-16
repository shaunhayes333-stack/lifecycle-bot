package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — which decision input was missing, one counter per feature.
 *
 * "DATA_INCOMPLETE" said a candidate lacked evidence but not WHICH evidence, so a dead provider and a coin nobody
 * trades looked the same. Every lane-playbook read now names each missing input separately:
 *
 *   BUY_PRESSURE     no stamped buy/sell split (DexScreener row or executed-trade tape).
 *   PRICE_CHANGE     no stamped 5m / 1h move.
 *   TAPE             no launch tape (crowd, flow, peak) for the mint.
 *   STRUCTURE        no time-ordered price shape (run-up, distance from peak, drawdown).
 *   CONTEXT          no plan setup, launch phase or market-sweep opportunity read.
 *   LIQUIDITY        no observed pool depth.
 *   BIRTH_TIME       chain birth unresolved (age unknown).
 *   SWAP_PROVENANCE  no executed swap records in the window (flow is not evidenced by trades).
 *
 * `missing` counts every evaluation that lacked the input; `mints` counts distinct mints that lacked it in the last
 * [MINT_WINDOW_MS_8144] (one device label per mint-episode, so the label rate tracks coins, not evaluation churn).
 */
object ProofDataFeatureCoverage8144 {

    enum class Feature8144(val short: String) {
        BUY_PRESSURE("bp"), PRICE_CHANGE("chg"), TAPE("tape"), STRUCTURE("structure"),
        CONTEXT("context"), LIQUIDITY("liq"), BIRTH_TIME("birth"), SWAP_PROVENANCE("swaps"),
    }

    private const val MINT_WINDOW_MS_8144 = 10 * 60_000L
    private const val MINT_CAP_8144 = 3_000

    private val evaluations = AtomicLong(0L)
    private val complete = AtomicLong(0L)
    private val missing = Array(Feature8144.values().size) { AtomicLong(0L) }
    private val missingMints = Array(Feature8144.values().size) { ConcurrentHashMap<String, Long>() }

    /** Pure: the features absent from one read, in declaration order. */
    fun missing8144(
        bp: Boolean, chg: Boolean, tape: Boolean, structure: Boolean,
        context: Boolean, liquidity: Boolean, birth: Boolean, swaps: Boolean,
    ): List<Feature8144> {
        val out = ArrayList<Feature8144>(8)
        if (!bp) out += Feature8144.BUY_PRESSURE
        if (!chg) out += Feature8144.PRICE_CHANGE
        if (!tape) out += Feature8144.TAPE
        if (!structure) out += Feature8144.STRUCTURE
        if (!context) out += Feature8144.CONTEXT
        if (!liquidity) out += Feature8144.LIQUIDITY
        if (!birth) out += Feature8144.BIRTH_TIME
        if (!swaps) out += Feature8144.SWAP_PROVENANCE
        return out
    }

    /** One evaluation's coverage for [mint]; returns what was missing. */
    fun observe8144(
        mint: String, nowMs: Long,
        bp: Boolean, chg: Boolean, tape: Boolean, structure: Boolean,
        context: Boolean, liquidity: Boolean, birth: Boolean, swaps: Boolean,
    ): List<Feature8144> {
        val gaps = missing8144(bp, chg, tape, structure, context, liquidity, birth, swaps)
        evaluations.incrementAndGet()
        if (gaps.isEmpty()) { complete.incrementAndGet(); return gaps }
        for (f in gaps) {
            missing[f.ordinal].incrementAndGet()
            if (mint.isBlank()) continue
            val seen = missingMints[f.ordinal]
            val prev = seen.put(mint, nowMs)
            if (prev == null || nowMs - prev >= MINT_WINDOW_MS_8144) {
                try { PipelineHealthCollector.labelInc("FEATURE_MISSING_8144_${f.name}") } catch (_: Throwable) {}
            } else {
                seen[mint] = prev                                    // keep the episode's start
            }
            if (seen.size > MINT_CAP_8144) seen.entries.removeIf { nowMs - it.value >= MINT_WINDOW_MS_8144 }
            if (seen.size > MINT_CAP_8144) seen.clear()
        }
        return gaps
    }

    fun missingCount8144(f: Feature8144): Long = missing[f.ordinal].get()

    /** Distinct mints missing [f] in the last [MINT_WINDOW_MS_8144]. */
    fun missingMints8144(f: Feature8144, nowMs: Long = System.currentTimeMillis()): Int =
        missingMints[f.ordinal].values.count { nowMs - it < MINT_WINDOW_MS_8144 }

    fun statusLine8144(nowMs: Long = System.currentTimeMillis()): String =
        "features8144 evals=${evaluations.get()} complete=${complete.get()} missing[" +
            Feature8144.values().joinToString(" ") { "${it.short}=${missing[it.ordinal].get()}/${missingMints8144(it, nowMs)}" } +
            "] (evaluations/mints10m)"

    internal fun clearForTest8144() {
        evaluations.set(0L); complete.set(0L)
        missing.forEach { it.set(0L) }
        missingMints.forEach { it.clear() }
    }
}
