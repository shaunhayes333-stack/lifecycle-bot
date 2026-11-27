# 9052 — priority evidence delivery on the existing chain-read workers

## Confirmed causal fault
ChainConfirm8950 created an unbounded FIFO with two workers. It stamped lastAsk at enqueue, armed the price/depth response clock before the worker dispatched, and scheduled its timed fallback from enqueue. Under backlog, stale work could consume the request gap, a callback could precede the actual read, and background history/discovery could occupy both workers. The 9046 device report recorded long evidence waits and very low stage read completion; these counters do not independently measure this particular queue delay.

## Implemented
- EvidenceReadQueue9052 is a bounded queue (128 pending, at most two active), exact mint+input coalescing and priority upgrade without renewing original queue age. Reserve 16 pending slots for held/exit/rug verification and 32 additional for urgent decisions; one of the existing two workers serves only urgent work, the other all priorities. No new worker pool.
- Held/exit/rug first; decision/evidence/execution and pocket/observed-flow LANE next; normal lane/stage/research after. No trading admission or veto added.
- Expire queued reads after bounded monotonic wait (5s urgent entry, 10s held, 8s normal discovery, 30s research), or generation change; no stale read dispatch. Queue rejection/expiry never consumes lastAsk or arms a response. Existing caller waits and provider freshness checks remain responsible for a new valid request; no blind ticket renewal.
- Provider pacing starts at actual dispatch. Price response latch starts at dispatch. Non-price fallback scheduled only after its own request path invoked and coalesced per generation/mint. Provider final result still remains independently verified; dispatch is not proof arrived.
- Expose queue backlog, active, full, expired, coalesced, promoted, dispatch, mean/max queue age, exceptions. Pure Kotlin queue tests passed 25 local assertions. Android worker integration, throughput and lifecycle smoke still pending.
- Optimise 9051 shadow learner's hot path: no regex creation or per-feature temporary sets, immediate return when no model. All new heads remain shadow only.

## Remaining
No guarantee of provider response or opportunity completion. Reserved worker may lower background discovery throughput under light load; measure device full-population queue/decision/first-send latency and coverage against 9046/9050. A saturated hard-risk worker still needs independent protective route/held mark service and verified retry. Complete actual evidence-driven no-slot FastLane requeue, all-desk owner/FDG/exit/accounting repairs, proof-qualified model and real managed outcome validation. Do not use increased admissions as economic acceptance. One integrated main release only when tested.
