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

Six regression tests cover exact-slice loss rejection, later quote deterioration, milestone/risk classification, and the previously omitted runtime call sites. Required CI includes Aate8168. Run release compile, required/full regression baseline, ART install/upgrade and runtime smoke before release acceptance.

## Export expansion requested during review

Added a bounded improvement-evidence section to the existing copy/export report. Hunter instrumentation reuses the actual band/mandate filter and ranking results without re-running decisions on export. Per lane: total/in-band/mandate-pass/selected counts, unknown input counts, three selected full mint IDs with cap, liquidity, age, activity and provider provenance. Cortex context counts distinguish reason-based executable proxies from native lane acceptance; no learning policy is changed. Targeted queue, admission delivery, profit-check and learner-exclusion counters are exported even when the general counter list truncates them. Counts explicitly identify their scope and repeated-event semantics. Missing data is not rendered as a healthy zero. Report formatting/limits are regression-tested.

## Native discovery contract mismatch

BLUECHIP hunter alone excluded all pump-suffixed mint addresses. BlueChipTraderAI's native filter has no suffix restriction: its market-cap, liquidity and quality tests own acceptance. Removed the hunter-only suffix exclusion while preserving the cap band and positive finite liquidity, with a regression fixture for a $2M graduated token and invalid liquidity/low-cap controls. This restores candidate consideration, not automatic live admission.
