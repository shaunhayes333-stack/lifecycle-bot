# 9064 — price-stop validation, launch dependency and native warm-up

## Evidence

The 28 November 20:09 Brisbane snapshot is 9063, 1,201 seconds into its session, with one completed LIVE MOONSHOT trade. Its buy was recorded at 20:06:05.370 and sell at 20:07:37.777 (92.407 seconds), with `THIN_LIQ_EARLY_RUG_BACKSTOP_-10`. Gross proceeds minus sold basis were -0.001830 SOL before additional fees. The launch clock linked the buy at +579 seconds to a 30-second opportunity. That diagnostic link alone does not prove expired launch evidence authorised the buy: later independent entries are intentionally tracked against the earlier fire too.

Native research still reported zero models/predictions, indexing 2,240 rows; 498 of 678 completed windows were censored. Heap was NORMAL at 322/512 MB, but the first Pump Portal lane decision took p50 13 seconds / p90 132 seconds. This evidence does not prove that widening stops, loosening safety or globally increasing hold times would help.

## Repairs

- The final quote validator excluded every reason containing `RUG`, including the thin-pool backstop already classified elsewhere as a price stop. That specific named stop now reaches executable quote revalidation even when its display mark recovered. Real rug/operator exits retain their exemption.
- The thin-pool risk check calls `doSell` directly, skipping `requestSellCore7948`'s venue check. Its final sell path now applies the existing venue contradiction check and bounded read wait. Confirmed losses still sell; the existing 6-second own-venue wait, 30-second maximum unread wait and catastrophic escape remain unchanged. No ordinary stop or holding threshold is widened.
- Selected launch-rule market evidence now explicitly marks the sealed decision as launch-dependent even if another admission branch did not append a launch trace. Its original deadline remains enforced at first broadcast.
- A later independent entry linked to an earlier launch no longer trains the launch strategy's trigger-to-fill latency. Legacy linked-arrival counters remain, with explicit launch/independent support in the report and fill trace. Independent setups remain eligible on their own evidence.
- Armed-launch, evidence-wait, chain-answer and stage-answer callbacks enqueue using the existing configuration fallback. The async fast-lane worker still reloads current configuration and runtime mode. This removes four duplicate synchronous configuration reads from feed/answer callbacks; it does not skip decision gates.
- Native journal indexing advances up to 1,024 rows per turn under the existing byte/retention limits. Already indexed scopes can attempt training before catch-up finishes. The 300-row candidate count, asset independence, chronological holdouts, scope boundaries, censoring and model-authority requirements are unchanged. This removes the full-file catch-up dependency, not the requirement for sufficient valid evidence.

## Validation

274 standalone production-model tests pass locally. New Android/JVM regressions cover the thin-pool reason through actual quote validation, contradictory vs breached quotes, bounded unread escape, independent vs dependent launch fills and deadlines, callback/final-door wiring, and the production trainer attempting a scope while the journal still has an unread tail without promoting duplicate assets.

Android compilation, signing/security, required and full historical tests, in-place upgrade and device runtime checks must pass on main before releasing the accepted APK. This change does not establish profitable live performance or resolve every source of missing observations.
