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
    ('ui/elements/editor',              'widget/texteditor'),
    # `ui/text/` is NOT one destination: SyntaxHighlighting writes colour spans onto UIText nodes, so
    # it is engine-specific and is COPIED with the editor that needs it (BY_NAME below); TextRange and
    # HighlightRegistry name no engine type and stay exactly where they are, because both engines
    # already reach them. The directory was in no batch at all until 6.5 tried to compile without it.
    ('ui/elements/graph',               'widget/graph'),
    ('ui/elements/canvas',              'widget/canvas'),
    ('ui/elements/list',                'widget/collection/list'),
    ('ui/elements/tree',                'widget/collection/tree'),
    ('ui/elements/table',               'widget/collection/table'),
    # THE CONFIG KIT IS ITS OWN THING, not a corner of `form` -- corrected during 6.2 and this table
    # was not, which is what 6.4 tripped on: the codemod builds a batch's ported imports from these
    # destinations, so every 6.4 copy that named a config control imported `widget.form.field`, a
    # package that has never existed. It compiles nowhere, so it fails loudly -- but it fails in the
    # BATCH, four files at a time, reading as a port defect rather than as a stale table.
    # `form` holds controls a caller places by hand (ColorSelector, SearchField); `config` is the
    # descriptor-driven generator over them.
    ('ui/elements/config/control',      'widget/config/control'),
    ('ui/elements/config',              'widget/config'),
    ('ui/elements/inspector',           'widget/config/inspector'),
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
    # ── 6.4 ────────────────────────────────────────────────────────────────────────────────────
    # THE PORT TYPES ARE MODEL, NOT WIDGET (D25). Six of PortType's seven members are facts about a
    # type; the seventh returns a UIElement and has ONE caller, which is the whole reason the SPI
    # and its registry could not live beside the graph model they describe.
    'PortType': 'graph/port',
    'BasicPortType': 'graph/port',
    'PortTypeRegistry': 'graph/port',
    # THE APP'S OWN VOCABULARY -- model-shaped, and still the application's rather than the graph
    # model's: a bridge onto CrystalGraphics' shader compiler, the literal form of a property's
    # default, a Settings declaration, and what a property NODE is. `graph.shader` empties completely
    # at 6.9, which is the point: `com.crystalgui.graph` goes back to being only the graph model.
    # ShaderPropertyNodes is here rather than in .blackboard because it and the bridge are mutually
    # recursive -- the bridge COMPILES a property node, it does not merely register one.
    'ShaderGraphBridge': 'app/shadergraph',
    'ShaderPropertyForm': 'app/shadergraph',
    'ShaderGraphSettings': 'app/shadergraph',
    'ShaderPropertyNodes': 'app/shadergraph/node',
    # THE APPLICATION. `graph.shader` held an editor, a properties panel, three previews, two field
    # widgets and five inspector sections in one flat directory, INSIDE the model's package -- while
    # importing `ui.elements.dock` and `ui.elements.workbench`, which a model package cannot.
    'BlackboardPanel': 'app/shadergraph/blackboard',
    'PropertyPill': 'app/shadergraph/blackboard',
    'CategoryHeader': 'app/shadergraph/blackboard',
    'InlineRename': 'app/shadergraph/blackboard',
    'MainPreviewPanel': 'app/shadergraph/preview',
    'ShaderNodePreview': 'app/shadergraph/preview',
    'ShaderGraphPreviews': 'app/shadergraph/preview',
    # `.node`, not `.field`: what a shader graph NODE is and how its fields are edited. Renamed
    # during the batch when the graph widgets gained their own `.node` split.
    'ShaderColorFieldWidget': 'app/shadergraph/node',
    'ShaderVectorFieldWidget': 'app/shadergraph/node',
    'ShaderPortArity': 'app/shadergraph/node',
    'ShaderInspectorSections': 'app/shadergraph',
    # DEFERRED TO 6.7, and structurally rather than incidentally: one `implements FileDocument` and
    # holds a `TextEditor` field, the other IS the registration with the dock and the workbench.
    'ShaderGraphEditor': 'app/shadergraph',
    'ShaderGraphContribution': 'app/shadergraph',
    # ── 6.5 / 6.6 ──────────────────────────────────────────────────────────────────────────────
    # CompletionRecency is an LRU of what was recently accepted, keyed by string. It is the ONE class
    # in the editor package that reaches no sibling's package-private surface and whose own is reached
    # by nobody -- so it is the one thing a split can take, and it belongs beside CompletionItem
    # rather than inside a widget. @see plan_m6.md 6.5
    'SyntaxHighlighting': 'widget/text',
    'CompletionRecency': 'text/lang',
    # THE EDITOR'S FOUR FEATURE PACKAGES (6.5). TextEditor and its 18 view parts are welded -- moving
    # the parts out alone costs 19 types and 65+ members, moving them WITH TextEditor costs zero -- so
    # the root holds the widget and its rendering and the LANGUAGE features leave. EditorFolding stays
    # because folding is view state by the engine's own rule (and moving it is 27 members, a third of
    # the whole bill for one file); DiffDecorations stays because its only readers are two view parts;
    # EditorCommands stays because it costs nothing either way and belongs beside its widget, as
    # GraphCommands and DesktopCommands do. @see plan_m6.md 6.5 section 1
    # The 18 VIEW PARTS. They and TextEditor reach each other's package-private surface freely, so a
    # `.part` package publishes the whole render protocol -- 17 types and ~72 members, on top of the
    # 78 the four feature packages cost. Taken anyway, deliberately: 22 files in one directory with a
    # 6,166-line class at the top of it is what the split exists to stop, and `render(int, int)` being
    # public is an honest statement of what it always was -- the contract between an editor and the
    # things that draw it. @see plan_m6.md 6.5
    # Folding and the diff model get packages of their own. Both were kept at the root on a price
    # argument -- 27 members for folding, and DiffDecorations' only readers are two view parts --
    # and both moved anyway, which took a further 25 published members between them. The root is
    # TextEditor and EditorCommands now, and nothing else.
    'EditorFolding': 'widget/texteditor/fold', 'DiffDecorations': 'widget/texteditor/diff',
    'EditorViewPart': 'widget/texteditor/part', 'DecorationPool': 'widget/texteditor/part',
    'CurrentLinePart': 'widget/texteditor/part', 'DiffBandsPart': 'widget/texteditor/part',
    'DiffChevronPart': 'widget/texteditor/part', 'ErrorStripePart': 'widget/texteditor/part',
    'FoldingDecorationsPart': 'widget/texteditor/part', 'GutterEdgePart': 'widget/texteditor/part',
    'IndentGuidesPart': 'widget/texteditor/part', 'InspectionWidgetPart': 'widget/texteditor/part',
    'LineNumbersPart': 'widget/texteditor/part', 'QuickFixBulbPart': 'widget/texteditor/part',
    'RulersPart': 'widget/texteditor/part', 'SelectionsPart': 'widget/texteditor/part',
    'SquigglesPart': 'widget/texteditor/part', 'ViewCursorsPart': 'widget/texteditor/part',
    'WhitespacePart': 'widget/texteditor/part', 'ZoomIndicatorPart': 'widget/texteditor/part',
    'CompletionPopup': 'widget/texteditor/suggest', 'CompletionSession': 'widget/texteditor/suggest',
    'CompletionRanking': 'widget/texteditor/suggest', 'EditorSuggest': 'widget/texteditor/suggest',
    'DocumentationPopup': 'widget/texteditor/doc', 'HoverDocumentation': 'widget/texteditor/doc',
    'SearchReplaceBar': 'widget/texteditor/find', 'EditorFind': 'widget/texteditor/find',
    'EditorLanguageFeatures': 'widget/texteditor/lang', 'EditorDiagnostics': 'widget/texteditor/lang',
    'DiagnosticActions': 'widget/texteditor/lang',
    # 6.6's FIVE sub-packages, and the price table above them was wrong by a factor of two. It quoted
    # `.window` at 97 published call sites and `.motion` at 24 and recommended taking neither; taken
    # TOGETHER the whole partition cost 52 members and 4 types, because most of those 97 are pairs a
    # split keeps on the SAME side. Measure the partition, never a package -- and measure it by
    # compiling. Four classes stay at the layer root, which is the whole of what a layer root is for.
    'Taskbar': 'desktop/taskbar', 'TaskbarEntryMotion': 'desktop/taskbar',
    'TaskbarPreviews': 'desktop/taskbar', 'TaskbarDesigner': 'desktop/taskbar',
    'WindowPreview': 'desktop/taskbar', 'WindowThumbnail': 'desktop/taskbar',
    'WindowSwitcher': 'desktop/switcher',
    'WindowFrame': 'desktop/window', 'WindowChrome': 'desktop/window',
    'WindowCommands': 'desktop/window', 'WindowRegistry': 'desktop/window',
    'WindowMove': 'desktop/window', 'WindowKeyboardMove': 'desktop/window',
    'SnapZones': 'desktop/window', 'SystemMenu': 'desktop/window',
    'WindowSnapshot': 'desktop/window',
    'WindowAnimator': 'desktop/motion', 'WindowAnimation': 'desktop/motion',
    'WindowGeometryAnimation': 'desktop/motion', 'WindowMotion': 'desktop/motion',
    # The ONE class a loader talks to, and the only reason this is a package rather than a fifth root
    # class. It is NOT neutral -- it holds a document and reads its focus owner.
    'ScreenOverlay': 'desktop/host',
    # An enum, a policy record and a presentation enum -- named by BOTH engines, so a package both
    # may name. ScreenOverlay was the plan's fourth candidate and does NOT qualify: it holds a
    # `UIWindow` and reads its focus owner, so it is a facade over the engine rather than an SPI a
    # host implements, and it stays in `desktop` as an ordinary copy. DesktopPresentation replaces
    # it -- a bare enum with no imports at all, read by UIWindow and by the taskbar.
    'WindowState': 'core/window', 'WindowPolicy': 'core/window',
    'DesktopPresentation': 'core/window',
    # `widget.graph.node` is the four that reach nothing package-private. GraphNode, NodePort and
    # PortDefaultEditor CANNOT be here: they share package-private members with GraphView by design,
    # and Java has no sub-package visibility, so moving them means publishing ten "only the view may
    # call this" methods. NodeWidgetFactory looked clean by the obvious test and was not -- it CALLS
    # GraphNode.bindToDocument. @see plan_m6.md 6.4
    'NodeCreationMenu': 'widget/graph/node',
    'NodeFieldBinder': 'widget/graph/node',
    'NodeFieldWidgets': 'widget/graph/node',
    # ui/elements root -- by kind, which is the whole reason the root is being split.
    'Button': 'widget/control', 'Checkbox': 'widget/control', 'CheckboxGroup': 'widget/control',
    'Switch': 'widget/control', 'Slider': 'widget/control', 'ProgressBar': 'widget/control',
    'TextField': 'widget/control', 'Dropdown': 'widget/control',
    'SymbolIcon': 'widget/control',
    # A WIDGET'S TIER IS DECIDED BY WHAT IT COMPOSES (M6.1): SearchField holds a Tooltip and
    # ColorSelector a Dropdown, both of which are `overlay` -- so neither can be in `control`,
    # which may name only control/text/scroll. LayeringTest is what said so.
    'SearchField': 'widget/form', 'ColorSelector': 'widget/form',
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
    # desktop's one leaf widget. It landed at 6.1 as `desktop/window` and moved UP at 6.6, when the
    # price table refused `.window` outright (97 published call sites, 57 on WindowFrame alone). A
    # package holding one leaf while the eleven classes it belongs with stay behind is a directory,
    # not a boundary -- the same argument that refused `.motion`.
    'WindowIcon': 'desktop/window',
}

