# Offline Cortex model regression harness

Run from any directory with Java 17, Bash and `unzip` installed:

```sh
bash lifecycle_apk/ci/cortex-jvm/run.sh --fetch --deps-dir /tmp/aate-cortex-jvm-deps
```

`--fetch` is needed only on the first run with approved network access. Dependencies are pinned to Kotlin 1.9.24, JUnit 4.13.2 and the app's JSON version and fetched from Maven Central. Omit `--fetch` for subsequent fully offline runs. The script fails rather than silently downloading when a dependency is missing without that flag.

Build outputs go to a fresh temporary directory printed by the script. Supply `--output-dir /path/to/test-results` for a reproducible destination. No emulator, Android SDK, running application, wallet credentials, RPC, exchange connection or trade executor is used.

The harness compiles the actual production ledger, scoreboard, calibration, grade book, label hygiene, frozen-verdict authority, ranking diagnostics, shadow-outcome persistence, outcome ownership, compressed trace archive, cross-asset learning seal, native learning enrollment, source-episode models and ScannerSourceBrain. Tests exercise per-token discrimination; both YES and NO wins/losses; own-lane attribution; late episode revisions after 1,600 intervening grades and restart; raw arithmetic calibration; frozen STRONG versus non-STRONG authority without future-outcome leakage; explicit unavailable shadow outcomes; selected-exit receipts and restart idempotence; source corrections and duplicate prevention; and legacy-versus-new source cohort separation.

Only service-facing adapters are supplied locally: cross-asset lane names, runner lane names, the desk setup vocabulary, Android preferences backed by an in-memory map, diagnostics, source tags and source-to-lane mapping. Prediction, training, calibration, authority arithmetic, JSON persistence and revisions are not stubbed. ScannerSourceBrain integration tests execute its real reader/writer code; Android disk durability and the actual service adapters remain outside this harness.

The actual FirstSight permission reader and LearningEntryTruth display calculations also compile here. FirstSight tests seed only isolated cached tickets and exercise revocation, wrong-lane and stale-mark refusals. Their TokenState adapter is a DTO; wallet, pricing, runner creation and grant-publication adapters fail immediately if reached, rather than grant synthetic authority.

Production and tests compile as separate Kotlin modules with friend visibility, matching Gradle's main/test boundary. This catches cross-module type/smart-cast errors that a one-module standalone compile would miss.

This does not compile the Android `Cortex7885`, `ExploreShadow8347`, scanner orchestration or execution orchestration. A few wiring assertions read those sources; they are not runtime integration tests. The Android unit suite and application build remain required before deployment. Passing model tests is not evidence of improved live profitability.

`Aate8372CortexRuntimeCodecTest` belongs to that Android unit suite, not this harness. It invokes the actual runtime's private entry and graded-checkpoint codecs, checking frozen assessment metadata, sparse voter edges, original prequential predictions, exact decay weights and lane/source/mode ownership. It does not call the runtime's assessment, persistence, service or execution entry points.

`Aate8372CortexRuntimeScoringTest` likewise belongs to Android unit tests. It calls the actual runtime score readers for flat-fit per-token discrimination and arithmetic net expectancy versus the preserved log-return diagnostic.

The harness also compiles the actual atomic replay-memo and reconciliation-publication helpers. Latch-controlled tests cover late older completion, revision/key mismatches, unchanged reuse, exclusive publication, stale-pass rejection and exception release. The surrounding journal replay and forensic reconciler are not compiled by this harness. `Aate8372ReplayAccountingTest` belongs to the Android unit suite and uses their actual replay value and freshness/attribution code to preserve the observed 1 SOL / 60,707,108,925 raw-unit discrepancy as a regression fixture.
