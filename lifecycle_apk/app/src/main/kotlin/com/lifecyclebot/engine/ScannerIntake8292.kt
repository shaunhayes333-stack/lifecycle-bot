package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * V5.0.8292 — scanner / intake source identity.
 *
 * Every scanner callback reached intake as source "SCANNER_DIRECT_<X>" with
 * allSources {X, "SCANNER_DIRECT"}, a watched mint was then re-admitted with
 * allSources {X, "REGISTRY_DUPLICATE_HYDRATE"}, and the chain launch feed tagged
 * "CHAIN_LAUNCH_8179" beside its own source. Those are intake PATHWAYS — how a
 * sighting reached admitProtectedMemeIntake — not discovery sources. They were:
 *  - learned as sources by ScannerSourceBrain (5.0.8286: "SCANNER_DIRECT n=139
 *    WR 16% LEARNED" is the union of every scanner, not a scanner);
 *  - counted as a second, confirming source (allSources.size >= 2), so the brain's
 *    probation shaping for weak single-source feeds could never fire on a scanner
 *    sighting;
 *  - read as a "distinct scanner" by probation, promoting a row on its own echo.
 */
object ScannerSourceTags8292 {
    /** Intake pathways: never a discovery source, never a confirmation. */
    val PATHWAY_TAGS_8292: Set<String> = setOf("SCANNER_DIRECT", "SCANNER_HEAL", "REGISTRY_DUPLICATE_HYDRATE")

    /** Feed wrappers / cohorts that are real provenance but never an independent second source. */
    private val WRAPPER_TAGS_8292: Set<String> = setOf("CHAIN_LAUNCH_8179", "PROBATION")

    /** One feed under two names. */
    private val ALIASES_8292: Map<String, String> = mapOf(
        "PUMP_PORTAL_WS" to "PUMP_PORTAL",
        "PUMPPORTAL" to "PUMP_PORTAL",
        "RAYDIUM_NEW" to "RAYDIUM_NEW_POOL",
    )

    fun isPathwayTag8292(raw: String): Boolean = raw.trim().uppercase() in PATHWAY_TAGS_8292

    /** Pure: the discovery source inside one tag ("SCANNER_DIRECT_DEX_BOOSTED" -> "DEX_BOOSTED"); "" for a pathway. */
    fun canonicalTag8292(raw: String): String {
        val s = raw.trim().uppercase().removePrefix("SCANNER_DIRECT_").removePrefix("SCANNER_HEAL_").trim()
        return if (s.isBlank() || s in PATHWAY_TAGS_8292) "" else s
    }

    /** Pure: the real discovery sources in a compound provenance string, first-seen order. */
    fun realSources8292(provenance: String): List<String> =
        provenance.split(',', ';', '|', '/', '+').map { canonicalTag8292(it) }.filter { it.isNotBlank() }.distinct()

    /** Pure: the canonical compound key ("PUMP_FUN_NEW,SCANNER_DIRECT" -> "PUMP_FUN_NEW"); "" when nothing real remains. */
    fun canonicalCompound8292(provenance: String): String = realSources8292(provenance).joinToString(",")

    /** Pure: independent feeds behind one sighting. Pathways, wrappers and aliases add none. */
    fun independentSourceCount8292(source: String, allSources: Collection<String>): Int =
        (allSources + source).flatMap { realSources8292(it) }
            .filter { it !in WRAPPER_TAGS_8292 }
            .map { ALIASES_8292[it] ?: it }
            .distinct().size

    /**
     * Pure: provenance a probation promotion carries into intake. "PROBATION" stays first (the cell family the
     * forward labels already book the pathway under), then the scanners that actually found the coin — before
     * 8292 a promotion arrived as allSources {"PROBATION"} and its discovering scanner was lost to all learning.
     */
    fun probationPromotionSources8292(originalSource: String, addedBy: String): Set<String> {
        val out = linkedSetOf("PROBATION")
        for (raw in listOf(originalSource, addedBy)) {
            for (part in raw.split(',', '|', '+')) {
                if (part.trim().uppercase().startsWith("PROBATION_DEMOTE:")) continue  // the demote reason, not a source
                val n = GlobalTradeRegistry.normalizeProbationSource7819(part)
                if (n.isNotBlank() && n != "PROBATION") out.add(n)
            }
        }
        return out
    }
}

/**
 * V5.0.8292 — learned source quality from the forward labels (ForwardReturnLabeler7731 SRC|<family> cells: the
 * 5-minute net outcome of every decision on that source's coins, traded or not).
 *
 * Used to RANK and THROTTLE intake, never to skip a safety check:
 *  - TokenMergeQueue orders ready entries by static scanner confidence; a proven-losing source now ranks lower
 *    and a proven-winning one higher, and a mint re-served within 10 minutes ranks after new mints;
 *  - probation TIMEOUT_AUTO_PROMOTE is withheld while the PROBATION pathway's own record is proven losing (the
 *    row stays observable and still promotes on a distinct scanner, price or RC evidence);
 *  - the protected-intake admission multiplier is capped when the primary source's record is proven losing.
 */
object ScannerSourceQuality8292 {
    private const val MIN_N_8292 = 30
    private const val LOSING_MARGIN_PCT_8292 = -5.0
    const val REPEAT_WINDOW_MS_8292 = 10L * 60_000L
    private const val REPEAT_PENALTY_8292 = 12
    const val LOSING_ADMISSION_MULT_8292 = 0.60
    private const val CACHE_MS_8292 = 30_000L

    data class Record8292(val n: Int, val meanPct: Double, val stderrPct: Double, val outlierSuspect: Boolean)

    /** Pure: merge-queue rank points in [-20, +10]; 0 until the record is mature and beyond one standard error. */
    fun rankAdj8292(r: Record8292?): Int {
        if (r == null || r.n < MIN_N_8292 || !r.meanPct.isFinite()) return 0
        val se = if (r.stderrPct.isFinite() && r.stderrPct > 0.0) r.stderrPct else 0.0
        if (r.meanPct > 0.0 && (r.outlierSuspect || r.meanPct - se <= 0.0)) return 0  // an outlier-led win proves nothing
        if (r.meanPct < 0.0 && r.meanPct + se >= 0.0) return 0
        val shrunk = r.meanPct * r.n / (r.n + 60.0)
        return (shrunk * 0.5).roundToInt().coerceIn(-20, 10)
    }

    /** Pure: the record is mature and losing beyond one standard error and the margin. */
    fun provenLosing8292(r: Record8292?): Boolean {
        if (r == null || r.n < MIN_N_8292 || !r.meanPct.isFinite()) return false
        val se = if (r.stderrPct.isFinite() && r.stderrPct > 0.0) r.stderrPct else 0.0
        return r.meanPct + se < LOSING_MARGIN_PCT_8292
    }

    /** Pure: rank delta for one merge entry (source record + re-serve penalty). */
    fun mergeRankDelta8292(sourceAdj: Int, lastEmitAgeMs: Long?): Int =
        sourceAdj - (if (lastEmitAgeMs != null && lastEmitAgeMs in 0L until REPEAT_WINDOW_MS_8292) REPEAT_PENALTY_8292 else 0)

    /** Pure: admission multiplier capped for a proven-losing primary source. */
    fun admissionMult8292(brainMult: Double, primary: Record8292?): Double =
        if (provenLosing8292(primary)) minOf(brainMult, LOSING_ADMISSION_MULT_8292) else brainMult

    private val cache8292 = ConcurrentHashMap<String, Pair<Long, Record8292?>>()

    /** Live: the forward-label record of [source]'s family, cached 30 s. Null when unknown. */
    fun record8292(source: String, nowMs: Long = System.currentTimeMillis()): Record8292? {
        val key = ScannerSourceTags8292.realSources8292(source).firstOrNull() ?: return null
        cache8292[key]?.let { (at, r) -> if (nowMs - at < CACHE_MS_8292) return r }
        val r = try {
            ForwardReturnLabeler7731.sourceStat8292(key)?.let {
                Record8292(it.n60, it.meanNet60Pct, it.stderr60Pct, it.outlierSuspect8185)
            }
        } catch (_: Throwable) { null }
        if (cache8292.size > 512) cache8292.clear()
        cache8292[key] = nowMs to r
        return r
    }

    /** Live: protected-intake admission multiplier — best brain multiplier over real sources, capped by the record. */
    fun admissionMultFor8292(source: String, allSources: Collection<String>): Double {
        val real = (listOf(source) + allSources).flatMap { ScannerSourceTags8292.realSources8292(it) }.distinct()
        val brain = real.map { try { ScannerSourceBrain.intakeMultiplier(it) } catch (_: Throwable) { 1.0 } }.maxOrNull() ?: 1.0
        return admissionMult8292(brain, real.firstOrNull()?.let { record8292(it) })
    }

    /**
     * Live: withhold a probation TIMEOUT promotion while the PROBATION pathway's forward record is proven losing
     * (5.0.8286: PROBATION|* cells -37% to -42% across lanes; the timeout promoted every parked row after 90 s on
     * no evidence at all). Logged once per row.
     */
    fun timeoutPromoteWithheld8292(entry: GlobalTradeRegistry.ProbationEntry): Boolean {
        if (!provenLosing8292(record8292("PROBATION"))) return false
        if (entry.promotionReason != WITHHELD_REASON_8292) {
            entry.promotionReason = WITHHELD_REASON_8292
            try { PipelineHealthCollector.labelInc(WITHHELD_REASON_8292) } catch (_: Throwable) {}
        }
        return true
    }

    const val WITHHELD_REASON_8292 = "PROBATION_TIMEOUT_PROMOTE_WITHHELD_LOSING_PATHWAY_8292"
}

/**
 * V5.0.8292 — the scanner re-served watched mints every poll. isSeen() remembers a sighting for 12 s, after
 * which a mint already on the watchlist was emitted again by every list that still carried it (DEX boosted /
 * trending / gainers), each emit costing two or three intake calls: 5.0.8286 last10m new=216 repeat=1300. The
 * watchlist refreshes its own price; the same source re-serving the same watched mint inside 5 minutes is no
 * new evidence. A different source is.
 */
object ScannerReemitGate8292 {
    const val WATCHED_REEMIT_MS_8292 = 5L * 60_000L
    private val lastEmit = ConcurrentHashMap<String, Long>()

    /** Pure decision. */
    fun suppress8292(lastEmitAtMs: Long?, watched: Boolean, nowMs: Long): Boolean =
        watched && lastEmitAtMs != null && (nowMs - lastEmitAtMs) in 0L until WATCHED_REEMIT_MS_8292

    fun shouldSuppress8292(mint: String, source: String, watched: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean =
        suppress8292(lastEmit["$mint|$source"], watched, nowMs)

    fun note8292(mint: String, source: String, nowMs: Long = System.currentTimeMillis()) {
        lastEmit["$mint|$source"] = nowMs
        if (lastEmit.size > 20_000) lastEmit.entries.removeIf { nowMs - it.value > WATCHED_REEMIT_MS_8292 }
    }
}
