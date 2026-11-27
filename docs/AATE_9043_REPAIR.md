# AATE 5.0.9047 — entry timing, evidence, and execution continuity

The 9043-named code and tests identify the first repair generation. Version 5.0.9044
also made direct proof wake-up keys monotonic, so a frozen or backward wall clock cannot
reuse an earlier BotService wake identity. Its complete automated release passed, but review
of the runtime artifact exposed persistence contention on the canonical-close delivery thread.
The 9045 repair addresses that measured path. Release 5.0.9046 also corrects isolation of the
SQLite integration fixture: detached book/scoreboard state is restored exactly, including an
originally empty book, after its worker is idle. Both version aliases advance to preserve
upgrade order if the intermediate 9045 validation publishes first. Version 5.0.9047
also addresses two existing-contract defects confirmed from the owner's later 21:17
Brisbane exports: missing trade flow represented as observed zero counts, and cached
launch proof remaining armed at a latency faster than the bot's current requirement.

## Follow-up audit: forming runners, flat entries, and what actually learns

The owner's `aate_pipeline_20261127_211748_471.txt` identifies installed version
5.0.9044 at `b0b97f26a`, captured at 21:17:45 Brisbane after 1,058 seconds. It does
not measure the subsequent 9045 persistence repair. The corresponding dataset is
`aate_edge_dataset_8537_1795778269722.jsonl.gz`; research is
`aate_edge_research_8877_1795778271802.jsonl.gz`. The earlier 21:02 snapshot is a
170-second startup sample and must not be presented as the longer session.

The longer report records three finalized LIVE buys and two sells, of which one
sell closes a position opened before this session. Its all-time/day/governor
performance counters are not the results of those three buys. The dataset contains
45,945 labelled rows; 520 have decision times in the current boot, covering 149
distinct mints. Of these, 56 rows over 39 mints carry `admitted=true`. Those include
native lane opinions and are not 56 actual buys. All 56 have unobserved volume
acceleration and new-buyer-count inputs, and zero values in the three short-window
buy-count columns. This is a matured-label sample, not every live decision.

The signal system has real consumers, but availability and authority are separate:

- `EdgeModel8537.snapshot` freezes decision-time inputs and the prediction.
  `EdgeModel8537.learn` grades that prediction before updating the models from its
  later managed forward outcome. The report records 167 new targets with
  `targetOrigin9043=DECISION_FROZEN`, with zero invalid or label-time substitutions.
- `TreeRanker8737` consumes 108 inputs: 19 core features and 89 additional learner
  columns. `Mind8746` combines model/voter predictions and availability, with
  frozen voters keyed by mint, lane and decision time. This is prospective
  association learning; a positive label is not causal proof of what made a coin run.
- `RunnerModel8861` separately learns from 24 tape features captured at the cap
  crossing, targeting doubling before the stop. Its existence and historical
  forward record do not prove timely executable delivery of its live selections.
- The sampled Cortex is active, but every reported lane has insufficient earned
  authority. It is incorrect to describe the graph as entirely disabled or to
  treat a large count of voters as proven selection skill.
- Admission can be supported by a proven model or empirical cell without a current
  named forming trigger. `NO_TRIGGER` therefore does not prove a disconnected wire.
  A broad positive historical cell also does not prove that this particular coin
  has fresh runner demand now.

The actual readiness gap persists beyond startup: among 2,594 meme-family
evaluations, 2,337 have unknown flow and only 56 have ready minute history.
The nine named families record nine positive signal occurrences in total. These
are one detector family's counts, not the total number of signals in the bot.
Nine armed launch fires yield one admission within the frozen window, zero
gate allows in that window and zero linked landings. First-verdict median is
30 seconds and first-gate median 52 seconds; never-judged fires stay in their
separate denominator rather than being assigned a fabricated latency. Safety
refusals are present, so these nine fires cannot be counted as nine missed winners.

