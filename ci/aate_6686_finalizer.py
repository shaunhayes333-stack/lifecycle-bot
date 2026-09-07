from pathlib import Path
import re

ROOT = Path("lifecycle_apk")
MAIN = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine"


def read(path: Path) -> str:
    return path.read_text()


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"6686 finalizer missing anchor: {label}")
    return text.replace(old, new, 1)


# 1) The 6686 RPC logic is correct, but its explanatory comment retained the
# retired fallback symbol. The generated regression test deliberately treats
# that literal as patch rot, so remove the stale symbol without changing logic.
wallet_path = MAIN / "network/SolanaWallet.kt"
wallet = read(wallet_path)
wallet = replace_once(
    wallet,
    "rpcUrl + WalletManager.FALLBACK_RPCS",
    "rpcUrl + the legacy fallback list",
    "SolanaWallet retired fallback literal",
)
write(wallet_path, wallet)


# 2) V5.0.6600's source-contract test predates mode-aware specialist capital.
# 6686 correctly keeps PAPER authority in PAPER and uses LIVE wallet authority
# in LIVE. Update only the stale assertion; do not weaken the causal-funnel test.
test6600_path = TEST / "Aate6600SpecialistAuthorityRestorationTest.kt"
test6600 = read(test6600_path)
test6600 = replace_once(
    test6600,
    '        assertTrue(toolkit.contains("PAPER_CAPITAL_AUTHORITY_6577+LANE_EXPECTANCY+OPPORTUNITY_PRESSURE"))\n',
    '        assertTrue(toolkit.contains("capitalSource6686"))\n'
    '        assertTrue(toolkit.contains("LIVE_WALLET_AUTHORITY_6686"))\n'
    '        assertTrue(!toolkit.contains("allocationDecisionSource=PAPER_CAPITAL_AUTHORITY_6577+LANE_EXPECTANCY+OPPORTUNITY_PRESSURE"))\n',
    "Aate6600 stale paper-only specialist capital assertion",
)
write(test6600_path, test6600)


# 3) Canonical executor identity must be (mode,mint), not mint-only. With paper
# shadowing and live execution both enabled, mint-only caches can promote/sell
# the wrong canonical position. Keep the legacy no-mode API for old callers,
# but make every mirror mutation explicitly mode-safe.
mirror_path = MAIN / "engine/truth/ExecutorCanonicalMirror6442.kt"
mirror = read(mirror_path)
mirror = replace_once(
    mirror,
    '    private val activePositionIdByMint = ConcurrentHashMap<String, String>()\n'
    '    private val lastClosedPositionIdByMint = ConcurrentHashMap<String, String>()\n',
    '    // V5.0.6686 — canonical identity is mode + mint. PAPER shadow trades and\n'
    '    // LIVE trades may legally coexist for the same mint; a mint-only cache\n'
    '    // can cross-promote or cross-close real capital.\n'
    '    private val activePositionIdByModeMint = ConcurrentHashMap<String, String>()\n'
    '    private val lastClosedPositionIdByModeMint = ConcurrentHashMap<String, String>()\n\n'
    '    private fun modeName(paperMode: Boolean): String = if (paperMode) "paper" else "live"\n'
    '    private fun modeKey(mint: String, paperMode: Boolean): String =\n'
    '        "${modeName(paperMode)}|${canonicalMint(mint)}"\n',
    "ExecutorCanonicalMirror mode-key maps",
)

position_block = re.compile(
    r'''    fun positionIdOf\(mint: String\): String \{.*?\n    \}\n\n    private fun allocatePositionId\(mint: String, paperMode: Boolean\): String \{.*?\n    \}''',
    re.S,
)
match = position_block.search(mirror)
if not match:
    raise SystemExit("6686 finalizer missing anchor: positionIdOf/allocatePositionId")
new_position_block = '''    fun positionIdOf(mint: String, paperMode: Boolean? = null): String {
        val cm = canonicalMint(mint)
        val opens = try {
            CanonicalPositionAuthority6441.openPositions().filter { it.mint == cm }
        } catch (_: Throwable) {
            emptyList()
        }

        if (paperMode != null) {
            val mode = modeName(paperMode)
            val key = modeKey(cm, paperMode)
            val restored = opens.firstOrNull { it.mode.equals(mode, ignoreCase = true) }?.positionId
            if (!restored.isNullOrBlank()) activePositionIdByModeMint[key] = restored
            return activePositionIdByModeMint[key]
                ?: lastClosedPositionIdByModeMint[key]
                ?: "${mode.uppercase()}:$cm:$runIdHash"
        }

        // Compatibility for older callers that do not carry mode yet. If one
        // canonical position exists, it is unambiguous. If both modes exist,
        // prefer LIVE and emit a forensic marker rather than silently selecting
        // a PAPER shadow position for a real-capital path.
        if (opens.size == 1) {
            val only = opens.first()
            val isPaper = only.mode.equals("paper", ignoreCase = true)
            activePositionIdByModeMint[modeKey(cm, isPaper)] = only.positionId
            return only.positionId
        }
        if (opens.size > 1) {
            val live = opens.firstOrNull { it.mode.equals("live", ignoreCase = true) }
            if (live != null) {
                activePositionIdByModeMint[modeKey(cm, false)] = live.positionId
                try {
                    ForensicLogger.lifecycle(
                        "CANONICAL_POSITION_MODE_AMBIGUITY_6686",
                        "mint=${cm.take(10)} opens=${opens.size} action=compat_prefer_live",
                    )
                    PipelineHealthCollector.labelInc("CANONICAL_POSITION_MODE_AMBIGUITY_6686")
                } catch (_: Throwable) {}
                return live.positionId
            }
            return opens.first().positionId
        }

        val liveKey = modeKey(cm, false)
        val paperKey = modeKey(cm, true)
        return activePositionIdByModeMint[liveKey]
            ?: activePositionIdByModeMint[paperKey]
            ?: lastClosedPositionIdByModeMint[liveKey]
            ?: lastClosedPositionIdByModeMint[paperKey]
            ?: "PAPER:$cm:$runIdHash"
    }

    private fun allocatePositionId(mint: String, paperMode: Boolean): String {
        val cm = canonicalMint(mint)
        val key = modeKey(cm, paperMode)
        val existing = activePositionIdByModeMint[key]
        if (!existing.isNullOrBlank()) return existing
        val id = "${if (paperMode) "PAPER" else "LIVE"}:$cm:$runIdHash:${positionSeq.incrementAndGet()}"
        activePositionIdByModeMint[key] = id
        return id
    }'''
