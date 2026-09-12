package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * V5.0.6741 — regression suite for the code-source repair of §1 and §2
 * of the operator directive.
 *
 * §1 — no path in the paper rebuild may stamp `lane = "REPLAY_6486"`
 *   on a canonical Position. The original owner lane must be recovered
 *   from LaneAttributionLedger6427.getEntryLane(positionId), with
 *   "UNRESOLVED_OWNER_6741" as the visible fallback when the ledger has
 *   no attribution (so exposure is still visible and not guessed).
 *   Recovery provenance is recorded separately via `runId =
 *   "REPLAY_RESTORE_${e.idempotencyKey}"`.
 *
 * §2 — no path may substitute a SOL/token figure into the USD/token
 *   `entryPriceUsd` field. The prior fallback `carryCost / qtyToken`
 *   is removed. When the durable carry has no unit-verified USD basis,
 *   entryPriceUsd stays 0.0 and `entryPriceSource =
 *   "CARRY_USD_BASIS_UNKNOWN_6741"` marks the exposure as
 *   unresolved-valuation (never a fabricated numeric basis).
 */
class Aate6741ProvenanceOwnerAndBasisSourceTest {

    @Test
    fun `no code path stamps lane equals REPLAY_6486 on a rebuilt canonical position`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        // The literal `lane = "REPLAY_6486"` string is the direct
        // producer the operator's dump complained about. It MUST be
        // removed from every reconstruction site in this file.
        val badAssignments = Regex("""lane\s*=\s*"REPLAY_6486"""").findAll(src).count()
        assertEquals(
            "No canonical-position rebuild site may set lane = \"REPLAY_6486\"; found $badAssignments",
            0, badAssignments,
        )
        // The replacement recovers the true owner from
        // LaneAttributionLedger6427 with an explicit unresolved marker
        // as fallback. Both markers MUST be present so future refactors
        // cannot silently regress to the string constant.
        assertTrue(
            "Rebuild sites must consult LaneAttributionLedger6427.getEntryLane for owner recovery",
            src.contains("LaneAttributionLedger6427.getEntryLane(e.positionId)") ||
                src.contains("LaneAttributionLedger6427.getEntryLane(pid)"),
        )
        assertTrue(
            "Rebuild sites must expose UNRESOLVED_OWNER_6741 when no attribution exists",
            src.contains("\"UNRESOLVED_OWNER_6741\""),
        )
        // Recovery provenance recorded separately from owner (not
        // masqueraded as strategy identity).
        assertTrue(
            "Recovery provenance must be recorded via runId REPLAY_RESTORE_ marker",
            src.contains("\"REPLAY_RESTORE_\${e.idempotencyKey}\"") ||
                src.contains("REPLAY_RESTORE_"),
        )
    }

    @Test
    fun `carry position rebuild does not fabricate USD entry price from SOL cost divided by qty`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        // The specific substitution the directive forbids:
        //   entryPriceUsd = carryCost / qtyToken
        // The line MUST NOT survive anywhere in the file. The 6741
        // repair keeps the value at 0.0 and stamps
        // "CARRY_USD_BASIS_UNKNOWN_6741" so the unresolved valuation is
        // visible without inventing a numeric basis.
        assertFalse(
            "carryCost / qtyToken6631 fabrication must be removed",
            src.contains("carryCost / qtyToken6631"),
        )
        assertFalse(
            "DERIVED_CARRY_COST_QTY_6631 entryPriceSource must be removed from producer (still allowed at consumer-side classifier)",
            src.contains("\"DERIVED_CARRY_COST_QTY_6631\""),
        )
        assertTrue(
            "unresolved-basis marker must be present so consumers can identify carry positions without USD basis",
            src.contains("CARRY_USD_BASIS_UNKNOWN_6741"),
        )
    }

    @Test
    fun `carry position rebuild also restores owner lane from attribution ledger`() {
        // Consistency check — the carry-rebuild path uses `pid` as the
        // positionId variable; the owner lookup MUST go through it.
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(
            "carry rebuild must recover owner from LaneAttributionLedger6427.getEntryLane(pid)",
            src.contains("LaneAttributionLedger6427.getEntryLane(pid)"),
        )
        assertFalse(
            "carry rebuild must not stamp \"RECOVERED_CARRY_6492\" as an execution lane (belongs to recovery provenance)",
            src.contains("lane = \"RECOVERED_CARRY_6492\""),
        )
    }
}
