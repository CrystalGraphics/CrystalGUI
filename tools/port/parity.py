#!/usr/bin/env python3
"""What a ported class LOST — declared members present in the old copy and absent in the new.

    python tools/port/parity.py            # every ported class
    python tools/port/parity.py 6.7        # one batch

**Why a tool and not a read.** A port is a copy plus a few hundred mechanical rewrites, and the
failure mode is not a wrong rewrite — the compiler catches those — it is a rewrite that DELETED
something. Every removal in this port was deliberate at the time (an `@Override` that no longer
overrides, a hook that no longer exists, a self-sizing hatch the new text widget replaced), and each
one was decided in the middle of chasing a compile error, which is exactly when a reader is least
able to tell "this has no counterpart" from "this needs one and I will come back to it".

**What it compares.** Declared method names, field names, and nested type names, by SIGNATURE-free
simple name. It deliberately does not compare bodies: a body differs on nearly every ported line and
the noise would bury the signal. A name present in one and not the other is the whole question.

**Every hit needs a reading, and most are fine.** A method that genuinely has no counterpart on the
new engine (`onLayoutChanged`, `tagName`, `writeState`) SHOULD be absent, and this reports it. What
the report is for is that the list is short enough to read, so the one entry that should not be there
cannot hide in it.
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'crystalgui')
LEDGER = os.path.join(ROOT, 'tools', 'port', 'port-ledger.tsv')

# A member DECLARATION at class-member indentation. Requires a modifier or a type before the name and
# a `(`, `=` or `;` after it, which is what separates a declaration from a call.
MEMBER = re.compile(
    r'^[ \t]+(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|'
    r'transient|volatile)\s+)*'
    r'(?:<[^>]{0,120}>\s*)?'
    r'(?:[\w.$]+(?:<[^;{=]{0,200}>)?(?:\[\])*\s+)?'
    r'(?P<name>\w+)\s*(?P<tail>[(=;])', re.M)

TYPE = re.compile(r'^[ \t]+(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\s+)*'
                  r'(?:class|interface|enum|record)\s+(?P<name>\w+)', re.M)

KEYWORDS = {'if', 'for', 'while', 'switch', 'return', 'new', 'this', 'super', 'try', 'catch',
            'else', 'do', 'throw', 'synchronized', 'assert', 'case', 'break', 'continue', 'yield',
            'instanceof', 'var', 'record', 'class', 'interface', 'enum'}


def strip(text):
    """Remove comments and string literals, so prose and messages cannot look like declarations."""
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    text = re.sub(r'//[^\n]*', '', text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    return text


def members(path):
    try:
        text = strip(io.open(path, encoding='utf-8', errors='replace').read())
    except IOError:
        return None
    out = set()
    for m in MEMBER.finditer(text):
        name = m.group('name')
        if name not in KEYWORDS:
            out.add(name)
    for m in TYPE.finditer(text):
        out.add(m.group('name'))
    return out


def rows(batch=None):
    for line in io.open(LEDGER, encoding='utf-8'):
        f = line.rstrip('\n').split('\t')
        if len(f) >= 7 and f[0] == 'CLASS' and f[6] == 'ported':
            if batch is None or f[4] == batch:
                yield f[1], f[3], f[4]


def main():
    batch = sys.argv[1] if len(sys.argv) > 1 else None
    total_lost = 0
    checked = 0
    report = []
    for path, dest, which in rows(batch):
        stem = path.rsplit('/', 1)[1]
        old = os.path.join(SRC, path.replace('/', os.sep) + '.java')
        new = os.path.join(SRC, dest.replace('/', os.sep), stem + '.java')
        if not os.path.isfile(old):
            continue  # a MOVE: there is no old copy left to compare against, by design.
        a, b = members(old), members(new)
        if a is None or b is None:
            continue
        checked += 1
        lost = sorted(a - b)
        if lost:
            total_lost += len(lost)
            report.append((which, stem, lost))

    report.sort()
    for which, stem, lost in report:
        print('%-5s %-26s %s' % (which, stem, ' '.join(lost)))
    print('\n%d ported classes compared, %d lost members across %d classes'
          % (checked, total_lost, len(report)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
