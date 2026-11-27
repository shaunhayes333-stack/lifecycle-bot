# AATE 5.0.9043 — entry timing, evidence, and execution continuity

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

The required build filter includes every `Aate9043*` test. Existing required and historical
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
