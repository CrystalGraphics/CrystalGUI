#!/usr/bin/env python3
"""
The M6 codemod -- plan_m6.md section 2.7. **The port is COPIED and transformed, never written.**

    python tools/port/codemod.py --batch 6.1              # copy + transform, print the residual
    python tools/port/codemod.py --batch 6.1 --dry-run    # print what it would do and the residual
    python tools/port/codemod.py --batch 6.1 --only Button

The census in plan_m6.md section 0.3 is exact enough to script: of 2,670 engine call sites across the
widget layer, 2,227 are mechanical and 443 need a reading. This does the 2,227 and prints the 443 as
`file:line kind` -- the reading list for the batch, which is then worked through by hand with the
budget line in section 5 as the number to hold to.

WHAT IT DOES NOT DO, AND MUST NOT. It does not decide anything. Every transformation below is a
rename or a re-spelling whose answer is the same at every site; anything whose answer depends on what
the widget MEANS is left alone and reported. A codemod that guessed would be wrong two hundred times
before anybody noticed once, which is why 6.1 -- the best-covered code in the repository -- is where
it is tested, and why one file per transformation is diffed by eye before the rest of a batch runs.

THE OLD FILE STAYS. A ported class is a COPY into its new package (the ledger's destination); the
original runs the game until 6.9. That is the strangler line, and it is what lets a batch be reverted
by deleting a directory.
"""

import argparse
import io
import os
import re
import shutil
import sys

# Windows consoles default to cp1252, which cannot encode a box-drawing character; the report is
# the tool's whole output, so it must not die formatting a rule.
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CORE = os.path.join(ROOT, 'core', 'src', 'main', 'java', 'com', 'crystalgui')
LEDGER = os.path.join(ROOT, 'tools', 'port', 'port-ledger.tsv')


# ── The mechanical transformations ──────────────────────────────────────────────────────────────
#
# (pattern, replacement, name). Order matters where one rewrite feeds another: the receiver rewrites
# run BEFORE the bare-method ones, or `getAttachedWindow().getInputHandler()` is half-rewritten.

