#!/usr/bin/env python3
"""Cut a cluster of methods out of one class into a collaborator in the SAME package.

    python tools/port/extract.py <source.java> <NewClass> <field> <method>[,<method>...]

Moves each named method -- with the javadoc and comment block immediately above it, which is where
half the value of this codebase lives -- into a new class beside the source, and leaves the source
holding a field of it. References from the moved bodies back to the source class are prefixed with
that field; anything the compiler then rejects is a member that has to be widened or delegated, and
javac names every one.

WHY A TOOL AND NOT AN EDITOR. Workbench is 3,598 lines and the split takes nine classes out of it. A
hand-move drops a comment, or half a comment, or attaches one to the wrong method -- and a comment
that has drifted onto the wrong method is worse than a missing one, because it reads as an
explanation. The cut is mechanical; the judgement is which methods form a cluster, and that is the
argument in the plan rather than anything this decides.

WHAT IT DELIBERATELY DOES NOT DO. It does not rewrite call sites in the source class: a moved public
method leaves a delegate behind, written by hand, because whether a method stays on the facade is an
API decision. And it does not widen anything -- the compile after it is the price, and it is meant to
be read.

AND IT DOES NOT TOUCH COMMENTS. The first version rewrote the whole block, so `this usually runs
inside the click that asked for it` became `workbench usually runs inside the click`, and a javadoc
`{@link #PATH_STATE}` became `{@link #workbench.PATH_STATE}`. Prose that has been through a
find-and-replace reads as prose somebody wrote, which is worse than a missing comment: the whole
value of a comment here is that it was meant. Comments and string literals are masked out before any
rewrite and put back after.
"""

import io
import os
import re
import sys

SIG = re.compile(
    r'^    (?:@\w+\s+)?(?:public |private |protected |static |final |synchronized |abstract )*'
    r'[A-Za-z_][\w<>,\[\]. ?]*\s+([a-zA-Z_]\w*)\s*\(')


def spans(lines):
    """Every method in the class, as (name, first line index, last line index) including its comment."""
    found = []
    i = 0
    while i < len(lines):
        match = SIG.match(lines[i])
        if match and lines[i].rstrip().endswith('{'):
            depth = 0
            j = i
            while j < len(lines):
                depth += lines[j].count('{') - lines[j].count('}')
                if depth == 0 and j > i:
                    break
                j += 1
            # THE COMMENT ABOVE IT, walked back over javadoc, // lines, annotations and blank lines
            # between them. A method arriving without its explanation is the one outcome this tool
            # exists to prevent.
            start = i
            k = i - 1
            while k >= 0:
                text = lines[k].strip()
                if text.startswith('*') or text.startswith('/**') or text.startswith('//') \
                        or text.startswith('@') or text.startswith('*/'):
                    start = k
                    k -= 1
                    continue
                break
            found.append((match.group(1), start, j))
            i = j + 1
            continue
        i += 1
    return found


COMMENT_OR_STRING = re.compile(
    r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S)


def mask(text):
    """Replaces every comment and string literal with a placeholder, so a rewrite cannot reach prose."""
    held = []

    def take(match):
        held.append(match.group(0))
        return '\x00%d\x00' % (len(held) - 1)

    return COMMENT_OR_STRING.sub(take, text), held


def unmask(text, held):
    return re.sub(r'\x00(\d+)\x00', lambda m: held[int(m.group(1))], text)


def main():
    if len(sys.argv) != 5:
        print(__doc__)
        return 2
    source, new_class, field, wanted = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4].split(',')

    text = io.open(source, encoding='utf-8').read()
    lines = text.split('\n')
    owner = os.path.basename(source)[:-len('.java')]
    package = re.search(r'^package ([\w.]+);', text, re.M).group(1)

    taken = []
    keep = [True] * len(lines)
    for name, start, end in spans(lines):
        if name not in wanted:
            continue
        taken.append((name, lines[start:end + 1]))
        for n in range(start, end + 1):
            keep[n] = False

    missing = [w for w in wanted if w not in [t[0] for t in taken]]
    if missing:
        print('not found: ' + ', '.join(missing))
        return 1

    body = []
    for name, block in taken:
        body.extend(block)
        body.append('')

    # THE REWRITE: every reference back to the owner goes through the field.
    #
    # `this` first, then the owner's own members BY NAME -- a moved body calls `documents` and
    # `activeFilePath()` unqualified, and in the new class those resolve to nothing at all.
    #
    # What is deliberately NOT rewritten is a name the moved code declares itself. A local called
    # `documents` would otherwise become `workbench.documents`, which compiles and means something
    # else -- the one failure mode here that is silent. Those are left to the compiler, which names
    # them precisely, and so is anything this misses: an under-rewrite is an error and an
    # over-rewrite is a bug.
    moved = '\n'.join(body)
    moved, masked = mask(moved)
    moved = re.sub(r'\bthis\.', field + '.', moved)
    moved = re.sub(r'\bthis::', field + '::', moved)
    moved = re.sub(r'\bthis\b(?!\s*[.:])', field, moved)

    kept = '\n'.join(line for line, on in zip(lines, keep) if on)
    moved_names = {name for name, _ in taken}
    methods = {name for name, _, _ in spans(lines)} - moved_names
    fields = set(re.findall(
        r'^    (?:private |protected |public |static |final |volatile |transient )+'
        r'[A-Za-z_][\w<>,\[\]. ?]*\s+([a-zA-Z_]\w*)\s*[=;]', kept, re.M))
    declared_here = set(re.findall(r'\b[A-Z][\w<>,\[\].?]*\s+([a-z]\w*)\s*[=)]', moved))

    for name in sorted(methods, key=len, reverse=True):
        moved = re.sub(r'(?<![\w.$])' + re.escape(name) + r'\(', field + '.' + name + '(', moved)
    for name in sorted(fields - declared_here, key=len, reverse=True):
        moved = re.sub(r'(?<![\w.$])' + re.escape(name) + r'\b(?!\s*\()', field + '.' + name, moved)

    # A CONSTANT is reached through the CLASS, not the instance -- and a static method that moved has
    # no instance to reach through at all.
    moved = re.sub(r'\b' + field + r'\.([A-Z][A-Z0-9_]+)\b', owner + r'.\1', moved)
    moved = unmask(moved, masked)

    out = [
        'package %s;' % package,
        '',
        '/**',
        ' * Extracted from {@link %s}. See the plan\'s §4.5 for why this cluster is one thing.' % owner,
        ' */',
        'final class %s {' % new_class,
        '',
        '    private final %s %s;' % (owner, field),
        '',
        '    %s(%s %s) {' % (new_class, owner, field),
        '        this.%s = %s;' % (field, field),
        '    }',
        '',
        moved,
        '}',
        '',
    ]
    target = os.path.join(os.path.dirname(source), new_class + '.java')
    io.open(target, 'w', encoding='utf-8').write('\n'.join(out))
    io.open(source, 'w', encoding='utf-8').write(
        '\n'.join(line for line, wanted_line in zip(lines, keep) if wanted_line))
    print('moved %d methods (%d lines) into %s' % (len(taken), len(body), target))
    return 0


if __name__ == '__main__':
    sys.exit(main())
