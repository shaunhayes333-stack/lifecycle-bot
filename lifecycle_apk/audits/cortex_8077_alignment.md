# Cortex 8077 strategy alignment

Preserved baseline: operator report 545, canonical LIVE 26 closes, 8W/18L, 30.8% WR, PF 1.49, +0.0014 SOL/trade. This is not the separate 21-trade strategy subset. No risk-limit change, live accounting rewrite, winner cap or learning reset.

Source fixes: retain positive-EV low-WR tactics in both rotation paths; classify BASE_BREAKOUT as breakout before BASE; generic SPECIALIST_EDGE/EXPERT_ENTRY retain native lane tactics; rank only fired same-lane setups and prefer proven matches over thin outliers; invalidate setup cache on changed market/evidence; UI reads the same local+hive record; persist bounded progressive setup receipts with their exact books; positive neutral benchmark applies to full Cortex proof; realized PAPER and LIVE inversions are isolated; invalid Brier/log-loss and overflow inputs cannot create authority.

Preserve existing branch fixes: decision-time forecasts, decay-weighted revisions, distinct setup bins, validated own-lane refusal audit, verified lifecycle takes, exact-position realized binding and finality-write coalescing.

Reading lanes: negative REFUSE means for QUALITY/BLUECHIP are avoided losers, not a reason to invert those lanes. EXPRESS STRONG n=2 and SHITCOIN REFUSE n=2 are insufficient to reverse strategies. CORE/MANIPULATED missed candidates need same-lane evidence, never automatic buying of every old refusal. Forward returns are not verified executable profits.

Verification: nine local numeric groups passed on production Cortex classes with compile-only JSON/Android boundaries; comment, authority and economic-unit scans passed. Aate8077StrategyAlignmentTest is required in CI. Full Android compile, blocking regressions and runtime smoke must pass on the final source before production acceptance.

Not asserted closed: original OOM allocator, provider entitlements/quotas, device-specific stop/finality latency, exact-mint quantity/orphan reconciliation and all missing causal predecessors. A raw confirmed OPEN with a zero validated funnel is an instrumentation discrepancy, not proof no trade occurred.

Lane-local playbook ordinal bins no longer borrow unrelated setup meanings from other lanes. Shared numeric feature priors remain active. Regression verifies both paths.

Final alignment: canonical lane aliases are used for tactic defaults. Generic launch-ladder setups preserve lane-native tactics. Untrained legacy seeds refresh once; any completed-trade evidence prevents reseeding. Crossed playbook ordinal voters are also lane-local. Ten local numeric groups pass, including extracted production tactic mapping and actual canonical lane identity; full Android CI remains the release check.


## Follow-up: report revision 1783, build 5.0.8076

This is the same pre-fix build, not validation of 8077. The earlier crash age advances from 47 to 137 minutes; the report does not establish a second crash. Heap has nevertheless risen to 511/512 MB despite 147 hard trims. The allocation owner remains unproven. Main-thread samples include deque iteration, startup, and queued preference work; 14 stall hints are not proof of an Android ANR.

- Canonical performance now has 33 closes, 8 wins / 25 losses: six additional losses since revision 788. Strategy-eligible 28 closes have PF 0.18 and -0.0656 SOL. Canonical PF 1.10 uses a different cohort; it does not validate the strategy. Forward Cortex gains and hindsight missed runners do not establish executable net edge. The -288.7% refuse label and extreme reported skill require provenance checks before authority promotion.
- Exit sweeps complete, but catastrophic trigger-to-broadcast p95 is 14.116 seconds (five samples), with four SLA breaches. Mark refresh advanced only 8 of 855 requests. A successful scheduler heartbeat is not fresh-price or timely-execution proof. The repair batches executable fallback quotes under one deadline and carries acquisition timestamps.
- Helius WS has zero acknowledged subscriptions and hundreds of failed handshakes. Manual connection attempts now respect the circuit cooldown and connection health requires an acknowledged open socket. PumpPortal explicitly denies trade streams for insufficient account balance; code cannot manufacture access. Chart history remains unavailable despite metadata cache hits.
- Journal backfills occur after sells for the same position IDs (METvsv, HtRfvJ). Backfill time must not be mistaken for trade time. MET quantity and basis differences and EH4cP8 oversell/orphan diagnostics require receipt-level reconciliation; this report alone is insufficient to rewrite live history. Four canonical live holdings remain protected; the additional host holding is explicitly OWNER_MANUAL, not an automatic adoption candidate.
- PROJECT_SNIPER has no native admits in 10,343 evaluations, dominated by birth hydration and phase handoff; DIP_HUNTER likewise has none, largely market-cap scope. CORE yields 24,072 times. MANIPULATED buyerEnabled=false is a configuration fact, not authorization to enable it. These need eligibility/ownership tracing, not blanket gate relaxation.
- The mathematical learning queue has 2,489 outstanding events. Cortex pending and chart queues remain large; heap pressure cannot be attributed solely to any one of these without profiling. Existing 8077 finality changes eliminate repeated full preference hydration/unchanged acknowledgement writes. Sustained on-device memory validation remains required.
- Specialist funnel windows evict records (25,249 evictions), so their counts cannot be subtracted from session execution counters. Legacy no-writer traces are not measurements. Different equity/ drawdown readings within this report are not an atomic snapshot. Historical replay closes are not new session trades.
- Lifecycle holds include stop-labelled requests while net-edge reporting says stops are never held; actual owner and emergency precedence must be inspected. No new stop widening or authority increase is justified by this report.

The persisted-history runtime fixture exposed exact accounting defects: missing durable sell valuation fields and replaying combined entry/exit fees as entry fees. 8077 now persists sell valuation and derives historical entry fees only from complete receipt evidence. Tests cover serialization, legacy unknown values, fee conservation, bounded concurrent quote deadlines, and websocket cooldowns. Runtime verification must still pass on the final head before release.
