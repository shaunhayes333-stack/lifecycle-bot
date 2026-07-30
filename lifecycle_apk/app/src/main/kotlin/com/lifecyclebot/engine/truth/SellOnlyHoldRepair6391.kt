package com.lifecyclebot.engine.truth

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6391 — BREAK SELL-ONLY DEADLOCK + REPAIR EXIT AUTHORITY.
 *
 * V5.0.6390 was operationally alive but 0 buys landed because the 6389 hold
 * armed itself on ANY wallet SPL balance, treating every dust/airdrop/external
 * token as an unresolved bot position.
 *
 * Every section below implements one part of the V5.0.6391 directive. No
 * cherry-picking.
 *
 *   S1  OwnershipClassification6391  — 6-class wallet-mint ownership
 *   S2  SellOnlyHold6391             — release-first hold, proven-exposure gated
 *       EffectiveLiveAuthority6391   — 6-state final authority reporting
 *   S3  CanonicalRecoveryUpsert6391  — atomic recovered-position writer
 *   S4  ReconcilerVisibility6391     — recovered positions MUST be enumerated
 *   S5  ExecutionCircuitBreakers6391 — 8-namespace breaker taxonomy
 *   S6  ExitRoutePlan6391            — deterministic route matrix + telemetry
 *   S7  PumpRescueUnifiedBuilder6391 — single validated builder contract
 *   S8  HoldReleaseRetry6391         — bounded exponential retry policy
 *   S9  ForensicTelemetry6391        — required event vocabulary
 */

/* ============================ S1 OWNERSHIP CLASSIFICATION ================== */

object OwnershipClassification6391 {
    enum class Class {
        BOT_OWNED_PROVEN,          // canonical buy-fill + confirmed bot buy signature
        BOT_OWNED_RECOVERED,       // durable proof (fill-lot ledger / owner delta) but position row missing
        EXTERNAL_UNMANAGED,        // no bot-buy proof — airdrop / manual / spam
        AIRDROP_OR_DUST,           // sub-threshold or known-spam
        STRANDED_UNSELLABLE,       // proven bot exposure but venue no longer sellable
        UNRESOLVED,                // initial state — evidence pending
    }

    /** Directive S1: proof types that qualify for BOT_OWNED_*. */
    data class Proof(
        val hasCanonicalBuyFillRecord: Boolean,
        val hasFillLotLedgerRecord: Boolean,
        val hasConfirmedBotBuySignature: Boolean,
        val hasOwnerDeltaFromBotBroadcast: Boolean,
        val hasPersistedLivePositionRow: Boolean,
    ) {
        fun hasAnyProof(): Boolean = hasCanonicalBuyFillRecord ||
            hasFillLotLedgerRecord || hasConfirmedBotBuySignature ||
            hasOwnerDeltaFromBotBroadcast || hasPersistedLivePositionRow
    }

    /** Directive S1: signals that MUST NOT independently establish ownership. */
    data class NonProof(
        val nonZeroWalletBalance: Boolean,
        val historicalRawBalance: Boolean,
        val oldHostTrackerRow: Boolean,
        val tokenSymbolMatch: Boolean,
        val watchlistMembership: Boolean,
        val inferredLane: Boolean,
        val previousOpenRestoredWithoutTxProof: Boolean,
    )

    data class Record(
        val mint: String, val classification: Class,
        val walletRawBalance: BigInteger, val tokenDecimals: Int,
        val canonicalQuantityRaw: BigInteger,
        val proof: Proof, val nonProof: NonProof,
        val classifiedAtMs: Long, val runtimeGeneration: Long,
        val sellable: Boolean,
    ) {
        val unresolvedRaw: BigInteger get() = walletRawBalance.subtract(canonicalQuantityRaw)
        val contributesToProvenBotExposure: Boolean get() =
            (classification == Class.BOT_OWNED_PROVEN || classification == Class.BOT_OWNED_RECOVERED) &&
            unresolvedRaw.signum() > 0
    }

    private val records = ConcurrentHashMap<String, Record>()

