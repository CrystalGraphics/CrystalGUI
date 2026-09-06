#!/usr/bin/env python3
"""Add the imports a PACKAGE SPLIT makes necessary, from the compiler's own error list.

    ./gradlew :core:compileJava > build.log 2>&1
    python tools/port/crossimports.py build.log

**Why this exists.** Splitting one package into five turns every same-package reference between the
new neighbours into a missing import. Nothing about it is a decision -- the type has exactly one
declaration in the new tree and the compiler has already named it -- but there are hundreds, and
doing them by hand is where a batch goes from a morning to a day. 6.7 alone splits `workbench` seven
ways and `dock` five, on 91 copied classes.

**It resolves against the NEW tree only.** A ported class must never import its old-engine twin: the
two have the same simple name and a resolver that considered both would pick one at random and
compile. So the search is rooted at the new-engine destinations, and a name that is ambiguous even
there is REPORTED rather than guessed -- there is no defensible way to choose, and an import placed
wrongly compiles and is then very hard to see.

**A name it cannot find is not a failure.** It usually means the class is in a later stage of the
same batch and has not been copied yet, which is ordinary and self-correcting: run the compile again
after that stage. Those are listed separately from the ambiguous ones, which are not self-correcting.
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CORE = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'crystalgui')

# Mirrors EngineBoundaryTest's NEW_PACKAGES. A ported class resolves against these and nothing else.
NEW_ROOTS = ('widget', 'desktop', 'workbench', 'app', 'document')

MISSING = re.compile(
    r'^(?P<file>[A-Za-z]:\\.*?\.java):(?P<line>\d+): error: cannot find symbol\s*$')
# `class X` for a type in a signature; `variable X` for a STATIC reference (`DockOrientation.HORIZONTAL`),
# which javac reports as an unresolved variable because it cannot know X is a type. Both need the
# same import, and leaving the second out is why the first run of this tool reported six classes
# as "not in the new tree yet" that were sitting right there.
SYMBOL = re.compile(r'^\s*symbol:\s+(?:class|variable)\s+(?P<name>[A-Z]\w*)\s*$')


def index_new_tree():
    """simple name -> [fully qualified], over the new-engine packages only."""
    out = {}
    for root in NEW_ROOTS:
        base = os.path.join(CORE, root)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, files in os.walk(base):
            pkg = os.path.relpath(dirpath, CORE).replace(os.sep, '.')
            for f in files:
                if f.endswith('.java') and f != 'package-info.java':
                    out.setdefault(f[:-5], []).append('com.crystalgui.' + pkg + '.' + f[:-5])
    return out


def wanted(log_path):
    """(file, simple name) pairs the compiler could not resolve."""
    lines = io.open(log_path, encoding='utf-8', errors='replace').read().splitlines()
    pairs = set()
    for i, line in enumerate(lines):
        m = MISSING.match(line)
        if not m:
            continue
        # javac prints the offending source line, a caret, then `symbol:` -- within a few lines.
        for j in range(i + 1, min(i + 6, len(lines))):
            s = SYMBOL.match(lines[j])
            if s:
                pairs.add((m.group('file'), s.group('name')))
                break
    return pairs


def add_import(path, fqn):
    """Insert one import, in sorted position among the existing block."""
    text = io.open(path, encoding='utf-8').read()
    line = 'import %s;' % fqn
    if line in text:
        return False
    imports = [(m.start(), m.end(), m.group(0))
               for m in re.finditer(r'^import .*?;$', text, re.M)]
    if imports:
        at = imports[-1][1]
        for start, _end, existing in imports:
            if existing > line:
                at = start - 1
                break
        text = text[:at] + '\n' + line + text[at:]
    else:
        m = re.search(r'^package [\w.]+;$', text, re.M)
        text = text[:m.end()] + '\n\n' + line + text[m.end():]
    io.open(path, 'w', encoding='utf-8', newline='\n').write(text)
    return True


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    index = index_new_tree()
    added = 0
    not_found, ambiguous = set(), set()
    for path, name in sorted(wanted(sys.argv[1])):
        # Only ever edit a file in the NEW tree; an old-engine file failing to compile is a real
        # defect and must not be papered over with an import of a ported class.
        rel = os.path.relpath(path, CORE).replace(os.sep, '/')
        if rel.split('/')[0] not in NEW_ROOTS:
            continue
        hits = index.get(name, [])
        # A class in its own package needs no import; that error is something else.
        own = 'com.crystalgui.' + os.path.dirname(rel).replace('/', '.')
        hits = [h for h in hits if h.rsplit('.', 1)[0] != own]
        if not hits:
            not_found.add(name)
        elif len(hits) > 1:
            ambiguous.add('%s -> %s' % (name, ', '.join(sorted(hits))))
        elif add_import(path, hits[0]):
            added += 1
    print('imports added: %d' % added)
    if not_found:
        print('\nnot in the new tree yet (%d) -- usually a later stage of the same batch; '
              'recompile after it lands:\n  %s' % (len(not_found), ' '.join(sorted(not_found))))
    if ambiguous:
        print('\nAMBIGUOUS (%d) -- resolve by hand, never guess:\n  %s'
              % (len(ambiguous), '\n  '.join(sorted(ambiguous))))
    return 0


if __name__ == '__main__':
    sys.exit(main())
