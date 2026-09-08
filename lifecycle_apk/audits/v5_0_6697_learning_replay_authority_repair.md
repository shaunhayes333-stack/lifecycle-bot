# V5.0.6697 Learning / Replay Authority Repair

Release authority: **V5.0.6697**

## Runtime evidence from 5.0.6696

The 5.0.6696 runtime was mechanically active but not learning from the same population it was closing:

- canonical CLOSED positions: 187
- RewardPurity finalized population: 19
- Unified Policy outcomes: 14
- observed WR window: 2W / 146L (1.4%)
- `reward_pop_mismatch:closed=187,finalized=19`
- `JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647=186`
- `REWARD_PURITY_LEARNING_BLOCKED_6448=186`
- repeated journal BUYs with `reason=CROSS_ASSET_CANONICAL_OPEN_6659` appeared for the same position ids that already had native PAPER BUY journal rows.

The runtime also showed simultaneous ledger/journal divergence (cash, open cost and realized PnL), while the canonical ledger itself remained conserved. This isolated the defect to projection/replay and learning fanout rather than raw account conservation.

## Root cause A — 6659 historical repair escaped its asset-class boundary

`CanonicalPaperTransaction6486.repairCryptoHistory6659()` describes itself as historical cross-asset repair, but 5.0.6696 selected every PAPER canonical position. That allowed SOLANA_TOKEN meme positions to receive a second `CROSS_ASSET_CANONICAL_OPEN_6659` projection.

### 6697 correction

`repairCryptoHistory6659()` now selects PAPER positions only when:

```kotlin
it.assetClass != AssetClass.SOLANA_TOKEN
```

The cross-asset repair therefore cannot create future repair BUY projections for native Solana meme inventory.

`JournalEconomicReplay6619` additionally contains a backward-compatible repair rule for already-persisted 6696 pollution: when a position has a native PAPER BUY, a `CROSS_ASSET_CANONICAL_OPEN_6659` BUY for that same position is treated as a superseded repair projection and contributes zero cash, basis, quantity or fees. It is ignored without creating a learning quarantine.

This is deliberately not deletion of journal history. The row remains durable and forensic; it simply cannot become a second economic mutation.

## Root cause B — false learning ACK semantics

`FinalizedBusConsumerBridge6465` previously returned `true` for a learning-ineligible or quarantined canonical terminal envelope without invoking the learner. `CanonicalFinalizedTradeBus6464` defines a true delivery result as a successful consumer ACK, so the bus could report parity while the learner population remained near zero.

### 6697 correction

The canonical bus now distinguishes three states per consumer:

1. **processed / ACK** — the consumer actually accepted and mutated from the event;
2. **excluded** — the event is terminally ineligible for that learning consumer and is not retried;
3. **pending / failed delivery** — the event remains eligible but has not yet been processed.

Learning-ineligible and learning-quarantined events are explicitly recorded with `CanonicalFinalizedTradeBus6464.exclude(...)`. They are not ACKs and cannot inflate `consumerUnique()`.

Dashboard remains a non-learning consumer and continues to receive the canonical terminal event so observability is not silently amputated.

## Root cause C — false ACKs survived process restart

`CanonicalFinalityPersistence6486` persisted the old ACK namespace. Importing those values after fixing the in-memory bus would have retained false-positive learner ACKs across restart and prevented re-delivery.

### 6697 correction

The durable ACK namespace is versioned to `ack6697:`. The old `ack:` keys remain on disk for forensic history but are not imported by the corrected bus.

The rich terminal persistence also now stores and restores `economicEventId`, preserving exact terminal-event identity across process death for post-commit reward verification.

## Required invariants after 6697

The following are source-level acceptance requirements:

- `repairCryptoHistory6659()` never selects `AssetClass.SOLANA_TOKEN`.
- A native PAPER BUY and its historical 6659 repair projection can never both affect replay economics.
- A consumer ACK means actual consumer processing, never merely "handled" or "excluded".
- Excluded learning samples are visible separately from processed samples.
- Legacy pre-6697 false ACKs are never imported as corrected ACKs.
- Exact `economicEventId` survives terminal persistence/replay.
- No hard rug, finality, quantity, oversell or economic safety gate is relaxed by this repair.
- Strategy/lane tuning remains downstream of canonical committed terminal economics.

## Regression coverage

`Aate6697LearningReplayAuthorityRepairTest` locks the source invariants for:

- non-Solana scoping of 6659 historical repair;
- supersession of duplicate 6659 BUY projections in journal replay;
- exclusion-vs-ACK semantics;
- versioned durable ACK namespace;
- persisted terminal economic event identity.

`ci/patch_rot_scan.py` also pins those authority boundaries before Gradle compilation so a later patch cannot silently reintroduce the same contradiction.

## Expected runtime readback

On a clean 6697 session/restart, the diagnostic trend should be:

- no new 6659 projection BUYs on SOLANA_TOKEN meme positions;
- `JOURNAL_CROSS_ASSET_OPEN_SUPERSEDED_6697` may appear only while consuming historical 6696 pollution;
- journal/ledger cash and open-cost deltas collapse toward zero after reconciliation;
- finalized consumer telemetry reports `processed=` and `excluded=` separately;
- RewardPurity / learner populations rise from the previously starved 19/14 population for clean terminal outcomes;
- `JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647` must not grow because of a superseded 6659 BUY projection.

This repair changes correctness authority and telemetry semantics. It does not claim that the 1.4% strategy result is solved by itself; it restores the trustworthy feedback population required for the existing adaptive policy, tactic switcher, hypothesis engine and exit-policy heads to learn from actual canonical outcomes.
