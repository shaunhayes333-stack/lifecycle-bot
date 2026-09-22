#!/usr/bin/env python3
"""
V5.0.7216 — LOCAL-FUNCTION VISIBILITY MODIFIER GUARD.

Three consecutive red builds (7213, 7214, 7215) died on this, twice over:

    MainActivity.kt:5227:5 Modifier 'private' is not applicable to 'local function'
    MainActivity.kt:5256:5 Modifier 'private' is not applicable to 'local function'
    MainActivity.kt:5239:17 Unresolved reference: preferOpenRow7213

Two helpers were written at member indentation and pasted INSIDE
buildUnifiedOpenPositions, eighty lines into its body. Kotlin has no visibility
modifiers on local functions, and a local function cannot be referenced before
its declaration, so the third error is the same mistake seen from the other
side.

This is not a new mistake in this file. MainActivity.kt:4689 still carries the
scar:

    fun updateCyclicPanel() {  // V5.9.225: removed 'private' — local functions
                               // can't use access modifiers

Same file, same defect, and nothing in CI caught either one. The other fourteen
gates are Python scans over Kotlin source and not one of them compiles
anything, so a four-minute Gradle round trip was the only thing that could see
it — which is exactly the cost ci/static_call_check.py and
ci/kotlin_expression_body_return.py were written to avoid.

HOW IT DECIDES. A `fun` is LOCAL when the innermost open brace enclosing it was
opened by something other than a type body. Scope kinds are tracked on a stack:
a brace opened by class/object/interface/enum/annotation/companion is a TYPE
scope, everything else (function bodies, lambdas, if/when/try, init blocks) is
CODE. A `fun` declared inside CODE carrying private/public/internal/protected
is the error.

Multi-line type headers are handled: a declaration line that names a type but
has not yet opened its brace sets a pending kind, so

    data class Foo(
        val a: Int,
    ) {

still opens a TYPE scope though the `{` lands on a line reading `) {`.

WHY THE LEXER IS A STATE MACHINE AND NOT A REGEX. The first cut of this file
stripped strings line by line and produced fourteen false positives. Every one
traced to the same shape, TradeDatabase.kt:202:

    runCatching { db.execSQL(<RAWQ>
        CREATE TABLE ...
    <RAWQ>.trimIndent()) }

(<RAWQ> standing in for Kotlin's triple quote, which cannot be written inside
this docstring.) The raw string opens on one line and closes seventeen lines
later on a line that ALSO carries the lambda's closing brace. A per-line
stripper cannot know it is inside a string, so it swallowed that `}` and every
subsequent member of the class looked nested one level too deep. A guard that
cries wolf on working code is worse than no guard, so the lexer now carries its
state across lines: NORMAL, BLOCK_COMMENT, STRING, RAW_STRING, CHAR and HOLE,
with escapes honoured and `${...}` template holes skipped whole — their braces
are balanced by construction, so ignoring them leaves the structural depth this
guard measures untouched.

Exit 1 on any hit. Every hit is a hard compile error, so there is nothing to
triage.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src"

VISIBILITY = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(private|public|internal|protected)\b"
    r"(?:\s+(?:suspend|inline|operator|infix|tailrec|external|override|open|final|abstract))*"
    r"\s+fun\b"
)
TYPE_DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|private|internal|protected|abstract|open|sealed|data|value|inner|"
    r"annotation|enum|expect|actual|external|final|companion)\s+)*"
    r"(class|object|interface)\b"
)


class Lexer:
    """Stateful Kotlin lexer that yields only the code characters of each line."""

    NORMAL, BLOCK_COMMENT, STRING, RAW_STRING, CHAR, HOLE = range(6)

    def __init__(self):
        self.state = self.NORMAL
        # `${ ... }` template holes are SKIPPED rather than emitted as code.
        # Their braces are balanced by construction, so ignoring them leaves the
        # structural brace depth — the only thing this guard measures —
        # untouched, and it avoids having to re-enter the right string state on
        # the hole's closing brace.
        self.hole_depth = 0
        self.hole_return = self.NORMAL

    def code_of(self, line: str) -> str:
        out = []
        i = 0
        n = len(line)
        while i < n:
            c = line[i]

            if self.state == self.NORMAL:
                if c == "/" and i + 1 < n and line[i + 1] == "/":
                    break                      # line comment: rest is not code
                if c == "/" and i + 1 < n and line[i + 1] == "*":
                    self.state = self.BLOCK_COMMENT
                    i += 2
                    continue
                if line.startswith('"""', i):
                    self.state = self.RAW_STRING
                    i += 3
                    continue
                if c == '"':
                    self.state = self.STRING
                    i += 1
                    continue
                if c == "'":
                    self.state = self.CHAR
                    i += 1
                    continue
                out.append(c)
                i += 1
                continue

            if self.state == self.BLOCK_COMMENT:
                j = line.find("*/", i)
                if j < 0:
                    break
                self.state = self.NORMAL
                i = j + 2
                continue

            if self.state == self.RAW_STRING:
                # A raw string has no escapes; only `"""` ends it, and `${` opens
                # a template hole.
                if line.startswith("${", i):
                    self.hole_return = self.RAW_STRING
                    self.hole_depth = 1
                    self.state = self.HOLE
                    i += 2
                    continue
                if line.startswith('"""', i):
                    self.state = self.NORMAL
                    i += 3
                    continue
                i += 1
                continue

            if self.state == self.STRING:
                if c == "\\":
                    i += 2
                    continue
                if line.startswith("${", i):
                    self.hole_return = self.STRING
                    self.hole_depth = 1
                    self.state = self.HOLE
                    i += 2
                    continue
                if c == '"':
                    self.state = self.NORMAL
                    i += 1
                    continue
                i += 1
                continue

            if self.state == self.CHAR:
                if c == "\\":
                    i += 2
                    continue
                if c == "'":
                    self.state = self.NORMAL
                    i += 1
                    continue
                i += 1
                continue

            if self.state == self.HOLE:
                # Skip the hole's contents. Braces inside it are balanced, so
                # counting them would be a no-op at best; nested string literals
                # carrying an unbalanced brace inside a template hole are the one
                # shape this does not model, and none exists in this module.
                if c == "{":
                    self.hole_depth += 1
                elif c == "}":
                    self.hole_depth -= 1
                    if self.hole_depth == 0:
                        self.state = self.hole_return
                i += 1
                continue

        # An unterminated single-quoted string or char literal cannot span a
        # line in Kotlin, so reset those at end of line. BLOCK_COMMENT and
        # RAW_STRING legitimately continue.
        if self.state in (self.STRING, self.CHAR):
            self.state = self.NORMAL
        return "".join(out)


