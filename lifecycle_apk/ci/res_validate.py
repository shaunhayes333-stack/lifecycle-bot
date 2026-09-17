#!/usr/bin/env python3
"""
V5.0.6933 — Android resource pre-flight.

Why this exists: the vNext restyle burned three CI cycles (~45 minutes) red on
resource errors that are all detectable statically in about a second. Every
check here runs before Gradle is invoked, so a bad drawable fails the build in
2 seconds instead of 14 minutes.

FAILS the build only on categories that cannot false-positive:

  * malformed XML
  * duplicate resource name within one values file and type
  * radial <gradient> with no android:gradientRadius        (AAPT2 fatal)
  * <gradient> with endColor but no startColor              (AAPT2 fatal)
  * <gradient> with centerColor but not both ends           (AAPT2 fatal)
  * invalid colour literal (not #RGB/#ARGB/#RRGGBB/#AARRGGBB)

WARNS (exit 0) on unresolved @color/@dimen/@drawable/@style references. That
one is advisory on purpose: framework and AppCompat resources (for example
?attr/selectableItemBackground, Widget.AppCompat.ProgressBar.Horizontal) live
outside this tree and are legitimately unresolvable from here. Warning rather
than failing keeps the gate trustworthy — a check that cries wolf gets
disabled, and then it protects nothing.

Mirrors the ci/*_scan.py convention already used by the build workflow.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

A = "{http://schemas.android.com/apk/res/android}"
def _find_res():
    """Locate app/src/main/res regardless of the caller's working directory.

    V5.0.6935: the build workflow sets `working-directory: lifecycle_apk` for
    every run step, so a repo-root-relative path silently fails there. This
    walks up from the script to find the res tree, which works from the repo
    root, from lifecycle_apk, or from anywhere else.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    for base in (here, os.path.dirname(here), os.path.dirname(os.path.dirname(here)), os.getcwd()):
        for cand in (
            os.path.join(base, "app", "src", "main", "res"),
            os.path.join(base, "lifecycle_apk", "app", "src", "main", "res"),
        ):
            if os.path.isdir(cand):
                return cand
    return os.path.join("lifecycle_apk", "app", "src", "main", "res")


RES = _find_res()
HEX = re.compile(r"^#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
REF = re.compile(r"[@?](?:android:)?(\w+)/([\w.]+)")

# Resource names that resolve against the framework / AppCompat, not this tree.
FRAMEWORK_PREFIX = (
    "Widget.AppCompat", "Theme.AppCompat", "TextAppearance.AppCompat",
    "Widget.Material", "Theme.Material", "TextAppearance.Material",
    "Base.", "android:",
)
FRAMEWORK_ATTRS = {
    "selectableItemBackground", "selectableItemBackgroundBorderless",
    "actionBarSize", "colorPrimary", "colorAccent", "colorControlNormal",
    "textAppearanceHeadline6", "textAppearanceBody2", "dividerHorizontal",
}

fatal, warn = [], []


def rel(p):
    return os.path.relpath(p, RES)


def collect_definitions():
    defined = defaultdict(set)
    for d in sorted(os.listdir(RES)):
        full = os.path.join(RES, d)
        if not os.path.isdir(full):
            continue
        if d.startswith("values"):
            for f in sorted(os.listdir(full)):
                if not f.endswith(".xml"):
                    continue
                p = os.path.join(full, f)
                try:
                    root = ET.parse(p).getroot()
                except Exception as e:
                    fatal.append(f"MALFORMED_XML {rel(p)}: {e}")
                    continue
                seen = defaultdict(set)
                for el in root:
                    name = el.get("name")
                    if not name:
                        continue
                    kind = el.get("type") if el.tag == "item" else el.tag
                    if not kind:
                        continue
                    if name in seen[kind]:
                        fatal.append(f"DUPLICATE_RESOURCE {kind}/{name} in {d}/{f}")
                    seen[kind].add(name)
                    defined[kind].add(name)
        else:
            kind = "mipmap" if d.startswith("mipmap") else d.split("-")[0]
            for f in os.listdir(full):
                defined[kind].add(f.rsplit(".", 1)[0])
    return defined


def check_colour_literals_and_gradients():
    for dirpath, _, files in os.walk(RES):
        for f in files:
            if not f.endswith(".xml"):
                continue
            p = os.path.join(dirpath, f)
            try:
                root = ET.parse(p).getroot()
            except Exception as e:
                fatal.append(f"MALFORMED_XML {rel(p)}: {e}")
                continue
            for el in root.iter():
                for k, v in el.attrib.items():
                    if v.startswith("#") and not HEX.match(v):
                        fatal.append(f"BAD_COLOUR {rel(p)}: {k.split('}')[-1]}={v}")
                if el.text and el.tag == "color":
                    t = el.text.strip()
                    if t.startswith("#") and not HEX.match(t):
                        fatal.append(f"BAD_COLOUR {rel(p)}: {el.get('name')}={t}")
            for g in root.iter("gradient"):
                gtype = g.get(A + "type")
                start = g.get(A + "startColor")
                end = g.get(A + "endColor")
                center = g.get(A + "centerColor")
                if gtype == "radial" and g.get(A + "gradientRadius") is None:
                    fatal.append(f"RADIAL_GRADIENT_NO_RADIUS {rel(p)} (AAPT2 fatal)")
                if end is not None and start is None:
                    fatal.append(f"GRADIENT_END_WITHOUT_START {rel(p)} (AAPT2 fatal)")
                if center is not None and (start is None or end is None):
                    fatal.append(f"GRADIENT_CENTER_WITHOUT_BOTH_ENDS {rel(p)} (AAPT2 fatal)")


def check_references(defined):
    unresolved = defaultdict(set)
    for dirpath, _, files in os.walk(RES):
        for f in files:
            if not f.endswith(".xml"):
                continue
            p = os.path.join(dirpath, f)
            try:
                txt = open(p, encoding="utf-8").read()
            except Exception:
                continue
            for m in REF.finditer(txt):
                kind, name = m.group(1), m.group(2)
                if m.group(0).startswith("@android:") or m.group(0).startswith("?android:"):
                    continue
                if name.startswith(FRAMEWORK_PREFIX) or name in FRAMEWORK_ATTRS:
                    continue
                if kind not in defined:
                    continue
                if name not in defined[kind]:
                    unresolved[f"{kind}/{name}"].add(rel(p))
    for k, v in sorted(unresolved.items()):
        warn.append(f"UNRESOLVED_REF {k} <- {', '.join(sorted(v)[:3])}")


def main():
    if not os.path.isdir(RES):
        print(f"res_validate: {RES} not found — nothing to check")
        return 0
    defined = collect_definitions()
    check_colour_literals_and_gradients()
    check_references(defined)

    counts = ", ".join(f"{k}={len(v)}" for k, v in sorted(defined.items()))
    print(f"res_validate: definitions -> {counts}")

    for w in warn:
        print(f"::warning::res_validate {w}")
    print(f"res_validate: {len(warn)} warning(s)")

    if fatal:
        for x in fatal:
            print(f"::error::res_validate {x}")
        print(f"\nres_validate: FAILED with {len(fatal)} fatal resource problem(s).")
        print("These break AAPT2 packaging. Fixing them here costs seconds; "
              "finding them in the APK build costs ~14 minutes.")
        return 1

    print("res_validate: OK — no fatal resource problems")
    return 0


if __name__ == "__main__":
    sys.exit(main())
