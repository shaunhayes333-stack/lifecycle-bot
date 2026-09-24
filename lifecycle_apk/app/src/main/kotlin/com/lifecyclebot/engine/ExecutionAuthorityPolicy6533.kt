package com.lifecyclebot.engine

/** V5.0.6533 — pure execution-authority policy shared by scheduler, FDG and tests. */
object ExecutionAuthorityPolicy6533 {
    private val trunk = setOf("STANDARD", "CORE", "V3", "V3_CORE")
    fun isTrunkLane(lane: String): Boolean = lane.trim().uppercase().replace('-', '_') in trunk

    private const val UNDERSAMPLED_CLOSES_7296 = 20

    fun selectOneRescue(
        mint: String,
        candidateVersion: Long,
        primaryLane: String,
        affinityLanes: Collection<String>,
        eligibleLanes: Collection<String>,
    ): String? {
        val primary = primaryLane.uppercase()
        val eligible = eligibleLanes.map { it.uppercase() }.filter { it != primary && !isTrunkLane(it) }.distinct()
        if (eligible.isEmpty()) return null
        val affinity = affinityLanes.map { it.uppercase() }.toSet()
        val pool = eligible.filter { it in affinity }.ifEmpty { eligible }.sorted()
        val stable = (mint.hashCode().toLong() xor candidateVersion).and(Long.MAX_VALUE)
        // V5.0.7293 — in PAPER the one rescue slot goes to the least-sampled
        // eligible lane (fewest journal closes, OracleTradeHistory7287), so a
        // specialist that has never traded gets the slot instead of losing a
        // hash lottery to lanes with hundreds of closes. Same single slot, no
        // extra fanout; live keeps the stable hash pick.
        val paper7293 = try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        if (paper7293 && pool.size > 1) {
            val byN = pool.map { lane ->
                lane to (try { com.lifecyclebot.engine.truth.OracleTradeHistory7287.lane(lane)?.n } catch (_: Throwable) { null } ?: 0)
            }
            // V5.0.7296 — 7293 gave the slot to the single least-sampled lane,
            // so one lane (SHITCOIN, n=1) took it on every candidate: 61% of all
            // lane evaluations, while EXPRESS/CYCLIC/TREASURY/CASHGEN dropped to
            // zero evaluations. The slot now rotates by the stable hash across
            // EVERY eligible lane still under the sample bar; only when none is
            // under it does the plain hash over the whole pool apply.
            val starved = byN.filter { it.second < UNDERSAMPLED_CLOSES_7296 }.map { it.first }
            if (starved.isNotEmpty()) {
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RESCUE_SLOT_TO_UNDERSAMPLED_LANE_7293") } catch (_: Throwable) {}
                return starved[(stable % starved.size).toInt()]
            }
        }
        return pool[(stable % pool.size).toInt()]
    }

    fun requiresSolanaTokenMap(chain: String, assetKey: String): Boolean =
        chain.equals("SOLANA", true) && !assetKey.startsWith("unresolved:", true) && !assetKey.startsWith("perps:", true)
}