RULES = [
    # -- IMPORTS FIRST, or the bare type rules below rewrite the PACKAGE too -------------------
    #
    # `\bUIElement\b -> UINode` turns `import com.crystalgui.ui.UIElement;` into an import of
    # `com.crystalgui.ui.UINode`, which does not exist -- the new types are in `ui.dom` and `ui.box`.
    # It fails the compile rather than passing silently, but it fails it once per widget with a
    # message that points at the import instead of at the rule, so it is worth spending four lines
    # here than seventeen manual repairs. `UIText` is a rename as well as a move (D15: it merges into
    # the engine's own text node), which is why it is listed with the packages rather than as a type.
    (r'import com\.crystalgui\.ui\.UIElement;', 'import com.crystalgui.ui.dom.UINode;', 'import'),
    # AN INLINE FQN, which the import rules above cannot see. `\bUIElement\b -> UINode` then rewrites
    # the last segment in place and leaves `com.crystalgui.ui.UINode`, a package that does not exist --
    # and it fails at the USE, not at an import, so it reads as the class being missing rather than as
    # the qualifier being stale. One site in 6.2 (SettingsConfigurator's `addRow` parameter).
    (r'\bcom\.crystalgui\.ui\.UIElement\b', 'com.crystalgui.ui.dom.UINode', 'import'),
    (r'\bcom\.crystalgui\.ui\.UIWindow\b', 'com.crystalgui.ui.dom.UIDocument', 'import'),
    (r'import com\.crystalgui\.ui\.UIWindow;', 'import com.crystalgui.ui.dom.UIDocument;', 'import'),
    (r'import com\.crystalgui\.ui\.elements\.UIText;', 'import com.crystalgui.widget.text.UIText;', 'import'),
    (r'import com\.crystalgui\.ui\.UIFrameTicker;', 'import com.crystalgui.ui.service.Animation;', 'import'),
    (r'import com\.crystalgui\.ui\.AnchoredPlacement;', 'import com.crystalgui.ui.service.AnchoredPlacement;', 'import'),

    # -- The base classes and the document -----------------------------------------------------
    (r'\bextends UIElement\b', 'extends UINode', 'base class'),
    (r'\bUIElement\b', 'UINode', 'element type'),
    (r'\bUIWindow\b', 'UIDocument', 'window type'),

    # -- Receivers, longest first ---------------------------------------------------------------
    (r'getAttachedWindow\(\)\.getInputHandler\(\)\.getDragController\(\)', 'DRAG_CONTROLLER', 'drag receiver'),
    (r'getInputHandler\(\)\.getDragController\(\)', 'DRAG_CONTROLLER', 'drag receiver'),
    (r'getAttachedWindow\(\)\.getInputHandler\(\)\.requestPointerFocus', 'document().focus().requestPointerFocus', 'focus'),
    (r'getAttachedWindow\(\)\.getInputHandler\(\)\.requestFocus', 'document().focus().requestFocus', 'focus'),
    (r'getInputHandler\(\)\.requestPointerFocus', 'focus().requestPointerFocus', 'focus'),
    (r'getInputHandler\(\)\.requestFocus', 'focus().requestFocus', 'focus'),
    (r'getInputHandler\(\)\.getFocusedElement', 'focus().focused', 'focus'),
    (r'getInputHandler\(\)\.blurIfFocused', 'focus().blurIfFocused', 'focus'),
    (r'getInputHandler\(\)\.onDidChangeFocus', 'focus().onDidChangeFocus', 'focus'),
    (r'getInputHandler\(\)\.setPointerCapture', 'input().setPointerCapture', 'capture'),
    (r'getInputHandler\(\)\.releasePointerCapture', 'input().releasePointerCapture', 'capture'),
    (r'getInputHandler\(\)\.pointerPosition', 'input().pointer', 'pointer'),
    (r'getInputHandler\(\)\.currentCursor', 'input().currentCursor', 'cursor'),
    (r'getInputHandler\(\)\.clearHoverIfHovered', 'input().invalidateHover', 'hover'),
    (r'getInputHandler\(\)', 'input()', 'input receiver'),
    (r'getAttachedWindow\(\)', 'document()', 'document receiver'),

    # -- Coordinates and the layout rect ---------------------------------------------------------
    #
    # `screenToLocal` becomes `toLocal`, and the ORIGIN MOVES: the old one did not subtract the
    # element's own origin, so its answer was an absolute layout coordinate. Any call site that then
    # added the origin back has to LOSE that addition -- which is why these two land in the reading
    # list as well (see RESIDUAL), rather than being trusted as a pure rename.
    (r'\bscreenToLocal\(', 'toLocal(', 'coordinates'),
    (r'\bcontainsScreenPoint\(', 'containsSurfacePoint(', 'coordinates'),
    # contentBox* -> contentBox*, NOT content*. `Box.contentWidth()` is the extent of what is INSIDE
    # a box; `contentBoxWidth()` is the box minus border and padding. Mapping one to the other on the
    # strength of the name made TextField -- which has no child nodes, so its content extent is zero --
    # push a zero-width scissor and clip its own text away.
    (r'getTaffyLayout\(\)\.contentBoxWidth\(\)', 'box().contentBoxWidth()', 'geometry'),
    (r'getTaffyLayout\(\)\.contentBoxHeight\(\)', 'box().contentBoxHeight()', 'geometry'),
    (r'getTaffyLayout\(\)', 'box()', 'geometry'),

    # -- Geometry: the runtime cache becomes the box --------------------------------------------
    (r'getRuntimeCache\(\)\.getWidth\(\)', 'box().width()', 'geometry'),
    (r'getRuntimeCache\(\)\.getHeight\(\)', 'box().height()', 'geometry'),
    (r'getRuntimeCache\(\)\.getX\(\)', 'box().x()', 'geometry'),
    (r'getRuntimeCache\(\)\.getY\(\)', 'box().y()', 'geometry'),
    (r'getRuntimeCache\(\)\.localToWorld\(\)', 'box().localToWorld()', 'geometry'),
    (r'getWindowX\(\)', 'box().worldX()', 'geometry'),
    (r'getWindowY\(\)', 'box().worldY()', 'geometry'),

    # -- Tree ------------------------------------------------------------------------------------
    (r'\.addChild\(', '.append(', 'tree'),
    (r'\.removeChild\(', '.remove(', 'tree'),
    (r'\.clearAllChildren\(\)', '.removeAll()', 'tree'),
    (r'\.getChildren\(\)', '.children()', 'tree'),
    (r'\.getParent\(\)', '.parent()', 'tree'),
    # RECEIVER-BLIND, deliberately: it is a node rename and it is right far more often than
    # not, but `Setting.getId()` and anything else keeping the old spelling comes across
    # renamed. It fails the COMPILE, loudly, at the call -- which is the outcome to want from
    # a rule that cannot know its receiver's type. 6.2 hit it three times in one file.
    (r'\.getId\(\)', '.id()', 'identity'),
    (r'\.getClasses\(\)', '.classes()', 'identity'),

    # -- Tickers become owned hooks --------------------------------------------------------------
    # The FQN spelling too -- two widgets write `implements com.crystalgui.ui.UIFrameTicker` inline
    # rather than importing it, and the bare rule below turns that into a package that does not exist.
    (r'\bcom\.crystalgui\.ui\.UIFrameTicker\b', 'com.crystalgui.ui.service.Animation.Hook', 'ticker type'),
    # ORDER MATTERS, and the longer form has to go first. `implements UIFrameTicker, Disposable.Gl`
    # under the bare rule leaves `, Disposable.Gl` dangling after `extends UINode`, which is a parse
    # error -- the GOOD outcome, since it stops the build. The bad one is
    # `implements A, UIFrameTicker, B` becoming `implements A, B` with a stray comma somewhere in the
    # middle. Three rules: ticker-and-more keeps the clause and drops the ticker, ticker-alone drops
    # the whole clause, and a trailing ticker drops its own comma.
    (r'\bimplements UIFrameTicker\s*,\s*', 'implements ', 'ticker interface'),
    (r'\bimplements UIFrameTicker\b', '', 'ticker interface'),
    (r',\s*UIFrameTicker\b', '', 'ticker interface'),
    (r'\bUIFrameTicker\b', 'Animation.Hook', 'ticker type'),
    (r'(\w+)\.registerTicker\(this\)', r'document().animation().every(this, this::tickFrame)', 'ticker'),
    (r'registerTicker\(this\)', 'document().animation().every(this, this::tickFrame)', 'ticker'),

    # -- Drag ------------------------------------------------------------------------------------
    (r'DRAG_CONTROLLER\.startDrag\(', 'Drag.start(', 'drag'),
    # Drag is STATIC now, so the receiver the rule above leaves behind has to go: `window.Drag.start`
    # is a field access on a variable, and it compiles as nothing at all.
    (r'\b(?:window|doc|document\(\))\.Drag\.', 'Drag.', 'drag'),
    # hasMode takes an INSTANCE; a caller with only a class asks mode(Class) instead.
    (r'DRAG_CONTROLLER\.isDragging\(\)', 'input().mode(Drag.class) != null', 'drag'),
    (r'DRAG_CONTROLLER\.', 'Drag.', 'drag receiver'),

    # -- The top layer ---------------------------------------------------------------------------
    (r'(\w+)\.addToTopLayer\(\)', r'document().promote(\1)', 'top layer'),
    (r'addToTopLayer\(\)', 'document().promote(this)', 'top layer'),
    (r'(\w+)\.removeFromTopLayer\(\)', r'document().demote(\1)', 'top layer'),
    (r'removeFromTopLayer\(\)', 'document().demote(this)', 'top layer'),
    (r'(\w+)\.isInTopLayer\(\)', r'document().isPromoted(\1)', 'top layer'),
    (r'isInTopLayer\(\)', 'document().isPromoted(this)', 'top layer'),

    # -- Dismissal -------------------------------------------------------------------------------
    (r'document\(\)\.pushCloseWatcher\(', 'document().dismiss().pushCloseWatcher(', 'dismiss'),
    (r'document\(\)\.popCloseWatcher\(', 'document().dismiss().popCloseWatcher(', 'dismiss'),
    (r'document\(\)\.pushAutoPopover\(', 'document().dismiss().pushAutoPopover(', 'dismiss'),
    (r'document\(\)\.popAutoPopover\(', 'document().dismiss().popAutoPopover(', 'dismiss'),
    (r'document\(\)\.lightDismiss\(', 'document().dismiss().lightDismiss(', 'dismiss'),
    (r'document\(\)\.popoverShowSeq\(\)', 'document().dismiss().showSeq()', 'dismiss'),
    (r'document\(\)\.nextPopoverShowSeq\(\)', 'document().dismiss().nextShowSeq()', 'dismiss'),

    # -- Modality --------------------------------------------------------------------------------
    (r'document\(\)\.pushModal\(', 'document().focus().pushModal(', 'modality'),
    (r'document\(\)\.popModal\(', 'document().focus().popModal(', 'modality'),
    (r'document\(\)\.isModalBlocked\(', 'document().focus().isInert(', 'modality'),

    # -- Scroll ----------------------------------------------------------------------------------
    (r'\.setScrollImmediate\(', '.box().setScroll(', 'scroll'),
    (r'\.getMaxScrollTop\(\)', '.box().maxScrollTop()', 'scroll'),
    (r'\.getMaxScrollLeft\(\)', '.box().maxScrollLeft()', 'scroll'),
    (r'\.getClientWidth\(\)', '.box().clientWidth()', 'scroll'),
    (r'\.getClientHeight\(\)', '.box().clientHeight()', 'scroll'),
    (r'\.clampScroll\(\)', '.box().clampScroll()', 'scroll'),
    (r'\.scrollIntoView\(\)', '.box().scrollIntoView()', 'scroll'),
    (r'\.getScrollLeft\(\)', '.scrollLeft()', 'scroll'),
    (r'\.getScrollTop\(\)', '.scrollTop()', 'scroll'),

    # -- Encapsulation ---------------------------------------------------------------------------
    (r'^\s*markAsInternal\(\);\s*\n', '', 'internal flag'),
    (r'\.addInternalChild\(', '.SHADOW_APPEND(', 'internal child'),
    (r'\bIMPORTANT_PIPELINE\b', 'IMPORTANT_PIPELINE', 'noop'),
]