# Which BATCH ports each destination (plan_m6.md section 5).
BATCH = [
    ('widget/control', '6.1'), ('widget/text', '6.1'), ('widget/scroll', '6.1'),
    ('widget/dnd', '6.1'),
    # `desktop/window` is 6.6's, and WindowIcon -- which landed there at 6.1 as the taskbar's leaf
    # widget -- keeps its own BATCH_OVERRIDE. A destination decides where a class GOES, never when
    # it went, and the two are only ever the same while a destination holds one batch's work.
    ('desktop/window', '6.6'),
    ('widget/layout', '6.2'), ('widget/overlay', '6.2'), ('widget/form', '6.2'),
    # THE CONFIG KIT, and this table is the second half of the correction 6.4 made to BY_DIR. Renaming
    # the destination from `widget/form` to `widget/config` left no batch prefix matching it, so all
    # twenty-five config files fell through to the longest OTHER match and were reported as 6.7's --
    # which is how a progress count can be wrong without any file moving. A destination appears in two
    # tables here and changing one is changing half of it.
    ('widget/config', '6.2'),
    ('chrome/status', '6.2'), ('chrome/notification', '6.2'),
    ('widget/collection', '6.3'), ('chrome', '6.3'),
    ('widget/canvas', '6.4'), ('widget/graph', '6.4'), ('graph/shader', '6.4'),
    ('graph/port', '6.4'), ('app/shadergraph', '6.4'), ('widget/graph/node', '6.4'),
    ('desktop/taskbar', '6.6'), ('desktop/switcher', '6.6'), ('core/window', '6.6'),
    ('desktop/motion', '6.6'), ('desktop/host', '6.6'),
    ('text/lang', '6.5'),
    ('widget/texteditor', '6.5'),
    ('widget/texteditor/suggest', '6.5'), ('widget/texteditor/doc', '6.5'),
    ('widget/texteditor/find', '6.5'), ('widget/texteditor/lang', '6.5'),
    ('widget/texteditor/part', '6.5'),
    ('widget/texteditor/fold', '6.5'), ('widget/texteditor/diff', '6.5'),
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
    # 6.6: the two IMPORTANT writes that became classes -- the compositor's own presence and a
    # window's overlay slot. Both are state a widget flips from its own bookkeeping, which is what
    # a state adjective IS, and both have the SAFE answer as the base rule.
    'live', 'occupied', 'placing',
}


