#!/usr/bin/env python3
"""
Generates the M6 port ledger -- plan_m6.md section 2.5.

    python tools/port/ledger.py            # regenerate tools/port/port-ledger.tsv
    python tools/port/ledger.py --check    # exit 1 if the file on disk is stale

TWO TABLES, ONE FILE. The CLASS table says where each of the ported files goes and whether it
has moved yet; the PART table classifies every `__part__` name in every sheet as A (a true shadow part),
B (light-tree structure) or C (a state flag) -- which is D1, and the single largest decision in M6.

THE CLASSIFICATION IS PROPOSED HERE AND CONFIRMED BY HAND. The heuristics below are the census
rules from plan_m6.md section 0.4 read mechanically, and they are right most of the time and wrong
in exactly the cases that matter: a name a sheet only ever uses as a leaf LOOKS like a part even
when it holds a caller's content, and the only way to know is to read the widget. So every row
carries a `source` column saying whether a human confirmed it, and PortLedgerTest fails on an
unconfirmed row for a class that has actually been ported -- the check bites when the answer is
about to be used, not before.

Why generated at all: 800 rows written by hand is 800 chances to mistype a name that no longer
exists, and the inputs (the file tree, the sheets) are already the truth. Regenerating is how the
ledger stays a description of the tree rather than a second copy of it that drifts.
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'crystalgui')
SHEETS = os.path.join(ROOT, 'core', 'src', 'main', 'resources', 'assets', 'crystalgui', 'ui', 'styles')
LEDGER = os.path.join(ROOT, 'tools', 'port', 'port-ledger.tsv')
LANGUAGE = os.path.join(ROOT, 'language', 'src', 'main', 'java', 'com', 'crystalgui', 'language', 'run', 'view')

# ── Where each source directory's classes land (plan_m6.md section 2.6) ──────────────────────────
#
# Longest prefix wins. A file whose destination is not decided by its directory alone is listed by
# name in BY_NAME below -- the leaf widgets are the only place that is needed, because `ui/elements`
# root held everything from a Button to a MarkupView.

BY_DIR = [
    ('ui/elements/editor',              'widget/editor'),
    ('ui/elements/graph',               'widget/graph'),
    ('ui/elements/canvas',              'widget/canvas'),
    ('ui/elements/list',                'widget/collection/list'),
    ('ui/elements/tree',                'widget/collection/tree'),
    ('ui/elements/table',               'widget/collection/table'),
    ('ui/elements/config/control',      'widget/form/field'),
    ('ui/elements/config',              'widget/form'),
    ('ui/elements/inspector',           'widget/form/inspector'),
    ('ui/elements/desktop',             'desktop'),
    ('ui/elements/dock',                'workbench/dock'),
    ('ui/elements/workbench/decoration','workbench/decoration'),
    ('ui/elements/workbench/document',  'workbench/document'),
    ('ui/elements/workbench',           'workbench'),
    ('ui/elements/chrome',              'chrome'),
    ('graph/shader',                    'graph/shader'),
    ('editor',                          'editor'),
    ('example/machine',                 'example/machine'),
    ('net/window',                      'net/window'),
]

# The Run panel lives in `language/`, outside core's tree, and stays where it is (plan_m6.md 1.2).
LANGUAGE_DEST = 'language/run/view'

BY_NAME = {
    # ui/elements root -- by kind, which is the whole reason the root is being split.
    'Button': 'widget/control', 'Checkbox': 'widget/control', 'CheckboxGroup': 'widget/control',
    'Switch': 'widget/control', 'Slider': 'widget/control', 'ProgressBar': 'widget/control',
    'TextField': 'widget/control', 'SearchField': 'widget/control', 'Dropdown': 'widget/control',
    'ColorSelector': 'widget/control', 'SymbolIcon': 'widget/control',
    'UIText': 'widget/text', 'MarkupView': 'widget/text',
    'Scroller': 'widget/scroll', 'ScrollerView': 'widget/scroll',
    'SplitView': 'widget/layout', 'TabView': 'widget/layout', 'Tab': 'widget/layout',
    'Popover': 'widget/overlay', 'Menu': 'widget/overlay', 'MenuItem': 'widget/overlay',
    'Tooltip': 'widget/overlay', 'Dialog': 'widget/overlay', 'DialogManager': 'widget/overlay',
    'InputDialog': 'widget/overlay',
    'DragGhost': 'widget/dnd', 'InsertionMarker': 'widget/dnd',
    'WidgetCensus': 'widget',  # a diagnostic, not a widget -- ported last, with 6.7's applications
    # chrome, which is a layer rather than a directory: a few of these are overlay/layout widgets.
    'ContextMenu': 'widget/overlay', 'MenuBuilder': 'widget/overlay', 'PageStack': 'widget/layout',
    # desktop's one leaf widget.
    'WindowIcon': 'desktop/window',
}

# Which BATCH ports each destination (plan_m6.md section 5).
BATCH = [
    ('widget/control', '6.1'), ('widget/text', '6.1'), ('widget/scroll', '6.1'),
    ('widget/dnd', '6.1'), ('desktop/window', '6.1'),
    ('widget/layout', '6.2'), ('widget/overlay', '6.2'), ('widget/form', '6.2'),
    ('chrome/status', '6.2'), ('chrome/notification', '6.2'),
    ('widget/collection', '6.3'), ('chrome', '6.3'),
    ('widget/canvas', '6.4'), ('widget/graph', '6.4'), ('graph/shader', '6.4'),
    ('widget/editor', '6.5'),
    ('desktop', '6.6'),
    ('workbench', '6.7'), ('editor', '6.7'), ('example/machine', '6.7'),
    ('net/window', '6.8'),
    ('language/run/view', '6.7'),
    ('widget', '6.7'),  # the bare `widget` destination is WidgetCensus alone; everything else is a tier
]

# ── Part classification (D1) ────────────────────────────────────────────────────────────────────
#
# C -- a STATE FLAG the widget toggles from its own listener. Recognised by name, because that is
# what a state flag is: an adjective. This list is the one part of the heuristic that is nearly
# always right, and a miss is cheap (a state flag mis-read as light structure is still a class).
STATE_NAMES = {
    'active', 'activating', 'animating', 'attention', 'blank', 'branded', 'busy', 'checked',
    'collapsed', 'dimmed', 'dock-dragging', 'dragging', 'editing', 'empty', 'empty-collapsed',
    'exiting', 'expanded', 'first', 'flipped', 'focused', 'fullscreen', 'has-checkable',
    'has-submenu', 'hidden', 'hud', 'inline', 'invalid', 'keyboard-moving', 'leaf', 'match',
    'maximized', 'missing', 'moving', 'no-input-gap', 'no-inputs', 'off', 'on', 'open',
    'panel-focused', 'panning', 'pinned', 'ports-empty', 'preferred-action', 'rising', 'searching',
    'second', 'selected', 'shown', 'sorted-asc', 'sorted-desc', 'truncated', 'unlabelled',
    'vertical', 'windowed', 'floating', 'full-width', 'problem-only', 'no-message', 'disabled',
    'thin', 'dock-bannered', 'unknown-type', 'caption-adopted',
}


def classify_parts():
    """Every `__part__` name in the sheets, with a proposed kind and the evidence for it."""
    selectors = []
    for dirpath, _dirs, files in os.walk(SHEETS):
        for f in sorted(files):
            if not f.endswith('.css'):
                continue
            text = io.open(os.path.join(dirpath, f), encoding='utf-8').read()
            text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
            for raw in re.findall(r'([^{}]+)\{', text):
                for sel in raw.split(','):
                    sel = sel.strip()
                    if sel and not sel.startswith('@'):
                        selectors.append(sel)

    names = {}
    for sel in selectors:
        compounds = [c for c in re.split(r'\s*>\s*|\s+', sel) if c]
        part_compounds = [i for i, c in enumerate(compounds) if '__' in c]
        for i in part_compounds:
            for name in re.findall(r'__([a-z0-9-]+)__', compounds[i]):
                info = names.setdefault(name, {'uses': 0, 'under_part': 0, 'above': 0, 'leaf_only': True})
                info['uses'] += 1
                # A part with another part ABOVE it in the same selector.
                if any(j < i for j in part_compounds):
                    info['under_part'] += 1
                # Something selected BENEATH this compound -- a tag or a part.
                if i < len(compounds) - 1:
                    info['above'] += 1
                    info['leaf_only'] = False

    rows = []
    for name in sorted(names):
        info = names[name]
        if name in STATE_NAMES:
            kind, why = 'C', 'state adjective'
        elif info['above']:
            # THE ONE STRONG SIGNAL: a rule selects THROUGH this name, so something lives inside it
            # that a sheet needs to reach -- which `::part()` cannot express (`::part(a)::part(b)` is
            # invalid CSS) and which is the definition of light-tree structure.
            kind, why = 'B', 'a rule selects through it (%d)' % info['above']
        else:
            # `under_part` is NOT a signal, and the first run of this generator proved it: `thumb`,
            # `mark`, `label`, `track` and `fill` -- the archetypal parts of Slider, Checkbox, Button
            # and Scroller -- were all called B because a sheet scopes them through a container
            # (`colorselector .__channel-row__ slider .__thumb__`). Being SCOPED BY an ancestor says
            # nothing about whether you hold anything; being SELECTED THROUGH says you do.
            kind = 'A'
            why = ('leaf in every rule%s'
                   % ('' if not info['under_part'] else ', scoped by an ancestor in %d' % info['under_part']))
        rows.append((name, kind, info['uses'], why))
    return rows


def destination(rel, stem):
    if stem in BY_NAME:
        return BY_NAME[stem]
    for prefix, dest in BY_DIR:
        if rel == prefix or rel.startswith(prefix + '/'):
            return dest
    return ''


def batch_of(dest):
    best = ('', '')
    for prefix, b in BATCH:
        if dest == prefix or dest.startswith(prefix + '/'):
            if len(prefix) > len(best[0]):
                best = (prefix, b)
    return best[1] or '?'


def classify_classes():
    rows = []
    roots = [(SRC, ''), (LANGUAGE, 'language/run/view')]
    for base, forced in roots:
        for dirpath, _dirs, files in os.walk(base):
            rel = forced or os.path.relpath(dirpath, SRC).replace('\\', '/')
            # `ui/elements` itself has no BY_DIR entry -- its 28 root files are placed by NAME,
            # which is the whole reason the root is being split -- so it has to be admitted
            # here, or every leaf widget is filtered out before BY_NAME is ever consulted.
            in_scope = rel == 'ui/elements' or any(
                    rel == p or rel.startswith(p + '/') for p, _ in BY_DIR)
            if not forced and not in_scope:
                continue
            for f in sorted(files):
                if not f.endswith('.java') or f == 'package-info.java':
                    continue
                stem = f[:-5]
                text = io.open(os.path.join(dirpath, f), encoding='utf-8', errors='replace').read()
                lines = text.count('\n')
                # A class that reaches the old engine is COPIED by the codemod; one that does not is
                # MOVED in the IDE, whose Move refactor fixes both engines' imports for nothing.
                touches = bool(re.search(r'\bUIElement\b|\bUIWindow\b|getRuntimeCache|CgUiPaintContext', text))
                dest = forced or destination(rel, stem)
                rows.append((rel + '/' + stem, lines, dest or rel, batch_of(dest),
                             'copy' if touches else 'move', 'pending'))
    return rows


def render():
    out = io.StringIO()
    out.write('# M6 PORT LEDGER -- generated by tools/port/ledger.py; regenerate, never hand-edit rows.\n')
    out.write('# Confirm a proposal by changing `proposed` to `confirmed` in the source column and\n')
    out.write('# leaving the rest alone; the generator preserves confirmations by name.\n')
    out.write('#\n')
    out.write('# CLASS\tpath\tlines\tdestination\tbatch\thow\tstatus\n')
    classes = classify_classes()
    for path, lines, dest, batch, how, status in classes:
        out.write('CLASS\t%s\t%d\t%s\t%s\t%s\t%s\n' % (path, lines, dest, batch, how, status))
    out.write('#\n# PART\tname\tkind\tuses\tsource\twhy\n')
    parts = classify_parts()
    confirmed = existing_confirmations()
    for name, kind, uses, why in parts:
        source = 'confirmed' if name in confirmed else 'proposed'
        kind = confirmed.get(name, kind)
        out.write('PART\t%s\t%s\t%d\t%s\t%s\n' % (name, kind, uses, source, why))
    return out.getvalue(), len(classes), len(parts)


def existing_confirmations():
    """Kinds a human has confirmed, so regenerating never silently un-decides one."""
    if not os.path.exists(LEDGER):
        return {}
    confirmed = {}
    for line in io.open(LEDGER, encoding='utf-8'):
        parts = line.rstrip('\n').split('\t')
        if len(parts) >= 5 and parts[0] == 'PART' and parts[4] == 'confirmed':
            confirmed[parts[1]] = parts[2]
    return confirmed


if __name__ == '__main__':
    text, n_classes, n_parts = render()
    if '--check' in sys.argv:
        current = io.open(LEDGER, encoding='utf-8').read() if os.path.exists(LEDGER) else ''
        if current != text:
            print('port-ledger.tsv is stale -- run: python tools/port/ledger.py')
            sys.exit(1)
        print('port-ledger.tsv is current (%d classes, %d parts)' % (n_classes, n_parts))
    else:
        io.open(LEDGER, 'w', encoding='utf-8', newline='\n').write(text)
        print('wrote %s: %d classes, %d parts' % (LEDGER, n_classes, n_parts))
