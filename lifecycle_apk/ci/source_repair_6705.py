#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    s = p.read_text()
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 source match, found {count}")
    p.write_text(s.replace(old, new, 1))
    print(f"repaired {label}")


gate = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
replace_once(
    gate,
    '''    private fun isShadowReadOnlyLane6487(rawLane: String): Boolean =
        rawLane.uppercase().trim().replace('-', '_').replace(' ', '_') in setOf("V3_CORE", "STANDARD", "CASHGEN")''',
    '''    // V5.0.6705 — CASHGEN is a canonical executable MemeTrader specialist.
    // MemeOwnershipInvariant6620 names only STANDARD/V3_CORE as observer-only.
    // Keeping CASHGEN here contradicted that source contract and suppressed its
    // FDG/ExecutionIntent publication before the trader could ever open.
    private fun isShadowReadOnlyLane6487(rawLane: String): Boolean =
        rawLane.uppercase().trim().replace('-', '_').replace(' ', '_') in setOf("V3_CORE", "STANDARD")''',
    "CASHGEN_EXECUTABLE_SPECIALIST_6705",
)

test_path = Path("lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6705CashgenExecutionAuthorityTest.kt")
test_path.write_text(r'''package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** V5.0.6705 — CASHGEN is executable; only STANDARD/V3_CORE are observers. */
class Aate6705CashgenExecutionAuthorityTest {
    @Before fun setup() {
        ExecutableOpenGate.resetForTests()
        LaneExecutionCoordinator.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
    }

    @After fun cleanup() { ExecutableOpenGate.resetForTests() }

    @Test fun `cashgen fdg allow materializes immutable execution intent`() {
        val mint = "CashgenExec6705${System.nanoTime()}"
        val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
        val intent = ExecutableOpenGate.recordFdgAndGetIntent6533(
            mint = mint,
            symbol = "CASH6705",
            lane = "CASHGEN",
            canExecute = true,
            reason = null,
            signal = "BUY",
            rugScore = 90,
            safetyTier = "SAFE",
            liquidityUsd = 5_000.0,
            preFdgVerdict = "BUY",
            candidateVersion = cv,
            entryScore = 82,
        )
        assertNotNull(intent)
        assertEquals("CASHGEN", intent!!.canonicalLane)
        assertTrue(intent.fdgAllowed)
        assertSame(intent, ExecutableOpenGate.activeExecutionIntent6519("PAPER", mint, cv))
    }

    @Test fun `shadow policy matches canonical meme ownership contract`() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val body = gate.substringAfter("private fun isShadowReadOnlyLane6487")
            .substringBefore("fun recordEntryAuthority6487")
        assertTrue(body.contains("V3_CORE"))
        assertTrue(body.contains("STANDARD"))
        assertFalse(body.contains("CASHGEN"))

        val ownership = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemeOwnershipInvariant6620.kt").readText()
        assertTrue(ownership.contains("\"MANIPULATED\", \"TREASURY\", \"CASHGEN\""))
        assertTrue(ownership.contains("setOf(\"STANDARD\", \"V3_CORE\")"))
    }
}
''')

rot = "lifecycle_apk/ci/patch_rot_scan.py"
replace_once(
    rot,
    "    # 6702 exit-liveness contracts.\n",
    '''    # 6705 specialist liveness contract. CASHGEN is explicitly executable in
    # MemeOwnershipInvariant6620; only STANDARD/V3_CORE may be shadow-only.
    open_gate = (SRC / "com/lifecyclebot/engine/ExecutableOpenGate.kt").read_text()
    forbid(errors, open_gate, 'setOf("V3_CORE", "STANDARD", "CASHGEN")', "CASHGEN_SHADOW_DISABLE_RETIRED_6705")
    require(errors, open_gate, 'setOf("V3_CORE", "STANDARD")', "OBSERVER_ONLY_LANES_6705")
    require(errors, open_gate, "CASHGEN is a canonical executable MemeTrader specialist", "CASHGEN_EXECUTABLE_SOURCE_CONTRACT_6705")

    # 6702 exit-liveness contracts.
''',
    "PATCH_ROT_CASHGEN_6705",
)

for temporary in (
    Path(".github/workflows/source-repair-6705.yml"),
    Path("lifecycle_apk/ci/source_repair_6705.py"),
):
    if temporary.exists():
        temporary.unlink()
        print(f"removed temporary {temporary}")