# A line whose code ends with one of these is still continuing a declaration
# header, so a pending type stays pending across it.
HEADER_CONTINUES = (",", "(", ":", "=", ">", "-", "+", "&", "|", "?")


def scan(path: Path):
    """Yield (lineno, modifier, text) for each visibility-modified local function."""
    hits = []
    lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
    lexer = Lexer()
    stack = []            # scope kinds, innermost last
    pending_type = False  # a type header seen, its body brace not yet opened
    paren_depth = 0

    for lineno, raw in enumerate(lines, start=1):
        code = lexer.code_of(raw)

        m = VISIBILITY.match(code)
        if m and stack and stack[-1] == "CODE":
            hits.append((lineno, m.group(1), raw.strip()[:90]))

        if paren_depth == 0 and TYPE_DECL.match(code):
            pending_type = True

        opened_body = False
        for ch in code:
            if ch == "(":
                paren_depth += 1
            elif ch == ")":
                if paren_depth > 0:
                    paren_depth -= 1
            elif ch == "{":
                # V5.0.7216 — ONLY a brace at paren depth 0 can be a type's
                # body. SolanaMarketScanner.kt:677 is why:
                #
                #   class SolanaMarketScanner(
                #       ...
                #       private val getBrain: () -> BotBrain? = { null },
                #   ) {
                #
                # That default-value lambda sits INSIDE the constructor
                # parentheses. The first cut of this guard let it consume the
                # pending type, so the real class body on the next line opened
                # as a CODE scope and every one of the class's 14 members was
                # reported as a local function. A lambda in an argument list is
                # a CODE scope and must not touch the header state.
                if pending_type and paren_depth == 0:
                    stack.append("TYPE")
                    pending_type = False
                    opened_body = True
                else:
                    stack.append("CODE")
            elif ch == "}":
                if stack:
                    stack.pop()

        # A header that closed without a body — `class Foo(val a: Int)`, or an
        # `object : Bar by baz` — must not leave the flag armed for the next
        # function body in the file.
        if pending_type and not opened_body and paren_depth == 0:
            stripped = code.rstrip()
            if stripped and not stripped.endswith(HEADER_CONTINUES):
                pending_type = False

    return hits


def main() -> int:
    files = sorted(SRC.rglob("*.kt"))
    if not files:
        print("kotlin_local_function_modifiers: no Kotlin sources found", file=sys.stderr)
        return 1
    findings = []
    for f in files:
        for lineno, mod, txt in scan(f):
            findings.append((f, lineno, mod, txt))

    if findings:
        print("kotlin_local_function_modifiers: FAIL — visibility modifier on a local function:")
        for f, lineno, mod, txt in findings:
            print(f"  {f.relative_to(ROOT.parent)}:{lineno}  '{mod}' on a local fun  |  {txt}")
        print()
        print("Kotlin rejects private/public/internal/protected on a function declared")
        print("inside another function's body. Either move the declaration out to the")
        print("enclosing class (and keep the modifier), or drop the modifier and make")
        print("sure it is declared BEFORE every use — a local function cannot be")
        print("forward-referenced. See MainActivity.kt:4689 for the 5.9.225 precedent.")
        return 1

    print(f"kotlin_local_function_modifiers: {len(files)} Kotlin file(s) scanned")
    print("kotlin_local_function_modifiers: OK — no visibility modifier on a local function")
    return 0


if __name__ == "__main__":
    sys.exit(main())
