"""Copy an old-engine test onto the new engine.

Runs `codemod.transform` (the same rules the main port used) and then the rules that are
specific to a TEST: the base class, the locally-built UIWindow that `UiDocumentTestBase`
already owns, and the local `frame()` helper it already provides.

USAGE  python tools/port/porttests.py <OldName>=<new.package> [...]

Everything mechanical is done here; what is left is listed per file as RESIDUE, because a
test that compiles is not a test that still asserts the same thing.
"""
import io, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import codemod

OLD_ROOT = 'core/src/test/java/com/crystalgui/ui'
NEW_ROOT = 'core/src/test/java/com/crystalgui'

# Applied AFTER codemod, so they see the already-renamed types.
TEST_RULES = [
    (r'import com\.crystalgui\.testsupport\.UiTestBase;',
     'import com.crystalgui.testsupport.UiDocumentTestBase;', 'base import'),
    (r'\bextends UiTestBase\b', 'extends UiDocumentTestBase', 'base class'),
    # `hasChild` has no counterpart: the node tree exposes the list.
    (r'\.hasChild\(', '.children().contains(', 'tree'),
    # The base owns the document; a locally-built one is a second, unpainted tree.
    (r'import com\.crystalgui\.ui\.Ui;\n', '', 'Ui holder'),
    # -- The MANUAL FRAME, collapsed ------------------------------------------------------------
    #
    # An old test drove the pipeline by hand because `UIWindow` exposed each stage separately, and
    # every one of them spelled it slightly differently. `UiDocumentTestBase.frame()` is the whole
    # sequence, in the order `UIDocument.frame` runs it -- so a collapsed call is not a shorthand,
    # it is the only spelling that still gets the ORDER right. Longest first: a file that drove
    # style+layout+input would otherwise have its input pair collapsed and the rest left standing.
    (r'[ \t]*\w+\.getStyleEngine\(\)\.calculateStyle\([^)]*\);\r*\n'
     r'[ \t]*\w+\.calculateLayout\(\);\r*\n'
     r'[ \t]*\w+\.input\(\)\.beginFrame\(\);\r*\n'
     r'[ \t]*\w+\.input\(\)\.endFrame\(\);',
     '        frame();', 'manual frame'),
    (r'[ \t]*\w+\.getStyleEngine\(\)\.calculateStyle\([^)]*\);\r*\n'
     r'[ \t]*\w+\.calculateLayout\(\);',
     '        frame();', 'manual style+layout'),
    (r'[ \t]*\w+\.input\(\)\.beginFrame\(\);\r*\n'
     r'[ \t]*\w+\.input\(\)\.endFrame\(\);',
     '        frame();', 'hover diff'),
    # KEEP THE DELTA. Collapsing `tickAnimations(0.6f)` to a bare `frame()` advances 16ms instead
    # of 600, so anything on a clock -- a caret blink, a tooltip delay, a transition, a repeat --
    # never fires, and the failure reads as the feature being broken rather than the clock never
    # having moved. Four TextField blink tests found this the expensive way.
    (r'\w+\.tickAnimations\(([^)]*)\);', r'frame(\1);', 'ticker'),
    (r'\w+\.updateWithoutPainting\(\);', 'frame();', 'headless frame'),

    # `getHoveredElement` answered a node; `boxes().hitTest` answers a BOX, and the base's `hit()`
    # does the hop. Safe because in a test the alias and `this.document` are the same tree.
    (r'\w+\.getHoveredElement\(', 'hit(', 'hit test'),
    (r'\.getStyleEngine\(\)', '.styleEngine()', 'style engine'),
    (r'\bgetRootElement\(\)', 'root()', 'root'),
    # -- What the base already owns --------------------------------------------------------------
    (r'\w+\.setUiScale\(', 'document.boxes().setUiScale(', 'ui scale'),
    (r'\n[ \t]*\w+\.init\(\s*\d+\s*,\s*\d+\s*\);', '', 'window init'),
    (r'new UIDocument\(Ui\.of\((\w+)\)\)', r'\1', 'Ui holder'),
    (r'Ui\.of\((\w+)\)', r'\1', 'Ui holder'),
    # A bare `getRuntimeCache()` the paired rules above did not reach. The cache WAS the geometry;
    # the box is, and it is NULLABLE -- so a chain off this is a site to read, not to trust.
    (r'\.getRuntimeCache\(\)', '.box()', 'geometry'),
    # The TYPE, which a helper's return type names. `RuntimeCache` was five memo cells hanging off
    # the element; `Box` is the geometry itself, in its own tree.
    (r'\bUINode\.RuntimeCache\b', 'Box', 'geometry'),
    # The cells themselves are gone with it: `localToWorld` was a CacheCell you called `.get()` on,
    # and is now the box's own composed matrix.
    (r'\.localToWorld\.get\(\)', '.localToWorld()', 'geometry'),
    (r'\.worldToLocal\.get\(\)', '.worldToLocal()', 'geometry'),
    # -- Geometry off the box --------------------------------------------------------------------
    #
    # `getRuntimeCache()` became `box()` above, which leaves the ACCESSORS in the old spelling. And
    # the origin moved with them: `Box.x()` is parent-relative where `getRuntimeCache().getX()` was
    # absolute, so any site that subtracted two of these is comparing two different spaces now.
    (r'\.box\(\)\.getX\(\)', '.box().x()', 'geometry'),
    (r'\.box\(\)\.getY\(\)', '.box().y()', 'geometry'),
    (r'\.box\(\)\.getWidth\(\)', '.box().width()', 'geometry'),
    (r'\.box\(\)\.getHeight\(\)', '.box().height()', 'geometry'),

    # -- Scrolling -------------------------------------------------------------------------------
    #
    # The offsets live on the NODE (they survive a freeze, which is what makes a hidden window come
    # back where it was); the EXTENTS live on the box, because they are what layout settled on --
    # except where the node overrides `scrollExtent`, which is what makes a virtualised list's thumb
    # the size of the model rather than of the dozen rows on screen.
    (r'(\w+)\.setScrollTop\(([^;]*?)\);', r'\1.scrollTo(\1.scrollLeft(), \2);', 'scroll'),
    (r'(\w+)\.setScrollLeft\(([^;]*?)\);', r'\1.scrollTo(\2, \1.scrollTop());', 'scroll'),
    (r'\.getScrollTop\(\)', '.scrollTop()', 'scroll'),
    (r'\.getScrollLeft\(\)', '.scrollLeft()', 'scroll'),
    (r'\.getScrollHeight\(\)', '.box().scrollHeight()', 'scroll'),
    (r'\.getScrollWidth\(\)', '.box().scrollWidth()', 'scroll'),
    (r'\.getMaxScrollTop\(\)', '.box().maxScrollTop()', 'scroll'),
    (r'\.getMaxScrollLeft\(\)', '.box().maxScrollLeft()', 'scroll'),
    (r'\.getClientWidth\(\)', '.box().clientWidth()', 'scroll'),
    (r'\.getClientHeight\(\)', '.box().clientHeight()', 'scroll'),
    # A `Box`-typed local reached through `boxOf(...)` or `box()` keeps the old accessor names, and
    # they are unqualified so the paired rules above cannot see them. Safe to do bluntly in a TEST:
    # nothing else in one has a `getX`. NOT safe in main sources, which is why it lives here.
    (r'\.getX\(\)', '.x()', 'geometry'),
    (r'\.getY\(\)', '.y()', 'geometry'),
    (r'\.getWidth\(\)', '.width()', 'geometry'),
    (r'\.getHeight\(\)', '.height()', 'geometry'),
    # -- Widget parts: a `__class__` became a `part=` name --------------------------------------
    #
    # The old engine gave every internal child a `__double-underscore__` CLASS a theme targeted;
    # this one gives it a PART, which is what `::part(name)` addresses. So the widgets renamed
    # `X_CLASS` to `X_PART` and the value lost its underscores.
    (r'\.isInternalUI\(\)', '.get(Attribute.PART).isEmpty() == false', 'part test'),
    (r'\bgetTopLayer\(\)', 'topLayerNode()', 'top layer'),
    (r'\.sendInputEvent\(', '.send(', 'dispatch'),
    (r'\bgetUiScale\(\)', 'boxes().uiScale()', 'ui scale'),
    # -- The registry: keyed by a namespaced Name now, not a bare tag string --------------------
    (r'ElementRegistry\.bootstrapBuiltins\(\)', 'UINodeRegistry.bootstrap()', 'registry'),
    (r'ElementRegistry\.create\((\w+)\)', r'UINodeRegistry.create(Name.parse(\1))', 'registry'),
    (r'ElementRegistry\.isRegistered\((\w+)\)', r'UINodeRegistry.isRegistered(Name.parse(\1))', 'registry'),
    (r'ElementRegistry\.tags\(\)', 'UINodeRegistry.names()', 'registry'),
    (r'\bElementRegistry\b', 'UINodeRegistry', 'registry'),
    # -- transform: a CASCADE property here, not a field on the element ------------------------
    #
    # `Box.setTransform` exists too and is a different channel -- the COMPOSITOR override the
    # window animations write. A test about CSS `transform` wants the computed style.
    (r'(\w+)\.setTransform\(([^;]*?)\);', r'\1.generalStyle(g -> g.transform(\2));', 'transform'),
    (r'\.getTransform\(\)', '.computedStyle().get(StylePropertyRegistry.TRANSFORM)', 'transform'),
    # -- Focus moved to its own service, so these are QUESTIONS ASKED OF IT ---------------------
    (r'(\w+)\.tabbable\(\)', r'document.focus().tabbable(\1)', 'focus'),
    (r'(\w+)\.focusable\(\)', r'document.focus().focusable(\1)', 'focus'),
    (r'\w+\.getFocusedElement\(\)', 'document.focus().focused()', 'focus'),
    (r'\w+\.requestFocus\(', 'document.focus().requestFocus(', 'focus'),
    (r'(\w+)\.isMouseOverElement\(', r'\1.containsSurfacePoint(', 'hit test'),
    # `AnchoredPlacement` moved into the service layer; an inline FQN keeps the old package.
    (r'com\.crystalgui\.ui\.AnchoredPlacement', 'com.crystalgui.ui.service.AnchoredPlacement', 'import'),
    # The resizer is engine-supplied now and lives with the other drag machinery.
    (r'\bUIResizer\b', 'Resizer', 'resize'),
    # `dotCenter()` answered in the plane's space implicitly; `dotCenterIn(space)` says WHICH,
    # because `Box.x()` is parent-relative and a subtraction of two boxes is no longer a
    # conversion. A test asking for a wire endpoint means the plane.
    (r'\.dotCenter\(\)', '.dotCenterIn(null)', 'geometry'),
    # `@Test(timeout = N)` runs only the method BODY on a fresh thread, while `@Before` -- which
    # builds the document and marks its frame-thread owner -- runs on the runner. So every
    # mutation in the body is refused: "must happen on the thread that runs frames (Test
    # worker), not on Time-limited test". The tree genuinely has no safe concurrent reader, so
    # the guard is right and the annotation is what has to go; a hang is still caught by the
    # build. AGENTS.md records the same interaction from the other direction.
    (r'@Test\(\s*timeout\s*=[^)]*\)', '@Test', 'frame thread'),
    # `Ui` was a trivial {rootElement} holder on the window; the document IS the root here.
    (r'\w+\.ui\.rootElement', 'document', 'root'),
    # THE COMPOSITOR NAMES THE DOCUMENT, not the other way round: the engine may not name a
    # compositor, so `UIWindow.desktop()` became `Desktop.of(document)`.
    (r'\w+\.desktop\(\)', 'Desktop.of(document)', 'desktop'),
    (r'\w+\.getRootTransform\(\)', 'document.boxes().rootTransform()', 'geometry'),
    #  collapsed to , and an INLINE spelling kept the old
    # qualifier -- , which is the node package, not the box one.
    (r'com\.crystalgui\.ui\.dom\.Box' + chr(92) + 'b', 'Box', 'geometry'),
    # -- The compositor ---------------------------------------------------------------------------
    #
    # The engine may not name a compositor, so `UIWindow.openWindow`/`desktop()`/`suspendDesktop`
    # all became calls on the Desktop, which names the DOCUMENT.
    (r'(\w+)\.openWindowInBackground\(([^;]*)\)', r'Desktop.of(document).addWindow(\2, false)', 'desktop'),
    (r'\w+\.openWindow\(', 'Desktop.of(document).addWindow(', 'desktop'),
    (r'\w+\.suspendDesktop\(\)', 'Desktop.of(document).suspend()', 'desktop'),
    (r'\w+\.resumeDesktop\(\)', 'Desktop.of(document).resume()', 'desktop'),
    (r'\w+\.isDesktopSuspended\(\)', 'Desktop.of(document).isSuspended()', 'desktop'),
    # Modality is ONE predicate the focus service owns, asked of the node.
    (r'\w+\.isModalBlocked\(([\w.()]+)\)', r'document.focus().isInert(\1)', 'modality'),
    # A node's position among its siblings is the parent's answer, not the node's.
    (r'(\w+)\.getSiblingIndex\(\)', r'\1.parent().indexOf(\1)', 'tree'),
    # -- A LIGHT-TREE QUERY FOR WHAT IS NOW A SHADOW PART ------------------------------------------
    #
    # These tests were written when every internal child was an ordinary light child carrying a
    # `__class__`, so `querySelector(".x")` found it. The same node is a shadow PART now and the
    # light-tree query answers nothing -- which reads as the widget not having been built rather
    # than as the query not reaching it. `deep`/`deepAll` are the test-side traversal that crosses
    # the boundary, which a test is the one caller with a reason to do.
    (r'(\w+)\.querySelectorAll\(', r'deepAll(\1, ', 'shadow query'),
    (r'(\w+)\.querySelector\(', r'deepOrNull(\1, ', 'shadow query'),
    (r'(\w+)\.getElementsByClassName\(', r'deepAll(\1, ', 'shadow query'),
    # -- The HOST SEAM moved off the engine and onto the compositor --------------------------------
    (r'\w+\.enterHudMode\(\)', 'Desktop.of(document).enterHudMode()', 'desktop'),
    (r'\w+\.exitHudMode\(\)', 'Desktop.of(document).exitHudMode()', 'desktop'),
    (r'\w+\.isHudMode\(\)', 'Desktop.of(document).isHudMode()', 'desktop'),
    (r'\w+\.presentation\(', 'Desktop.of(document).presentation(', 'desktop'),
    (r'\w+\.overlayHitTest\(', 'Desktop.of(document).screenOverlay().overlayHitTest(', 'desktop'),
    # A live gesture is an `InputMode` PUSHED ON A STACK now, so the window routes nothing: the key
    # goes through the ordinary path and whichever mode is on top decides. There is deliberately no
    # `routeKeyTo*` to map onto.
    (r'\w+\.routeKeyToWindowSwitcher\(([^)]*)\)', r'keyPress(\1)', 'input mode'),
    (r'\w+\.routeKeyToKeyboardMove\(([^,]*),\s*[^)]*\)', r'keyPress(\1)', 'input mode'),
    # A node's position among its siblings is the PARENT's answer; the chained receiver form too.
    (r'([\w.()]+)\.getSiblingIndex\(\)', r'\1.parent().indexOf(\1)', 'tree'),
    (r'(?<![.\w])addInternalChild\(', 'appendStructural(', 'structure'),
    # The interval moved beside its sibling in `ButtonState`, which is the class that decides
    # whether a press continues a multi-click run and owned the other half of the answer already.
    (r'\w*\.?\bmultiClickInterval\b', 'ButtonState.MULTI_CLICK_INTERVAL_MS', 'input'),
    # Modality is the focus service's; the TOPMOST modal is the last one pushed.
    (r'\w+\.getActiveModal\(\)',
     'document.focus().modals().isEmpty() ? null'
     ' : document.focus().modals().get(document.focus().modals().size() - 1)', 'modality'),
    # `init(w, h)` is the base's job, whatever the arguments look like.
    (r'[ \t]*\w+\.init\([^;]*\);[^\r\n]*\r*\n', '', 'window init'),
    (r'\w+\.topLayerNode\(\)\.add\((\w+)\)', r'document.promote(\1)', 'top layer'),
    (r'(?<![.\w])document\(\)(?!\s*\{)', 'document', 'document'),
    # Inertness is ONE predicate the focus service owns, asked ABOUT a node rather than of it --
    # which is what lets the modal half change for nearly every node at once without invalidating
    # anything cached on them.
    (r'([\w.()]+)\.isInert\(\)', r'document.focus().isInert(\1)', 'inertness'),
    (r'(?<![.\w])isInert\(\)', 'document.focus().isInert(this)', 'inertness'),
    # Drag is an InputMode on the stack, not a controller hanging off the input handler.
    (r'\w+\.getDragController\(\)\.isDragging\(\)', 'document.input().mode(Drag.class) != null', 'drag'),
    (r'\w+\.getDragController\(\)', 'document.input()', 'drag'),
    (r'\w+\.topLayerNode\(\)\.elements\(\)', 'java.util.List.copyOf(document.promotedNodes())', 'top layer'),
    (r'\w+\.topLayerNode\(\)\.isEmpty\(\)', 'document.promotedNodes().isEmpty()', 'top layer'),
    # Light dismiss and the close-watcher cascade are the Dismiss service's -- two SEPARATE stacks,
    # because the same element is routinely in one and not the other (a modal has a close watcher and
    # is not light-dismissable; a MANUAL popover is in neither).
    (r'\w+\.getAutoPopovers\(\)', 'document.dismiss().autoPopovers()', 'dismiss'),
    (r'\w+\.getCloseWatchers\(\)', 'document.dismiss().closeWatchers()', 'dismiss'),
    (r'\w+\.getTopCloseWatcher\(\)', 'document.dismiss().topCloseWatcher(null)', 'dismiss'),
    (r'\w+\.lightDismiss\(', 'document.dismiss().lightDismiss(', 'dismiss'),
    # A ticker is OWNED now -- dropped when its owner disconnects, dormant while frozen.
    (r'(\w+)\.registerTicker\(', r'document.animation().every(\1, ', 'ticker'),
    # The old `ui` field on a test fixture was the window.
    # NOT inside a string literal or a resource path: `crystalgui:ui/fonts/...` became
    # `crystalgui:document/fonts/...`, the font failed to load, text measured zero, and every
    # assertion downstream saw a null box -- reading as a widget that was never laid out.
    (r'(?<![.\w:/"])ui(?![\w(/"])', 'document', 'window'),
    # The 962-line handler became four services; `Input` is the one a test names.
    (r'\bUIInputHandler\b', 'Input', 'input service'),
    (r'import com\.crystalgui\.ui\.input\.Input;', 'import com.crystalgui.ui.service.Input;', 'input import'),
]


