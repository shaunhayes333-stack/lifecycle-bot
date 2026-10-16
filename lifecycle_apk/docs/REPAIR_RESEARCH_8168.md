# 8168: executable profit checks and emergency proof polling

Evidence: device 8167 / 37382ebe1, report 229, 1192 seconds uptime. Three partial receipts were labelled PARTIAL_BELOW_PROFIT_FLOOR_LOSS while profitQuoteRefused8163 remained zero. One hard stop recorded 21.325 seconds trigger-to-broadcast, including 19.220 seconds queue time. These observations identify investigation targets; aggregate telemetry cannot attribute every millisecond to a particular call.

Read the existing expert cheat sheet and field manual before changing execution. Preserve confirmed accounting, exact sold-slice basis, safety exits, and signed transaction identity. No entry threshold or learning reset changes.

## Confirmed wiring defects

ProfitExitTruth8163 was called only by executeProfitLockSell. checkPartialSell, requestPartialSell, and liveSellInScope7314 could build a Jupiter transaction without checking executable SOL against the exact confirmed slice cost. Added checks before transaction construction, inside re-quote loops where present. Added the emitted runner/tail/lifecycle milestone reason families to profit classification. Risk/manual/orphan reasons remain exempt.

Raydium serial and racing routes check their own quoted proceeds and raw quantity. Direct PumpPortal attempts carry the original exit reason and rerun the existing profit preflight. This preflight uses Jupiter as a corroborating economic quote; it is not proof of the direct route's eventual settlement. Accounting continues to record actual finalized proceeds, including losses. Route-level refusals do not release the enclosing worker's locks while it may try another route.

BalanceProofPoller discarded desiredExitReason when resolving a holding. It now calls the existing resolveForExit authority, preserving the emergency path's confirmed-proof reuse and existing freshness/absence protections. This removes a demonstrated bypass, but does not prove that all observed queue latency is resolved.

## Remaining evidence gaps

Launch tape is still empty while other trade tapes receive prints. Its recovery depends on a direct witnessed birth within the existing 45-minute window; add distinct missing-birth, outside-window, capacity, and recovered counters rather than manufacturing a fresh birth from discovery time. The next device report can distinguish these cases.

Milady's missed-runner diagnostic names AT_LAUNCH_CAP_8047 and a different summary reason. Do not loosen admission based on the later peak alone. Need decision-time evidence and the actual trace row to establish eligibility.

Old missing-signature reconciliation exceptions, missing historical cost legs, and current sell-queue/finality delays remain under investigation. Neither profit restoration nor complete remediation is established by these changes.

## Validation

Eight regression tests cover exact-slice loss rejection, later quote deterioration, milestone/risk classification, and the previously omitted runtime call sites. Required CI includes Aate8168. Run release compile, required/full regression baseline, ART install/upgrade and runtime smoke before release acceptance.

## Export expansion requested during review

Added a bounded improvement-evidence section to the existing copy/export report. Hunter instrumentation reuses the actual band/mandate filter and ranking results without re-running decisions on export. Per lane: total/in-band/mandate-pass/selected counts, unknown input counts, three selected full mint IDs with cap, liquidity, age, activity and provider provenance. Cortex context counts distinguish reason-based executable proxies from native lane acceptance; no learning policy is changed. Targeted queue, admission delivery, profit-check and learner-exclusion counters are exported even when the general counter list truncates them. Counts explicitly identify their scope and repeated-event semantics. Missing data is not rendered as a healthy zero. Report formatting/limits are regression-tested.

## Native discovery contract mismatch

BLUECHIP hunter alone excluded all pump-suffixed mint addresses. BlueChipTraderAI's native filter has no suffix restriction: its market-cap, liquidity and quality tests own acceptance. Removed the hunter-only suffix exclusion while preserving the cap band and positive finite liquidity, with a regression fixture for a $2M graduated token and invalid liquidity/low-cap controls. This restores candidate consideration, not automatic live admission.

## Missing setup input repair and all-lane audit

All twelve meme lane profiles exist in LaneHunter7297. ResidentHunterWorker hands nine supported overlay names into the separate Crypto Universe books (CASHGEN maps to TREASURY); this is not evidence of stocks/forex/perps hunter coverage. The device reports those producers at zero and crypto candidates blocked downstream. Do not label these as healthy all-trader delivery without tracing their enablement and receivers.

Confirmed shape repair defect: the missing-input callback re-seeded candles, but both primary provider writes required history.size < 10. A full stale or invalid history could never accept a valid repair. Missing-shape requests now explicitly enter repair mode, recheck the current history under its lock, and replace only an unobserved shape with ordered, non-synthetic, positive, fresh provider candles. A concurrently refreshed valid history is preserved. Failed refresh leaves evidence missing; repair never promotes 4H candles into the 1m shape. Existing provider budgets remain. Regression covers stale full history, concurrent fresh history, stale fetched bars, reversed timestamps, synthetic bars and insufficient history.

Export now also includes latest per-lane setup classification with full mint, timestamp, exact missing-field states and classifier reason, plus targeted hydration and hunter-delivery counters. Missing refresher registration and thrown callbacks are explicitly counted. This does not establish that every missing-input cause is repaired, nor change Cortex grading authority.

## Native-reader scope continuity

A first resident claim previously replaced the entire bounded bootstrap native-reader scope. Resident claims now augment that existing scope, and resident membership participates in the native cache fingerprint. Thus a new claim cannot remove previously eligible native readers or remain invisible behind an unchanged cached snapshot. Twelve valid resident lanes can all produce their own native opinions. The primary-plus-one-rescue execution election, enabled-trader authority, safety and FDG are unchanged. Tests assert monotonic scope, unknown-lane exclusion, and all twelve hunter/native-book lane identities. This does not claim all cross-asset traders are enabled or that every candidate is suitable for every lane.
