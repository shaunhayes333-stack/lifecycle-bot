package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max

/**
 * ScannerSourceBrain — V5.0.4097 (per-source intake AGI brain)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator P0 mandate: "find any fix any lane starvation. if the scanners
 * need brains give them one and wire them into the stack."
 *
 * What this brain does:
 *   • Tracks per-source outcome (win count, loss count, sum-of-pnl) using the
 *     same Bernoulli/logistic update vocabulary as UnifiedPolicyHead +
 *     UnifiedExitPolicyHead, so the AGI swarm shares one mental model of
 *     "authority graduation".
 *   • Outputs an `intakeMultiplier(source)` in [0.40, 1.80] that the scanner
 *     loop uses to ORDER and WEIGHT scan calls. Sources that produce winners
 *     get prioritised; sources that bleed get demoted (never disabled — soft
 *     shape only, doctrine-compliant).
 *   • Detects lane starvation (BLUECHIP / DIP_HUNTER / QUALITY ≤ 5 evals in
 *     the last cycle window) and applies a temporary boost to established-
 *     asset-feeding sources for the next cycle.
 *
 * Authority tiers mirror UnifiedPolicyHead:
 *   BOOTSTRAP      < 20 samples → neutral 1.0
 *   ADVISORY       ≥ 20         → narrow shape [0.80, 1.20]
 *   LEARNED        ≥ 60         → wider     [0.60, 1.40]
 *   AUTHORITATIVE  ≥ 150        → widest    [0.40, 1.80]
 *
 * NOT a hard gate — never zeroes a source, never blacklists. The scanner
 * still runs every source on every cycle; the brain only re-orders them
 * and adjusts how aggressively their results boost the candidate score.
 *
 * Persisted via SharedPreferences ("scanner_source_brain"). Fail-open.
 */
object ScannerSourceBrain {

    private const val TAG = "ScannerSourceBrain"
    private const val PREFS = "scanner_source_brain"
    private const val KEY = "state_v1"
    private const val DECISIONS_KEY_8372 = "decision_episodes_8372"

    private const val BOOT_THRESHOLD = 20L
    private const val LEARNED_THRESHOLD = 60L
    private const val AUTH_THRESHOLD = 150L

    enum class AuthorityTier { BOOTSTRAP, ADVISORY, LEARNED, AUTHORITATIVE }

    private data class SourceStats(
        var wins: Long = 0L,
        var losses: Long = 0L,
        var pnlSumPct: Double = 0.0,
        var lastUpdated: Long = 0L,
    ) {
        fun samples(): Long = wins + losses
        fun winRate(): Double {
            val n = samples()
            return if (n > 0) wins.toDouble() / n else 0.5
        }
        fun avgPnlPct(): Double {
            val n = samples()
            return if (n > 0) pnlSumPct / n else 0.0
        }
    }

    private val stats = ConcurrentHashMap<String, SourceStats>()
    // Historical stats mixed Executor closes with pre-8372 refused labels. Preserve them, but never pool their
    // sample counts with the new once-per-token observation cohort or call a shadow return realised money.
    private val decisionEpisodes8372 = ScannerSourceEpisodes8372()
    @Volatile private var committedDecisions8372 = ScannerSourceEpisodes8372()
    private val decisionAdds8372 = AtomicLong(0)
    private val decisionRevisions8372 = AtomicLong(0)
    private val decisionNoops8372 = AtomicLong(0)
    private val saveQueued8372 = AtomicBoolean(false)
    private val saveOk8372 = AtomicLong(0)
    private val saveFailed8372 = AtomicLong(0)
    private val saveExecutor8372 = Executors.newSingleThreadExecutor { work ->
        Thread(work, "scanner-source-persist").apply { isDaemon = true }
    }
    private val starvationBoostUntilMs = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var ctx: Context? = null
    @Volatile private var loaded = false

    fun init(context: Context) {
        loaded = false
        ctx = context.applicationContext
        committedDecisions8372 = ScannerSourceEpisodes8372()
        load()
        loaded = true
        ErrorLogger.info(TAG, "🧠 brain init: ${stats.size} sources tracked")
    }

