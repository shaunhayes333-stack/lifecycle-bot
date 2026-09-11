# V5.0.6735 CI witness repair

Base: 2fbce390a32c3459d32a445a27a56418a12d7826 (production 6734).

## Verified input

Run 34603501492: full release units and APK passed; runtime job 103281354799
failed because the enforced execution-spine witness was missing. Its captured
log contains 13 unique committed PAPER_TICKET_TERMINAL_OPEN_6514 receipts.
The legacy summary reported PAPER_BUY_OK=0 because that label is not the
current commit receipt. The earlier, unpromoted 6735 recovery run 34603215324
has five assertion failures; that candidate is not copied onto this source.

## Changes

- Acceptance start/result witnesses go directly to Android logcat in addition
  to the forensic stream. ForensicLogger drops LIFECYCLE under queue pressure;
  mandatory acceptance is too infrequent and too important to use that queue alone.
- Remove journal repair/replay from acceptance close. It consumes reconciler
  evidence, retaining exact failure rules for unknown/invalid economics.
- Audit reads the completed result instead of closing/rebasing a sampling window.
- Existing runtime workflow uses receipt-derived buy counts and requires a
  completed 120-second passing witness from its current start. Any reported
  failed invariant still fails CI. Test evidence is uploaded even when red.

## Validation

Golden Tape literal scan, authority contradiction scan, and patch-rot scan pass
locally. Eight parser tests pass. Actual production acceptance Kotlin compiled
and passed a dependency-isolated harness testing warm-up, restart, result
retention, unavailable forensic logging, and unreconciled-account rejection.
Full Android release units, APK assembly and emulator validation require CI;
they are not claimed passed for this new source before those runs complete.

This is a scoped runtime/CI repair, not a claim that pricing, strategy,
reconciliation, win rate or profitability is completely repaired.
