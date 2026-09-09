# V5.0.6705 specialist authority repair

Authoritative source repair after the red V5.0.6702 release path.

## Repairs carried forward

- V5.0.6703 restores the established pending-sell closing/closed telemetry contract without weakening close authority.
- V5.0.6704 removes the fabricated pre-FDG executable causal identity from CanonicalSizingBridge6532. Pre-FDG sizing remains active/advisory and can only stamp executable sizing telemetry when an immutable ExecutionIntent already owns the exact mode/mint/candidateVersion.
- V5.0.6704 patch-rot checks pin that causal-authority rule so the 6674 fallback cannot return.
- V5.0.6705 removes CASHGEN from ExecutableOpenGate's shadow/read-only set. MemeOwnershipInvariant6620 already defines CASHGEN as a canonical executable specialist and STANDARD/V3_CORE as the observer-only lanes.
- Aate6705CashgenExecutionAuthorityTest verifies a CASHGEN FDG BUY materializes one immutable PAPER ExecutionIntent and verifies the observer-only source contract.
- patch_rot_scan.py now forbids reintroducing CASHGEN into the shadow-only set.

## Acceptance requirement

The release is not considered green unless the standard Build AATE APK workflow passes its literal scan, authority contradiction scan, repository patch-rot scan, complete release unit-test suite, release assembly, and artifact upload on this source lineage.