# A test that lived IN `com.crystalgui.ui` needed no import for the engine's own types. Ported out
# of that package it needs one for every type it names, and javac reports each USE rather than the
# missing import -- 13 "cannot find symbol" in the first batch of six, all of them this. Keyed on
# the type being named and no import already present, so re-running the port is idempotent.
NEEDED_IMPORTS = [
    ('UINode', 'com.crystalgui.ui.dom.UINode'),
    ('UIDocument', 'com.crystalgui.ui.dom.UIDocument'),
    ('UISlot', 'com.crystalgui.ui.dom.UISlot'),
    ('ShadowRoot', 'com.crystalgui.ui.dom.ShadowRoot'),
    ('Attribute', 'com.crystalgui.ui.dom.Attribute'),
    ('Name', 'com.crystalgui.ui.dom.Name'),
    ('Box', 'com.crystalgui.ui.box.Box'),
    ('Drag', 'com.crystalgui.ui.service.Drag'),
    ('Input', 'com.crystalgui.ui.service.Input'),
    # Shared between the two engines -- the transform is a value type, not an engine type.
    ('UITransform', 'com.crystalgui.ui.UITransform'),
    ('UINodeRegistry', 'com.crystalgui.ui.dom.UINodeRegistry'),
    ('StylePropertyRegistry', 'com.crystalgui.style.property.StylePropertyRegistry'),
    ('Desktop', 'com.crystalgui.desktop.Desktop'),
    ('ButtonState', 'com.crystalgui.ui.input.ButtonState'),
    # A TEST base, which the index does not see -- it scans main sources only.
    ('EditorTestBase', 'com.crystalgui.widget.texteditor.EditorTestBase'),
]


