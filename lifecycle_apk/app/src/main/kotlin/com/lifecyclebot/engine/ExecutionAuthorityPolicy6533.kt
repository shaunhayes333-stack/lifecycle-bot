package com.lifecyclebot.engine

/** V5.0.6533 — pure execution-authority policy shared by scheduler, FDG and tests. */
object ExecutionAuthorityPolicy6533 {
    private val trunk = setOf("STANDARD", "CORE", "V3", "V3_CORE")
    fun isTrunkLane(lane: String): Boolean = lane.trim().uppercase().replace('-', '_') in trunk

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
        return pool[(stable % pool.size).toInt()]
    }

    fun requiresSolanaTokenMap(chain: String, assetKey: String): Boolean =
        chain.equals("SOLANA", true) && !assetKey.startsWith("unresolved:", true) && !assetKey.startsWith("perps:", true)
}