    /**
     * V5.0.4597 — INTAKE BLACKOUT for provably-toxic sources (operator P0).
     * `intakeMultiplier` only SHRINKS size, doesn't stop ingestion. Field data
     * showed PUMP_FUN_NEW,SCANNER_DIRECT at n=282 WR=19% mult=0.63 tier=
     * AUTHORITATIVE still feeding 130 tokens per session — the brain KNEW it
     * was bad but had no way to stop the flood. This returns true when the
     * source is:
     *   • Mature (n >= 30) AND WR < 20% → skip 3-in-4 tokens
     *   • Very mature (n >= 60) AND WR < 15% → skip 9-in-10 tokens
     *   • Extremely toxic (n >= 100) AND WR < 12% → skip 24-in-25 tokens
     * A probe still gets through so the source can self-heal on regime flip.
     * Doctrine-safe: soft-shape (probabilistic skip, not veto), fail-open.
     */
    private val intakeSkipCounter = java.util.concurrent.atomic.AtomicLong(0L)
    fun shouldSkipIntake(source: String): Boolean {
        return try {
            val key = normalise(source)
            // V5.0.7403 — do not amputate the leading sensor because the OLD
            // decision stack traded its candidates badly. Pump create/new-token
            // feeds are the only sources that can supply birth-time/ignition
            // evidence before DEX/trending confirmation exists. Their learned
            // history may still damp priority/size via intakeMultiplier(), but
            // it may not delete 75-96% of the observations at intake.
            val leadingBirthSource7403 =
                key.contains("PUMP_PORTAL_WS") ||
                key == "PUMP_PORTAL" ||
                key.contains("PUMP_FUN_NEW")
            if (leadingBirthSource7403) {
                try { PipelineHealthCollector.labelInc("LEADING_SOURCE_BLACKOUT_BYPASSED_7403") } catch (_: Throwable) {}
                return false
            }
            val s = read8372(key) ?: return false
            val n = s.n
            if (n < 30) return false
            val wr = s.wr
            // V5.0.6284 — NET-EV OVERRIDE. Previously blackout fired on WR
            // alone, tossing 24/25 tokens from sources like PUMP_FUN_NEW
            // (WR=14% n=309) even when the rare winners paid for the crowd
            // of small losses. Now: if the source's avg PnL is net-positive,
            // do NOT blackout — the memecoin distribution needs those wins.
            val avgPnl = s.mean
            if (avgPnl > 0.0) {
                try { PipelineHealthCollector.labelInc("SCANNER_INTAKE_NET_EV_POSITIVE_ALLOW_6284_${key.take(30)}") } catch (_: Throwable) {}
                return false
            }
            val skipEvery = when {
                n >= 100 && wr < 0.12 -> 25L  // extremely toxic: 24/25 tokens skipped
                n >= 60  && wr < 0.15 -> 10L  // very mature toxic: 9/10 skipped
                n >= 30  && wr < 0.20 -> 4L   // mature bleeder: 3/4 skipped
                else                  -> return false
            }
            val n2 = intakeSkipCounter.incrementAndGet()
            val skip = (n2 % skipEvery) != 0L  // let 1-in-N through as probe
            if (skip) {
                try { PipelineHealthCollector.labelInc("SCANNER_INTAKE_BLACKOUT_4597_${key.take(30)}") } catch (_: Throwable) {}
            }
            skip
        } catch (_: Throwable) { false }
    }

