# V5.0.6696 Canonical Learning + Fluid Hold/Exit Repair

Runtime 5.0.6695 proved that the bot could execute a paper trade after a soft advisor objection while deliberately setting `paperLearningEligible6519=false`. Those outcomes then arrived at the finalized bus as non-learning ACKs, starving TacticSwitcher and policy consumers. Executor also synchronously checked `RewardPurityGate.outcomeOf()` before the exact paper economic event was committed; the 6695 trace showed `REWARD_PURITY_LEARNING_BLOCKED_6448` roughly 20 ms before `PAPER_ATOMIC_COMMIT_OK_6632`.

This repair makes four source corrections:

1. **Executed soft-advisor paper outcomes remain trainable.** Hard safety still returns before execution; soft advisor evidence is retained in the reason and may shape the trade, but cannot erase its causal outcome.
2. **Legacy LearningEligibility telemetry recognizes explicit finalized/simulated paper proof** instead of labelling every paper close `PAPER_OR_SYNTHETIC`.
3. **ForwardOutcomeModel and UnifiedExitPolicyHead join TacticSwitcher on CanonicalFinalizedTradeBus6464.** Consumer delivery already waits for the exact canonical economic event and retries, so terminal credit is post-commit rather than dependent on Executor timing. Exit reason is carried in the immutable envelope so the corrected 6009 exit-optimal label is preserved.
4. **HeldPositionPivotArbiter is actuated before HoldingLogicLayer on every held-position evaluation.** Its existing 20-second minimum hold, 45-second anti-thrash cooldown and conviction margin remain intact; it soft-switches exit technique only and never disables a lane.

No hard rug/finality/security gate is removed. TacticSwitcher remains rotation-only.