# Whole METHODS that have no counterpart and are deleted outright, body and all.
DELETED_METHODS = [
    (r'\n\s*@Override\s*\n\s*public boolean acceptsPublicChildren\(\)\s*\{[^}]*\}\n', 'acceptsPublicChildren'),
]

# What the codemod REFUSES to touch: each is a reading, and its site is reported instead.
RESIDUAL = [
    (r'importantPipeline|StyleOrigin\.IMPORTANT', 'IMPORTANT write -- section 4.5: a Measurable, a box call, an INLINE write or a class'),
    (r'screenToLocal|containsScreenPoint', 'coordinate conversion -- section 4.4: worldToLocal subtracts the box origin and screenToLocal did not'),
    (r'stopPropagation\(\)', 'stopPropagation -- DOM semantics now: is this "end the walk" or "pre-empt my own later listeners"?'),
    (r'insertInternalChildAt|removeInternalChild', 'dynamic restructure -- a shadow mutation (kind A) or a light one (kind B), per the ledger'),
    (r'onResizeModeChanged|onUserResize|resizeOrigin(Left|Top)|applyResizeOrigin|canMoveResizeOrigin|resizeContainingBlock|onPositionModeChanged|UIResizer',
     'resize hook -- D6: the resize mode over an edge band, no handle nodes'),
    (r'void onLayoutChanged', 'post-layout callback -- section 4.4: geometry feedback becomes a Measurable; placement and windowing stay'),
    (r'void paint(Self|Overlay|Outline|Children)', 'paint override -- paintContent/paintDecoration, re-based to the box origin'),
    (r'ctx\.mirroring|mirrored\(|WindowSnapshot', 'mirror -- BoxTree.mirror is a second box; the mirrored flag has no counterpart'),
    (r'SHADOW_APPEND', 'internal child -- shadow append + part= (kind A) or light append + class (kind B), per the ledger'),
    (r'\bsetGhost\b', 'drag ghost -- Drag.withGhost, per drag'),
    (r'getTaffyLayout|getTaffyTree|clearLayoutCache', 'layout internals -- read the box instead'),
    (r'\bmeasureFunc\b', 'measure -- implement Measurable'),
]