    /**
     * V5.0.6856 §THE_BRAIN_LEARNED_INTO_KEYS_NOBODY_READ — the write key and the
     * scanner's read key were different strings, so the two never met.
     *
     * Executor:21209/24149 record with `source = ts.source`, and ts.source is a
     * COMPOUND provenance label — this file's own V5.0.4597 comment quotes a real
     * one: "PUMP_FUN_NEW,SCANNER_DIRECT at n=282 WR=19%". But
     * SolanaMarketScanner:3940 reads `intakeMultiplier(token.source.name)`, which
     * is the bare TokenSource enum name "PUMP_FUN_NEW", and ScannerDiversityBandit
     * :32 reads a bare family name too. `normalise` only trims and uppercases — it
     * never splits — so those lookups missed every time, fell through to
     * BOOTSTRAP, and returned a flat 1.0 forever.
     *
     * The consequence was one-sided and easy to miss: Executor:10810 and
     * LiveStylePivotRouter:185 read with the same compound ts.source and DID
     * resolve, so the brain visibly shaped entry SIZE while the scanner-side
     * ordering and intake priority it was actually built for — "the scanner loop
     * uses it to ORDER and WEIGHT scan calls", per this file's header — silently
     * received nothing.
     *
     * Fix: record the outcome under the compound key AND under each component, so
     * a lookup by either form resolves. A component key's stats are then the union
     * over every compound source containing it, which is exactly what a scanner
     * asking "how is PUMP_FUN_NEW doing?" means.
     */
    private fun componentKeys6856(source: String): List<String> {
        // V5.0.8292 — pathway tags (SCANNER_DIRECT, REGISTRY_DUPLICATE_HYDRATE, ...) are not sources.
        val full = normalise(source)
        if (full.isBlank()) return emptyList()
        val parts = full.split(',', ';', '|', '/', '+')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != full }
            .distinct()
        return listOf(full) + parts
    }

    /** Called by Executor at journal close. Mirrors ScannerLearning.recordTrade. */
    fun recordOutcome(source: String, pnlPct: Double) {
        if (!loaded || !pnlPct.isFinite()) return
        // A blank provenance is not a source. Recording it would found a cohort
        // named after a placeholder, which is the exact class of defect V5.0.6851
        // removed from the terminal ledgers — count it and drop it instead.
        val keys = componentKeys6856(source)
        if (keys.isEmpty()) {
            try { PipelineHealthCollector.labelInc("SCANNER_SOURCE_BRAIN_UNATTRIBUTED_OUTCOME_6856") } catch (_: Throwable) {}
            return
        }
        val now = System.currentTimeMillis()
        keys.forEach { key ->
            val s = stats.getOrPut(key) { SourceStats() }
            synchronized(s) {
                if (pnlPct > 0.0) s.wins++ else s.losses++
                s.pnlSumPct += pnlPct
                s.lastUpdated = now
            }
        }
        // Keep the legacy/Executor cohort's existing checkpoint cadence. New Cortex observations use the separate
        // episode API below, not an extra sample in this historically mixed book.
        requestSave8372(now)
    }

    /**
     * All valid YES/NO observations teach discovery once per token episode, independent of FDG admission. The
     * caller supplies a lane-independent tokenEpisodeId and an exact lane/decisionId; the first contributing lane
     * owns corrections. Mode is the ORIGINAL decision context, not the eventual shadow execution kind.
     */
    fun recordDecisionOutcome8372(source: String, tokenEpisodeId: String, decisionId: String, mode: String,
                                  pnlPct: Double, decisionAtMs: Long): Boolean {
        if (!loaded) return false
        val row = ScannerSourceEpisodes8372.Outcome(tokenEpisodeId, decisionId, mode.trim().uppercase(),
            componentKeys6856(source), pnlPct, decisionAtMs)
        val added = decisionEpisodes8372.record(row)
        if (added) { decisionAdds8372.incrementAndGet(); requestSave8372(System.currentTimeMillis()) }
        else decisionNoops8372.incrementAndGet()
        return added
    }

    /** A later witnessed label or exact shadow terminal replaces its owner's earlier contribution (N does not rise). */
    fun reviseDecisionOutcome8372(source: String, tokenEpisodeId: String, decisionId: String, mode: String,
                                  pnlPct: Double, decisionAtMs: Long): Boolean {
        if (!loaded) return false
        val row = ScannerSourceEpisodes8372.Outcome(tokenEpisodeId, decisionId, mode.trim().uppercase(),
            componentKeys6856(source), pnlPct, decisionAtMs)
        val revised = decisionEpisodes8372.revise(row)
        if (revised) { decisionRevisions8372.incrementAndGet(); requestSave8372(System.currentTimeMillis()) }
        else decisionNoops8372.incrementAndGet()
        return revised
    }

    /**
     * ACK only after the source book's exact contribution (or legitimate dedupe) survived a successful preferences
     * commit. Call after record/revise; this method never creates a sample. A false return schedules a coalesced
     * background checkpoint so a shadow delivery can retain/retry its receipt without blocking a trader loop on disk.
     */
    fun decisionDeliveryAcknowledged8372(source: String, tokenEpisodeId: String, decisionId: String, mode: String,
                                         pnlPct: Double, decisionAtMs: Long): Boolean {
        if (!loaded || ctx == null) return false
        val row = ScannerSourceEpisodes8372.Outcome(tokenEpisodeId, decisionId, mode.trim().uppercase(),
            componentKeys6856(source), pnlPct, decisionAtMs)
        if (committedDecisions8372.acknowledges(row)) return true
        save()
        return false
    }

    private fun requestSave8372(now: Long) {
        if (now - lastSaveMs7913 >= 30_000L) { lastSaveMs7913 = now; save() }
    }

    @Volatile private var lastSaveMs7913 = 0L

    private fun tier8372(n: Long): AuthorityTier = when {
            n >= AUTH_THRESHOLD     -> AuthorityTier.AUTHORITATIVE
            n >= LEARNED_THRESHOLD  -> AuthorityTier.LEARNED
            n >= BOOT_THRESHOLD     -> AuthorityTier.ADVISORY
            else                    -> AuthorityTier.BOOTSTRAP
        }

    private data class Read8372(val n: Long, val wr: Double, val mean: Double, val centred: Double, val evidence: String)

    private fun read8372(key: String): Read8372? {
        val decision = decisionEpisodes8372.snapshot(key)
        // Existing n>=20 maturity, plus an uncertainty check. A handful of outliers must not seize source authority.
        if (decision != null && decision.supported(BOOT_THRESHOLD)) {
            return Read8372(decision.samples, decision.winRate, decision.mean, decision.netAwareCentred(), "DECISION_EPISODES")
        }
        val legacy = stats[key] ?: return null
        return synchronized(legacy) {
            Read8372(legacy.samples(), legacy.winRate(), legacy.avgPnlPct(), (legacy.winRate() - 0.5) * 2.0, "LEGACY_MIXED")
        }
    }

    fun authority(source: String): AuthorityTier = tier8372(read8372(normalise(source))?.n ?: 0L)

    data class SourceSnapshot7658(
        val source: String,
        val samples: Long,
        val winRate: Double,
        val avgPnlPct: Double,
        val authority: AuthorityTier,
        val evidence8372: String = "LEGACY_MIXED",
        val decisionSamples8372: Long = 0L,
    )

    /**
     * V5.0.7658 - read-only source cohort state for Super Intelligence.
     * Does not reorder scanners, apply starvation boosts, skip intake or mutate stats.
     */
    fun sourceSnapshot7658(source: String): SourceSnapshot7658? {
        val key = normalise(source)
        if (key.isBlank()) return null
        val s = read8372(key) ?: return null
        return SourceSnapshot7658(
                source = key,
                samples = s.n,
                winRate = s.wr,
                avgPnlPct = s.mean,
                authority = tier8372(s.n),
                evidence8372 = s.evidence,
                decisionSamples8372 = decisionEpisodes8372.snapshot(key)?.samples ?: 0L,
            )
    }

    /** Multiplier in [0.40, 1.80] depending on tier + winRate.
     *  Caller multiplies its baseline priority by this. */
    fun intakeMultiplier(source: String): Double {
        val key = normalise(source)
        val s = read8372(key) ?: return checkStarvationBoost(key, 1.0)
        val n = s.n
        if (n < BOOT_THRESHOLD) return checkStarvationBoost(key, 1.0)
        // Smooth s-curve around 0.5 (random) ‒ wr=0.7 yields ~+0.3 above 1.0
        val centred = s.centred  // [-1, +1]; decision cohorts preserve the supported net-EV sign
        val (floor, cap) = when (authority(key)) {
            AuthorityTier.AUTHORITATIVE -> 0.40 to 1.80
            AuthorityTier.LEARNED       -> 0.60 to 1.40
            AuthorityTier.ADVISORY      -> 0.40 to 1.20  // V5.0.4123: was 0.80 floor, too generous for 4% WR sources
            AuthorityTier.BOOTSTRAP     -> 1.00 to 1.00
        }
        val raw = 1.0 + centred * 0.6
        return checkStarvationBoost(key, raw.coerceIn(floor, cap))
    }

    /**
     * V5.0.6856 §THE_BANDIT_ASKED_ABOUT_SOURCES_THAT_DO_NOT_EXIST — family-level
     * aggregate. ScannerDiversityBandit mapped each family to a single
     * "representative source" and looked that one string up, but six of its eight
     * names — DEXSCREENER, COINMARKETCAP, SCANNER_TRENDING, PUMP_PORTAL, BIRDEYE,
     * OTHER — are not TokenSource values and never appear as a recorded key. Only
     * COINGECKO_TRENDING and RAYDIUM_NEW_POOL were real. So six families always
     * resolved to a missing key, returned a flat 1.0, and the "bandit" degenerated
     * into a fixed sort that could never reorder anything no matter what the
     * scanners produced.
     *
     * A family is not one source anyway: DEX covers DEX_TRENDING, DEX_GAINERS and
     * DEX_BOOSTED; BIRDEYE covers four separate feeds. This pools every recorded
     * key matching any of the given tokens and runs the same tier/s-curve maths
     * over the union, so a family's ordering reflects all of its feeds.
     */
    fun familyIntakeMultiplier6856(vararg tokens: String): Double {
        return try {
            val needles = tokens.map { it.trim().uppercase() }.filter { it.isNotBlank() }
            if (needles.isEmpty()) return 1.0
            val decision8372 = decisionEpisodes8372.familySnapshot(needles)
            if (decision8372 != null && decision8372.supported(BOOT_THRESHOLD)) {
                val (floor, cap) = when (tier8372(decision8372.samples)) {
                    AuthorityTier.AUTHORITATIVE -> 0.40 to 1.80
                    AuthorityTier.LEARNED -> 0.60 to 1.40
                    AuthorityTier.ADVISORY -> 0.40 to 1.20
                    AuthorityTier.BOOTSTRAP -> 1.00 to 1.00
                }
                return (1.0 + decision8372.netAwareCentred() * 0.6).coerceIn(floor, cap)
            }
            var wins = 0L
            var losses = 0L
            stats.forEach { (key, s) ->
                if (needles.any { key.contains(it) }) {
                    synchronized(s) { wins += s.wins; losses += s.losses }
                }
            }
            val n = wins + losses
            if (n < BOOT_THRESHOLD) return 1.0
            val wr = wins.toDouble() / n
            val centred = (wr - 0.5) * 2.0
            val (floor, cap) = when {
                n >= AUTH_THRESHOLD    -> 0.40 to 1.80
                n >= LEARNED_THRESHOLD -> 0.60 to 1.40
                else                   -> 0.40 to 1.20
            }
            (1.0 + centred * 0.6).coerceIn(floor, cap)
        } catch (_: Throwable) { 1.0 }
    }

    /**
     * V5.0.6292 — SCANNER × LANE COHESION MULTIPLIER.
     *
     * Every scanner source has a natural lane affinity — PUMP_FUN_NEW ships
     * fresh moonshots, COINGECKO_ESTABLISHED ships bluechips, DEX_TRENDING
     * ships quality volume, MEME_REGISTRY_RESTORE ships recovered dust.
     * When the source-picked lane matches the source's natural home, we
     * amplify. When it's mismatched (e.g. a bluechip source being sold as
     * a moonshot, or a fresh degen mint being routed to a treasury bag),
     * we dampen. Fully bounded in [0.75, 1.30].
     *
     * Returns 1.0 (neutral) when either arg is blank or unknown mapping.
     * Fail-open — no vetoes, purely size shape.
     */
    fun laneSourceCohesion(source: String, lane: String): Double {
        if (source.isBlank() || lane.isBlank()) return 1.0
        val src = source.uppercase()
        val ln = lane.uppercase()
        // Strong affinity — the source ships this lane's natural token
        val strongMatch = when {
            // V5.0.7297 — a lane-hunted row is that lane's natural token by construction.
            com.lifecyclebot.engine.market.LaneHunter7297.laneFromSource(src) == ln -> true
            (src.contains("PUMP_FUN") || src.contains("PUMP_PORTAL")) && ln == "MOONSHOT" -> true
            (src.contains("PUMP_FUN") || src.contains("PUMP_PORTAL")) && ln == "SHITCOIN" -> true
            src.contains("RAYDIUM_NEW_POOL") && ln == "STANDARD" -> true
            src.contains("RAYDIUM_NEW_POOL") && ln == "MOONSHOT" -> true
            src.contains("DEX_TRENDING") && ln == "QUALITY" -> true
            src.contains("DEX_TRENDING") && ln == "STANDARD" -> true
            src.contains("DEX_BOOSTED") && ln == "CASHGEN" -> true
            src.contains("DEX_BOOSTED") && ln == "EXPRESS" -> true
            src.contains("COINGECKO_ESTABLISHED") && ln == "BLUECHIP" -> true
            src.contains("COINGECKO_ESTABLISHED") && ln == "TREASURY" -> true
            src.contains("BLUECHIP_WATCHLIST") && ln == "BLUECHIP" -> true
            src.contains("DIP_HUNTER") && ln == "DIP_HUNTER" -> true
            src.contains("PROJECT_SNIPER") && ln == "PROJECT_SNIPER" -> true
            src.contains("SCANNER_DIRECT") && (ln == "STANDARD" || ln == "MOONSHOT") -> true
            else -> false
        }
        // Hard mismatch — source ships the OPPOSITE risk profile
        val mismatch = when {
            src.contains("COINGECKO_ESTABLISHED") && (ln == "SHITCOIN" || ln == "MOONSHOT") -> true
            (src.contains("PUMP_FUN") || src.contains("PUMP_PORTAL")) && (ln == "BLUECHIP" || ln == "TREASURY") -> true
            src.contains("MEME_REGISTRY_RESTORE") && (ln == "BLUECHIP" || ln == "TREASURY") -> true
            src.contains("BLUECHIP_WATCHLIST") && (ln == "SHITCOIN" || ln == "MOONSHOT") -> true
            else -> false
        }
        // Weak affinity — source contains one word of the lane's family
        val weakMatch = when {
            !strongMatch && !mismatch && src.contains("MEME") && (ln == "MOONSHOT" || ln == "SHITCOIN") -> true
            !strongMatch && !mismatch && src.contains("HOT_CONVICTION") && (ln == "STANDARD" || ln == "MOONSHOT") -> true
            !strongMatch && !mismatch && src.contains("RESTORED") && (ln == "STANDARD" || ln == "SHITCOIN") -> true
            else -> false
        }
        return when {
            mismatch -> 0.75
            strongMatch -> 1.30
            weakMatch -> 1.10
            else -> 1.00
        }
    }

    /** When BLUECHIP/DIP_HUNTER/QUALITY are starving, pin established-asset
     *  feeders at a 1.5x boost for the next 3 cycles regardless of brain tier. */
    fun signalStarvation(established: Boolean) {
        if (!established) return
        val now = System.currentTimeMillis()
        // Boost duration: 60s window
        val boostUntil = now + 60_000L
        ESTABLISHED_FEEDERS.forEach { src ->
            val key = normalise(src)
            starvationBoostUntilMs.getOrPut(key) { AtomicLong(0L) }.set(boostUntil)
        }
        try {
            ErrorLogger.warn(
                TAG,
                "⚠ STARVATION DETECTED: boosting ${ESTABLISHED_FEEDERS.size} established-asset sources for 60s"
            )
            // Narrate into sentience family so the personality reflects on it
            SentienceOrchestrator.noteRuntimeEvent(
                "SCANNER_STARVATION_BOOST",
                "established_feeders_boosted=${ESTABLISHED_FEEDERS.size} window_ms=60000",
                "WARN"
            )
        } catch (_: Throwable) {}
    }

    private fun checkStarvationBoost(key: String, baseline: Double): Double {
        val until = starvationBoostUntilMs[key]?.get() ?: 0L
        return if (until > System.currentTimeMillis()) {
            (baseline * 1.5).coerceIn(0.4, 1.8)
        } else baseline
    }

    /** Brain telemetry summary for the unified report. */
    fun summary(): String {
        val sources8372 = stats.keys + decisionEpisodes8372.sources()
        if (sources8372.isEmpty()) return "ScannerSourceBrain: bootstrap (no samples yet)"
        val sb = StringBuilder("ScannerSourceBrain (V5.0.8372): ${sources8372.size} sources\n")
        sb.append("  decisions8372[episodes=${decisionEpisodes8372.size()} added=${decisionAdds8372.get()} revised=${decisionRevisions8372.get()} duplicateOrInvalid=${decisionNoops8372.get()} persistOk=${saveOk8372.get()} persistFailed=${saveFailed8372.get()}] separateFromLegacy=true\n")
        // V5.0.8292 — legacy pathway keys (SCANNER_DIRECT, ...REGISTRY_DUPLICATE_HYDRATE) are kept, not shown as sources.
        val legacy8292 = stats.keys.count { normalise(it) != it }
        if (legacy8292 > 0) sb.append("  legacyPathwayKeys8292=$legacy8292 (stored, unread)\n")
        sources8372.filter { normalise(it) == it }.mapNotNull { src -> read8372(src)?.let { src to it } }
            .sortedByDescending { it.second.n }.take(12).forEach { (src, s) ->
            val n = s.n
            val tier = authority(src).name
            val wr = (s.wr * 100).toInt()
            val mult = intakeMultiplier(src)
            sb.append("  $src n=$n WR=${wr}% net=${"%+.2f".format(s.mean)}% mult=${"%.2f".format(mult)} tier=$tier evidence=${s.evidence} decisionN=${decisionEpisodes8372.snapshot(src)?.samples ?: 0}\n")
        }
        return sb.toString().trimEnd()
    }

    /** Tier counts for UI pill rendering. */
    fun tierCounts(): Map<AuthorityTier, Int> {
        val out = mutableMapOf<AuthorityTier, Int>()
        AuthorityTier.values().forEach { out[it] = 0 }
        (stats.keys + decisionEpisodes8372.sources()).forEach { out[authority(it)] = (out[authority(it)] ?: 0) + 1 }
        return out
    }

    // V5.0.6856 — a blank source is not a source named "UNKNOWN". The old
    // `.ifBlank { "UNKNOWN" }` here meant every lookup with a missing provenance
    // resolved to one shared bucket, so unattributed rows pooled into a single
    // cohort and then shaped intake for every source that had lost its label —
    // the same placeholder-as-identity defect V5.0.6851 removed from the terminal
    // ledgers. Lookups on a blank now simply miss and fall through to neutral.
    // V5.0.8292 — canonical: pathway tags dropped and "SCANNER_DIRECT_X" read as X, so
    // "PUMP_FUN_NEW,SCANNER_DIRECT" and "SCANNER_DIRECT_PUMP_FUN_NEW" resolve to the source PUMP_FUN_NEW and a
    // pure pathway ("SCANNER_DIRECT") resolves to nothing (neutral). Legacy pathway keys stay stored, unread.
    private fun normalise(s: String): String = ScannerSourceTags8292.canonicalCompound8292(s)

    private fun save() {
        val c = ctx ?: return
        if (!saveQueued8372.compareAndSet(false, true)) return
        // A bounded episode book is larger than the old handful of source counters. Never serialize it on a trader loop.
        saveExecutor8372.execute { try {
            val obj = JSONObject()
            stats.forEach { (k, v) ->
                synchronized(v) {
                    obj.put(k, JSONObject().apply {
                        put("w", v.wins)
                        put("l", v.losses)
                        put("p", v.pnlSumPct)
                        put("t", v.lastUpdated)
                    })
                }
            }
            val state = decisionEpisodes8372.export()
            val episodes = JSONObject().put("retiredThrough", state.retiredThroughMs)
                .put("rows", JSONArray().apply {
                    state.outcomes.forEach { o -> put(JSONObject().put("episode", o.tokenEpisodeId)
                        .put("decision", o.decisionId).put("mode", o.mode).put("sources", JSONArray(o.sourceKeys))
                        .put("pnl", o.pnlPct).put("at", o.decisionAtMs)) }
                })
            val committed = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, obj.toString())
                .putString(DECISIONS_KEY_8372, episodes.toString())
                .commit()
            if (committed) {
                // Publish exactly the snapshot written, not the mutable book which may have been revised during IO.
                if (ctx === c) committedDecisions8372 = ScannerSourceEpisodes8372().also { it.restore(state) }
                saveOk8372.incrementAndGet()
            } else saveFailed8372.incrementAndGet()
        } catch (_: Throwable) { saveFailed8372.incrementAndGet() } finally { saveQueued8372.set(false) } }
    }

    private fun load() {
        val c = ctx ?: return
        try {
            val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: "{}"
            val obj = JSONObject(raw)
            obj.keys().forEach { k ->
                val o = obj.optJSONObject(k) ?: return@forEach
                stats[k] = SourceStats(
                    wins = o.optLong("w", 0L),
                    losses = o.optLong("l", 0L),
                    pnlSumPct = o.optDouble("p", 0.0),
                    lastUpdated = o.optLong("t", 0L),
                )
            }
        } catch (_: Throwable) { }
        try {
            val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DECISIONS_KEY_8372, null) ?: return
            val obj = JSONObject(raw)
            val rows = obj.optJSONArray("rows") ?: return
            val outcomes = ArrayList<ScannerSourceEpisodes8372.Outcome>()
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val sources = o.optJSONArray("sources") ?: continue
                outcomes.add(ScannerSourceEpisodes8372.Outcome(o.optString("episode"), o.optString("decision"),
                    o.optString("mode"), (0 until sources.length()).map { sources.optString(it) },
                    o.optDouble("pnl", Double.NaN), o.optLong("at")))
            }
            val state = ScannerSourceEpisodes8372.State(obj.optLong("retiredThrough"), outcomes)
            decisionEpisodes8372.restore(state)
            committedDecisions8372 = ScannerSourceEpisodes8372().also { it.restore(state) }
        } catch (_: Throwable) { }
    }

    /** Sources known to feed established Solana mid/large-cap assets. */
    private val ESTABLISHED_FEEDERS = setOf(
        "COINGECKO_ESTABLISHED",
        "SOLANA_BLUECHIP_WATCHLIST",  // V5.0.4589 — hard-coded blue-chip mints
        "DEX_TRENDING",
        "BIRDEYE_TRENDING",
        "BIRDEYE_MARKETS",
        "DEX_GAINERS",
        "GECKO_TOP_VOLUME",
        // V5.0.7297 — lane hunts for established-band lanes.
        "MARKET_HUNT_QUALITY",
        "MARKET_HUNT_BLUECHIP",
        "MARKET_HUNT_DIP_HUNTER",
        "MARKET_HUNT_TREASURY",
        "MARKET_HUNT_CASHGEN",
    )
}