The three purchased tokens do not support a blanket post-peak conclusion. One
near-entry research row has a later observed peak of only about +0.695%, supporting
the flat-selection concern. Another has an observed +11.59% peak 163 seconds after
its decision, which contradicts calling that entire move already over. A third
retains a positive native observation at 21:00:37 while the actual buy is at
21:04:06 and closes at a loss at 21:04:47. The earlier counterfactual and the later
fill are different experiments. `ForwardReturnLabeler7731` deliberately preserves
the first native observation and separately requests an exact execution read from
Cortex; rewriting the first observation with later information would introduce
look-ahead bias. These exports do not provide an exact fill-linked feature record
for every attempt, and `fill=true` on a dataset row is not chain execution proof.

### R13: distinguish missing flow from observed zero

The existing fallback from `LanePlaybook7907` to
`WhaleDetector.launchFlow7401` returns zero counts for an empty mint queue. Those
finite values reach `LearnerColumns8798` and the tree, and inflate reported coverage
to 100% even when the signed tape is absent. This is an input-integrity defect,
independent of whether any particular buy lost money.

The repaired fallback must carry an actual in-window observation timestamp. An
empty, expired or future-only window supplies unknown counts, represented by the
existing NaN/missing convention. An observed sells-only window still supplies real
zero buy counts. Existing fresh `BuyerFlow8666` evidence keeps priority. The repair
uses the existing time window, adds no entry threshold, and changes no feature
dimensions. Historical ambiguous zeros remain historical; the repair must not
invent missingness, rewrite source rows or reset valid trained state.

Acceptance must exercise the production producer and LanePlaybook-to-learner
path for absent, expired, future-only, observed-zero and positive windows,
including exact time boundaries and source priority. A finite-count assertion
alone is insufficient: the exported learner values and observed coverage must
reflect the same evidence availability.

### R14: select launch proof at the current measured latency

`LaunchReplay8744` previously selected `proven` using one required-delay snapshot
and returned it unchanged until the next 30-minute replay. The later phone report
shows a 20-second rule armed alongside a 23-second current requirement; the earlier
snapshot shows 28 seconds. This proves a stale qualification at report time,
not that every preceding fire was stale when created. The requirement uses the
existing p75 tape lag plus decide/send budget, or measured trigger-to-landing p75
once supported; p90 remains diagnostic.

Publish an immutable generation of already-graded candidate proofs. At use time,
choose a proof qualified for the current requirement, preserving the existing
preference for replay proof before forward proof and each family's ranking.
A longer-delay proof may be selected only if it was actually graded and passed
the existing proof tests. No hot-path replay, fit, future outcome, newly invented
delay or relaxed proof bar may be used. Lab/base fallbacks must satisfy the same
latency contract. If no supported candidate qualifies, no launch authority exists.

An existing fire retains its original rule, source event, monotonic clock, identity
and deadline. A later latency requirement cannot lengthen its window or turn it
into a different opportunity. Every evidence read must revalidate that frozen
proof against the current requirement. Tests must demonstrate live reselection
without a new replay, immutable publication, valid fallback ordering, loss of
authority when no grade qualifies, and no deadline renewal when latency changes.

For tape signals, the actual graded delay and the existing evidence lifetime are
different quantities. Freeze the actual delay into each immutable signal replay
record and then into its fire. Preserve the established non-fast 120-second
lifetime and fast-signal extra-bar window, but never use either lifetime as proof
that a slower execution delay was graded. An unknown grade grants no authority.
The fire also retains its own graded stop when another rule becomes the current
winner; the existing ordinary stop cap remains in force. Regression tests must
exercise the production admission-to-stop path and both signal lifetime cases.

### Release and performance boundaries for this follow-up

The preceding 5.0.9046 commit `cf901ca5a9555245e511195a7d16badd40b28dc9`
passed 233 standalone checks, 3,526 required tests and 10,316 historical tests,
with zero failures, errors or skips, plus release/upgrade/runtime gates. Those
results are evidence for that commit, not an automatic pass for 5.0.9047.
The final 9047 commit must independently pass the full release path, including the
new behavioral regressions, before its single accepted APK is delivered.

