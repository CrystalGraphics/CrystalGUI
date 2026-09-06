#!/usr/bin/env python3
"""
Per widget: CAN it be a shadow host, and what does the answer cost?

D1 assumed most `__x__` names are shadow parts. The M6.1 batch says otherwise, and this is the
measurement that settles it per widget rather than per opinion.

A widget can be a shadow host only if NOTHING in any shipped sheet reaches THROUGH its structure --
because `::part()` has no spelling for that. Two shapes, both fatal:

    HOST .__a__ .__b__       a part under a part      (`::part(a)::part(b)` is invalid CSS)
    HOST .__a__ tag ...      a tag under a part       (nothing descends from a leaf)

Everything else -- a rule ending at `HOST .__a__`, with or without pseudo-classes -- twins cleanly
into `HOST::part(a)` and is what tools/port/twins.py already does.
"""
import io, re, glob, collections

COMMENT = re.compile(r'/\*.*?\*/', re.S)
PART = re.compile(r'\.__([a-z0-9-]+)__')
TAG = re.compile(r'^[a-z][a-z0-9-]*')


def selectors():
    for path in sorted(glob.glob(
            'core/src/main/resources/assets/crystalgui/ui/**/*.css', recursive=True)):
        text = COMMENT.sub('', io.open(path, encoding='utf-8').read())
        for block in re.findall(r'([^{}]+)\{', text):
            for alt in block.split(','):
                sel = alt.strip()
                if sel and not sel.startswith('@'):
                    yield path.split('/')[-1], sel


def main():
    through = collections.defaultdict(list)   # widget -> rules reaching through it
    ending = collections.Counter()            # widget -> rules that twin cleanly
    for _, sel in selectors():
        compounds = sel.split()
        if len(compounds) < 2:
            continue
        head = TAG.match(compounds[0])
        if not head:
            continue
        widget = head.group(0)
        # Where is the FIRST part, and is anything after it?
        for i, c in enumerate(compounds[1:], start=1):
            if not PART.search(c):
                continue
            if i < len(compounds) - 1:
                through[widget].append(sel)
            else:
                ending[widget] += 1
            break

    widgets = sorted(set(list(through) + list(ending)))
    print('%-18s %8s %8s   %s' % ('widget', 'through', 'ending', 'verdict'))
    print('-' * 72)
    shadow = light = 0
    for w in widgets:
        n = len(through[w])
        verdict = 'LIGHT (kind B)' if n else 'shadow ok'
        if n:
            light += 1
        else:
            shadow += 1
        print('%-18s %8d %8d   %s' % (w, n, ending[w], verdict))
    print('-' * 72)
    print('%d widgets can host a shadow tree, %d must stay light' % (shadow, light))
    print('%d rules reach through a widget and have NO ::part() spelling'
          % sum(len(v) for v in through.values()))


main()