def ensure_imports(text):
    """Adds an import for every engine type the file names and does not already import."""
    additions = []
    for simple, fqn in NEEDED_IMPORTS:
        if re.search(r'\b' + simple + r'\b', text) and ('import %s;' % fqn) not in text:
            additions.append('import %s;' % fqn)
    if not additions:
        return text
    match = re.search(r'^import [\w.]+;', text, re.M)
    if not match:
        return text
    joined = chr(10).join(sorted(additions)) + chr(10)
    return text[:match.start()] + joined + text[match.start():]

def drop_local_frame(text):
    """Removes a test's own `frame()`/`layoutOnly()` helper, javadoc and all.

    Every old test hand-rolled one, because `UIWindow` exposed each stage separately -- and each
    hand-rolled version drove them in a slightly different order. `UiDocumentTestBase.frame()` runs
    the order `UIDocument.frame` runs, so deleting the local one is not tidying: it is the only way
    the ported test still asserts against the pipeline the engine actually has. It also has to go
    because the base declares it FINAL, so a survivor is a compile error rather than a shadow.

    Brace-matched rather than regexed, since these bodies contain braces of their own.
    """
    out = text
    for name in ('frame', 'layoutOnly'):
        pattern = re.compile(r'\n([ \t]*)(?:private|protected|public)[\w ]*? void ' + name + r'\(\)\s*\{')
        while True:
            match = pattern.search(out)
            if not match:
                break
            depth, i = 0, match.end() - 1
            while i < len(out):
                if out[i] == '{':
                    depth += 1
                elif out[i] == '}':
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            head = out[:match.start()]
            # Take the javadoc immediately above it too, or the file keeps a comment for a method
            # that is gone -- which reads as the helper having been lost rather than replaced.
            doc = re.search(r'\n[ \t]*/\*\*(?:[^*]|\*(?!/))*\*/\s*$', head)
            if doc:
                head = head[:doc.start()]
            out = head + out[i + 1:]
    return out