9046 runtime exercised queued durable-close delivery and snapshot array reuse.
Shared-monitor waits remain: a canonical-close callback waited 3.255 seconds
behind snapshot capture, and `CortexTiming.fusedMove` waited 23.680 seconds behind
its loading path. These are not proof that persistence encoding still blocks the
canonical callback. The emulator ran PAPER, light idle rather than deep idle,
and a much smaller dataset than the phone. Neither its green status nor the two
9047 contract repairs establishes handset tail latency, early-runner capture,
after-cost expectancy or a high win rate. Matched executable replay and a mature
forward cohort remain the financial acceptance gates described below.

## Scope and starting point

This change implements repairs in the existing AATE architecture on `main`, starting at
`21c60ff34f169331f1afe810724c6120b261583d` (5.0.9042). It follows the owner's 27 November
engineering repair contract. Native desks, configured modes, hard safety, executable economics,
and capital limits remain authoritative. No live order or mode promotion is part of validation.

The supplied 27 November 17:31 Brisbane report covers 522 seconds after startup. Its canonical
FDG section records 0 allows / 139 block events and EXEC=0. Crypto records 62 submissions,
0 allows and 0 dispatches. The one canonical open position is PAPER; LIVE inventory is zero.
These are event counts, not counts of missed winning trades. Seven-day P&L and restored runner
banks cannot be attributed to this eight-minute process.

The 9041 lease repair was downstream of approval and was not exercised. The 9042 seed change
removed headroom waiting but still produced a 474,618 ms seed (including the turn wait), with
one heavy job running without its permit. Neither narrow change constituted the full repair.

## Established causes and repaired paths

### Decision evidence and runtime scheduling

`DatasetReplay8863` now restores a common checkpoint for the pocket ledger, paying cells,
managed bands, agreement dataset cells and LLM bands, including deduplication state. The
checkpoint binds the evidence policy/learning epoch, exact byte cursor, the SHA-256 of the
entire consumed dataset prefix, and a complete payload checksum. Only complete appended rows
are replayed. A corrupt, incompatible, truncated or replaced dataset rebuilds the derived
views from retained source history. Interrupted cache writes retain the preceding generation.

All five states are restored and saved while the shared seed owner still holds the loading
gate; live-label workers are released afterwards. An individual sink failure in production
fails the seed attempt rather than publishing a partly applied multi-ledger generation.
Queued live updates retain their callbacks on the existing ledger workers until a complete
generation is ready. Readiness and mutation share the seed lock, so a failed loader cannot let
a live update race another worker's rebuild. Failed seed attempts use bounded retry pacing;
exceptions from a partly applied live mutation are not automatically retried.
The original dataset and economic journal are never overwritten by this cache.

`JobCost8813` gives decision seeding precedence over research working-set allocation. The seed
uses the existing yielding CPU priority. A timed-out or interrupted batch cannot execute
outside the exclusive turn. Dataset replay, launch evaluation and tree training cooperate in
bounded CPU slices while retaining that turn, so they do not claim to free memory they still own.

Cold migration, dataset rotation and an incompatible epoch still require a full rebuild.
First-start duration and device tail latency must be measured; a cache implementation alone
does not prove a five-second entry budget or the requested two-hour handset soak.

### Canonical-close persistence contention found in the 9044 runtime audit