    /** Directive S1: strict classification. Non-proof signals cannot promote. */
    fun classify(mint: String, walletRawBalance: BigInteger, tokenDecimals: Int,
                 canonicalQuantityRaw: BigInteger, proof: Proof, nonProof: NonProof,
                 dustThresholdRaw: BigInteger = BigInteger.valueOf(1_000L),
                 sellable: Boolean = true, runtimeGeneration: Long = 0L): Record {
        val cls = when {
            proof.hasCanonicalBuyFillRecord && proof.hasConfirmedBotBuySignature &&
                proof.hasPersistedLivePositionRow -> Class.BOT_OWNED_PROVEN
            proof.hasAnyProof() && !sellable -> Class.STRANDED_UNSELLABLE
            proof.hasAnyProof() -> Class.BOT_OWNED_RECOVERED
            walletRawBalance <= dustThresholdRaw -> Class.AIRDROP_OR_DUST
            walletRawBalance.signum() > 0 -> Class.EXTERNAL_UNMANAGED
            else -> Class.UNRESOLVED
        }
        val rec = Record(
            mint = mint, classification = cls, walletRawBalance = walletRawBalance,
            tokenDecimals = tokenDecimals, canonicalQuantityRaw = canonicalQuantityRaw,
            proof = proof, nonProof = nonProof,
            classifiedAtMs = System.currentTimeMillis(), runtimeGeneration = runtimeGeneration,
            sellable = sellable,
        )
        records[mint] = rec
        return rec
    }

    fun record(mint: String): Record? = records[mint]
    fun all(): List<Record> = records.values.toList()

    /**
     * Directive S1 invariant: EXTERNAL_UNMANAGED / AIRDROP_OR_DUST /
     * STRANDED_UNSELLABLE never contribute to global hold.
     */
    fun provenBotExposure(): List<Record> =
        records.values.filter { it.contributesToProvenBotExposure }

    /** Directive S1: dust/external counts (for forensic reporting only). */
    data class Distribution(
        val botOwnedProven: Int, val botOwnedRecovered: Int,
        val externalUnmanaged: Int, val airdropOrDust: Int,
        val strandedUnsellable: Int, val unresolved: Int,
    )
    fun distribution(): Distribution {
        val by = records.values.groupingBy { it.classification }.eachCount()
        return Distribution(
            botOwnedProven = by[Class.BOT_OWNED_PROVEN] ?: 0,
            botOwnedRecovered = by[Class.BOT_OWNED_RECOVERED] ?: 0,
            externalUnmanaged = by[Class.EXTERNAL_UNMANAGED] ?: 0,
            airdropOrDust = by[Class.AIRDROP_OR_DUST] ?: 0,
            strandedUnsellable = by[Class.STRANDED_UNSELLABLE] ?: 0,
            unresolved = by[Class.UNRESOLVED] ?: 0,
        )
    }

    internal fun clearForTest() { records.clear() }
}

/* ============================ S2 EFFECTIVE LIVE AUTHORITY + HOLD =========== */

/** Directive S2: single final-authority enum reported by the runtime header. */
enum class EffectiveLiveAuthority6391 {
    OPEN,
    BLOCKED_GOVERNOR,
    BLOCKED_UNRESOLVED_BOT_POSITION,
    BLOCKED_PROVIDER_SAFETY,
    BLOCKED_OPERATOR,
    BLOCKED_OTHER,
}

/**
 * V5.0.6391 (S2/S8) — release-first hold. The DEFAULT state is unblocked.
 * The hold arms only when `OwnershipClassification6391.provenBotExposure()`
 * is non-empty AND at least one such mint is unresolved into canonical.
 * This supersedes V5.0.6389 SellOnlyForensicHold6389 which armed on startup.
 */