def alias_window(text):
    """Runs the rewrite below until no window declaration is left -- a file may have several.

    Then handles the INHERITED case: a subclass of a ported base uses the base's field by name, and
    the tool works one file at a time, so it never sees the declaration that was renamed. Twenty-seven
    uses across the editor tests were exactly this. Guarded on the file declaring no `window` of its
    own, since then a bare `window` can only be the base's.
    """
    while True:
        out = _alias_one_window(text)
        if out == text:
            break
        text = out
    if re.search(r'(?m)^[ 	]*(?:private |protected |public )?(?:final |static )*\w+ window', text):
        return text
    if not re.search(r'(?<![.\w])window(?![\w(])', text):
        return text
    text = re.sub(r'(?<![.\w])window\.', 'document.', text)
    return re.sub(r'(?<![.\w])window(?![\w(])', 'document', text)


def _alias_one_window(text):
    """Points a test's own `UIWindow` field at the base's document instead of deleting it.

    An old test declared its own window, built a root under it and then named that field a few
    dozen times. Deleting the declaration leaves every one of those dangling, and deleting the
    ASSIGNMENT loses the append that put the root in a tree at all -- so the test compiles into
    something that runs against an empty document and fails for the wrong reason.

    The field's name is whatever the test chose, so the rewrite is generated per file: drop the
    declaration, turn its assignment into the append the base's document needs, and rename every
    remaining use. Prose is left alone -- only a use that is a real identifier is renamed.
    """
    match = re.search(r'\n[ \t]*(?:private |protected )?(?:final )?UIDocument (\w+);', text)
    if match is None:
        # A LOCAL declaration, which is what `UIDocument w = new UIWindow(Ui.of(root));` collapses
        # to once the Ui-holder rule has run. Same treatment: the declaration goes, its initialiser
        # becomes the append that puts the root in a tree, and the name becomes `document`.
        local = re.search(r'\n([ \t]*)(?:final )?UIDocument (\w+) = (\w+);[ \t]*\r*', text)
        if local is None or local.group(2) == 'document':
            return text
        name = local.group(2)
        text = (text[:local.start()] + '\n' + local.group(1)
                + 'document.append(' + local.group(3) + ');' + text[local.end():])
        text = re.sub(r'(?<![.\w])' + name + r'\.', 'document.', text)
        text = re.sub(r'(?<![.\w])' + name + r'(?![\w(])', 'document', text)
        return text
    name = match.group(1)
    if name == 'document':
        return text
    text = text[:match.start()] + text[match.end():]
    # `window = root;` is what `new UIWindow(Ui.of(root))` collapsed to, and it is the ONE line that
    # still has to do something: the root has to join a tree.
    # `\r*$` is load-bearing: these files are CRLF, and in MULTILINE mode `$` matches before the
    # `\n` with the `\r` still in the way -- so a `$`-anchored rule silently matches nothing at all
    # and the rename below then turns the assignment into `document = root;`, which does not compile.
    text = re.sub(r'(?m)^([ \t]*)' + name + r' = (\w+);[ \t]*(?://[^\r\n]*)?\r*$',
                  r'\1document.append(\2);', text)
    text = re.sub(r'(?<![.\w])' + name + r'\.', 'document.', text)
    text = re.sub(r'(?<![.\w])' + name + r'(?![\w(])', 'document', text)
    return text


