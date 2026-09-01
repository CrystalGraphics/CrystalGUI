#!/usr/bin/env python3
"""Publish the members a PACKAGE SPLIT put on the far side of a boundary, from the compiler's list.

    ./gradlew :core:compileJava > build.log 2>&1
    python tools/port/publish.py build.log

**What it does and does not decide.** A member that was package-private and is now reached across a
split has to become public; that is the split's price, not a judgement, and javac has already named
every one. What it never does is widen something nobody asked for: it acts only on
`X is not public in Y` errors, so a member no caller needs stays exactly as encapsulated as it was.

**It reports the bill.** A split's cost is the count of members it publishes, and that number is the
only honest measure of whether a boundary was in the right place -- `plan_m6.md` quotes it per batch.

**The regex is anchored on the DECLARATION, and that is the whole difficulty.** The first version
matched an indented line beginning with a modifier and prefixed `public`, which also matches a
statement: it wrote `public return foo;` into 60 lines across two batches. A declaration is matched
here by requiring a member shape after the modifiers -- a type and a name followed by `(`, `=`, `;`
or `<` -- and by requiring the exact member name javac reported, never a general sweep.
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CORE = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'crystalgui')

# `foo() is not public in Bar; cannot be accessed from outside package`
NOT_PUBLIC = re.compile(
    r'error: (?P<member>[\w$]+)(?:\([^)]*\))? is not public in (?P<owner>[\w.$]+);')

MODIFIERS = r'(?:static\s+|final\s+|abstract\s+|synchronized\s+|native\s+|default\s+|<[^>]+>\s+)*'


def owner_file(simple):
    """The single new-tree file declaring this type, or None."""
    hits = []
    for root in ('widget', 'desktop', 'workbench', 'app', 'document'):
        base = os.path.join(CORE, root)
        for dirpath, _d, files in os.walk(base) if os.path.isdir(base) else ():
            if simple + '.java' in files:
                hits.append(os.path.join(dirpath, simple + '.java'))
    return hits[0] if len(hits) == 1 else None


def publish(path, member):
    """Make one member public. Returns whether anything changed."""
    text = io.open(path, encoding='utf-8').read()
    # A METHOD, a FIELD or a CONSTRUCTOR, at class-member indentation, already package-private.
    # The trailing group is what separates a declaration from a statement.
    pattern = re.compile(
        r'^(?P<indent>[ \t]+)(?!public\b|private\b|protected\b)'
        r'(?P<mods>' + MODIFIERS + r')'
        r'(?P<rest>(?:[\w.$<>\[\], ?]+\s+)?' + re.escape(member) + r'\s*[(=;<])',
        re.M)
    new, n = pattern.subn(
        lambda m: m.group('indent') + 'public ' + m.group('mods') + m.group('rest'), text, count=1)
    if n:
        io.open(path, 'w', encoding='utf-8', newline='\n').write(new)
    return bool(n)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    log = io.open(sys.argv[1], encoding='utf-8', errors='replace').read()

    wanted = set()
    for m in NOT_PUBLIC.finditer(log):
        wanted.add((m.group('owner').rsplit('.', 1)[-1], m.group('member')))

    done, missed = 0, []
    for owner, member in sorted(wanted):
        path = owner_file(owner)
        if path and publish(path, member):
            done += 1
        else:
            missed.append('%s.%s' % (owner, member))
    print('members published: %d' % done)
    if missed:
        print('\nCOULD NOT PUBLISH (%d) -- declaration not matched, do these by hand:\n  %s'
              % (len(missed), ' '.join(missed)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
