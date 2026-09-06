#!/usr/bin/env python3
"""Javadoc references in the ported tree that name a member nothing declares.

    python tools/port/danglinglinks.py           # report
    python tools/port/danglinglinks.py --fix     # apply the known renames, report the rest

**Why this is the port's characteristic rot.** A rename is caught by the compiler everywhere except
in prose, and this port renamed a great deal: `getRuntimeCache` -> `box`, `paintOverlay` ->
`paintDecoration`, `onLayoutChanged` -> `connected`, `dotCenter()` -> `dotCenterIn(space)`. Every
`{@link}` at those names still resolves to nothing, and the surrounding sentence is usually still
CORRECT -- which is what makes it worth fixing rather than deleting: the explanation survived the
port and only the pointer died.

**What it does not report.** Enum constants, records' component accessors and Lombok-generated
getters are declared in ways a regex cannot see, so a name matching one of those shapes is skipped.
The bias is deliberate: a false negative costs a stale link, a false positive costs a reader chasing
a reference that was fine.
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'crystalgui')
NEW_ROOTS = ('widget', 'desktop', 'workbench', 'app', 'document',
             'ui/dom', 'ui/box', 'ui/service')

MEMBER = re.compile(r'^[ \t]+(?:(?:public|private|protected|static|final|abstract|synchronized|'
                    r'native|default|transient|volatile)\s+)*'
                    r'(?:<[^>]{0,120}>\s*)?(?:[\w.$]+(?:<[^;{=]{0,200}>)?(?:\[\])*\s+)?'
                    r'(?P<name>\w+)\s*[(=;]', re.M)
# An enum constant or a record component -- neither matches the shape above.
ENUM_CONST = re.compile(r'^[ \t]+(?P<name>[A-Z][A-Z0-9_]*)\s*[,;(]', re.M)
RECORD = re.compile(r'record\s+\w+\s*\((?P<body>[^)]*)\)', re.S)
LINK = re.compile(r'(?P<open>\{@link\s+|@see\s+)(?P<type>[\w.$]*)#(?P<member>\w+)')

# What the port renamed. The counterpart, or None where the member genuinely has none and the link
# has to be rewritten by a human into whatever the sentence should now say.
RENAMES = {
    'getRuntimeCache': 'box',
    'paintOverlay': 'paintDecoration',
    'paintSelf': 'paintContent',
    'paintOutline': 'paintDecoration',
    'onLayoutChanged': 'connected',
    'onWindowChanged': 'connected',
    'getAttachedWindow': 'document',
    'applyScrollOffset': 'setScrollOffsets',
    'setScrollTop': 'setScrollOffsets',
    'setScrollLeft': 'setScrollOffsets',
    'screenToLocal': 'toLocal',
    'getSiblingIndex': 'indexOf',
    'dotCenter': 'dotCenterIn',
    'getStyleEngine': 'styles',
    'addInternalChild': 'append',
    'removeInternalChild': 'remove',
    'addChildAt': 'insertAt',
    'getChildren': 'children',
    'getParent': 'parent',
    'getId': 'id',
}


def declarations(path):
    text = io.open(path, encoding='utf-8', errors='replace').read()
    body = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    names = {m.group('name') for m in MEMBER.finditer(body)}
    names |= {m.group('name') for m in ENUM_CONST.finditer(body)}
    for m in RECORD.finditer(body):
        names |= set(re.findall(r'(\w+)\s*(?:,|$)', m.group('body')))
    # A Lombok-generated accessor: `@Getter` on the class or on any field.
    if '@Getter' in body or '@Data' in body or '@Value' in body:
        names |= {'get' + n[:1].upper() + n[1:] for n in names}
        names |= {'is' + n[:1].upper() + n[1:] for n in names}
    return names


def main():
    fix = '--fix' in sys.argv
    index = {}
    for dirpath, _d, files in os.walk(SRC):
        rel = os.path.relpath(dirpath, SRC).replace(os.sep, '/')
        if not any(rel == r or rel.startswith(r + '/') for r in NEW_ROOTS):
            continue
        for f in files:
            if f.endswith('.java'):
                index.setdefault(f[:-5], os.path.join(dirpath, f))

    cache = {}

    def decl(cls):
        if cls not in cache:
            cache[cls] = declarations(index[cls]) if cls in index else None
        return cache[cls]

    base = set()
    for b in ('UINode', 'UIDocument', 'Box'):
        if decl(b):
            base |= decl(b)

    fixed, unresolved = 0, []
    for cls, path in sorted(index.items()):
        text = io.open(path, encoding='utf-8', errors='replace').read()
        out, changed = [], False
        last = 0
        for m in LINK.finditer(text):
            t = (m.group('type') or '').rsplit('.', 1)[-1]
            name = m.group('member')
            owner = t or cls
            known = decl(owner)
            if known is None:
                continue
            if name in known or name in base:
                continue
            new = RENAMES.get(name)
            if fix and new and (new in known or new in base):
                out.append(text[last:m.start()])
                out.append(m.group('open') + (m.group('type') or '') + '#' + new)
                last = m.end()
                changed = True
                fixed += 1
            else:
                unresolved.append((cls, (t + '#' if t else '#') + name))
        if changed:
            out.append(text[last:])
            io.open(path, 'w', encoding='utf-8', newline='\n').write(''.join(out))

    for cls, ref in unresolved:
        print('  %-28s {@link %s}' % (cls, ref))
    if fix:
        print('\n%d renamed, %d left for a human' % (fixed, len(unresolved)))
    else:
        print('\n%d dangling javadoc references' % len(unresolved))
    return 0


if __name__ == '__main__':
    sys.exit(main())
