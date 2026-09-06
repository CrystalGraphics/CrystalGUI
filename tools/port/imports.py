#!/usr/bin/env python3
"""Give an extracted class the imports it needs, from the class it came out of.

    python tools/port/imports.py <source.java> <extracted.java>

Copies every import the source has, keeps the ones whose simple name actually appears in the
extracted body, and drops the rest. Two rules, both learned here: an unused import is a claim about a
dependency the code does not have -- the tree carries a `LayeringTest` that reads them -- and a
missing one is a compile error that says nothing about which of forty imports it wanted.

Static imports are matched on their last segment, which is the name a body uses.
"""

import io
import re
import sys


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    source, extracted = sys.argv[1], sys.argv[2]

    src = io.open(source, encoding='utf-8').read()
    out = io.open(extracted, encoding='utf-8').read()

    head, _, body = out.partition('\n\n')
    if not body:
        print('no body: is %s an extracted class?' % extracted)
        return 1

    wanted = []
    for line in re.findall(r'^import .*;$', src, re.M):
        simple = line.rstrip(';').split('.')[-1].strip()
        if simple == '*':
            wanted.append(line)
            continue
        if re.search(r'\b%s\b' % re.escape(simple), body):
            wanted.append(line)

    package = re.search(r'^package [\w.]+;$', out, re.M).group(0)
    rebuilt = package + '\n\n' + '\n'.join(sorted(set(wanted))) + '\n\n' + body.lstrip('\n')
    io.open(extracted, 'w', encoding='utf-8').write(rebuilt)
    print('kept %d of %d imports' % (len(wanted), len(re.findall(r'^import ', src, re.M))))
    return 0


if __name__ == '__main__':
    sys.exit(main())
