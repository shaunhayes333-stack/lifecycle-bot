# 8167 repair evidence and remaining limits

Reference builds: 8114 (379ad2fa0), 8117 (372aff553). Repair base: 8166 (7fe46e8ee).
Guidance read: docs/FIELD_MANUAL.md; docs/EXPERT_CRYPTO_TRADER_CHEAT_SHEET.md; Cortex plan sections A-I; REPAIR_LEDGER_8128_8142.md.

## Confirmed defects repaired

- TokenMapAuthority: unchanged executable route flags invalidated their own cache generation. Compare transitions, retaining invalidation for new price/liquidity/pool/migration evidence.
- LaunchTape: decoded trades without a PumpPortal create frame were discarded despite witnessed chain birth. Recover only from observed pump-create timestamps inside the existing TTL.
- LanePlaybook: existing shape fields prevented reading available swap provenance. Always read the cached trade tape; missing fields alone use its feature fallback.
- AdmissionHandoff / HandoffReceiverTasks: unhydrated and stale-price reasons could settle a receiver before data arrived. Preserve DATA states across the ledger and runtime adapter; existing expiry, safety and ownership constraints remain.
- StructureTracker / EntryStrategySnapshot: strictly pre-fill prints inside an open bucket were ignored; cost capture also used mutable global mode. Read eligible prints without future leakage and use sealed entry mode.
- ForwardReturnLabeler: curve and high-step checks ran after entry rebasing. Reject untrusted prices before changing entry, rebase state or peak history.
- SizingQuoteRecheck / Executor: final Jupiter quote output was not used to detect execution-price deterioration. Check the actual quote as a lower bound on drag alongside existing market movement and full round-trip costs. Do not add quote-inclusive fees/impact to those costs twice.
- Finalized bus: report recent live per-position exclusion reasons so historical replay totals cannot conceal current missing learner delivery.

- Runtime smoke exposed three committed paper stock partials absent from the journal: the training-only 8% partial-label floor was incorrectly used as an accounting rejection. Accounting now shares the same basis and return-math checks without the training-label predicate; learner eligibility remains unchanged. The full-suite gate before this extra fix was 5,276 tests with zero new/changed failures. Release startup was separately blocked by a Pixel Launcher ANR, and is retried unchanged.

## Investigated; not treated as proven code regressions

- Zero complete feature reads means all eight features were not simultaneously present, including launch-specific tape. It is not zero valid inputs or zero decisions.
- Two canonical closes versus one strategy-window close requires the device's exact eligibility reason. Do not fabricate training samples or bypass proof/quarantine requirements.
- UnifiedExitPolicyHead excludes missing/nonpositive MFE because its current target is capture of an observed favorable excursion. This is not proof all loss learning stopped.
- Lane-evaluation/intake ratios mix repeat evaluations with intake events; the per-candidate executable fanout invariant was zero.
- Runner hold/rung changes after 8117 were intentional repairs for overlapping sellers and unverified peaks. No blanket rollback.
- CurveTicks already benches accountSubscribe method-not-found endpoints and reconnects through existing configured RPCs. The snapshot shows a subsequent open connection with one subscription; zero ticks alone does not prove that replacement endpoint failed.
- PumpPortal free launch/migration subscriptions already exist; paid token-trade capability requires a funded linked key. No credentials or funds were changed.
- Route-minimum sizing and fees can consume small moves. Existing measured edge and cost checks remain, without increasing size or weakening entry thresholds.
- Historical winners cannot be faithfully replayed from summary percentages alone; exact on-device decision snapshots, marks and confirmed fills are not in this repository.

## External references checked

- https://developers.jup.ag/docs/api-reference/swap/v1/quote : outAmount includes AMM/platform fees, excludes slippage.
- https://pumpportal.fun/data-api/real-time/ : one websocket; free create/migration, keyed funded token-trade streams.
- https://www.helius.dev/solana-webhooks-websockets/ : account subscription capabilities.

## Verification

Nine targeted 8167 tests are in the required GitHub test selection. Existing historical regression and Android install/upgrade checks remain enabled. Passing source guards is not a substitute for those checks. Profitability and live learner delivery require subsequent device evidence; no reset of learning or claimed restoration of historical profits.
