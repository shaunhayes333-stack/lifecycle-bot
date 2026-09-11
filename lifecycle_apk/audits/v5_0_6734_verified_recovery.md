# 5.0.6734 — scoped recovery and economic integrity

Basis: operator runtime 5.0.6733, captured 11 September 2026 21:42 Brisbane.

## Implemented source repairs

- Scope advisory consensus to PAPER/LIVE, owner lane and candidate mint. Deduplicate correlated LLM/Sentience and streak/performance families. Re-reading an evidence ID does not extend its expiry. Legacy unscoped publishers cannot stop scoped orders; independent deterministic safety/capital guards remain.
- Remove report rendering's global performance-veto mutation. Preserve actual lane/mode loss evidence and exact clear semantics.
- Preserve candidate version, mode and material source evidence in FDG cache identity; explicitly invalidate stale-feedback cache and retry ownership. Do not reuse an allow across modes or remove candidate version to cosmetically lower fanout.
- Zero-sized intents can only be upgraded by matching owner/action/decision/safety semantics. Reused intents are not counted as newly created intents.
- Resolve price, provenance, pool and timestamp as complete provider tuples. Do not freshen an old price with a different source's newer timestamp. Reuse an already validated fresher winner after an observation publication race. Require current marks on paper entry and ticket reseal; retain strict LIVE liquidity proof.
- Remove paper fill-price clamping to stop-label bands. A -99% quote must not be transformed into a fabricated -15% fill. Existing quote/basis validation and explicit simulation costs remain.
- Replay full/partial closes against the exact position's remaining quantity, not the aggregate quantity of every position sharing a mint. Another lot cannot cover an oversell. Historical carry remains explicitly separated; no broad ledger-guard bypass or manufactured balance reset.
- Compare durable reward ACKs/exclusions with the canonical event population. The old 196 versus 63 report mixed a lifetime bus with a volatile process-local W/L map; it did not prove 130 outcomes were never learned. ACKs outside the current canonical population do not count.
- Single-flight per-consumer/event delivery prevents concurrent retries from double-mutating a learner. Failed consumers remain retryable; excluded events are never ACKed as learned. Persist exclusions separately and restore them on registration.
- Restore CORE and canonical lane aliases to the specialist close-side learning consumer. Preserve exact first-published event economics on retries.
- Wire actual parsed LLM text outcomes into bounded inference-health telemetry. Credential/connectivity results are no longer presented as inference-capacity proof. Existing provider fallback/cooldown mechanisms remain; unavailable credentials and provider quotas are not fabricated or bypassed.
- Version APKs from committed AATE_VERSION rather than staged repair titles. Pin runtime smoke checkout to the upstream build SHA. Retire the prior conflicting staged recovery writer/script.

## Validation

Locally: Golden Tape literal scan, authority contradiction scan and patch-rot scan passed. Twenty component checks compiled and passed using the production consensus, price registry/provenance, typed economic schema, paper replay and finalized bus with peripheral Android/storage/network ports isolated. This is not a full Android app build or a live execution test.

The same regression class is included in the normal JUnit suite. Full Android compilation, full unit suite, APK build and runtime smoke must be reported from their actual workflow results, not inferred from local component checks.

## Interpretation boundaries

BUY is also the mechanical side of a legitimate PROBE_ONLY order. Preserve the immutable final decision and size semantics rather than banning probes from ambiguous log ordering. FDG/intake is not a unique-candidate duplicate-order count. A corrected software pipeline does not establish future win rate, live profitability or automatic loss recovery. Historical invalid economics remain visible/quarantined; no trade history is erased to improve reported performance.
