# AATE 5.0.8842 — Epoch-consistent prediction authority

## Verified fault (5.0.8840, pipeline 2026-11-17 23:59)

- The clean rebuild (8819) restarts `edge_model_8722.bin` but `LearningResetSweep7781.LEARNING_FILES_7975` did not contain `mind_8746.json`.
- `Mind8746.restore8749` accepted the old JSON state without checking model or clean-learning epoch.
- The report shows `mind8746[learned=44185 warm=true]` but the rebuilt edge model reports only `trained=3810 learned=583`. The `MIND8767` model was the deciding predictor in all twelve lanes, carrying old out-of-sample refusal records and refusing virtually every candidate.
- `EdgeModel8537.learn` previously fed an old pending snapshot to `Mind8746.learn8746` before checking the snapshot's model epoch; an old snapshot could re-teach the mind after the clean rebuild.

## Source repair

1. The stacked model's save file is now `mind_8842.json`. It is a **new** model, not a migration of old weighted evidence. The original is left untouched for forensic inspection until a learning reset archives it.
2. Save/restore state carries `v=2`, the authoritative `EdgeModel8537.MODEL_EPOCH_8642`, and the active `LearningEpoch8164` timestamp. A mismatch is discarded cold, rather than adopted as model authority.
3. `LearningResetSweep7781.LEARNING_FILES_7975` now archives/resets both old and new mind stores along with the edge-model cache.
4. `EdgeModel8537.learn` rejects snapshots from before the clean-learning epoch and old model epochs **before** any stacker, ridge, dataset, pocket or paying-cell learner sees them.
5. The decision model key is now `MIND8842`, so the old `MIND8767` out-of-sample refusal authority is never transferred to the rebuilt model. Until 300 clean observations, it abstains in favor of the existing tree/ridge path; its positive and negative authority must be re-earned by out-of-sample results.

## Preserved constraints

Hard rug/sellability checks, route/depth/cost, exact-mint quarantine, wallet reconciliation, stop-loss, V3/V4 authority and the live trade seal remain unchanged. Reboot does not invent profitable evidence. The change corrects model contamination and authority lineage; it does not automatically certify that the remaining gates find profitable trades.

## Acceptance

- Regression: old schema, old model epoch and old learning epoch cannot restore a stacked model; current schema round-trips; both mind files included in reset inventory; pending old labels cannot teach any new model; updated key cannot inherit OOS record.
- Static quality: qualified references, Kotlin val assignments, patch rot, economic units, Kotlin syntax sentinels, diff hygiene.
- CI: GitHub Actions release APK and runtime smoke must both succeed on the **same** main SHA; record installable APK artifact.
- Runtime: `mind8746[...]` must report `kept=mind_8842.json` and clean `learned` count, model key `MIND8842` only after warm. Track candidate → proven price → strategy → FDG allow → executor filled → wallet and net PnL against 8840 (0 FDG approvals, 0 buy attempts). No claims of 600–1000% captured gains until canonical live fills/sells establish them after fees.
