package com.lifecyclebot.engine

/**
 * V5.0.8094 — pure: may a runtime position be retired after a canonical close?
 * The close must be at or after this entry (a later close of an earlier entry
 * cannot retire a fresh buy) and the wallet must be PROVEN empty of the mint by a complete wallet snapshot taken at
 * or after the close ([walletZeroProvenAt8094]); a missing tracker row is not proof.
 */
internal fun closedRuntimeRetirable8094(entryTimeMs: Long, closedAtMs: Long, walletZeroProvenAtMs: Long): Boolean =
    closedAtMs > 0L && closedAtMs >= entryTimeMs && walletZeroProvenAtMs >= closedAtMs

/**
 * V5.0.8094 — pure: the wallet proves zero for a mint at [snapshotAtMs] only from a COMPLETE snapshot that holds the
 * mint at dust or not at all. A missing tracker row, a partial snapshot or no snapshot proves nothing (0).
 */
internal fun walletZeroProvenAt8094(snapshotComplete: Boolean, snapshotAtMs: Long, heldRaw: java.math.BigInteger?, dustRaw: java.math.BigInteger): Long =
    if (snapshotComplete && snapshotAtMs > 0L && (heldRaw == null || heldRaw <= dustRaw)) snapshotAtMs else 0L

/** V5.0.8094 — pure: a counter-safe sub-reason token for a pre-trade block. */
internal fun pretradeSubReason8094(raw: String): String {
    // The leading words up to the first measured value: "HARD BLOCK: Low liquidity $0" -> HARD_BLOCK_LOW_LIQUIDITY.
    val t = raw.trim().uppercase().replace(':', ' ').split(Regex("\\s+"))
        .takeWhile { w -> w.isNotEmpty() && w.none { it.isDigit() || it == '$' } }
        .joinToString("_") { w -> w.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("") }
        .replace(Regex("_+"), "_").trim('_').take(40)
    return t.ifBlank { "UNSPECIFIED" }
}

/**
 * V5.0.8094 — pure: was the blocking fact visible when FDG sealed the order?
 * PRESENT_AT_SEAL means the pre-seal decision admitted it (a gap before the
 * seal); CHANGED_AFTER_SEAL means it is new evidence the last-mile gate caught.
 */
internal fun pretradeSealRelation8094(sealTier: String, sealHardNo: Boolean, sealRug: Int): String = when {
    sealHardNo -> "PRESENT_AT_SEAL"
    sealTier.equals("HARD_BLOCK", true) || sealTier.equals("BLOCKED", true) -> "PRESENT_AT_SEAL"
    sealRug in 0..10 -> "PRESENT_AT_SEAL"
    else -> "CHANGED_AFTER_SEAL"
}

/**
 * V5.0.8094 — pure: a lane's refusals are inverted when its refused cohort (>= 20
 * graded 60-minute labels) earns more than zero AND more than its admitted
 * cohort (when that has >= 10 labels), each by at least one standard error.
 */
internal fun refusalInverted8094(refN: Int, refMeanPct: Double, refSePct: Double, admN: Int, admMeanPct: Double): Boolean {
    if (refN < 20 || !refMeanPct.isFinite()) return false
    val se = if (refSePct.isFinite() && refSePct >= 0.0) refSePct else return false
    val lower = refMeanPct - se
    if (lower <= 0.0) return false
    return admN < 10 || !admMeanPct.isFinite() || lower > admMeanPct
}
