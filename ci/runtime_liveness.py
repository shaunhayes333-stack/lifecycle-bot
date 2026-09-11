#!/usr/bin/env python3
"""Read actual loop events, not dashboard counts or lossy-label absence.

BOT_LOOP_TICK and LOOP_TOP are emitted by the same production bot loop.
Neither this helper nor a successful liveness probe replaces the separate
completed-window execution-spine acceptance check.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re

TICK = re.compile(r"\[BOT_LOOP_TICK\]\s+n=[1-9]\d*\s+watch=")
LOOP_TOP = re.compile(
    r"\[LOOP_TOP\]\s+#\d+\s+loop=[1-9]\d*\s+running=true\s+loopJob=true(?:\s|$)"
)


def loop_count(text: str) -> int:
    """Count witnessed loop records, never inferred trades or fills."""
    return sum(bool(TICK.search(line) or LOOP_TOP.search(line)) for line in text.splitlines())


def has_marker(text: str, expression: str) -> bool:
    # Existing callers supply literal labels, or a | separated set of labels.
    # Expand only the dedicated loop label. A mere count/report reference to
    # BOT_LOOP_TICK must not pass the startup liveness check.
    labels = expression.split("|")
    if "BOT_LOOP_TICK" in labels and loop_count(text):
        return True
    return any(label and label != "BOT_LOOP_TICK" and label in text for label in labels)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["has-marker", "count-loop"])
    parser.add_argument("log", type=Path)
    parser.add_argument("expression", nargs="?", default="BOT_LOOP_TICK")
    args = parser.parse_args()
    try:
        text = args.log.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        parser.exit(2, f"Cannot read runtime evidence: {exc}\n")
    if args.action == "count-loop":
        print(loop_count(text))
        return 0
    return 0 if has_marker(text, args.expression) else 1


if __name__ == "__main__":
    raise SystemExit(main())