def ported_imports(batch=None):
    """
    `import com.crystalgui.ui.elements.X;` -> the package X was PORTED into.

    Derived from the ledger rather than listed, because the list grows with every batch and a
    hand-written one is a thing to forget an entry from. The failure when you do is not a compile
    error at the import -- it resolves perfectly to the OLD class -- it is a cascade of
    "cannot find symbol: method append(TextField)" at every CALL, because the old class is a
    UIElement and the new tree's methods are not on it. 6.2 hit it on InputDialog, whose `Popover`
    came across pointing at `ui.elements` while every other type in the file had moved.
    """
    out = []
    for line in io.open(LEDGER, encoding='utf-8'):
        f = line.rstrip(chr(10)).split(chr(9))
        if len(f) < 7 or f[0] != 'CLASS':
            continue
        # A MOVE re-homes the class for both engines the moment it happens, so it counts
        # whatever its status; a COPY only once the copy exists.
        # ...and a row in the batch BEING PORTED counts too, or the copies do not import each
        # other's new homes: InspectorForm imported ui.elements.config.ConfiguratorPanel, which
        # resolves perfectly to the OLD class, so every call on it failed as 'no suitable method'
        # rather than at the import. Cross-references WITHIN a batch are the commonest kind.
        in_batch = batch is not None and f[4] == batch
        if f[5] != 'move' and f[6] != 'ported' and not in_batch:
            continue
        old, dest = f[1], f[3]
        simple = old[old.rindex('/') + 1:]
        old_pkg = 'com.crystalgui.' + old[:old.rindex('/')].replace('/', '.')
        new_pkg = 'com.crystalgui.' + dest.replace('/', '.')
        if old_pkg == new_pkg:
            continue
        out.append((r'import ' + re.escape(old_pkg + '.' + simple) + r';',
                    'import ' + new_pkg + '.' + simple + ';',
                    'ported import'))
    return out


