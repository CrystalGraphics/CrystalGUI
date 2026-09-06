#!/usr/bin/env python3
"""Price a package split by COMPILING it, which is the only measurement that cannot over-count.

Copies one package into a scratch tree, applies a candidate partition (package declarations plus the
cross-package imports the split needs), and leaves it for javac. Every `is not public` error is one
member the split would have to publish; every other error is a real obstacle the static count could
not see.

    python pricesplit.py <src-dir> <scratch-pkg> <assign.json>
    python pricesplit.py --clean <scratch-root>

THE LOOP IS THE MEASUREMENT, not a convenience:

    python tools/port/pricesplit.py <src> com.crystalgui.zsplit assign.json
    python tools/port/nested.py core/src/main/java/com/crystalgui/zsplit
    ./gradlew :core:compileJava            # count `is not public`; publish them; repeat until clean
    python tools/port/pricesplit.py --clean core/src/main/java/com/crystalgui/zsplit

`nested.py` runs FIRST and is not optional. A single unresolved type marks its whole class erroneous
and javac stops attributing everything downstream, so one `TextEditor.StableViewport` error hid 307
others and made a split that costs 78 published members measure as costing none -- twice, in one
afternoon. Read a `0` only from a build that is genuinely clean.

WHY THIS EXISTS RATHER THAN A STATIC COUNT. Three plan sections priced a package split by matching
identifiers in the source, and all three were wrong. A regex cannot resolve a receiver, so `height`,
`left`, `next`, `row` and `size` -- ordinary locals in every editor view part -- all counted as
reaching TextEditor; and counting each candidate package LEAVING ALONE charges every tightly-coupled
pair twice, because a pair that moves together crosses nothing. 6.6 quoted 97 + 24 and paid 52; 6.5
quoted 285 and paid 78. The compiler is the only thing that knows, and on a scratch copy it answers
while the plan is still being written rather than after the port has landed.
"""
import io
import json
import os
import re
import shutil
import sys

ROOT = 'core/src/main/java'


def clean(scratch_root):
    if os.path.isdir(scratch_root):
        shutil.rmtree(scratch_root)
        print('removed', scratch_root)


def main():
    if sys.argv[1] == '--clean':
        clean(sys.argv[2])
        return

    src_dir, scratch_pkg, assign_path = sys.argv[1], sys.argv[2], sys.argv[3]
    assign = json.loads(io.open(assign_path, encoding='utf-8').read())

    old_pkg = src_dir.replace('\\', '/').split(ROOT + '/')[1].replace('/', '.')
    scratch_root = os.path.join(ROOT, *scratch_pkg.split('.'))
    clean(scratch_root)

    names = [f[:-5] for f in sorted(os.listdir(src_dir)) if f.endswith('.java')]
    where = {n: assign.get(n, 'root') for n in names}
    # package for a class: the scratch root, plus its group unless it is `root`.
    def pkg_of(n):
        return scratch_pkg if where[n] == 'root' else scratch_pkg + '.' + where[n]

    for n in names:
        text = io.open(os.path.join(src_dir, n + '.java'), encoding='utf-8').read()
        text = re.sub(r'^package\s+[\w.]+;', 'package ' + pkg_of(n) + ';', text, count=1, flags=re.M)

        # Every sibling this class names and no longer shares a package with needs an import.
        adds = []
        for other in names:
            if other == n or pkg_of(other) == pkg_of(n):
                continue
            if re.search(r'\b' + other + r'\b', text):
                adds.append('import ' + pkg_of(other) + '.' + other + ';')
        if adds:
            m = re.search(r'^package .*?;$', text, re.M)
            text = text[:m.end()] + '\n\n' + '\n'.join(sorted(adds)) + text[m.end():]

        out_dir = os.path.join(ROOT, *pkg_of(n).split('.'))
        os.makedirs(out_dir, exist_ok=True)
        io.open(os.path.join(out_dir, n + '.java'), 'w', encoding='utf-8', newline='\n').write(text)

    groups = {}
    for n in names:
        groups.setdefault(where[n], []).append(n)
    print('wrote %d files into %s' % (len(names), scratch_root))
    for g, members in sorted(groups.items()):
        print('  %-10s %2d' % (g, len(members)))


if __name__ == '__main__':
    main()
