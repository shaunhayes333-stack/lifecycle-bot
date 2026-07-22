package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V5.0.6324 — PROVIDER AUTHORITY REGISTRY (operator hotfix §9).
 *
 * Separate provider responsibilities so Dexscreener degradation does
 * NOT corrupt canonical quantity / cost / realised PnL.
 *
 *   A. Discovery authority
 *   B. Display/analytics authority
 *   C. Execution authority
 *   D. Accounting authority
 *   E. Exit-risk authority
 *
 * Dexscreener may serve A and B while degraded; it must never be the
 * SOLE authority for C/D/E when fresher chain data exists.
 */
object ProviderAuthority {

    enum class Role { DISCOVERY, DISPLAY, EXECUTION, ACCOUNTING, EXIT_RISK }

    data class ProviderConfig(
        val name: String,
        val roles: Set<Role>,
        val degradedRolesRestricted: Set<Role>,
    )

    /** Baseline provider matrix. Callers can query [allowedRoles] to
     *  filter which providers may source quantity/cost/PnL data. */
    private val DEFAULT: Map<String, ProviderConfig> = mapOf(
        "WALLET" to ProviderConfig("WALLET", setOf(Role.EXECUTION, Role.ACCOUNTING, Role.EXIT_RISK), emptySet()),
        "HELIUS" to ProviderConfig("HELIUS", setOf(Role.EXECUTION, Role.ACCOUNTING, Role.EXIT_RISK), setOf(Role.EXECUTION)),
        "JUPITER" to ProviderConfig("JUPITER", setOf(Role.EXECUTION, Role.ACCOUNTING, Role.EXIT_RISK), setOf(Role.ACCOUNTING)),
        "RAYDIUM" to ProviderConfig("RAYDIUM", setOf(Role.EXIT_RISK, Role.DISPLAY), emptySet()),
        "ORCA" to ProviderConfig("ORCA", setOf(Role.EXIT_RISK, Role.DISPLAY), emptySet()),
        "METEORA" to ProviderConfig("METEORA", setOf(Role.EXIT_RISK, Role.DISPLAY), emptySet()),
        "PUMPPORTAL" to ProviderConfig("PUMPPORTAL", setOf(Role.DISCOVERY, Role.DISPLAY, Role.EXIT_RISK), setOf(Role.ACCOUNTING)),
        "DEXSCREENER" to ProviderConfig("DEXSCREENER", setOf(Role.DISCOVERY, Role.DISPLAY), setOf(Role.EXECUTION, Role.ACCOUNTING, Role.EXIT_RISK)),
        "BIRDEYE" to ProviderConfig("BIRDEYE", setOf(Role.DISCOVERY, Role.DISPLAY), setOf(Role.ACCOUNTING)),
    )

    private val degradedProviders = ConcurrentHashMap<String, Long>()

    fun markDegraded(name: String) {
        degradedProviders[name.uppercase()] = System.currentTimeMillis()
    }
    fun clearDegraded(name: String) { degradedProviders.remove(name.uppercase()) }
    fun isDegraded(name: String): Boolean = degradedProviders.containsKey(name.uppercase())

    /** Roles this provider may fulfill given its current health. */
    fun allowedRoles(name: String): Set<Role> {
        val cfg = DEFAULT[name.uppercase()] ?: return emptySet()
        return if (isDegraded(cfg.name)) cfg.roles - cfg.degradedRolesRestricted else cfg.roles
    }

    /** Does this provider currently satisfy the given role? */
    fun canFulfill(name: String, role: Role): Boolean = allowedRoles(name).contains(role)

    data class ProviderSample(val name: String, val value: Double, val ageMs: Long)

    /**
     * Pick the authoritative sample from a set of provider observations,
     * given the required role. Degraded providers are excluded. If two
     * or more samples disagree by more than [deviationThresholdPct],
     * emit [PROVIDER_AUTHORITY_CONFLICT_6324] and return the freshest
     * healthy sample (or null if none can fulfill the role).
     */
    fun chooseAuthority(
        mint: String,
        executionId: String,
        role: Role,
        samples: List<ProviderSample>,
        deviationThresholdPct: Double = 8.0,
    ): ProviderSample? {
        val eligible = samples.filter { canFulfill(it.name, role) && it.value > 0.0 }
        if (eligible.isEmpty()) return null
        val values = eligible.map { it.value }
        val hi = values.max()
        val lo = values.min()
        val meanBase = (hi + lo) / 2.0
        val deviationPct = if (meanBase > 0.0) (hi - lo) / meanBase * 100.0 else 0.0
        val chosen = eligible.minByOrNull { it.ageMs } ?: return null
        if (deviationPct > deviationThresholdPct && eligible.size >= 2) {
            try {
                ForensicLogger.lifecycle(
                    "PROVIDER_AUTHORITY_CONFLICT_6324",
                    "mint=${mint.take(10)} executionId=${executionId.take(24)} role=$role providerValues=${eligible.joinToString(",") { "${it.name}=${it.value}" }} providerAges=${eligible.joinToString(",") { "${it.name}=${it.ageMs}ms" }} deviationPct=${"%.2f".format(deviationPct)} chosenAuthority=${chosen.name} action=USE_FRESHEST_HEALTHY",
                )
                PipelineHealthCollector.labelInc("PROVIDER_AUTHORITY_CONFLICT_6324")
            } catch (_: Throwable) {}
        }
        return chosen
    }

    /** Cross-provider deviation helper for exit-risk confirmation. */
    fun deviationPct(a: Double, b: Double): Double {
        if (a <= 0.0 || b <= 0.0) return Double.NaN
        val mean = (a + b) / 2.0
        return if (mean > 0.0) abs(a - b) / mean * 100.0 else Double.NaN
    }

    fun snapshotDegraded(): Set<String> = degradedProviders.keys.toSet()
}