def ledger_rows(batch, only):
    rows = []
    for line in io.open(LEDGER, encoding='utf-8'):
        f = line.rstrip('\n').split('\t')
        if len(f) < 7 or f[0] != 'CLASS':
            continue
        path, _lines, dest, row_batch, how, status = f[1], f[2], f[3], f[4], f[5], f[6]
        if row_batch != batch or status == 'ported':
            continue
        if only and not path.endswith('/' + only):
            continue
        rows.append((path, dest, how))
    return rows


def transform(text, path='', batch=None):
    applied = {}
    # The ported-import rules go FIRST and are recomputed per run, because what is ported changes
    # between batches -- see ported_imports().
    for pattern, replacement, name in ported_imports(batch):
        text, n = re.subn(pattern, replacement, text, flags=re.M)
        if n:
            applied[name] = applied.get(name, 0) + n
    for pattern, replacement, name in RULES:
        # D15 SHIPPED at 6.1, so there is no rename here any more. The pair used to be
        # `UIText -> TextNode`, the interim spelling every consumer needed until the merge landed,
        # with a guard stopping it firing on UIText's own port. The merge went the other way round --
        # the merged class keeps UIText's name and its `text` tag -- so the rename is now a plain
        # import rewrite and the guard has nothing to guard. Left as a note because a batch ported
        # against the old rule comes out naming a class that no longer exists, which is what 6.2's
        # first run did: `private final TextNode titleLabel` in a tree with no TextNode in it.
        text, n = re.subn(pattern, replacement, text, flags=re.M)
        if n:
            applied[name] = applied.get(name, 0) + n
    for pattern, name in DELETED_METHODS:
        text, n = re.subn(pattern, '\n', text, flags=re.M)
        if n:
            applied[name] = applied.get(name, 0) + n
    return text, applied