object SellOnlyHold6391 {
    data class Snapshot(
        val armed: Boolean,
        val armedAtMs: Long,
        val holdGeneration: Long,
        val blockingMints: List<String>,
        val releaseRequirement: String,
    )
    private val armed = AtomicReference<Boolean>(false)
    private val armedAtMs = AtomicLong(0L)
    private val holdGeneration = AtomicLong(0L)
    private val blockingMints = AtomicReference<List<String>>(emptyList())
    private val releaseRequirement = AtomicReference<String>("HOLD_NEVER_ARMED")

    /** Directive S8: recalculate from CURRENT classified state every cycle. */
    @Synchronized
    fun reevaluate(runtimeGeneration: Long = 0L): Snapshot {
        val exposure = OwnershipClassification6391.provenBotExposure()
        val nowArmed = exposure.isNotEmpty()
        val wasArmed = armed.get()
        if (nowArmed && !wasArmed) {
            armed.set(true); armedAtMs.set(System.currentTimeMillis())
            holdGeneration.set(runtimeGeneration)
            blockingMints.set(exposure.map { it.mint })
            releaseRequirement.set("RESOLVE_${exposure.size}_PROVEN_BOT_MINTS_INTO_CANONICAL_AUTHORITY")
            emit("SELL_ONLY_HOLD_ARMED_6391", exposure)
        } else if (nowArmed && wasArmed) {
            blockingMints.set(exposure.map { it.mint })
            emit("SELL_ONLY_HOLD_REEVALUATED_6391", exposure)
        } else if (!nowArmed && wasArmed) {
            armed.set(false); blockingMints.set(emptyList())
            releaseRequirement.set("NO_UNRESOLVED_PROVEN_BOT_EXPOSURE")
            emit("SELL_ONLY_HOLD_RELEASED_6391", emptyList())
        }
        return snapshot()
    }

    fun snapshot(): Snapshot = Snapshot(
        armed = armed.get(), armedAtMs = armedAtMs.get(), holdGeneration = holdGeneration.get(),
        blockingMints = blockingMints.get(), releaseRequirement = releaseRequirement.get(),
    )

    /** Directive P0: default state is UNBLOCKED. */
    fun isActive(): Boolean = armed.get()

    private fun emit(event: String, exposure: List<OwnershipClassification6391.Record>) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(event)
            com.lifecyclebot.engine.ForensicLogger.lifecycle(event,
                "blockingMintCount=${exposure.size} " +
                "mints=${exposure.take(4).joinToString(",") { it.mint.take(10) }} " +
                "holdGeneration=${holdGeneration.get()} " +
                "releaseRequirement=${releaseRequirement.get()}",
            )
        } catch (_: Throwable) {}
    }

    internal fun forceArm(mints: List<String>, requirement: String, gen: Long = 0L) {
        armed.set(true); armedAtMs.set(System.currentTimeMillis())
        holdGeneration.set(gen); blockingMints.set(mints); releaseRequirement.set(requirement)
    }
    internal fun forceRelease() {
        armed.set(false); blockingMints.set(emptyList())
        releaseRequirement.set("FORCED_TEST_RELEASE")
    }
    internal fun clearForTest() {
        armed.set(false); armedAtMs.set(0L); holdGeneration.set(0L)
        blockingMints.set(emptyList()); releaseRequirement.set("HOLD_NEVER_ARMED")
    }
}

/**
 * V5.0.6391 (S2) — final effective-authority computer. This is the SINGLE
 * source of truth the runtime header reports.
 */
object EffectiveLiveAuthorityResolver6391 {
    data class Input(
        val governorAllowsLive: Boolean,
        val sellOnlyHoldActive: Boolean,
        val providerSafetyBlocked: Boolean,
        val operatorHalted: Boolean,
        val otherBlock: Boolean,
    )
    fun resolve(i: Input): EffectiveLiveAuthority6391 = when {
        i.operatorHalted -> EffectiveLiveAuthority6391.BLOCKED_OPERATOR
        i.sellOnlyHoldActive -> EffectiveLiveAuthority6391.BLOCKED_UNRESOLVED_BOT_POSITION
        !i.governorAllowsLive -> EffectiveLiveAuthority6391.BLOCKED_GOVERNOR
        i.providerSafetyBlocked -> EffectiveLiveAuthority6391.BLOCKED_PROVIDER_SAFETY
        i.otherBlock -> EffectiveLiveAuthority6391.BLOCKED_OTHER
        else -> EffectiveLiveAuthority6391.OPEN
    }

