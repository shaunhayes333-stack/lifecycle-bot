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
        raise SystemExit(f"6686 missing anchor: {label}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 1) Token-account wallet snapshots must use the SAME provider authority that
#    6685 installed for normal RPC.  Old patch-rot left this path on the legacy
#    fallback list and returned a JSON-RPC provider error as if it were a valid
#    terminal response, poisoning wallet reconciliation.
# ---------------------------------------------------------------------------
wallet_path = MAIN / "network/SolanaWallet.kt"
wallet = read(wallet_path)

fn_pat = re.compile(
    r"    private fun walletRpcEndpointsForTokenSnapshot\(\): List<String> \{.*?\n    \}\n\n    private fun rpcTokenAccountsByOwnerFast",
    re.S,
)
m = fn_pat.search(wallet)
if not m:
    raise SystemExit("6686 walletRpcEndpointsForTokenSnapshot block missing")
new_fn = '''    private fun walletRpcEndpointsForTokenSnapshot(): List<String> {
        // V5.0.6686 — PATCH-ROT REPAIR. 6685 migrated generic RPC calls to the
        // encrypted runtime provider authority but this token-account path was
        // accidentally left on rpcUrl + WalletManager.FALLBACK_RPCS. That made
        // wallet reconciliation use a different provider ladder from execution.
        // One authority, one round-robin/cooldown policy, no credential drift.
        val candidates = com.lifecyclebot.engine.RuntimeProviderAuthority6685
            .rpcCandidates(rpcUrl)
        return applyRoundRobin(candidates)
    }

    private fun rpcTokenAccountsByOwnerFast'''
wallet = wallet[:m.start()] + new_fn + wallet[m.end():]

start = wallet.find("    private fun rpcTokenAccountsByOwnerFast")
end = wallet.find("    private fun heliusDasFungibleTokensByOwner", start)
if start < 0 or end < 0:
    raise SystemExit("6686 token-account RPC function boundaries missing")
segment = wallet[start:end]
err_pat = re.compile(
    r'''                val err = json\.optJSONObject\("error"\)\n                if \(err != null\) \{.*?\n                \}\n                return json''',
    re.S,
)
em = err_pat.search(segment)
if not em:
    raise SystemExit("6686 token-account JSON-RPC error block missing")
new_err = '''                val err = json.optJSONObject("error")
                if (err != null) {
                    // V5.0.6686 — ANY JSON-RPC error from getTokenAccountsByOwner
                    // is an endpoint failure, never a usable wallet snapshot.
                    // In 6685 an unsupported/free-plan provider returned
                    // "chain is not available on free plan"; because it was not
                    // classified as auth/rate-limit, the function returned that
                    // JSON immediately and never tried the next healthy RPC.
                    val msg = err.optString("message", err.toString())
                    markEndpointUnhealthy(endpoint, "RPC:${msg.take(24)}")
                    failures.add("${endpoint.take(24)}:RPC:${msg.take(48)}")
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "WALLET_RPC_PROVIDER_ERROR_FAILOVER_6686",
                            "program=${programId.take(8)} endpoint=${endpoint.take(48)} err=${msg.take(96)} action=continue_next_rpc",
                        )
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_RPC_PROVIDER_ERROR_FAILOVER_6686")
                    } catch (_: Throwable) {}
                    continue
                }
                return json'''
segment = segment[:em.start()] + new_err + segment[em.end():]
wallet = wallet[:start] + segment + wallet[end:]
write(wallet_path, wallet)


# ---------------------------------------------------------------------------
# 2) Wallet-held positive balances need a canonical LIVE position when a real
#    persisted/finalized buy basis exists. WalletReconciler previously rebuilt
#    only status.tokens, leaving HostWalletTokenTracker > canonical LIVE and
#    therefore leaving real bags outside canonical exit/UI authority.
# ---------------------------------------------------------------------------
recovery_path = MAIN / "engine/LiveCanonicalRecovery6686.kt"
write(recovery_path, r'''package com.lifecyclebot.engine

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import java.math.BigInteger

/**
 * V5.0.6686 — wallet-positive -> canonical LIVE recovery bridge.
 *
 * This does NOT invent an entry basis. A wallet mint is promoted into canonical
 * LIVE authority only when the existing runtime position, persisted position,
 * or finalized canonical buy fill proves a positive cost + entry price.
 * Unknown-basis wallet rows remain visible to HostWalletTokenTracker recovery
 * and are never made trainable by this bridge.
 */
object LiveCanonicalRecovery6686 {
    const val VERSION = "V5.0.6686_LIVE_CANONICAL_RECOVERY"

    private data class Basis(
        val entryCostSol: Double,
        val entryPriceUsd: Double,
        val lane: String,
        val openedAtMs: Long,
        val source: String,
        val pool: String,
        val dex: String,
        val identity: String,
    )

    fun recoverWalletSnapshot(
        status: BotStatus,
        walletMints: Map<String, CanonicalTokenAmount>,
    ): Int {
        if (walletMints.isEmpty()) return 0
        val existingLive = try {
            CanonicalPositionAuthority6441.activeMintProjections6490("live")
                .map { it.mint }.toMutableSet()
        } catch (_: Throwable) { mutableSetOf<String>() }
        val persisted = try { PositionPersistence.loadPositions() } catch (_: Throwable) { emptyMap() }
        var repaired = 0

        for ((mint, amount) in walletMints) {
            if (mint.isBlank() || amount.raw <= BigInteger.ONE || existingLive.contains(mint)) continue
            val ts = try { status.tokens[mint] } catch (_: Throwable) { null }
            val runtimePos = ts?.position
            val saved = persisted[mint]

            val basis: Basis? = when {
                runtimePos != null && !runtimePos.isPaperPosition &&
                    runtimePos.costSol.isFinite() && runtimePos.costSol > 0.0 &&
                    runtimePos.entryPrice.isFinite() && runtimePos.entryPrice > 0.0 -> Basis(
                        entryCostSol = runtimePos.costSol,
                        entryPriceUsd = runtimePos.entryPrice,
                        lane = runtimePos.tradingMode.ifBlank { "WALLET_RECOVERED" },
                        openedAtMs = runtimePos.entryTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        source = runtimePos.entryPriceSource.ifBlank { "RUNTIME_POSITION_BASIS_6686" },
                        pool = runtimePos.entryPoolAddress,
                        dex = runtimePos.entryDex,
                        identity = runtimePos.positionId.ifBlank { "runtime" },
                    )

                saved != null && !saved.isPaperPosition &&
                    saved.costSol.isFinite() && saved.costSol > 0.0 &&
                    saved.entryPrice.isFinite() && saved.entryPrice > 0.0 -> Basis(
                        entryCostSol = saved.costSol,
                        entryPriceUsd = saved.entryPrice,
                        lane = saved.tradingMode.ifBlank { "WALLET_RECOVERED" },
                        openedAtMs = saved.entryTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        source = saved.entryPriceSource.ifBlank { "PERSISTED_POSITION_BASIS_6686" },
                        pool = saved.entryPoolAddress,
                        dex = saved.entryDex,
                        identity = saved.positionId.ifBlank { "persisted" },
                    )

                else -> {
                    val fill = try { CanonicalBuyFillRegistry.get(mint) } catch (_: Throwable) { null }
                    if (fill != null && fill.solSpentNet.isFinite() && fill.solSpentNet > 0.0) {
                        val usd = when {
                            fill.entryPriceUsd.isFinite() && fill.entryPriceUsd > 0.0 -> fill.entryPriceUsd
                            fill.entryPriceSol.isFinite() && fill.entryPriceSol > 0.0 -> {
                                val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
                                if (solUsd > 0.0) fill.entryPriceSol * solUsd else 0.0
                            }
                            else -> 0.0
                        }
                        if (usd > 0.0) Basis(
                            entryCostSol = fill.solSpentNet,
                            entryPriceUsd = usd,
                            lane = fill.lane.ifBlank { "WALLET_RECOVERED" },
                            openedAtMs = fill.entryTsMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            source = "CANONICAL_BUY_FILL_RECOVERY_6686",
                            pool = "",
                            dex = "",
                            identity = fill.buySignature.ifBlank { "fill" },
                        ) else null
                    } else null
                }
            }

            if (basis == null) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686",
                        "mint=${mint.take(12)} raw=${amount.raw} decimals=${amount.decimals} action=retain_wallet_tracking_no_invented_basis",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686")
                } catch (_: Throwable) {}
                continue
            }

            val safeIdentity = basis.identity.replace(Regex("[^A-Za-z0-9]"), "").takeLast(14).ifBlank { "basis" }
            val positionId = runtimePos?.positionId?.takeIf { it.isNotBlank() }
                ?: "LIVE_RECOVERED_6686:${mint.take(16)}:$safeIdentity"
            val result = try {
                CanonicalPositionAuthority6441.openPosition(
                    idempotencyKey = "LIVE_WALLET_CANONICAL_RECOVERY_6686:$mint:$safeIdentity",
                    positionId = positionId,
                    mint = mint,
                    symbol = ts?.symbol?.ifBlank { mint.take(8) } ?: mint.take(8),
                    lane = basis.lane,
                    runId = "RECOVERY_6686",
                    entryCostSol = basis.entryCostSol,
                    openedQtyRaw = amount.raw,
                    tokenDecimals = amount.decimals,
                    feesSol = 0.0,
                    paperMode = false,
                    modeOverride = "live",
                    entryPriceUsd = basis.entryPriceUsd,
                    entryPriceSource = basis.source,
                    entryPoolAddress = basis.pool,
                    entryDex = basis.dex,
                    quantityScale = amount.decimals,
                )
            } catch (_: Throwable) { CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION }

            if (result == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                existingLive.add(mint)
                repaired++
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_POSITION_RECOVERED_6686",
                        "mint=${mint.take(12)} pid=${positionId.take(28)} lane=${basis.lane} raw=${amount.raw} decimals=${amount.decimals} cost=${basis.entryCostSol} source=${basis.source}",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_POSITION_RECOVERED_6686")
                } catch (_: Throwable) {}
            } else if (result != CanonicalPositionAuthority6441.MutateResult.DUPLICATE) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_RECOVERY_REJECTED_6686",
                        "mint=${mint.take(12)} result=$result action=retain_wallet_tracking",
                    )
                } catch (_: Throwable) {}
            }
        }
        return repaired
    }
}
''')

reconciler_path = MAIN / "engine/WalletReconciler.kt"
reconciler = read(reconciler_path)
marker = "LIVE_CANONICAL_RECOVERY_6686"
if marker not in reconciler:
    anchor = "        // ── Pass 2: zombie closure ──────────────────────────────────────────\n"
    insert = '''        // V5.0.6686 — after status/HostWallet recovery, bind every wallet-positive
        // LIVE holding with proven economic basis back into the canonical authority.
        // This closes the HostWalletTokenTracker > canonical LIVE gap that made real
        // bags disappear from canonical exit/UI management after token-map drift.
        try {
            val canonicalRecovered6686 = LiveCanonicalRecovery6686.recoverWalletSnapshot(status, walletMints)
            changes += canonicalRecovered6686
            if (canonicalRecovered6686 > 0) {
                ForensicLogger.lifecycle("LIVE_CANONICAL_RECOVERY_6686", "recovered=$canonicalRecovered6686 walletMints=${walletMints.size}")
            }
        } catch (t: Throwable) {
            try { ForensicLogger.lifecycle("LIVE_CANONICAL_RECOVERY_FAILED_6686", "err=${t.message?.take(120)} action=retain_wallet_tracking") } catch (_: Throwable) {}
        }

'''
    reconciler = replace_once(reconciler, anchor, insert + anchor, "WalletReconciler canonical recovery")
write(reconciler_path, reconciler)


# ---------------------------------------------------------------------------
# 3) UI open-position projection must be canonical-first. The old 6496 cache
#    rebuilt its list from status.tokens and QuantityInvariantAuthority, so a
#    pruned/stale token-map row disappeared from UI even while canonical held it.
# ---------------------------------------------------------------------------
ui_proj_path = MAIN / "engine/truth/CanonicalUiPositionProjection6686.kt"
write(ui_proj_path, r'''package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState

/** V5.0.6686 — immutable canonical position -> UI TokenState projection. */
object CanonicalUiPositionProjection6686 {
    const val VERSION = "V5.0.6686_CANONICAL_UI_POSITION_PROJECTION"

    fun project(status: BotStatus): List<TokenState> {
        val canonical = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
        if (canonical.isEmpty()) return emptyList()
        val byMint: Map<String, TokenState> = try {
            status.tokens.entries.associate { it.key to it.value }
        } catch (_: Throwable) {
            try { status.tokens.values.toList().associateBy { it.mint } } catch (_: Throwable) { emptyMap() }
        }

        return canonical.mapNotNull { p ->
            try {
                val qty = if (p.quantityScale in 0..18)
                    p.remainingQtyRaw.toBigDecimal().movePointLeft(p.quantityScale).toDouble()
                else 0.0
                if (!qty.isFinite() || qty <= 0.0) return@mapNotNull null
                val remainingCost = (p.entryCostSol - p.soldCostBasisSol).coerceAtLeast(0.0)
                val existing = byMint[p.mint]
                val base = existing?.position
                val projectedPosition = if (base != null) {
                    base.copy(
                        qtyToken = qty,
                        entryPrice = p.entryPriceUsd,
                        entryTime = p.openedAtMs,
                        costSol = remainingCost,
                        highestPrice = base.highestPrice.takeIf { it > 0.0 } ?: p.entryPriceUsd,
                        lowestPrice = base.lowestPrice.takeIf { it > 0.0 } ?: p.entryPriceUsd,
                        entryPriceSource = p.entryPriceSource,
                        entryPoolAddress = p.entryPoolAddress,
                        entryDex = p.entryDex,
                        isPaperPosition = p.mode.equals("paper", true),
                        tradingMode = p.lane,
                        positionId = p.positionId,
                        pendingVerify = false,
                    )
                } else {
                    Position(
                        qtyToken = qty,
                        entryPrice = p.entryPriceUsd,
                        entryTime = p.openedAtMs,
                        costSol = remainingCost,
                        highestPrice = p.entryPriceUsd,
                        lowestPrice = p.entryPriceUsd,
                        entryPhase = "CANONICAL_UI_RECOVERY_6686",
                        entryPriceSource = p.entryPriceSource,
                        entryPoolAddress = p.entryPoolAddress,
                        entryDex = p.entryDex,
                        isPaperPosition = p.mode.equals("paper", true),
                        tradingMode = p.lane,
                        tradingModeEmoji = "🔗",
                        positionId = p.positionId,
                        pendingVerify = false,
                    )
                }
                if (existing != null) {
                    existing.copy(position = projectedPosition)
                } else {
                    TokenState(
                        mint = p.mint,
                        symbol = p.symbol.ifBlank { p.mint.take(8) },
                        name = "Canonical Position",
                        source = "CANONICAL_UI_PROJECTION_6686",
                        position = projectedPosition,
                    )
                }
            } catch (t: Throwable) {
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "CANONICAL_UI_POSITION_PROJECTION_FAILED_6686",
                        "mint=${p.mint.take(12)} pid=${p.positionId.take(24)} err=${t.message?.take(100)}",
                    )
                } catch (_: Throwable) {}
                null
            }
        }
    }
}
''')

ui_auth_path = MAIN / "engine/truth/UiSnapshotAuthority6496.kt"
ui_auth = read(ui_auth_path)
refresh_pat = re.compile(
    r"    private fun refresh\(status: BotStatus\) \{.*?\n        cached\.set\(Snapshot\(list, System\.currentTimeMillis\(\)\)\)\n    \}",
    re.S,
)
rm = refresh_pat.search(ui_auth)
if not rm:
    raise SystemExit("6686 UiSnapshotAuthority refresh block missing")
new_refresh = '''    private fun refresh(status: BotStatus) {
        refreshes.incrementAndGet()
        // V5.0.6686 — canonical-first. Do not rebuild open inventory from
        // status.tokens: that map is discovery/render state and can be pruned.
        val list = try {
            CanonicalUiPositionProjection6686.project(status)
        } catch (_: Throwable) { emptyList() }
        cached.set(Snapshot(list, System.currentTimeMillis()))
    }'''
ui_auth = ui_auth[:rm.start()] + new_refresh + ui_auth[rm.end():]
write(ui_auth_path, ui_auth)

vm_path = MAIN / "ui/BotViewModel.kt"
vm = read(vm_path)
vm = replace_once(
    vm,
    "            val openSnap = try { status.openPositions.toList() } catch (_: Throwable) { emptyList() }",
    "            val openSnap = try { com.lifecyclebot.engine.truth.CanonicalUiPositionProjection6686.project(status) } catch (_: Throwable) { emptyList() }",
    "BotViewModel immediate canonical open snapshot",
)
vm = replace_once(
    vm,
    "        val openSnapshot = try { status.openPositions.toList() } catch (_: Throwable) { emptyList() }",
    "        val openSnapshot = try { com.lifecyclebot.engine.truth.CanonicalUiPositionProjection6686.project(status) } catch (_: Throwable) { emptyList() }",
    "BotViewModel polling canonical open snapshot",
)
write(vm_path, vm)


# ---------------------------------------------------------------------------
# 4) Specialist capital report must not print PAPER balance as LIVE authority.
#    This is reporting only; it does not alter order size or admission policy.
# ---------------------------------------------------------------------------
toolkit_path = MAIN / "engine/ToolkitSignalSheet.kt"
toolkit = read(toolkit_path)
old_capital = '''        val capital = try { com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null }
        val sharedCash = capital?.availableCashSol ?: 0.0
        val sharedEquity = capital?.totalEquitySol ?: sharedCash'''
new_capital = '''        val paperMode6686 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }
        val capital = if (paperMode6686) try { com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null } else null
        val sharedCash = if (paperMode6686) capital?.availableCashSol ?: 0.0 else try { BotService.status.walletSol.coerceAtLeast(0.0) } catch (_: Throwable) { 0.0 }
        val sharedEquity = if (paperMode6686) capital?.totalEquitySol ?: sharedCash else sharedCash
        val capitalSource6686 = if (paperMode6686) "PAPER_CAPITAL_AUTHORITY_6577" else "LIVE_WALLET_AUTHORITY_6686"'''
toolkit = replace_once(toolkit, old_capital, new_capital, "specialist capital mode authority")
toolkit = replace_once(
    toolkit,
    "allocationDecisionSource=PAPER_CAPITAL_AUTHORITY_6577+LANE_EXPECTANCY+OPPORTUNITY_PRESSURE",
    "allocationDecisionSource=${capitalSource6686}+LANE_EXPECTANCY+OPPORTUNITY_PRESSURE",
    "specialist capital source label",
)
write(toolkit_path, toolkit)


# ---------------------------------------------------------------------------
# 5) Source-level regression shield. These tests intentionally pin the exact
#    cross-mode/provider/UI contracts that regressed in 6685.
# ---------------------------------------------------------------------------
test_path = TEST / "Aate6686LiveAuthorityRecoveryTest.kt"
write(test_path, r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6686LiveAuthorityRecoveryTest {
    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    @Test fun `token account snapshots use runtime provider authority and rotate every rpc error`() {
        val wallet = src("com/lifecyclebot/network/SolanaWallet.kt")
        val fn = wallet.substringAfter("private fun walletRpcEndpointsForTokenSnapshot")
            .substringBefore("private fun rpcTokenAccountsByOwnerFast")
        assertTrue(fn.contains("RuntimeProviderAuthority6685") && fn.contains("rpcCandidates(rpcUrl)"))
        assertTrue(fn.contains("applyRoundRobin(candidates)"))
        assertFalse(fn.contains("WalletManager.FALLBACK_RPCS"))
        val fast = wallet.substringAfter("private fun rpcTokenAccountsByOwnerFast")
            .substringBefore("private fun heliusDasFungibleTokensByOwner")
        assertTrue(fast.contains("WALLET_RPC_PROVIDER_ERROR_FAILOVER_6686"))
        assertTrue(fast.contains("action=continue_next_rpc"))
    }

    @Test fun `wallet positive live positions recover into canonical authority only with proven basis`() {
        val bridge = src("com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt")
        val reconciler = src("com/lifecyclebot/engine/WalletReconciler.kt")
        assertTrue(bridge.contains("activeMintProjections6490(\"live\")"))
        assertTrue(bridge.contains("PositionPersistence.loadPositions()"))
        assertTrue(bridge.contains("CanonicalBuyFillRegistry.get(mint)"))
        assertTrue(bridge.contains("modeOverride = \"live\""))
        assertTrue(bridge.contains("openedQtyRaw = amount.raw"))
        assertTrue(bridge.contains("LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686"))
        assertTrue(reconciler.contains("LiveCanonicalRecovery6686.recoverWalletSnapshot(status, walletMints)"))
    }

    @Test fun `ui positions project from canonical authority not mutable discovery token map`() {
        val projection = src("com/lifecyclebot/engine/truth/CanonicalUiPositionProjection6686.kt")
        val authority = src("com/lifecyclebot/engine/truth/UiSnapshotAuthority6496.kt")
        val vm = src("com/lifecyclebot/ui/BotViewModel.kt")
        assertTrue(projection.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(authority.contains("CanonicalUiPositionProjection6686.project(status)"))
        assertFalse(authority.substringAfter("private fun refresh(status: BotStatus)").substringBefore("fun current()").contains("status.tokens.values"))
        assertTrue(vm.split("CanonicalUiPositionProjection6686.project(status)").size - 1 >= 2)
    }

    @Test fun `live specialist report cannot source capital from paper ledger`() {
        val toolkit = src("com/lifecyclebot/engine/ToolkitSignalSheet.kt")
        val report = toolkit.substringAfter("fun specialistCapitalReport6599()")
            .substringBefore("fun contributionSummary")
        assertTrue(report.contains("paperMode6686"))
        assertTrue(report.contains("LIVE_WALLET_AUTHORITY_6686"))
        assertTrue(report.contains("capitalSource6686"))
    }
}
''')

print("V5.0.6686 live authority repair staged successfully")