def part_owners():
    """Which class declares each `__x__` name — a name may have several owners, and usually does."""
    owners = {}
    for base in (SRC, LANGUAGE):
        for dirpath, _dirs, files in os.walk(base):
            for f in sorted(files):
                if not f.endswith('.java'):
                    continue
                text = io.open(os.path.join(dirpath, f), encoding='utf-8', errors='replace').read()
                for name in set(re.findall(r'"__([a-z0-9-]+)__"', text)):
                    owners.setdefault(name, set()).add(f[:-5])
    return owners


def classify_parts():
    """Every (owner, name) pair, with a proposed kind and the evidence for it.

    KEYED BY OWNER, NOT BY NAME, and the first widget ported proved why. `__pre-icon__` is declared
    by SIX classes -- Button, Tab, DragGhost, WindowPreview, ProjectFileTree, StripeView -- and its
    kind is not the same for all of them: it is Button's own icon slot (A) and the slot a window
    preview puts a whole WindowIcon widget into (B, because a rule reaches through it to the
    monogram). `__label__`, `__content__`, `__icon__`, `__title__`, `__close__` and `__header__` are
    all shared the same way; `.__content__` being claimed by three unrelated widgets is a standing
    invariant row, and a table keyed by name alone reproduces exactly that mistake.
    """
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

    owners = part_owners()
    # Which names each class declares, and the tag it answers -- how a selector is attributed.
    declares = {}
    for name, who in owners.items():
        for cls in who:
            declares.setdefault(cls, set()).add(name)

    def owns(cls, sel_before, name):
        """Whether `cls` is plausibly the subject of a selector whose prefix is `sel_before`.

        A selector counts toward an owner when it names that owner's TAG or one of the owner's OTHER
        parts before reaching this one -- `taskbar .__entry__ .__icon__` is the taskbar's, not
        Button's, even though both declare `__icon__`. An UNSCOPED rule (`.__thumb__ { }`) counts for
        every owner, which is right: it really does reach all of them, and that is the bug the port
        exists to remove.
        """
        # A name NO literal declares -- built by concatenation, like MarkupView's `__markup-h` + level
        # + `__` and the region names assembled from an enum. Nothing can attribute it, so every rule
        # mentioning it is evidence about it, and it still needs a kind: the port has to know whether
        # to write `part=` or a class, and a name the scan cannot see is exactly the one that would
        # otherwise be discovered by a rule silently not matching.
        if cls == '(unowned)':
            return True
        if not sel_before.strip():
            return True
        tag = cls.lower()
        if re.search(r'(^|[\s>]) *' + re.escape(tag) + r'', ' ' + sel_before):
            return True
        mine = declares.get(cls, set()) - {name}
        for other in re.findall(r'__([a-z0-9-]+)__', sel_before):
            if other in mine:
                return True
        return False

    names = {}
    for sel in selectors:
        compounds = [c for c in re.split(r'\s*>\s*|\s+', sel) if c]
        part_compounds = [i for i, c in enumerate(compounds) if '__' in c]
        for i in part_compounds:
            before = ' '.join(compounds[:i])
            for name in re.findall(r'__([a-z0-9-]+)__', compounds[i]):
                for cls in owners.get(name, {'(unowned)'}):
                    if not owns(cls, before, name):
                        continue
                    info = names.setdefault((cls, name),
                                            {'uses': 0, 'under_part': 0, 'above': 0})
                    info['uses'] += 1
                    if any(j < i for j in part_compounds):
                        info['under_part'] += 1
                    if i < len(compounds) - 1:
                        info['above'] += 1

    rows = []
    for (owner, name) in sorted(names):
        rows.append(propose(owner, name, names[(owner, name)]))
    # A pair no attributable rule mentions still has to be classified: the port must know whether to
    # write `part=` or a class, and "no rule names it" is not "it does not exist".
    seen = set(names)
    for name in sorted(owners):
        for owner in sorted(owners[name]):
            if (owner, name) not in seen:
                rows.append(propose(owner, name, {'uses': 0, 'under_part': 0, 'above': 0}))
    return rows


