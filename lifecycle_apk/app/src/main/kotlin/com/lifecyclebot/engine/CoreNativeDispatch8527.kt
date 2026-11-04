package com.lifecyclebot.engine

/** A native proposal is executable only under the current election, never by running CORE first. */
internal object CoreNativeDispatch8527 {
    val nativeLanes8527 = setOf("CORE", "CYCLIC", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP",
        "MOONSHOT", "SHITCOIN", "MANIPULATED", "EXPRESS", "PROJECT_SNIPER", "DIP_HUNTER")

    fun hasNativeExecutor8527(lane: String): Boolean =
        lane.trim().uppercase().replace("BLUE_CHIP", "BLUECHIP").replace("SHITCOIN_EXPRESS", "EXPRESS") in nativeLanes8527

    fun currentCore8527(
        primaryLane: String, snapshot: SpecialistBrainBridge7542.Snapshot?,
        currentSnapshot: SpecialistBrainBridge7542.Snapshot?, readyVersion: Long?,
        currentVersion: Long, sealedOwner: String?, sameMode: Boolean, occupied: Boolean,
        canPropose: Boolean, shadowOnly: Boolean, nowMs: Long,
        currentChoice: NativeOpportunityPolicy8522.Decision?,
    ): SpecialistBrainBridge7542.Opinion? {
        if (!primaryLane.equals("CORE", true) || !sameMode || occupied || !canPropose || shadowOnly ||
            currentVersion <= 0L || readyVersion != currentVersion ||
            (!sealedOwner.isNullOrBlank() && !sealedOwner.equals("CORE", true))) return null
        val s = snapshot ?: return null
        val current = currentSnapshot ?: return null
        // A TTL-only refresh changes capture/read times, not the election. Any changed evidence
        // fingerprint or raw/effective lane opinion still requires a new election, including a
        // changed competitor. Current policy source/version/mode are separately checked by the caller.
        if (s.mint != current.mint || s.fingerprint != current.fingerprint ||
            s.originalOpinions8522 != current.originalOpinions8522 || s.opinions != current.opinions ||
            nowMs - s.evaluatedAtMs !in 0L..5_000L ||
            nowMs - current.evaluatedAtMs !in 0L..5_000L) return null
        if (s.opportunityDecisions8522["CORE"] != null && currentChoice == null) return null
        val original = s.originalOpinions8522["CORE"] ?: return null
        val effective = NativeOpportunityPolicy8522.apply8522(original, currentChoice)
        return effective.takeIf { it.lane == "CORE" && it.authoritative && it.eligible }
    }
}