def _part_constants():
    """Every `X_PART` constant the new widgets declare, as the set of X."""
    names = set()
    for base, _, files in os.walk('core/src/main/java/com/crystalgui'):
        for f in files:
            if not f.endswith('.java'):
                continue
            text = io.open(os.path.join(base, f), encoding='utf-8', errors='ignore').read()
            for m in re.finditer(r'\b([A-Z][A-Z0-9_]*)_PART\b', text):
                names.add(m.group(1))
    return names


_PARTS = None


def rename_part_constants(text):
    """`X_CLASS` -> `X_PART`, but ONLY where the new engine declares an `X_PART`.

    Both kinds survive the port and they are not interchangeable: an internal child that a theme
    addressed by `__class__` became a `part=` name, while a STATE class (`__selected__`) is still a
    class, because a part is a leaf a rule addresses by name and a state is something the widget
    flips. A blanket rename turned `HEADER_CLASS` and `SELECTED_CLASS` into constants that do not
    exist -- so the list is read off the widgets rather than guessed.
    """
    global _PARTS
    if _PARTS is None:
        _PARTS = _part_constants()
    def swap(m):
        return m.group(1) + '_PART' if m.group(1) in _PARTS else m.group(0)
    return re.sub(r'\b([A-Z][A-Z0-9_]*)_CLASS\b', swap, text)