Candidate `b0b97f26addb6d53ee1d111dcd6e4c934a2fbf6c` passed 3,507 required tests and
10,297 historical tests, with zero failures, errors or skips. Its
[runtime run](https://github.com/shaunhayes333-stack/lifecycle-bot/actions/runs/45975017393)
also passed. Reviewing the artifact exposed a remaining timing defect in an unchanged
baseline Cortex path: a durable-close caller waited 42.162 seconds for `persistLock8325`.
A reported snapshot encoded for 30.854 seconds outside the Cortex assessment monitor but
inside that persistence lock; the corresponding close-job measurement was 30.855 seconds
wall time and 1.016 seconds of that thread's CPU. Snapshot capture separately held the
shared Cortex monitor for 3.251 seconds. These are distinct measurements, not one global
30-second freeze. Bot loops and canonical PAPER receipts progressed during the wait.

The serialized encoder must remain bounded to avoid concurrent whole-graph buffers under
the observed allocation pressure. Canonical-close delivery therefore queues durability on
the persistence worker and returns `RETRY`; an exact durable receipt remains the condition
for acknowledgement. Books, pending observations, grades and their close receipts retain
their atomic transaction and once-only grading contract. Scheduled, coalesced retries must
continue through pacing and transient store refusal without needing a new market tick.

Snapshot capture also uses a capture-local identity memo for detached voter-edge arrays.
Assessments sharing a live voter-table array share one detached copy within that snapshot;
different source arrays and later snapshots remain separate. Mutable values, predictions,
traces, selection limits and receipts keep their existing copy/selection behavior. The memo
does not survive capture or cache a second whole graph. Capture own-thread CPU and copied/
reused edge-array counts are reported beside elapsed snapshot timing so the next runtime
can distinguish reduced copying from scheduling or garbage-collection delays.

This repair does not by itself establish a handset p99 or eliminate the separately observed
snapshot-capture hold. The runtime was PAPER with no live wallet/positions; it cold-seeded
only 78 dataset rows after restart. Its doze check exercised light idle because deep idle
was unavailable on that emulator. These boundaries remain explicit in acceptance reporting.

### Entry policy, evidence waits, handoff and attribution

The composed lane-authority path distinguishes the real current-high guard from learned stage
priors. An already-authorized model pick retains the learned-prior exemption declared by its
policy; hard safety, required data and executable economics remain binding.

Evidence waits belong to their lane and stable opportunity. Another lane's refusal cannot
remove a live wait. Candidate-version churn does not renew the original deadline. Per-mint
direct-request throttling is independent of wait ownership and ended/expired opportunities.
An attempt carried by a launch-fire authorization retains that fire's deadline through ticket
binding. The final first-submit boundary revalidates it before any provider send. An unsent
expiry is a local revalidation outcome, not a provider failure or permission to reuse old evidence.
After restart, a recovered signed BUY with no submitted marker remains subject to chain
resolution; missing in-memory authorization cannot authorize its first broadcast. Already
submitted signatures retain their existing identical-byte reconciliation path.

Causal handoff validates decision-captured receiving-scope epochs/revisions instead of copying
current receiving stamps over an old decision. Same-millisecond terminals and wall-clock
movement cannot erase intervening invalidation.

Launch tracking preserves distinct opportunities and matches candidate/lane, attempt and
signature where exact identity is available. First-event times remain paired with their own
verdicts. Unmatched legacy mint-only callbacks are not credited as exact fire captures.

### Research integrity and authority coverage

The supplied research file contains a 4,190,610,000% outcome for mint
`Q63Ud2GrbqSKDP6KZjBnozPFQiPjBLMRxDiR2AYakrt`, while its held-peak field is 2.96527%.
It contaminated nine authority cells. It predates this 9042 startup; no evidence established
that it trained the main MIND model. `ResearchEvidence9043` now validates the same research
outcome contract on append and read, including authority and pocket consumers. Unsupported
extremes remain visible as quarantined/unpriced evidence, not fabricated -100% observed trades.
Verified large moves retain their full observed values; no new trading return ceiling is added.

The only 1-hour PAYS door had 81 directly priced assets and claimed 100% coverage. Original
refusal identity was being changed by the `LOSTMARK8983/` observation wrapper. The actual
same-window cohort contains 321 distinct assets: 90 priced and 231 unpriced, or 28.0374%
coverage. Its observed priced mean is +26.4302%; under the existing explicitly conservative
missing=-100 stress calculation, its mean is -64.5523% and ex-top-three result -66.8440%.
It does not meet the existing PAYS rule. The regression fixture preserves all 321 rows.

Original refusal identity and read provenance are now separate fields. Known legacy prefixes
are normalized without rewriting source files. Missingness stays in the original population.
The defect was reproduced against all 438 exported door-cell count pairs, with zero mismatches.

### Frozen training targets

New predictions capture the effective stop with the decision. The forward-label observation
also freezes its decision, scalp and long play stops/ladders, execution latency assumptions,
window floor and meme doctrine inputs. Its checkpoint preserves that versioned recipe across
restart. Later policy changes cannot silently alter a new observation's original assumptions.

New labels retain a versioned, full-precision receipt for input net, costs, stop and exact
training target. Replay uses that receipt and rejects a damaged/unknown new receipt. Model and
managed-band arithmetic share the same existing recipe. Old observations remain explicitly
`LEGACY_RECOMPUTED`; old pending decisions labelled now are `LEGACY_LABEL_TIME_STOP`, never
retrospectively decision-frozen. Valid history is preserved. This improves reproducibility
without inventing missing history.

### Crypto and runner continuity

The canonical crypto data-wait path now applies the existing integrity-versus-history policy
before its early return. Pending buy-door safety proof is explicitly critical. Only otherwise
eligible, independently backed native crypto may carry noncritical missing history through to
the remaining economics, sizing and authority checks. WAIT and terminal BLOCK counters differ.

Runner state uses mode and canonical position identity. Ticket acceptance is not broadcast.
Actual sends and uncertain submissions are joined to the durable exact transaction intent;
restart and timeout do not prove an uncertain send failed. Confirmed partial raw quantity and
duplicate economic callbacks must update a rung exactly once. Stale closed-position cleanup
must not clear a concurrently replaced holding. The focused regression suite covers these
state transitions, including correct refusal and retry paths.

xStocks being unarmed, Hyperliquid LIVE_OFF/testnet, unsupported routes, and negative own-record
backing are separate conditions. This change does not activate those desks to manufacture
activity. Nine retained orphan-sell lineage exceptions still require trustworthy original
receipts/basis; no entry history or P&L is invented to make reconciliation green.

## Acceptance and delivery contract

The required build filter includes every `Aate9043*` and `Aate9045*` test. Existing required and historical
suites, signed APK verification, retained-state upgrade checks, and exact-commit Android
runtime/device checks remain required. No failure baseline or acceptance threshold is relaxed.

| Requirement | Concrete evidence required |
| --- | --- |
| R01 baseline and scope | Starting SHA above, four supplied exports, exact changes and final main SHA |
| R02 lifecycle identity | Separate fires/lanes, exact gate time/reason and attempt/signature joins; unknowns explicit |
| R03 canonical verdict | Composed entry and crypto tests with positive policy, correct hard refusal and WAIT |
| R04 data recovery | Competing lanes, deadline expiry, stable opportunity and independent provider throttle |
| R05 timing | Permit timeout/cancellation, evidence-before-research, checkpoint/tail tests; handset timing separately measured |
| R06 ownership/handoff | Captured destination epochs, same-ms terminal, changed scope and no unsupported restamp |
| R07 economics/size | Existing economics and size regression gates plus unchanged critical crypto checks |
| R08 exits/finality | Rejected, uncertain, restarted, partial and duplicate rung delivery with exact intent identity |
| R09 realized capture | Preserve exit policy; no claim of improved trading returns from code or peak labels alone |
| R10 evidence authority | Actual 321-row fixture, extreme quarantine, consumer parity and frozen-target receipts |
| R11 accounting | Existing economic/upgrade/runtime gates; retained orphans remain unresolved until proven |
| R12 release | Exact final main SHA, signed nondebuggable APK and checksum, passing CI and honest limitations |

Local focused tests establish only their named behaviors. The full application and emulator
must pass on the final commit. The requested target-phone two-hour workload, 1,000 observations
per p99 claim, executable chronological replay with original quote/tape availability, and
forward after-cost performance cannot be certified from the short supplied summary exports.
Do not report them as passed without their actual evidence. Engineering delivery and forward
trading performance have separate completion statuses.

## Recovery

The decision checkpoint is a rebuildable derivative; invalidating it never deletes original
labels or the journal. Legacy research and target rows remain readable with explicit quality
and provenance. Do not downgrade while transaction status is uncertain or discard persisted
pending runner attempts. Resolve pending signatures and reconcile actual holdings before an
operator-directed rollback. Preserve the source dataset, economic journal and transaction
ledger when diagnosing any upgrade/restart failure.
