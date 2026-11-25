"""Audit the main sources for the four bug families this project has actually shipped.

Each family is here because it shipped once and was found by luck:

  A  read-after-consume    a stack is read after it was emptied or handed over
  B  existence-not-content a check asks whether something is present, not whether it has content
  C  silent-success        a verb reports success with a message that cannot be a measurement
  D  stale-state           state that a rename, reload or dimension change should invalidate

Run from the repository root:

    python tools/audit-bug-families.py

Prints one line per finding as `family  file:line  detail` and exits 0. It is a reading aid, not a gate:
every hit still has to be judged, and docs/AUDIT.md records the verdict for each one.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "src" / "main" / "java"

findings = []

SPEND = re.compile(r"ItemStack\.EMPTY|consumeItem\(|add\(|addItem\(|setItem\(|split\(")
READ = re.compile(r"\b(\w+)\.(getCount|getItem|copy|isEmpty|is|has|get)\(")
LOCAL_STACK = re.compile(r"ItemStack\s+(\w+)\s*=")
COMPONENT = re.compile(r"\.has\(\s*DataComponents\.(\w+)\s*\)")
CONSTANT_OK = re.compile(r'ActionOutcome\.ok\("([a-z\-]+)",\s*"([^"]*)"\)')
STALE_FIELD = re.compile(r"private\s+(?!static)(?!final)[\w<>,. ]+\s+(active|activeTarget|workTarget|"
                         r"planOrigin|\w*Cache|cached\w*)\s*[;=]")


def add(family, path, line, text):
    findings.append((family, f"{path.relative_to(ROOT).as_posix()}:{line}", text.strip()[:140]))


def family_a():
    """A stack read after the line that handed it over.

    The shape shipped four times: a stack leaves the bag (or goes into a container) and is then read for its
    id, count or components. A stack read *inside* the call that spends it is fine, so a hit only counts when
    the spending line does not itself mention the variable.
    """
    for path in MAIN.rglob("*.java"):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        spent = {}
        for i, line in enumerate(lines, start=1):
            for name in LOCAL_STACK.findall(line):
                spent.pop(name, None)
            for match in READ.finditer(line):
                name = match.group(1)
                if name in spent:
                    add("A", path, i, "'%s' read after it was spent at line %d: %s"
                        % (name, spent.pop(name), line))
            if SPEND.search(line):
                in_scope = LOCAL_STACK.findall("\n".join(lines[max(0, i - 8):i]))
                for name in in_scope:
                    if re.search(r"\b%s\b" % name, line) or "ItemStack.EMPTY" in line:
                        spent.setdefault(name, i)


def family_b():
    """A component's presence deciding something, where its content is what matters."""
    for path in MAIN.rglob("*.java"):
        for i, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
            for component in COMPONENT.findall(line):
                add("B", path, i, "presence of %s decides something: %s" % (component, line))


def family_c():
    """A success whose message is a constant: it cannot be reporting what the world did."""
    for path in MAIN.rglob("*.java"):
        for i, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
            for verb, message in CONSTANT_OK.findall(line):
                add("C", path, i, '%s reports success with a constant message: "%s"' % (verb, message))


def family_d():
    """State that a rename, a reload or a dimension change should invalidate, found by its name."""
    for path in MAIN.rglob("*.java"):
        for i, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
            if STALE_FIELD.search(line):
                add("D", path, i, line)


def main():
    for family in (family_a, family_b, family_c, family_d):
        family()
    if not findings:
        print("no findings in any family")
        return 0
    for family, where, text in findings:
        print("%s  %s  %s" % (family, where, text))
    print("\n%d finding(s). Each still needs judging; docs/AUDIT.md records the verdicts." % len(findings))
    return 0


if __name__ == "__main__":
    sys.exit(main())