def ensure_base(text):
    """Gives a test class the document base when it declares none.

    Not every old test extended `UiTestBase` -- several installed the platform themselves, because
    they wanted a custom `CgInputService` (a modifier state, a held mouse button). Those classes then
    have no `document` and no `frame()`, and javac reports one error per USE rather than one for the
    missing base: 19 "cannot find symbol: variable document" in one batch, all of them this.

    JUnit runs a superclass @Before before the subclass's, so a test that installs its own platform
    service still wins -- adding the base takes nothing away from it.
    """
    if 'extends' in text.split('public class ')[-1].split('{')[0]:
        return text
    if 'UIDocument' not in text and 'UINode' not in text:
        return text
    out = re.sub(r'public class (\w+Test) \{',
                 r'public class \1 extends UiDocumentTestBase {', text, count=1)
    if out != text and 'import com.crystalgui.testsupport.UiDocumentTestBase;' not in out:
        match = re.search(r'^import [\w.]+;', out, re.M)
        if match:
            out = (out[:match.start()]
                   + 'import com.crystalgui.testsupport.UiDocumentTestBase;' + chr(10)
                   + out[match.start():])
    return out


_INDEX = None


def _class_index():
    """Simple name -> FQN for every class in main sources, dropping ambiguous names.

    An ambiguous one is left out on purpose: guessing between two packages writes an import that
    compiles against the wrong class, which is worse than the missing-import error it replaces.
    """
    index = {}
    for root in ('core/src/main/java',):
        for base, _, files in os.walk(root):
            for f in files:
                if not f.endswith('.java') or f == 'package-info.java':
                    continue
                simple = f[:-5]
                # PUBLIC ONLY. A package-private type is unreachable from a test in another package,
                # so importing it swaps a "cannot find symbol" for a "is not public" -- no better.
                body = io.open(os.path.join(base, f), encoding='utf-8', errors='ignore').read()
                if not re.search(r'(?m)^public (?:final |abstract |sealed )*'
                                 r'(?:class|interface|enum|record) ' + simple + r'\b', body):
                    continue
                package = os.path.relpath(base, root).replace(os.sep, '.')
                index.setdefault(simple, set()).add(package + '.' + simple)
    # DURING A STRANGLER PORT NEARLY EVERY NAME IS AMBIGUOUS -- both copies exist, which is the whole
    # point of the strangler. So drop the OLD engine's packages first and see whether one candidate is
    # left; only a name that is still ambiguous among NEW packages is genuinely unresolvable.
    OLD = ('com.crystalgui.ui.elements', 'com.crystalgui.graph.', 'com.crystalgui.ui.UIElement')
    resolved = {}
    for simple, candidates in index.items():
        fresh = {c for c in candidates if not any(c.startswith(o) for o in OLD)}
        pick = fresh if fresh else candidates
        if len(pick) == 1:
            resolved[simple] = next(iter(pick))
    return resolved


