Build 5.0.6402 — Universal SL Invariant + Provider Circuit Breakers
====================================================================

Shipped
-------
* §C UniversalSlLeaseRegistry6402 — start/done invariant enforced via
  try/finally around every exit-coordinator universal sweep. Stale
  leases > 10s auto-reap emit UNIVERSAL_SL_STALE_LEASE_RESET_6402.
* §D ProviderCircuitBreaker6402 wired at BirdeyeApi.getRaw and
  HeliusCreatorHistory.postRaw:
    - Birdeye 401/403 → permanent auth-terminal circuit.
    - Helius  429     → exponential shared backoff, Retry-After hint.
    - 5xx             → 2s transient cool-down.
    - Success         → clears transient state (auth-terminal stays).
* §H SameMintCandidateEpoch6402 wired at ExecutableOpenGate PAPER
  same-mint gate — 68+11 duplicate lifecycle rows collapse into a
  single suppression counter with 2s cooldown; unregisterPosition
  bumps the mint epoch on close.
* §A BotLoopStageTiming6402 substrate (Stage enum + time { }) ready
  for wire-in around every top-level loop stage.
* §G ExitPendingOrphanGuard6402 substrate ready for wire-in at every
  exitPending read site.

Tests
-----
* Bundle6402SubstrateTest — 22 invariants (lease reap TTL, Birdeye
  401 auth-terminal persistence, Helius 429 exponential backoff,
  stage-timing exception rethrow, same-mint cooldown + epoch bump,
  all four exit-pending verdicts).

Snapshot expectations on the V5.0.6402 APK
------------------------------------------
* Universal SL start/done reads N/N.
* PROVIDER_CIRCUIT_OPENED_BIRDEYE_AUTH_TERMINAL_6402 fires once
  after the first 401; every subsequent request short-circuits.
* PROVIDER_CIRCUIT_RATE_LIMITED_HELIUS_6402 reflects real 429s
  (shared backoff, not per-mint retry storm).
* EXEC_OPEN_SAME_MINT_ALREADY_OPEN_COOLDOWN_6371 lifecycle rows
  collapse; excess dedup lands in SAME_MINT_CANDIDATE_SUPPRESSED_6402.
