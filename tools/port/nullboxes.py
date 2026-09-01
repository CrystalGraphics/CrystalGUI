#!/usr/bin/env python3
"""Every `box()` read in the new-engine tree that is not guarded against null.

    python tools/port/nullboxes.py

**Why this needs a tool.** `UINode.box()` is NULLABLE -- a node has a box only while connected,
unfrozen, and neither `hidden` nor `display: none`, where the old engine's `getRuntimeCache()` always
answered. 185 unguarded chains came across in the port, and they have been surfacing one crash at a
time ever since: `TextEditor.measureScrollbars` on the first frame of every editor, five in
`WindowFrame` (so `moveTo` before `addWindow` threw), `WindowPreview.matchHeaderToThumbnail` on every
minimise. Each was fixed where it appeared, which is a strategy that finds them in the order a user
does.

**Every fixed site already had the guard's INTENT written above it** -- "a non-positive box is
refused", "a zero box carries no information", "a definite width is counted and nothing else is" --
and none covered null, because on the old engine there was no null to cover. That is what makes them
invisible on a read: the line looks considered, and it is, about the wrong engine.

**What counts as guarded.** An assignment into a local that is then null-tested, or a direct null test
of the call. The check is deliberately shallow: a site whose guard it cannot SEE is reported, because
a false positive costs one reading and a false negative costs a crash.
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'crystalgui')
NEW_ROOTS = ('widget', 'desktop', 'workbench', 'app', 'document',
             'ui/dom', 'ui/box', 'ui/service')

CHAIN = re.compile(r'(?P<recv>[\w.]*?)\bbox\(\)\s*\.\s*(?P<member>[a-zA-Z]\w*)')
METHOD = re.compile(
    r'^    (?:@\w+\s*\n    )*'
    r'(?:public |private |protected |static |final |abstract |synchronized |default )*'
    r'[\w<>\[\],. ?]+\s+\w+\s*\([^;{]*\)\s*\{', re.M)


def method_spans(code):
    """(start, end) of each method body, so a guard is looked for in the right scope."""
    spans = []
    for m in METHOD.finditer(code):
        depth, i = 0, m.end() - 1
        while i < len(code):
            if code[i] == '{':
                depth += 1
            elif code[i] == '}':
                depth -= 1
                if depth == 0:
                    spans.append((m.start(), i))
                    break
            i += 1
    return spans


def guarded(body, recv):
    """Does this method visibly check the box before reading it?"""
    who = recv.rstrip('.')
    pattern = (r'\bBox\s+(\w+)\s*=\s*' + re.escape(who) + r'\s*\.\s*box\(\)') if who \
        else r'\bBox\s+(\w+)\s*=\s*box\(\)'
    for m in re.finditer(pattern, body):
        name = re.escape(m.group(1))
        if re.search(r'\b' + name + r'\s*(==|!=)\s*null', body):
            return True
    call = (who + '.box()') if who else 'box()'
    if re.search(re.escape(call) + r'\s*(==|!=)\s*null', body):
        return True
    return False


def main():
    hits = []
    for dirpath, _dirs, files in os.walk(SRC):
        rel = os.path.relpath(dirpath, SRC).replace(os.sep, '/')
        if not any(rel == r or rel.startswith(r + '/') for r in NEW_ROOTS):
            continue
        for f in sorted(files):
            if not f.endswith('.java'):
                continue
            text = io.open(os.path.join(dirpath, f), encoding='utf-8', errors='replace').read()
            # Blank comments out, keeping line numbers, so prose cannot look like a read.
            code = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), text, flags=re.S)
            code = re.sub(r'//[^\n]*', '', code)
            spans = method_spans(code)
            for m in CHAIN.finditer(code):
                body = None
                for a, b in spans:
                    if a <= m.start() <= b:
                        body = code[a:b]
                        break
                if body is None or guarded(body, m.group('recv')):
                    continue
                hits.append((rel + '/' + f[:-5],
                             code[:m.start()].count('\n') + 1, m.group(0)))

    for where, line, what in hits:
        print('  %-50s :%-5d %s' % (where, line, what))
    print('\n%d unguarded box() reads' % len(hits))
    return 0


if __name__ == '__main__':
    sys.exit(main())
