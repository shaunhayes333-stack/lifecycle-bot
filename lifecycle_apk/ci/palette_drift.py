#!/usr/bin/env python3
"""
V5.0.7015 — palette drift guard.

WHY THIS EXISTS
===============
The operator restyled this app three times (V5.0.6931, 6998, 7007) and each
time reported that the screens looked the same. The reason was never a bad
colour choice. It was that the app had FOUR palettes running at once and only
one of them lived in res/values/colors.xml:

  1. res/values/colors.xml            — what every restyle edited
  2. AateUi.kt's constants            — claimed to mirror (1), silently drifted
                                        at V5.0.7007 and stayed drifted
  3. per-screen `private val green`   — CryptoAlt, CollectiveBrain, Lab and
     blocks                             Journal each invented their own
  4. 552 inline 0xAARRGGBB literals   — scattered through 22 files in the ui
                                        package, reachable from nothing

V5.0.7013-7014 collapsed (2), (3) and (4) into (1). This script is what stops
them separating again, because nothing else can: the app compiles, runs and
looks wrong, and the only signal is a human opening it and saying "that did not
land". That is an 18-minute build plus a device install plus a judgement call.
This is a second.

WHAT IT CHECKS
==============
  A. AateUi's constants equal the colors.xml entries they name. A drift here is
     the 7007 failure exactly, and it is invisible in review because both sides
     are individually correct.

  B. No retired palette literal reappears in the ui package. These are the
     PRE-7007 values — the mint green, the blue-violet purple, the near-white
     text. A new literal is not banned; a resurrected one is, because it means
     someone pasted from an old screen.

Exit code 1 on either. Both failures name the file, the line and the token to
use instead, so the fix is mechanical.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, "..", "app", "src", "main")
COLORS_XML = os.path.join(APP, "res", "values", "colors.xml")
AATE_UI = os.path.join(APP, "kotlin", "com", "lifecyclebot", "ui", "AateUi.kt")
UI_DIR = os.path.join(APP, "kotlin", "com", "lifecyclebot", "ui")

# AateUi constant -> the colors.xml name it claims to mirror.
MIRROR = {
    "BG": "aate_bg",
    "BG_DEEP": "aate_bg_deep",
    "SURFACE": "aate_surface",
    "SURFACE_2": "aate_surface_2",
    "SURFACE_3": "aate_surface_3",
    "STROKE": "aate_stroke",
    "STROKE_SOFT": "aate_stroke_soft",
    "TEXT": "aate_text",
    "TEXT_SECONDARY": "aate_text_secondary",
    "TEXT_MUTED": "aate_text_muted",
    "PURPLE": "aate_purple",
    "PURPLE_BRIGHT": "aate_purple_bright",
    "BLUE": "aate_blue",
    "CYAN": "aate_cyan",
    "GREEN": "aate_green",
    "AMBER": "aate_amber",
    "RED": "aate_red",
    "PINK": "aate_pink",
}

# Pre-7007 values, retired by V5.0.7013. hex -> the token that replaced it.
RETIRED = {
    "16E6A1": "AateUi.GREEN",
    "FF4D6D": "AateUi.RED",
    "FFB020": "AateUi.AMBER",
    "F5F7FF": "AateUi.TEXT",
    "63759B": "AateUi.TEXT_MUTED",
    "A7B7D8": "AateUi.TEXT_SECONDARY",
    "9A4DFF": "AateUi.PURPLE",
    "B36BFF": "AateUi.PURPLE_BRIGHT",
    "31C7FF": "AateUi.CYAN",
    "0D192B": "AateUi.SURFACE",
    "101E33": "AateUi.SURFACE_3",
    "193250": "AateUi.STROKE_SOFT",
    "2B4B78": "AateUi.STROKE",
    "030712": "AateUi.BG",
}

# AateUi and AateComponents6994 are where the palette is DEFINED, and the
# retired values are named in the drift guard's own docs. They are exempt.
EXEMPT = {"AateUi.kt", "AateComponents6994.kt"}

LITERAL_RE = re.compile(
    r'(?:0xFF(?P<a>[0-9A-Fa-f]{6})\.toInt\(\))'
    r'|(?:parseColor\("#(?P<b>[0-9A-Fa-f]{6})"\))'
)


def read_colors_xml():
    with open(COLORS_XML, encoding="utf-8") as fh:
        body = fh.read()
    out = {}
    for name, value in re.findall(
        r'<color\s+name="([^"]+)"\s*>\s*#([0-9A-Fa-f]{6,8})\s*</color>', body
    ):
        out[name] = value.upper()[-6:]
    return out


def read_aate_ui():
    with open(AATE_UI, encoding="utf-8") as fh:
        body = fh.read()
    out = {}
    for name, value in re.findall(
        r'const\s+val\s+([A-Z_0-9]+)\s*=\s*0x([0-9A-Fa-f]{8})\.toInt\(\)', body
    ):
        out[name] = value.upper()[-6:]
    return out


def main():
    problems = []

    # ── A. mirror check ───────────────────────────────────────────────────
    xml = read_colors_xml()
    kt = read_aate_ui()
    checked = 0
    for const, res_name in sorted(MIRROR.items()):
        if const not in kt:
            problems.append(
                "MISSING_CONSTANT AateUi.%s is declared in the mirror map but "
                "not in AateUi.kt" % const
            )
            continue
        if res_name not in xml:
            problems.append(
                "MISSING_COLOR colors.xml has no <color name=\"%s\">, which "
                "AateUi.%s claims to mirror" % (res_name, const)
            )
            continue
        checked += 1
        if kt[const] != xml[res_name]:
            problems.append(
                "PALETTE_DRIFT AateUi.%s = #%s but colors.xml %s = #%s — the "
                "Kotlin-painted screens and the XML screens are now two "
                "different colour schemes. Move both or neither."
                % (const, kt[const], res_name, xml[res_name])
            )

    # ── B. retired literal check ──────────────────────────────────────────
    scanned = 0
    for fname in sorted(os.listdir(UI_DIR)):
        if not fname.endswith(".kt") or fname in EXEMPT:
            continue
        scanned += 1
        path = os.path.join(UI_DIR, fname)
        with open(path, encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                for m in LITERAL_RE.finditer(line):
                    hexv = (m.group("a") or m.group("b")).upper()
                    if hexv in RETIRED:
                        problems.append(
                            "RETIRED_LITERAL %s:%d uses #%s, a pre-7007 palette "
                            "value. Use %s." % (fname, lineno, hexv, RETIRED[hexv])
                        )

    print("palette_drift: %d constants mirrored, %d ui files scanned"
          % (checked, scanned))
    if problems:
        for p in problems:
            print("::error::palette_drift %s" % p)
        print("palette_drift: FAIL — %d problem(s)" % len(problems))
        return 1
    print("palette_drift: OK — one palette, two languages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
