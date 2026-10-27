# 5.0.8402 delivery and learning audit

Scope: follow the 8400 delivery defects and the uploaded 8401 report (0128092a0), through chart requests, admission, learning persistence and market exit monitoring. This is a bounded repair, not a claim that every trading strategy is profitable or every external data source is available.

## Confirmed defects repaired

- OHLCV same-key admission updated its timestamp before rejecting a local repeat. Repeated reads could indefinitely postpone admission. Atomic admission now updates only when admitted. A virtual-time regression exercises repeated reads through the window.
- The shared queue waited for Gecko capacity before checking cached/alternate bars. Initial fetch now checks available data immediately; an actual WAIT_BUDGET enters the budget wait. A coalesced caller with a shorter deadline no longer terminates another caller's owned request.
- TradePlan's two-worker executor silently discarded work after recording a two-minute attempt cooldown. Rejection now releases that attempt; provider waits use the shared typed queue. Chart fallback uses the queue with a bounded three-second caller deadline and reads local executed tape first. Actual answered-empty data remains distinct from capacity/deadline/failure.
- Cortex's real shadow-checkpoint caller counted unavailable terminal receipts as learned outcomes, overriding the 8396 batching policy. It now counts Terminal8372.learned. A prior forward-trained alias does not make an unavailable shadow receipt a newly learned shadow outcome. Durable acknowledgements still wait for successful storage.
- Older paired predictions restored by 8401 were absent from predictive-authority coverage and its cache counts. They now participate as unresolved original decisions, deduplicated by episode; no invented gains or losses.
- Cross-asset price lookup depended on an in-memory identity map lost across restart. Only mappings for the selected retained forward observations now share their checkpoint transaction. Restoration keeps newer mappings and preserves native price provenance. No prices or new freshness timestamps are manufactured. Active forward identities are excluded from map pruning; observation capacity remains bounded.
- Forex, metals and commodities duplicated the stock exit-monitor dependency on the shared Default CPU pool. Each monitor now has one process-owned worker and cancellation checks at scan/position boundaries. All four dispatchers are tested with the Default pool occupied.

## Checked without relaxing policy

- CandidateAdmission8136 resolves its chosen strategy before checking candidate-specific execution economics. The uploaded count of 936 economic refusals is not evidence that 936 executable trades were wrongly rejected. No economics threshold was lowered.
- Cross-asset shorts are excluded from a long-only forward labeler intentionally; allowing them through would invert learning.
- Shadow write flushing drains bounded batches through the requested receipt boundary. Retry counts include waiting for durability and are not counts of lost trades.
- Native shadow enrollment already exists independently of live FDG. No duplicate shadow system was added.
- The uploaded 8401 report recorded five FDG allows and one finalized live buy/sell, with no forward-terminal storage/capacity failures. It still had substantial missing data and unresolved outcome coverage. One profitable close does not establish recovered selection quality.

## Validation contract

The required test filter includes 8401 and 8402 plus the existing lane/history suites. The complete historical suite, release APK build, Android upgrade/startup verification, and 120-second execution-spine witness remain required. The final APK must come from the same verified revision. A synthetic emulator run does not reproduce the owner's entire restored on-device history or prove future trading returns.