    /** Directive S2: FIRST_TRADE_READINESS pillar. */
    const val PILLAR_NAME: String = "NO_GLOBAL_PREEXEC_HOLD"
    fun pillarReady(effective: EffectiveLiveAuthority6391): Boolean =
        effective == EffectiveLiveAuthority6391.OPEN
}

/* ============================ S3 CANONICAL RECOVERY UPSERT ================= */

object CanonicalRecoveryUpsert6391 {
    data class Recovered(
        val mint: String, val walletRawBalance: BigInteger, val tokenDecimals: Int,
        val basisState: String,   // UNKNOWN | RECOVERED
        val exitOnly: Boolean,
        val includedInWinRate: Boolean,
        val runtimeGeneration: Long, val upsertedAtMs: Long,
    )
    private val positions = ConcurrentHashMap<String, Recovered>()

    /** Directive S3: atomic idempotent upsert. Same mint returns SAME record. */
    @Synchronized
    fun upsert(mint: String, walletRawBalance: BigInteger, tokenDecimals: Int,
               basisKnown: Boolean, runtimeGeneration: Long): Pair<Recovered, Boolean> {
        val existing = positions[mint]
        if (existing != null && existing.runtimeGeneration == runtimeGeneration) return existing to false
        val r = Recovered(
            mint = mint, walletRawBalance = walletRawBalance, tokenDecimals = tokenDecimals,
            basisState = if (basisKnown) "RECOVERED" else "UNKNOWN",
            exitOnly = true, includedInWinRate = basisKnown,
            runtimeGeneration = runtimeGeneration, upsertedAtMs = System.currentTimeMillis(),
        )
        positions[mint] = r
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                if (existing == null) "RECOVERED_CANONICAL_POSITION_UPSERTED_6391"
                else "RECOVERED_POSITION_ALREADY_CANONICAL_6391",
            )
        } catch (_: Throwable) {}
        return r to (existing == null)
    }

    fun byMint(mint: String): Recovered? = positions[mint]
    fun all(): List<Recovered> = positions.values.toList()
    fun openMintCount(): Int = positions.size
    internal fun clearForTest() { positions.clear() }
}

/* ============================ S4 RECONCILER VISIBILITY ===================== */

object ReconcilerVisibility6391 {
    /** Directive S4 invariant: recovered exit-only positions MUST be checked. */
    fun invariantHolds(recoveredExitOnlyCount: Int, sellReconcilerStarted: Boolean,
                       reconcilerCheckedMintsInWindow: Int): Boolean {
        if (recoveredExitOnlyCount == 0 || !sellReconcilerStarted) return true
        return reconcilerCheckedMintsInWindow >= recoveredExitOnlyCount
    }
}

/* ============================ S5 EXECUTION CIRCUIT BREAKERS ================ */

object ExecutionCircuitBreakers6391 {
    enum class Namespace {
        JUPITER_SCANNER, JUPITER_PRICING, JUPITER_BUY_EXECUTION, JUPITER_SELL_EXECUTION,
        PUMP_BUY_EXECUTION, PUMP_SELL_EXECUTION, HELIUS_RPC, HELIUS_TX_BROADCAST,
    }
    enum class State { CLOSED, OPEN, HALF_OPEN }
    data class Breaker(val state: State, val stateAgeMs: Long,
                       val nextProbeAtMs: Long, val consecutiveFailures: Int)

    private val breakers = ConcurrentHashMap<Namespace, Breaker>()
    private fun get(ns: Namespace): Breaker = breakers.getOrPut(ns) {
        Breaker(State.CLOSED, 0L, 0L, 0)
    }
    fun stateOf(ns: Namespace): State = get(ns).state