def resolve_imports(text, package):
    """Imports every main-source class the file NAMES and does not already have.

    The batches move classes into sub-packages -- `MainPreviewPanel` into `.preview`,
    `ShaderPropertyNodes` into `.node` -- and a test that used to sit beside them needs an import for
    each. javac reports one error per USE, so a handful of moved classes reads as a wall.
    """
    global _INDEX
    if _INDEX is None:
        _INDEX = _class_index()
    additions = []
    for simple, fqn in _INDEX.items():
        if fqn.rsplit('.', 1)[0] == package:
            continue                      # same package, no import needed
        if ('import %s;' % fqn) in text:
            continue
        if re.search(r'(?<![\w.])' + simple + r'(?![\w])', text) and                 not re.search(r'import [\w.]*\.' + simple + ';', text):
            additions.append('import %s;' % fqn)
    if not additions:
        return text
    match = re.search(r'^import [\w.]+;', text, re.M)
    if not match:
        return text
    return text[:match.start()] + chr(10).join(sorted(set(additions))) + chr(10) + text[match.start():]


def retarget_old_fqns(text):
    """Points an INLINE fully-qualified name at the new engine's package.

    An import rule cannot see one, and a blanket `ui.elements.` -> `widget.` is wrong because the
    widgets went into sub-packages -- `MenuItem` is in `widget.overlay`, not `widget`. The index
    already knows where each one landed, so the simple name is what decides.
    """
    global _INDEX
    if _INDEX is None:
        _INDEX = _class_index()
    def swap(m):
        target = _INDEX.get(m.group(1))
        return target if target else m.group(0)
    return re.sub(r'com\.crystalgui\.(?:ui\.elements|graph)(?:\.[a-z]\w*)*\.([A-Z]\w+)', swap, text)


