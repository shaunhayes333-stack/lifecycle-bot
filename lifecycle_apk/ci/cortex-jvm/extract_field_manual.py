#!/usr/bin/env python3
"""Compile the exact pure FieldManual mandate declarations without Android services.

The harness does not substitute a second lane/horizon table. Only these declarations
are copied verbatim from the checked-out production file; all grading, evidence,
epoch, calibration and ledger classes compile from their complete production files.
Deliberately strict declaration boundaries fail on unsupported source-format drift.
"""
from pathlib import Path
import re
import sys


def one_block(source: str, header: str, closing: str) -> str:
    lines = source.splitlines(keepends=True)
    starts = [i for i, line in enumerate(lines) if line.rstrip("\n") == header]
    if len(starts) != 1:
        raise ValueError(f"Expected exactly one production declaration: {header.strip()}")
    start = starts[0]
    for end in range(start + 1, len(lines)):
        if lines[end].rstrip("\n") == closing:
            return "".join(lines[start:end + 1])
    raise ValueError(f"Missing declaration boundary: {header.strip()}")


def extract(source: str) -> str:
    setup = one_block(source, "    enum class SetupFamily(val code: Char, val label: String) {", "    }")
    mandate = one_block(source, "    data class LaneMandate(", "    )")
    lookup = one_block(source, "    fun mandateFor(lane: String?): LaneMandate {", "    }")
    starts = re.findall(r"(?m)^    private val ([A-Z_]+_LANE) = LaneMandate\(", source)
    declarations = re.findall(r"(?m)^    private val [A-Z_]+_LANE = LaneMandate\([^\n]+\)$", source)
    if not starts or len(starts) != len(set(starts)) or len(starts) != len(declarations):
        raise ValueError("Lane mandate declarations changed shape; update the strict extractor")
    required = set(re.findall(r"\b[A-Z_]+_LANE\b", lookup))
    if not required.issubset(set(starts)):
        raise ValueError(f"Missing production mandates: {sorted(required - set(starts))}")
    return ("package com.lifecyclebot.engine.truth\n\n"
            "// Generated from the checked-out production FieldManual7715; no copied policy table.\n"
            "object FieldManual7715 {\n" + setup + "\n" + mandate + "\n"
            + "\n".join(declarations) + "\n\n" + lookup + "}\n")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: extract_field_manual.py production/FieldManual7715.kt output/FieldManual7715.kt")
    source = Path(sys.argv[1]).read_text(encoding="utf-8")
    output = extract(source)
    Path(sys.argv[2]).write_text(output, encoding="utf-8")
    print("Extracted unchanged production FieldManual lane mandate declarations")


if __name__ == "__main__":
    main()