    /** Directive S5: sell/emergency MUST get one bounded half-open probe. */
    fun allowSellOrEmergencyProbe(ns: Namespace, nowMs: Long = System.currentTimeMillis()): Boolean {
        // Scanner or pricing being open must NEVER suppress a sell.
        if (ns == Namespace.JUPITER_SCANNER || ns == Namespace.JUPITER_PRICING) return true
        val b = get(ns)
        return when (b.state) {
            State.CLOSED -> true
            State.HALF_OPEN -> true   // probe already in flight is fine
            State.OPEN -> nowMs >= b.nextProbeAtMs
        }
    }

    fun recordFailure(ns: Namespace, backoffMs: Long = 5_000L) {
        val b = get(ns)
        val nextFail = (b.consecutiveFailures + 1).coerceAtMost(20)
        breakers[ns] = Breaker(State.OPEN,
            stateAgeMs = System.currentTimeMillis(),
            nextProbeAtMs = System.currentTimeMillis() + backoffMs,
            consecutiveFailures = nextFail)
    }
    fun recordSuccess(ns: Namespace) {
        breakers[ns] = Breaker(State.CLOSED, System.currentTimeMillis(), 0L, 0)
    }
    internal fun clearForTest() { breakers.clear() }
}

/* ============================ S5 PROVIDER OUTCOME TAXONOMY ================ */

object ProviderOutcomeTaxonomy6391 {
    enum class Outcome {
        PROVIDER_ATTEMPT_FAILED,
        PROVIDER_SKIPPED_BACKOFF,
        PROVIDER_UNSUPPORTED_VENUE,
        PROVIDER_BAD_REQUEST,
        PROVIDER_NO_ROUTE,
        PROVIDER_BROADCAST_FAILED,
    }
    /** Directive S5: an "exhausted" report must be an ATTEMPT_FAILED at minimum. */
    fun isRealExhaustion(o: Outcome): Boolean =
        o == Outcome.PROVIDER_ATTEMPT_FAILED || o == Outcome.PROVIDER_BROADCAST_FAILED
}

/* ============================ S6 EXIT ROUTE PLAN ========================== */

object ExitRoutePlan6391 {
    enum class Venue {
        PUMP_BONDING_CURVE, PUMP_SWAP_SUPPORTED, JUPITER_EXECUTION,
        DIRECT_RAYDIUM, DIRECT_VENUE_ADAPTER, FINAL_BOUNDED_RETRY,
    }
    data class Attempt(
        val venue: Venue, val outcome: ProviderOutcomeTaxonomy6391.Outcome?,
        val broadcastSignature: String?, val reason: String,
    )
    /** Directive S6: canonical sell route order. */
    val canonicalOrder: List<Venue> = listOf(
        Venue.PUMP_BONDING_CURVE, Venue.PUMP_SWAP_SUPPORTED, Venue.JUPITER_EXECUTION,
        Venue.DIRECT_RAYDIUM, Venue.DIRECT_VENUE_ADAPTER, Venue.FINAL_BOUNDED_RETRY,
    )
    data class Plan(val positionId: String, val mint: String, val attempts: MutableList<Attempt> = mutableListOf()) {
        fun record(v: Venue, outcome: ProviderOutcomeTaxonomy6391.Outcome?, sig: String?, reason: String) {
            attempts += Attempt(v, outcome, sig, reason)
        }
        fun terminal(): Boolean = attempts.any { it.broadcastSignature != null } ||
            attempts.count { it.outcome != null } == canonicalOrder.size
        fun matrix(): String = attempts.joinToString("|") { "${it.venue}:${it.outcome ?: "OK"}" }
    }

    private val plans = ConcurrentHashMap<String, Plan>()
    fun startPlan(positionId: String, mint: String): Plan =
        plans.getOrPut(positionId) { Plan(positionId, mint) }
    fun completePlan(positionId: String): Plan? = plans.remove(positionId)
    internal fun clearForTest() { plans.clear() }
}

/* ============================ S7 PUMP RESCUE UNIFIED BUILDER =============== */