def propose(owner, name, info):
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
        return (owner, name, kind, info['uses'], why)


def destination(rel, stem):
    if stem in BY_NAME:
        return BY_NAME[stem]
    for prefix, dest in BY_DIR:
        if rel == prefix or rel.startswith(prefix + '/'):
            return dest
    return ''


# A class whose DESTINATION is in one batch but whose PORT waits for another. Two so far, both
# 6.4's, both blocked on 6.7 by a supertype or a field rather than by a reference that could be
# stubbed -- `ShaderGraphEditor implements FileDocument` and holds a `TextEditor`, and
# `ShaderGraphContribution` IS the registration with the dock and the workbench. Recorded here
# rather than by moving their destination, because where a class BELONGS and when it can GO are
# different questions and conflating them is how a deferral becomes a lost file.
# A class the heuristic calls neutral that STILL has to be copied, because the old engine's own copy
# of the batch names it and stays until 6.9 (plan_m6.md 6.4 D27). `theOldEngineNamesNothingOfTheNew`
# scans everything that is not new-engine, which includes `ui/elements/graph`, `ui/elements/canvas`
# and `graph/shader` -- so a move into `widget.*` or `app.*` fails the boundary scan on the day it
# lands, however neutral the class itself is. The exceptions are the two heading for `graph.port`,
# which is a package BOTH engines may name.
HOW_OVERRIDE = {
    # 6.5: SEVEN more the heuristic reads as pure, and every one is named by the OLD editor package,
    # which runs the game until 6.9. `com/crystalgui/widget/` is a NEW_PACKAGES prefix, so a move
    # there is the old engine reaching into the new one. `CompletionRecency` is the ONE genuine move:
    # it names nothing but `text.lang` types, and `text/lang` is a package both engines may name.
    'SyntaxHighlighting': 'copy',
    'CompletionRanking': 'copy',
    'CompletionSession': 'copy',
    'DiagnosticActions': 'copy',
    'DiffDecorations': 'copy',
    'EditorDiagnostics': 'copy',
    'EditorFolding': 'copy',
    'HoverDocumentation': 'copy',
    # 6.6: SIX classes the `touches` heuristic reads as pure, because they name no UIElement and no
    # paint context -- and every one of them is named by the OLD Desktop or WindowFrame, which still
    # run the game until 6.9. `com/crystalgui/desktop/` is a NEW_PACKAGES prefix, so a move there is
    # the old engine reaching into the new one: the boundary scan fails on the day it lands, and the
    # old engine stops compiling besides. Only a NEUTRAL destination can take a move.
    'DesktopSession': 'copy',
    'SnapZones': 'copy',
    'WindowKeyboardMove': 'copy',
    'WindowMotion': 'copy',
    'WindowRegistry': 'copy',
    'WorldRect': 'copy',
    'GraphConnection': 'copy',
    'GraphSelection': 'copy',
    'NodeWidgetFactory': 'copy',
    'ShaderGraphBridge': 'copy',
    'ShaderGraphSettings': 'copy',
    'ShaderPropertyForm': 'copy',
}