def rename_clashing_document(text):
    """Renames a test's OWN `document` when it is not the UI one.

    `UiDocumentTestBase` owns a field called `document`, and the port points every window reference
    at it -- so a test that already had a `document` of its own (a `GraphDocument`, a
    `TextFileDocument`) ends up with two things by one name. javac reports it at the USE, as a method
    missing from the wrong type, which reads as the API having changed rather than as a clash.
    """
    match = re.search(r'(?m)^[ 	]*(?:private |protected |public )?(?:final |static )*'
                      r'(\w*Document)\s+document\b', text)
    if not match or match.group(1) == 'UIDocument':
        return text
    fresh = match.group(1)[0].lower() + match.group(1)[1:]      # GraphDocument -> graphDocument
    text = re.sub(r'(?<![.\w])document(?![\w(])', '@@DOC@@', text)   # every bare `document`
    # Put the BASE's field back wherever it is used as one: those are the sites the port introduced.
    text = text.replace('@@DOC@@.append(', 'document.append(')                .replace('@@DOC@@.styleEngine(', 'document.styleEngine(')                .replace('@@DOC@@.boxes(', 'document.boxes(')                .replace('@@DOC@@.input(', 'document.input(')                .replace('@@DOC@@.focus(', 'document.focus(')                .replace('@@DOC@@.dismiss(', 'document.dismiss(')                .replace('@@DOC@@.promote(', 'document.promote(')                .replace('@@DOC@@.demote(', 'document.demote(')                .replace('@@DOC@@.isPromoted(', 'document.isPromoted(')                .replace('@@DOC@@.promotedNodes(', 'document.promotedNodes(')                .replace('@@DOC@@.removeAll(', 'document.removeAll(')                .replace('@@DOC@@.animation(', 'document.animation(')                .replace('@@DOC@@.lifecycle(', 'document.lifecycle(')
    return text.replace('@@DOC@@', fresh)


def port(old_name, new_package):
    # A bare name is a test in `ui/`, which is where most of them are. A name with a `/` is a path
    # relative to `com/crystalgui/` -- the style, graph and workbench tests live outside `ui/`.
    if '/' in old_name:
        src = os.path.join(NEW_ROOT, old_name + '.java')
        old_name = old_name.rsplit('/', 1)[1]
    else:
        src = os.path.join(OLD_ROOT, old_name + '.java')
    if not os.path.isfile(src):
        return old_name, ['NOT FOUND: ' + src]
    text = io.open(src, encoding='utf-8', newline='').read()
    text, _ = codemod.transform(text, path=src, batch=None)

    applied = []
    for pattern, repl, label in TEST_RULES:
        text, n = re.subn(pattern, repl, text)
        if n:
            applied.append('%s x%d' % (label, n))

    text = re.sub(r'^package [\w.]+;', 'package %s;' % new_package, text, count=1, flags=re.M)
    text = rename_part_constants(text)
    text = ensure_base(text)
    text = ensure_imports(text)
    text = retarget_old_fqns(text)
    text = resolve_imports(text, new_package)
    text = rename_clashing_document(text)
    text = drop_local_frame(text)
    text = alias_window(text)

    residue = []
    for probe, why in [
        (r'\bwindow\b', 'names `window` — should be `document`'),
        (r'\bUIDocument\b', 'still builds a UIDocument'),
        (r'private void frame\(\)', 'has its own frame() — the base provides one'),
        (r'consumeMouseEvent|consumeKeyboardEvent', 'drives input directly'),
        (r'markAsInternal|addInternalChild', 'internal children — now a shadow tree'),
        (r'getStyleEngine\(\)', 'reaches the style engine'),
        (r'\.x\(\)\s*[+)]|\.y\(\)\s*[+)]',
         'builds a POINT from box().x()/y() -- parent-relative here, absolute on the old engine.'
         ' Use worldX()/worldY(), or centreOf(node) on the base'),
        (r'\*\s*2f|\*\s*uiScale|UI_SCALE',
         'scales a coordinate by hand -- the old UIWindow defaulted to uiScale 2 and'
         ' UiDocumentTestBase sets none, so the fixture must say what it wants'),
        (r'\.__[a-z-]+__',
         'a __class__ selector -- if that part now lives in a shadow tree, only ::part(name) reaches it'),
    ]:
        if re.search(probe, text):
            residue.append(why)

    dest_dir = os.path.join(NEW_ROOT, new_package.split('com.crystalgui.')[1].replace('.', os.sep))
    if not os.path.isdir(dest_dir):
        os.makedirs(dest_dir)
    io.open(os.path.join(dest_dir, old_name + '.java'), 'w', encoding='utf-8', newline='').write(text)
    return old_name, residue


if __name__ == '__main__':
    for arg in sys.argv[1:]:
        name, package = arg.split('=')
        n, residue = port(name, package)
        print('%-28s -> %s' % (n, package))
        for r in residue:
            print('    RESIDUE  ' + r)
