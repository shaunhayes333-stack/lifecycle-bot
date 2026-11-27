# 9062: held exit identity, witnessed price supply and stop clocks

## Price and data repairs included

The legacy sell-journal canonical-fill override substituted SOL/token directly
when USD/token was missing. It now accepts only finite positive historical USD
evidence from the fill and otherwise retains the row's existing USD snapshot.
No present-day exchange rate is used to invent a historical price. Confirmed SOL
cost/proceeds accounting is unchanged. `Aate9062JournalUnitsTest` covers a fill
with a valid SOL price but missing/invalid USD and its actual journal consumer.

The label collector's legacy endpoint view treats OFFWATCH as a market read and
then selects the newest candidate. A newer provider snapshot can therefore hide
a still-fresh independent chain mark. Native research subsequently rejects the
dependent winner, despite a usable source already having been collected.

`NativePriceEvidence9062` selects a separate, strictly witnessed candidate from
that same collected list. It preserves event and publication clocks, USD units,
asset identity, source proof, venue and decimals. Missing decimals may use the
existing mint observation only if that evidence was available by decision time.
Conflicts, unknowns and marks older than five seconds remain unusable. The cache
is capped at 2,048 assets and never refreshes a mark's event time on lookup.
Native decision/path capture can recover this candidate when its supplied mark
fails proof. The legacy endpoint price choice is unchanged. `recoveredProof9062` counts uses,
not unique observations or demonstrated profitability.

If an actionable LIVE observation still lacks proof, the existing ChainConfirm8950
queue verifies price/depth from the curve or pool and reads the mint only when
decimals are unknown or conflicting. Missing depth that prevents cost assessment
uses that same mechanism. Requests share the existing mint/input coalescing and
dispatch budgets across lanes; no polling worker or new RPC client is added.
The price answer wakes the existing pipeline callback, rather than waiting for
the normal scan. Reassessment creates a fresh decision: later evidence never
retroactively fills the rejected observation. Passive and PAPER research do not
request these additional reads. Classic crypto's actionable Solana observations
use this same verification path when the mark is absent.
The crypto trader's live pre-submit price wait also requests this shared chain
verification, since that early return occurs before LiveEdgeGate. Non-Solana
identities remain with their own providers; the Solana queue rejects them.

The prior native venue check accepted any string at least 32 characters long,
including `MINT_ROUTE:<mint>`. Native enrollment, witnessed-trade capture and
journal reload now require the explicit curve identity or a base58 pool-address
shape. This does not replace on-chain account validation; it prevents a routing
alias from masquerading as venue evidence. Existing journal files remain intact;
rows lacking the required venue identity cannot train the native models.

StopSlip kept its first trigger until a canonical close, even when an executable
quote invalidated the unsigned stop. The execution tracker closed that attempt,
but a later valid stop inherited the old slippage clock. Cleanup is now connected
to the successful identity-bound CloseLease invalidation. It removes only the
exact captured diagnostic trigger; a later trigger survives stale cleanup.
Signed/unresolved attempts retain their first trigger and real retry latency.
`invalidated9062` exposes these cancelled diagnostic clocks. This corrects data;
it does not erase actual execution delay or change sell permission.

## Observed defect and change

The owner's 03:38 pipeline capture recorded a 31,346 ms blocked held-position
exit pass. Its stack ran from `BotService.planTickRead7739` through
`ExitProfile7955.planFor7955` and `entrySetup7955` into
`LanePlaybook7907.strategyPick8153`. The latter shares the playbook monitor
with learning checkpoints. Missing frozen entry setup was causing a held exit
read to select a strategy again.

`planFor7955` now uses the immutable entry snapshot's lane and setup. If its
setup is absent, NONE, or NO_TRIGGER, it uses the existing lane/NONE profile.
Candidate setup selection is unchanged. An unknown setup is not reconstructed
from a later chart. The position's first resulting plan remains cached as before.
The existing profile learner and hard stops remain unchanged; this removes the
LanePlaybook ranking dependency, not every possible source of exit latency.

## Latest capture: do not confuse versions or clocks

The 04:30 capture is already running 9061 at PR merge ref `68724d7ac`.
It reports no held-exit stall in this shorter session, so the earlier 31-second
stall cannot be asserted as the cause of the newest 6PZRuh loss.

The journal moved from -0.003108 SOL at 03:10 to -0.068635 SOL at 04:25.
The 15 additional closes contain two wins and 13 losses. Rounded row deltas sum
to -0.065528 SOL (summary delta -0.065527 SOL). 99CQuNiF and 6PZRuh together
lost 0.033190 SOL, about 50.65% of that incremental net loss.

The same mint Split first returned +75.90% in EXPRESS and later -10.77% in
MOONSHOT. The later entry was 2.49 times the previous exit price, but this alone
does not prove a local peak or justify a universal re-entry ban. A high score is
also not sufficient: Knoware scored 83 and lost 5.15%.

The new capture reports 1,452 native records stored, 29 checkpoints, no dropped
records and no write failures. Models and predictions are still zero; native
research has no live authority. This verifies persistence progress, not
profitability. DEPENDENT_BASIS remains the dominant proof rejection (19,288).

The short sell-spine emergency metric averages 3,767 ms to broadcast, while
`emergencyRoute8308` reports 24,534 ms average and 68,875 ms maximum for three
Jupiter emergency samples. `stopSlip8327` reports 77,802 ms average trigger to
fill over four emergency samples. These are distinct cohorts and clocks; the
smallest metric must not be presented as end-to-end latency.

## Research export limits

The attached short-horizon export contains 50,473 rows and 6,576 assets; the
extended research export contains 185,997 rows and 6,355 assets. They overlap
historical populations and must not be added as independent trade evidence.
The short export has no exact setup or price-proof columns. The extended
export has only 91 price-proof records and 9,961 rows with known age. Unpriced,
lost-mark and quarantined observations require explicit handling. Endpoint
returns cannot establish achievable entry/exit paths or executed profits.

Reproduce the read-only cohort analysis with `tools/audit_native_learning.py`
and the two owner-supplied gzip exports ending in `1795804232446` and
`1795804235628`. No threshold was optimized from these losses.

## Regression checks

`Aate9062HeldExitIdentityTest` exercises the production held-plan read while a
different thread holds the actual LanePlaybook monitor. It must return the
lane/NONE profile before that monitor is released. Additional tests retain a
frozen EXPRESS/DIAMOND_HANDS_RUNNER identity despite a changed display lane,
and preserve the entry lane for blank/NONE/NO_TRIGGER setup values.

`Aate9062PriceEvidenceTest` covers a fresh witnessed price hidden by a newer
provider endpoint, clock/units/source/venue/decimal rejection, cache ordering,
and actual collector/native-consumer wiring. The production journal test also
rejects a forged route alias on reload. The pure model suite includes these
price checks. `Aate9062StopClockTest` exercises actual lease invalidation,
re-arming, stale cleanup and signed-owner protection.

The required CI suite includes these tests. Full historical tests, signed APK
verification, previous-release upgrade, and device runtime acceptance remain
release requirements. This document does not claim that an unrun check passed.