mirror = mirror[:match.start()] + new_position_block + mirror[match.end():]

# Both canonical mutation paths carry paperMode; bind them to the exact mode.
needle = "            val positionId = positionIdOf(mint)\n"
if mirror.count(needle) < 2:
    raise SystemExit(f"6686 finalizer expected >=2 mode-unsafe positionIdOf calls, found {mirror.count(needle)}")
mirror = mirror.replace(needle, "            val positionId = positionIdOf(mint, paperMode)\n", 2)

abort_pat = re.compile(
    r'''    fun abortBuy6485\(mint: String, reason: String\) \{.*?\n    \}''',
    re.S,
)
am = abort_pat.search(mirror)
if not am:
    raise SystemExit("6686 finalizer missing anchor: abortBuy6485")
new_abort = '''    fun abortBuy6485(mint: String, reason: String, paperMode: Boolean? = null) {
        val cm = canonicalMint(mint)
        val key = if (paperMode != null) {
            modeKey(cm, paperMode)
        } else {
            val matches = activePositionIdByModeMint.keys.filter { it.endsWith("|$cm") }
            if (matches.size != 1) {
                if (matches.size > 1) {
                    try {
                        ForensicLogger.lifecycle(
                            "CANONICAL_ABORT_MODE_AMBIGUITY_6686",
                            "mint=${cm.take(10)} activeModes=${matches.size} action=no_cross_mode_abort",
                        )
                        PipelineHealthCollector.labelInc("CANONICAL_ABORT_MODE_AMBIGUITY_6686")
                    } catch (_: Throwable) {}
                }
                return
            }
            matches.first()
        }
        val positionId = activePositionIdByModeMint.remove(key) ?: return
        try { CanonicalPositionAuthority6441.abortEntry6485(positionId, refundPaperFacade = false, reason = reason) } catch (_: Throwable) {}
        try { IdempotencyKeyStore6437.markTerminal(buyIdempotencyKey(positionId), "BUY_ABORTED_6485") } catch (_: Throwable) {}
    }'''
mirror = mirror[:am.start()] + new_abort + mirror[am.end():]

mirror = replace_once(
    mirror,
    '                    lastClosedPositionIdByMint[canonicalMint(mint)] = positionId\n'
    '                    activePositionIdByMint.remove(canonicalMint(mint), positionId)\n',
    '                    val modeMintKey6686 = modeKey(mint, paperMode)\n'
    '                    lastClosedPositionIdByModeMint[modeMintKey6686] = positionId\n'
    '                    activePositionIdByModeMint.remove(modeMintKey6686, positionId)\n',
    "ExecutorCanonicalMirror terminal mode-key cleanup",
)

if "activePositionIdByMint" in mirror or "lastClosedPositionIdByMint" in mirror:
    raise SystemExit("6686 finalizer: retired mint-only canonical mirror map still present")
write(mirror_path, mirror)


# 4) This coordinator is LIVE-only, so remove the remaining ambiguous lookup.
finalizer_path = MAIN / "engine/sell/SellFinalizationCoordinator.kt"
finalizer = read(finalizer_path)
finalizer = replace_once(
    finalizer,
    "ExecutorCanonicalMirror6442.positionIdOf(intent.mint)",
    "ExecutorCanonicalMirror6442.positionIdOf(intent.mint, paperMode = false)",
    "SellFinalizationCoordinator explicit LIVE canonical lookup",
)
write(finalizer_path, finalizer)


# 5) Pin the mode-safe identity contract so future patch stacking cannot regress
# it back to mint-only while still producing a green build.
mode_test_path = TEST / "Aate6686ModeSafeCanonicalIdentityTest.kt"
write(mode_test_path, r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6686ModeSafeCanonicalIdentityTest {
    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    @Test fun `executor canonical mirror identity is mode plus mint`() {
        val mirror = src("com/lifecyclebot/engine/truth/ExecutorCanonicalMirror6442.kt")
        assertTrue(mirror.contains("activePositionIdByModeMint"))
        assertTrue(mirror.contains("modeKey(mint: String, paperMode: Boolean)"))
        assertTrue(mirror.contains("positionIdOf(mint: String, paperMode: Boolean? = null)"))
        assertTrue(mirror.contains("positionIdOf(mint, paperMode)"))
        assertTrue(mirror.contains("it.mode.equals(mode, ignoreCase = true)"))
        assertTrue(mirror.contains("CANONICAL_POSITION_MODE_AMBIGUITY_6686"))
        assertFalse(mirror.contains("activePositionIdByMint"))
        assertFalse(mirror.contains("lastClosedPositionIdByMint"))
    }

    @Test fun `live sell finalizer uses explicit live canonical identity`() {
        val sell = src("com/lifecyclebot/engine/sell/SellFinalizationCoordinator.kt")
        assertTrue(sell.contains("positionIdOf(intent.mint, paperMode = false)"))
    }
}
''')

print("V5.0.6686 final authority/test contract repair staged successfully")