BATCH_OVERRIDE = {
    'SyntaxHighlighting': '6.5',
    # NOT PORTED, EVER: TextRange and HighlightRegistry name no engine type and both engines
    # already reach them where they are. Listed rather than filtered out, because a file the
    # ledger omits is a file nobody notices is missing -- which is exactly how SyntaxHighlighting
    # was in no batch at all until 6.5 failed to compile.
    'TextRange': 'stays',
    'HighlightRegistry': 'stays',
    # WindowIcon's destination moved from `desktop/window` to `desktop` at 6.6, and `desktop` is 6.6's
    # -- but the class itself shipped at 6.1 as the taskbar's leaf widget. A destination decides where
    # a class GOES, never when it went.
    'WindowIcon': '6.1',
    'ShaderGraphEditor': '6.7',
    'ShaderGraphContribution': '6.7',
    # THE DEFERRAL CASCADED. ShaderInspectorSections names ShaderGraphEditor four times, so it waits
    # with it -- which 6.4's audit missed by asking which classes reach OUTSIDE the batch, when the
    # question is which reach outside what is SHIPPING.
    'ShaderInspectorSections': '6.7',
}


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
            # `ui/text` is admitted the same way and for a narrower reason: exactly ONE of its three
            # classes is engine-specific, so the directory has no single destination and BY_NAME
            # places the one that moves. It was in no batch at all until 6.5 failed to compile.
            in_scope = rel in ('ui/elements', 'ui/text') or any(
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
                batch = BATCH_OVERRIDE.get(stem) or batch_of(dest)
                how = HOW_OVERRIDE.get(stem) or ('copy' if touches else 'move')
                rows.append((rel + '/' + stem, lines, dest or rel, batch, how, 'pending'))
    return rows


def render():
    out = io.StringIO()
    out.write('# M6 PORT LEDGER -- generated by tools/port/ledger.py; regenerate, never hand-edit rows.\n')
    out.write('# Confirm a proposal by changing `proposed` to `confirmed` in the source column and\n')
    out.write('# leaving the rest alone; the generator preserves confirmations by name.\n')
    out.write('#\n')
    out.write('# CLASS\tpath\tlines\tdestination\tbatch\thow\tstatus\n')
    classes = classify_classes()
    statuses = existing_statuses()
    for path, lines, dest, batch, how, status in classes:
        status = statuses.get(path, status)
        out.write('CLASS\t%s\t%d\t%s\t%s\t%s\t%s\n' % (path, lines, dest, batch, how, status))
    out.write('#\n# PART\towner\tname\tkind\tuses\tsource\twhy\n')
    parts = sorted(classify_parts())
    confirmed = existing_confirmations()
    for owner, name, kind, uses, why in parts:
        key = owner + '/' + name
        source = 'confirmed' if key in confirmed else 'proposed'
        kind = confirmed.get(key, kind)
        out.write('PART\t%s\t%s\t%s\t%d\t%s\t%s\n' % (owner, name, kind, uses, source, why))
    return out.getvalue(), len(classes), len(parts)


def existing_statuses():
    """Which classes have been ported, so regenerating never silently un-ports one."""
    if not os.path.exists(LEDGER):
        return {}
    statuses = {}
    for line in io.open(LEDGER, encoding='utf-8'):
        parts = line.rstrip('\n').split('\t')
        if len(parts) >= 7 and parts[0] == 'CLASS':
            statuses[parts[1]] = parts[6]
    return statuses


def existing_confirmations():
    """Kinds a human has confirmed, so regenerating never silently un-decides one."""
    if not os.path.exists(LEDGER):
        return {}
    confirmed = {}
    for line in io.open(LEDGER, encoding='utf-8'):
        parts = line.rstrip('\n').split('\t')
        if len(parts) >= 6 and parts[0] == 'PART' and parts[5] == 'confirmed':
            confirmed[parts[1] + '/' + parts[2]] = parts[3]
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
