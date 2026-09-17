#!/usr/bin/env python3
"""Parse committed paper receipts and the non-lossy acceptance witness.

This observer never treats trade attempts, restored inventory, or a missing
acceptance report as successful execution. No trading thresholds are changed.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

START = "EXECUTION_SPINE_WINDOW_STARTED_6662"
OK = "EXECUTION_SPINE_ACCEPTANCE_6647_OK"
FAIL = "EXECUTION_SPINE_ACCEPTANCE_6647_FAIL"
OPEN = "PAPER_TICKET_TERMINAL_OPEN_6514"
FIELD = re.compile(r"\b([A-Za-z][A-Za-z0-9_]*)=([^\s]+)")


def inspect_log(text: str) -> dict[str, Any]:
    """Read one captured run, deduplicating repeated receipt/witness lines."""
    starts: list[int] = []
    witnesses: dict[tuple[str, str, str], dict[str, str]] = {}
    buys: set[str] = set()
    malformed_receipts = 0
    for line in text.splitlines():
        fields = dict(FIELD.findall(line))
        if START in line and fields.get("atMs", "").isdigit():
            starts.append(int(fields["atMs"]))
        if OPEN in line and fields.get("committed", "").lower() == "true":
            ticket = fields.get("ticketId", "")
            if ticket and ":PAPER:" in ticket and fields.get("paper", "").lower() == "true":
                buys.add(ticket)
            else:
                malformed_receipts += 1
        # Direct tag is the dedicated witness; forensic copies are diagnostic,
        # but may still be parsed for older builds and duplicate harmlessly.
        verdict = "FAIL" if FAIL in line else "OK" if OK in line else None
        if verdict:
            fields["verdict"] = verdict
            key = (fields.get("windowStartMs", ""), fields.get("durationMs", ""), verdict)
            witnesses[key] = fields
    latest = max(starts, default=0)
    reasons: list[str] = []
    current = [v for v in witnesses.values() if v.get("windowStartMs", "").isdigit()
               and int(v["windowStartMs"]) == latest and latest > 0]
    failures = [v for v in witnesses.values() if v["verdict"] == "FAIL"]
    passing = [v for v in current if v["verdict"] == "OK"
               and v.get("durationMs", "").isdigit() and int(v["durationMs"]) >= 120_000]
    if latest == 0:
        reasons.append("NO_WINDOW_START")
    if failures:
        reasons.extend(v.get("failures", "UNSPECIFIED_ACCEPTANCE_FAILURE") for v in failures)
    if not passing:
        reasons.append("NO_COMPLETED_PASSING_CURRENT_WINDOW")
    return {
        "passed": not reasons,
        "latest_window_start_ms": latest,
        "canonical_paper_buys": len(buys),
        "unidentified_committed_receipts": malformed_receipts,
        "acceptance_witnesses": list(witnesses.values()),
        "failures": reasons,
        # V5.0.6888 — the observed values V5.0.6884 added to the FAIL witness.
        # Carried out separately so they can be annotated without being
        # mistaken for failure reasons by anything reading `failures`.
        "failure_details": [v["detail"] for v in failures if v.get("detail")],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--output", type=Path, default=Path("runtime_evidence.json"))
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    try:
        result = inspect_log(args.log.read_text(encoding="utf-8", errors="replace"))
    except OSError as exc:
        result = {"passed": False, "failures": [f"LOG_UNAVAILABLE:{exc}"], "canonical_paper_buys": 0}
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    report = ("===== Canonical runtime evidence (receipt-derived) =====\n"
              f"Unique committed PAPER buy tickets: {result['canonical_paper_buys']}\n"
              f"Current acceptance window passed: {result['passed']}\n"
              f"Failures: {' | '.join(result['failures']) or 'none'}\n"
              "Legacy PAPER_BUY_OK and PAPER_TICKET_COMMITTED label counts are not fill proof.\n")
    print(report, end="")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as handle:
            handle.write("\n" + report)
    if not result["passed"]:
        _annotate(result)
    return 0 if result["passed"] else 1


def _annotate(result: dict[str, Any]) -> None:
    """Emit the verdict as GitHub Actions error annotations.

    V5.0.6888 — this report was printed to stdout only. Step logs and the
    uploaded artifacts both live in blob storage, which is not reachable from
    every environment that needs to read a red build; the sole failure
    annotation on a red smoke run was the runner's own generic "Process
    completed with exit code 1", so a reviewer could see THAT the acceptance
    witness failed but never WHICH invariant. Annotations are served by the
    REST API (/check-runs/{id}/annotations) rather than blob storage, so
    routing the reasons through `::error::` makes every red build readable
    wherever the API is.

    Purely additive: the printed report, the JSON output, the summary file and
    the exit code are all unchanged.
    """
    def esc(text: str) -> str:
        # GitHub workflow-command escaping for annotation message payloads.
        return (str(text).replace("%", "%25")
                .replace("\r", "%0D").replace("\n", "%0A"))

    for reason in result.get("failures", []):
        print(f"::error title=ACCEPTANCE_FAILURE::{esc(reason)}")
    for detail in result.get("failure_details", []):
        print(f"::error title=ACCEPTANCE_DETAIL::{esc(detail)}")
    print(
        "::error title=ACCEPTANCE_SUMMARY::"
        f"paperBuyTickets={result.get('canonical_paper_buys', 0)} "
        f"latestWindowStartMs={result.get('latest_window_start_ms', 0)} "
        f"unidentifiedReceipts={result.get('unidentified_committed_receipts', 0)}"
    )


if __name__ == "__main__":
    raise SystemExit(main())