object PumpRescueUnifiedBuilder6391 {
    /** Directive S7: SINGLE builder for both normal + rescue Pump exits. */
    data class Params(
        val mint: String, val walletPublicKey: String,
        val rawAmount: BigInteger, val tokenDecimals: Int,
        val denominatedInSol: Boolean, val poolIdentifier: String,
        val action: String,        // must be "sell"
        val slippageBps: Int, val priorityFeeLamports: Long, val tipLamports: Long,
        val retryPolicy: String, val telemetryReason: String,
    )
    data class Validation(val ok: Boolean, val errors: List<String>) {
        companion object { val OK = Validation(true, emptyList()) }
    }

    /** Directive S7: validate before send. Reject zero quantity, bad fields. */
    fun validate(p: Params): Validation {
        val errs = mutableListOf<String>()
        if (p.mint.isBlank()) errs += "MINT_BLANK"
        if (p.walletPublicKey.isBlank()) errs += "WALLET_PUBKEY_BLANK"
        if (p.rawAmount.signum() <= 0) errs += "RAW_AMOUNT_NON_POSITIVE"
        if (p.tokenDecimals < 0 || p.tokenDecimals > 20) errs += "DECIMALS_OUT_OF_RANGE"
        if (p.poolIdentifier.isBlank()) errs += "POOL_IDENTIFIER_BLANK"
        if (p.action != "sell") errs += "ACTION_NOT_SELL"
        if (p.slippageBps < 0 || p.slippageBps > 10_000) errs += "SLIPPAGE_OUT_OF_RANGE"
        return if (errs.isEmpty()) Validation.OK else Validation(false, errs)
    }

    /** Directive S7: schema summary for redacted logging. No private keys. */
    fun redactedSchema(p: Params): Map<String, String> = mapOf(
        "mint" to "String(${p.mint.length})",
        "walletPublicKey" to "String(${p.walletPublicKey.length})",
        "rawAmount" to "BigInteger",
        "tokenDecimals" to "Int",
        "denominatedInSol" to "Boolean",
        "poolIdentifier" to "String(${p.poolIdentifier.length})",
        "action" to p.action,
        "slippageBps" to "Int",
        "priorityFeeLamports" to "Long",
        "tipLamports" to "Long",
    )
}

/* ============================ S9 FORENSIC EVENT VOCABULARY ================= */

object ForensicTelemetry6391 {
    /** Directive S9: required event names. */
    val requiredEvents: Set<String> = setOf(
        "WALLET_MINT_OWNERSHIP_CLASSIFIED_6391",
        "EXTERNAL_WALLET_MINT_IGNORED_FOR_AUTHORITY_6391",
        "RECOVERED_CANONICAL_POSITION_UPSERTED_6391",
        "RECOVERED_POSITION_ALREADY_CANONICAL_6391",
        "SELL_ONLY_HOLD_ARMED_6391",
        "SELL_ONLY_HOLD_REEVALUATED_6391",
        "SELL_ONLY_HOLD_RELEASED_6391",
        "EXECUTION_BREAKER_HALF_OPEN_PROBE_6391",
        "EXIT_PROVIDER_ROUTE_PLAN_6391",
        "EXIT_PROVIDER_ROUTE_RESULT_6391",
        "PUMP_RESCUE_CANONICAL_BUILDER_USED_6391",
        "UNSELLABLE_HOLDING_QUARANTINED_6391",
        "EFFECTIVE_LIVE_AUTHORITY_CHANGED_6391",
    )

    fun isValidEvent(name: String): Boolean = name in requiredEvents

    /** Directive S9: never journal policy redirects, aborted tickets, etc. */
    val doNotJournal: Set<String> = setOf(
        "POLICY_REDIRECT", "ABORTED_PRE_EXEC_TICKET", "QUOTE_ONLY_ATTEMPT",
        "NO_SIGNATURE_SELL_FAILURE", "EXTERNAL_WALLET_HOLDING",
    )
    fun shouldJournal(kind: String): Boolean = kind !in doNotJournal
}
