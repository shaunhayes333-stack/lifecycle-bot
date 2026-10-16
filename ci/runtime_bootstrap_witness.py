#!/usr/bin/env python3
"""V5.0.8144 — name the bootstrap step that failed or stalled, from logcat.

Every red Runtime Smoke from 5.0.8117 to 5.0.8143 annotated
``lastServicePhase=CORE_STORES_READY serviceFailure=Undefined``. "Undefined" was
never an exception class: the witness regex ``SERVICE_BOOTSTRAP_FAILED_6516.*?type=``
also matched START_BLOCKED_SERVICE_BOOTSTRAP_FAILED_6516, whose ``failure=`` field
carries ART's verifier text (``copy1 v1<-v2 type=Undefined cat=1``), so the one
annotation a remote reviewer can read named a register type instead of the
VerifyError and the class ART rejected. And the harness then waited out the full
360-second window for a service that had already failed terminally.

This module reads the failure and the 8144 step witnesses precisely:
  failed    exit 0 when the service bootstrap has failed terminally (no READY);
  annotate  print ``::error`` annotations: failure type, rejected class, the step
            the failure or stall happened in, and the slowest steps.
It never certifies a pass; it only makes a red run say why.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

READY = "SERVICE_BOOTSTRAP_READY_6516"
UI_START = "UI_START_DISPATCHED_6517"
# The service's own forensic failure line: "SERVICE_BOOTSTRAP_FAILED_6516  type=X msg=...".
# Not START_BLOCKED_SERVICE_BOOTSTRAP_FAILED_6516, not the ErrorLogger echo with a colon.
FAILED = re.compile(r"(?<![A-Z_])SERVICE_BOOTSTRAP_FAILED_6516\s+type=([A-Za-z0-9_]+)")
REJECTED = re.compile(r"Verifier rejected class ([A-Za-z0-9_.$]+)")
VERIFY_DETAIL = re.compile(r"Verifier rejected class [^\n]{0,400}")
STEP_FAILED = re.compile(r"SERVICE_BOOTSTRAP_FAILED_STEP_8144 [^\n]*")
STALL = re.compile(r"SERVICE_BOOTSTRAP_STALL_8144 [^\n]*")
SLOWEST = re.compile(r"SERVICE_BOOTSTRAP_SLOWEST_8144 [^\n]*")


def _last(pattern: re.Pattern[str], text: str, group: int = 0) -> str:
    found = [m.group(group) for m in pattern.finditer(text)]
    return found[-1] if found else ""


def service_failure_type(text: str) -> str:
    """Exception class of the last service-bootstrap failure, or ''."""
    return _last(FAILED, text, 1)


def rejected_class(text: str) -> str:
    """Class ART's verifier rejected, or ''."""
    return _last(REJECTED, text, 1)


def terminal_failure(text: str) -> bool:
    """The service bootstrap failed after the last UI Start dispatch and never reported ready.

    Only the window after the last UI_START_DISPATCHED_6517 counts, so a failure left
    in the capture by an earlier, force-stopped process can never cut a fresh start short.
    """
    cut = text.rfind(UI_START)
    window = text[cut:] if cut >= 0 else text
    return bool(service_failure_type(window)) and READY not in window


def annotations(text: str) -> list[str]:
    out: list[str] = []
    failure = service_failure_type(text)
    if failure:
        out.append(f"::error title=BOOTSTRAP_FAILURE_8144::type={failure}")
    rejected = rejected_class(text)
    if rejected:
        out.append(f"::error title=BOOTSTRAP_REJECTED_CLASS_8144::{rejected}")
        detail = _last(VERIFY_DETAIL, text)
        if detail:
            out.append(f"::error title=BOOTSTRAP_VERIFIER_DETAIL_8144::{_esc(detail)}")
    for title, pattern in (("BOOTSTRAP_FAILED_STEP_8144", STEP_FAILED),
                           ("BOOTSTRAP_STALL_8144", STALL),
                           ("BOOTSTRAP_SLOWEST_8144", SLOWEST)):
        line = _last(pattern, text)
        if line:
            out.append(f"::error title={title}::{_esc(line[:900])}")
    return out


def _esc(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def _read(paths: list[str]) -> str:
    chunks = []
    for p in paths:
        try:
            chunks.append(Path(p).read_text(encoding="utf-8", errors="replace"))
        except OSError:
            continue
    return "\n".join(chunks)


def main(argv: list[str]) -> int:
    if len(argv) < 3 or argv[1] not in ("failed", "annotate"):
        print("usage: runtime_bootstrap_witness.py failed|annotate LOG [LOG...]", file=sys.stderr)
        return 2
    text = _read(argv[2:])
    if argv[1] == "failed":
        return 0 if terminal_failure(text) else 1
    for line in annotations(text):
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
