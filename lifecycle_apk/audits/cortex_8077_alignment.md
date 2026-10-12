# Cortex 8077 strategy alignment

Preserved baseline: operator report 545, canonical LIVE 26 closes, 8W/18L, 30.8% WR, PF 1.49, +0.0014 SOL/trade. This is not the separate 21-trade strategy subset. No risk-limit change, live accounting rewrite, winner cap or learning reset.

Source fixes: retain positive-EV low-WR tactics in both rotation paths; classify BASE_BREAKOUT as breakout before BASE; generic SPECIALIST_EDGE/EXPERT_ENTRY retain native lane tactics; rank only fired same-lane setups and prefer proven matches over thin outliers; invalidate setup cache on changed market/evidence; UI reads the same local+hive record; persist bounded progressive setup receipts with their exact books; positive neutral benchmark applies to full Cortex proof; realized PAPER and LIVE inversions are isolated; invalid Brier/log-loss and overflow inputs cannot create authority.

Preserve existing branch fixes: decision-time forecasts, decay-weighted revisions, distinct setup bins, validated own-lane refusal audit, verified lifecycle takes, exact-position realized binding and finality-write coalescing.

Reading lanes: negative REFUSE means for QUALITY/BLUECHIP are avoided losers, not a reason to invert those lanes. EXPRESS STRONG n=2 and SHITCOIN REFUSE n=2 are insufficient to reverse strategies. CORE/MANIPULATED missed candidates need same-lane evidence, never automatic buying of every old refusal. Forward returns are not verified executable profits.

Verification: nine local numeric groups passed on production Cortex classes with compile-only JSON/Android boundaries; comment, authority and economic-unit scans passed. Aate8077StrategyAlignmentTest is required in CI. Full Android compile, blocking regressions and runtime smoke must pass on the final source before production acceptance.

Not asserted closed: original OOM allocator, provider entitlements/quotas, device-specific stop/finality latency, exact-mint quantity/orphan reconciliation and all missing causal predecessors. A raw confirmed OPEN with a zero validated funnel is an instrumentation discrepancy, not proof no trade occurred.

Lane-local playbook ordinal bins no longer borrow unrelated setup meanings from other lanes. Shared numeric feature priors remain active. Regression verifies both paths.

Final alignment: canonical lane aliases are used for tactic defaults. Generic launch-ladder setups preserve lane-native tactics. Untrained legacy seeds refresh once; any completed-trade evidence prevents reseeding. Crossed playbook ordinal voters are also lane-local. Ten local numeric groups pass, including extracted production tactic mapping and actual canonical lane identity; full Android CI remains the release check.