def residual(path, text):
    out = []
    for i, line in enumerate(text.split('\n'), start=1):
        if line.lstrip().startswith('*') or line.lstrip().startswith('//'):
            continue  # a mention in prose is not a call site
        for pattern, why in RESIDUAL:
            if re.search(pattern, line):
                out.append((path, i, why, line.strip()[:100]))
                break
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--batch', required=True, help='which milestone, e.g. 6.1')
    ap.add_argument('--only', help='one class, by simple name')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    rows = ledger_rows(args.batch, args.only)
    if not rows:
        print('nothing pending for batch %s%s' % (args.batch, ' matching ' + args.only if args.only else ''))
        return 1

    total_applied = {}
    all_residual = []
    for path, dest, how in rows:
        src = os.path.join(CORE, path + '.java')
        if not os.path.isfile(src):
            print('  MISSING %s' % src)
            continue
        stem = path.rsplit('/', 1)[1]
        dst = os.path.join(CORE, dest.replace('/', os.sep), stem + '.java')
        text = io.open(src, encoding='utf-8').read()

        if how == 'move':
            # Engine-neutral: the IDE's Move refactor fixes both engines' imports for nothing, and a
            # copy would leave two of it. Reported, never done here.
            print('  MOVE (do this in the IDE) %-44s -> %s' % (path, dest))
            continue

        out, applied = transform(text, path, args.batch)
        out = re.sub(r'^package [\w.]+;', 'package com.crystalgui.' + dest.replace('/', '.') + ';', out, count=1, flags=re.M)
        for name, n in applied.items():
            total_applied[name] = total_applied.get(name, 0) + n
        all_residual.extend(residual(dest + '/' + stem + '.java', out))
        if args.dry_run:
            print('  COPY %-46s -> %s  (%d rewrites)' % (path, dest, sum(applied.values())))
        else:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            io.open(dst, 'w', encoding='utf-8', newline='\n').write(out)
            print('  COPY %-46s -> %s  (%d rewrites)' % (path, dest, sum(applied.values())))

    print('\n── mechanical, applied ' + '─' * 50)
    for name in sorted(total_applied, key=lambda k: -total_applied[k]):
        print('  %-24s %d' % (name, total_applied[name]))
    print('  %-24s %d' % ('TOTAL', sum(total_applied.values())))

    print('\n── the reading list: %d sites ' % len(all_residual) + '─' * 40)
    by_why = {}
    for path, line, why, snippet in all_residual:
        by_why.setdefault(why, []).append('%s:%d  %s' % (path, line, snippet))
    for why in sorted(by_why, key=lambda k: -len(by_why[k])):
        print('\n  %s  (%d)' % (why, len(by_why[why])))
        for site in by_why[why]:
            print('    ' + site)
    return 0


if __name__ == '__main__':
    sys.exit(main())
