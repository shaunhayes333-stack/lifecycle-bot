# 8113–8114 source comparison

Operator reports approximately 40% wins and doubled cash on Thursday night, 15 October 2026 (Brisbane), around builds 8113–8114. These are reported observations, not independently established net realised returns.

Verified source baseline: 8113 `361727241`, 8114 `379ad2fa0`. Their trading production source is identical; differences are version metadata and CI failure-message normalization.

## Relevant differences requiring decision-time replay

- Canonical candidate admission was added after this baseline. By 8161 its specific refusals and data deferrals precede the older proven-setup/runner/cold-start door in LiveEdgeGate7877. A runner that passed the older path may now defer on missing data or fail its selected-strategy economics. This precedence is confirmed; whether it explains the operator's session is not proven.
- Selected-strategy economics, cost-aware sizing, pre-broadcast quote rechecks and runner receipt accounting evolved after 8114. These include legitimate safety/finality fixes and must not be blindly reverted to recover BUY volume.
- The newly repaired score-floor revote, last-slot lane-prior veto and ambiguous failure attribution are older defects, not proof of a post-8114 regression. Repairing them preserves immutable FDG authority but does not by itself establish historical causality.
- Actual incident replay still needs the affected attempt's immutable input snapshot and eventual execution/terminal outcome. Aggregate lane counts cannot identify that path.

## Preserved comparison history

### engine/truth/LiveEdgeGate7877.kt

```text
ec7956af3 Merge branch 'wt-rrun' into int8178
e404c5126 V5.0.8161: unify candidate admission and handoff evaluation
14c170305 Merge branch 'worktree-agent-ad1366c8f32791c4c' into claude/fix-traders-buying-vw6Oc
8e39d3aee Merge branch 'worktree-agent-a9a94ef7fe71d1564' into claude/fix-traders-buying-vw6Oc
0eeee43ab Merge admission workstream: one candidate admission record, specific edge vs generic floor, DATA_INCOMPLETE defers
372aff553 5.0.8117: loss-streak cooldown becomes a setting (off by default); runs are bought while they develop
f2647011c 5.0.8115: the spike-chase rule applies to confirmed runs again
```

### engine/truth/CandidateAdmission8136.kt

```text
ec7956af3 Merge branch 'wt-rrun' into int8178
e57ffa215 Merge AATE 5.0.8173 verified repair baseline (#55)
50db6a54f Merge 5.0.8169 report repairs, historical test repair and live buy routes (#54)
e404c5126 V5.0.8161: unify candidate admission and handoff evaluation
14c170305 Merge branch 'worktree-agent-ad1366c8f32791c4c' into claude/fix-traders-buying-vw6Oc
8e39d3aee Merge branch 'worktree-agent-a9a94ef7fe71d1564' into claude/fix-traders-buying-vw6Oc
0eeee43ab Merge admission workstream: one candidate admission record, specific edge vs generic floor, DATA_INCOMPLETE defers
```

### engine/truth/NetEdge8030.kt

```text
42b071d67 8197: entry economics require 1.5x the round trip (was 2x), owner's choice
e57ffa215 Merge AATE 5.0.8173 verified repair baseline (#55)
50db6a54f Merge 5.0.8169 report repairs, historical test repair and live buy routes (#54)
14c170305 Merge branch 'worktree-agent-ad1366c8f32791c4c' into claude/fix-traders-buying-vw6Oc
8bcdb9042 Merge branch 'worktree-agent-a0bd1676024fe9b07' into claude/fix-traders-buying-vw6Oc
0eeee43ab Merge admission workstream: one candidate admission record, specific edge vs generic floor, DATA_INCOMPLETE defers
```

### engine/RunnerRungBook8153.kt

```text
e404c5126 V5.0.8161: unify candidate admission and handoff evaluation
00ec7f0fb Merge branch 'worktree-agent-aa1fc74ff2f58d8cc' into claude/fix-traders-buying-vw6Oc
```

### engine/Executor.kt

```text
b7b351d4f Merge branch 'wt-rexit' into int8193
e57ffa215 Merge AATE 5.0.8173 verified repair baseline (#55)
50db6a54f Merge 5.0.8169 report repairs, historical test repair and live buy routes (#54)
1744793ae Merge 8167 pipeline evidence, execution economics and accounting repairs
2f1d7d759 8164: sells that never land hand the emergency to Jupiter instead of looping PumpPortal
4352d0cc5 8163: require executable slice profit, refresh SOL truth, and widen discovery
e404c5126 V5.0.8161: unify candidate admission and handoff evaluation
00ec7f0fb Merge branch 'worktree-agent-aa1fc74ff2f58d8cc' into claude/fix-traders-buying-vw6Oc
84ea92d20 Merge branch 'worktree-agent-abffcd90f172af16d' into claude/fix-traders-buying-vw6Oc
41b7e6d89 V5.0.8153 StopLatency: emergency stop hot path answers a known holding without blocking reads
8bcdb9042 Merge branch 'worktree-agent-a0bd1676024fe9b07' into claude/fix-traders-buying-vw6Oc
c9907c8cf Merge branch 'worktree-agent-a5a75c20453f1ca93' into claude/fix-traders-buying-vw6Oc
bc6b6ce52 Merge branch 'worktree-agent-a8cb6dc9fed864624' into claude/fix-traders-buying-vw6Oc
bcf24c251 Merge branch 'worktree-agent-ad3b186777583b8dd' into claude/fix-traders-buying-vw6Oc
f49613d8a Merge worktree-agent-adb84cbf711dd7723
f7b8208b5 5.0.8133: every peak/high writer refuses a one-feed jump, not just the canonical peak
1aabc4d18 5.0.8126: keep the 7736 pinned line; the frozen-coin hand-off is its own line before it
9b7331083 5.0.8122: a coin frozen by its issuer is recognised, blacklisted and handed to the proof poller
```
