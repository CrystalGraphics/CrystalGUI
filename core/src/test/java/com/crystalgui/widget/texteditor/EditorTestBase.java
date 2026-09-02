package com.crystalgui.widget.texteditor;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.keymap.KeymapResolver;
import com.crystalgui.widget.texteditor.find.EditorFind;
import com.crystalgui.widget.texteditor.fold.EditorFolding;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.ui.service.Input;
import org.junit.Before;

import static org.junit.Assert.*;

/**
 * The fixture every editor test shares — a document, an input stub, a clipboard and the helpers that read
 * what was drawn.
 *
 * <p>Split out of {@code TextEditorTest} when that file reached 5,162 lines and 244 tests, along the same
 * seams {@code TextEditor} itself was split on: {@link EditorFindTest}, {@link EditorFoldingTest} and
 * {@link EditorViewTest} sit beside {@code EditorFind}, {@code EditorFolding} and the view parts, and
 * {@code TextEditorTest} keeps the document, the caret and the keys.</p>
 *
 * <h3>The helpers all live here, and deliberately not with the tests that use most of them</h3>
 *
 * <p>They read the realised elements — {@code allWithClass}, {@code renderedLines}, {@code countOf} — and
 * almost every one has at least two callers across the four classes now. Distributing them by "who uses
 * this most" is how a second copy of {@code allWithClass} appears in a later file, and two answers to
 * "which elements are on screen" is exactly the kind of divergence these tests exist to catch.</p>
 */
public abstract class EditorTestBase extends UiDocumentTestBase {


    protected Input input;

    protected TextEditor editor;

    /**
     * The live modifier mask.
     *
     * <p>{@code Input} reads modifiers from {@code CgPlatform.input().getCurrentModifiers()},
     * <b>not</b> from the key events it is handed — so synthesising a Shift key-down does nothing at all.
     * The mask is platform state, and a test sets it by being the platform.</p>
     */
    protected static final String NL = "\n";

    protected int modifiers;

    protected long wheelClock = 50L;

    protected String clipboard = "";

    @Before
    public void installInputStub() {
        // PRISTINE SHEETS FIRST. A theme swap is process-wide and refills StyleSheet.DEFAULT in place, so
        // any earlier test that built something applying settings leaves every var() in this JVM resolving
        // to that theme's value instead of its fallback. @see UiThemeManager#resetForTesting
        com.crystalgui.style.theme.UiThemeManager.getInstance().resetForTesting();
        modifiers = 0;
        clipboard = "";
        // Clipboard and modifiers live on the same service, so one stub covers both.
        TestPlatformService.install().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return modifiers; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return clipboard; }
            @Override public void setClipboard(String text) { clipboard = text; }
        });
    }

    protected TextEditor build(String text) {
        editor = new TextEditor(text);
        editor.layout(l -> l.width(300).height(120));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));

        UINode root = new UINode().layout(l -> l.width(300).height(200));
        root.append(editor);
        document.append(root);
        // THE USER-AGENT SHEET, applied on purpose. It is never injected automatically, and without it
        // none of the editor's own CSS exists -- so the gutter had no padding, the line numbers no
        // alignment, and the paint order no z-indices. Every test here ran against that until the gutter's
        // metrics moved into the sheet and one of them noticed. AGENTS.md warns about exactly this: a
        // test that asserts on default.css behaviour without applying it exercises no CSS at all.
        document.styleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        input = document.input();
        settle();
        document.focus().requestFocus(editor);
        return editor;
    }

    /**
     * Advances the frame AND pumps an input frame.
     *
     * <p>{@code updateWithoutPainting()} deliberately does no input handling — no frame was presented, so
     * hover has nothing to be relative to — which means it never sets {@code firstFrameOver}. And
     * {@code consumeKeyboardEvent} early-returns until that flag is set, so without the
     * begin/endFrame pair here every key is silently dropped before dispatch and the widget looks
     * completely dead while being perfectly correct.</p>
     */
    /**
     * Steps frames until nothing is left in flight — <b>including work on a worker</b>.
     *
     * <h3>Three frames is not "settled" once anything is scheduled</h3>
     *
     * <p>This used to be three {@code updateWithoutPainting()} calls. Those do drain the shared scheduler
     * ({@code advanceFrame} drains before the passes that read the results), but draining only delivers
     * jobs that have <em>finished</em> — and three headless frames take microseconds, so whether a worker
     * has got there is a coin flip on thread scheduling and machine load.</p>
     *
     * <p>Folding is the one that made this visible. {@code EditorFolding} sends a whole-document pass to
     * a worker when the provider allows it AND {@code JobScheduler.hasShared()} — and that second
     * condition is itself decided by test ORDER, since the shared scheduler is created lazily by whatever
     * ran first. So the same folding test was synchronous in one run and racing in the next, and failed
     * about one run in four with a different assertion each time: "some arrows are on screen", "no child
     * with class __fold-placeholder__", "the fold must actually have hidden something".</p>
     *
     * <p>Bounded rather than open-ended: a test that genuinely never settles must fail on its own
     * assertion, not hang here.</p>
     */
    protected void settle() {
        for (int i = 0; i < 3; i++) frame();
        if (com.crystalgui.core.async.JobScheduler.hasShared()) {
            com.crystalgui.core.async.JobScheduler shared =
                    com.crystalgui.core.async.JobScheduler.shared();
            for (int spin = 0; spin < 200; spin++) {
                if (shared.waitingCount() == 0 && shared.runningCount() == 0) break;
                // Yield rather than sleep: the work is on another thread and this one has nothing to do
                // but let it run, and a sleep would put a fixed cost on every settle in the suite.
                Thread.yield();
                frame();
            }
            // One more, so a result delivered by the last drain is acted on by a frame that sees it.
            frame();
        }
        if (input != null) {
            input.beginFrame();
            input.endFrame();
        }
    }

    protected void key(int code) {
        key(code, CgModifiers.NONE);
    }

    protected void key(int code, int held) {
        this.modifiers = held;
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event('\0', code, true, false, 2L));
        this.modifiers = 0;
        settle();
    }

    protected void type(String text) {
        for (char c : text.toCharArray()) {
            input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(c, 0, true, false, 3L));
        }
        settle();
    }

    protected Object lineFontFamily() {
        for (UINode child : allDescendants()) {
            if (!child.hasClass(TextEditor.LINE_CLASS)) continue;
            return child.children().get(0).getStyle().getGeneralGroup().fontFamily();
        }
        throw new AssertionError("no line realised");
    }

    protected java.util.Map<Integer, UINode> realisedRowsOf(TextEditor target) {
        java.util.Map<Integer, UINode> rows = new java.util.LinkedHashMap<>();
        int index = 0;
        for (UINode child : target.children()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) rows.put(index++, child);
        }
        return rows;
    }

    /** Whether the realised line showing {@code row} carries any range under {@code name}. */
    protected boolean lineHasHighlight(int row, String name) {
        String wanted = editor.buffer().line(row);
        for (UINode child : allDescendants()) {
            if (!child.hasClass(TextEditor.LINE_CLASS)) continue;
            UIText text = (UIText) child.children().get(0);
            if (!text.getText().equals(wanted)) continue;
            if (!text.highlights().get(name).isEmpty()) return true;
        }
        return false;
    }

    protected void collectNumbers(UINode root, java.util.List<String> out) {
        for (UINode child : root.children()) {
            if (child.hasClass(TextEditor.LINE_NUMBER_CLASS)) {
                UIText label = (UIText) child.children().get(0);
                if (child.box().height() > 0f) out.add(label.getText());
            }
            collectNumbers(child, out);
        }
    }

    protected static int rowsOf(String text) {
        return text.split(NL, -1).length;
    }

    /**
     * One row far wider than the test viewport.
     *
     * <p>Sized against the measurement rather than guessed: the editor is 300px and a space at font-size
     * 8 advances about 1.9px, so the wrap column is around 150. A first attempt at these tests used an
     * 86-character line, which correctly did <b>not</b> wrap — and read as soft wrap being broken.</p>
     */
    protected static String longLine() {
        StringBuilder out = new StringBuilder();
        while (out.length() < 400) out.append("alpha beta gamma delta epsilon zeta eta theta ");
        return out.toString().trim();
    }

    /**
     * The line elements currently ON SCREEN — <b>pooled ones excluded</b>.
     *
     * <p>{@code recycleLine} keeps a line attached and hides it with {@code display: none} rather than
     * detaching it, so the editor's line pool is now part of the tree. Selecting by class alone therefore
     * answers "every line element that has ever existed", and the topmost of those is whichever one the
     * pool happens to hold — which is what {@code assertRowsAlign} was comparing a gutter number against.
     * The production code never had this question: it reads its {@code realisedLines} map.</p>
     */
    protected java.util.List<UINode> linesOf() {
        java.util.List<UINode> out = new java.util.ArrayList<>();
        for (UINode line : allWithClass(TextEditor.LINE_CLASS)) {
            // A POOLED LINE HAS NO BOX, which is this engine's own way of saying "not laid out" -- a
            // hidden node gets none at all, where the old one gave a zero-sized box and the display
            // property had to be read off the Taffy bridge to tell the two apart. Asking for the box
            // is both the honest test and the thing the sort below needs anyway.
            if (line.box() != null) {
                out.add(line);
            }
        }
        // IN ROW ORDER, which is no longer tree order. A recycled line keeps its place in the children
        // list now, so a reused element shows whatever row it was next handed — where detaching and
        // re-adding used to append it and leave tree order tracking realisation order by accident. The
        // editor never relied on that: every line is absolutely positioned by `top`, and that is the only
        // thing that says which row an element is showing.
        out.sort(java.util.Comparator.comparingDouble(line -> line.box().y()));
        return out;
    }

    /**
     * Every descendant carrying {@code name}.
     *
     * <p>Recursive since the text moved into {@code __text-viewport__}: the lines, caret, bands, guides
     * and markers are that element's children now rather than the editor's, so a one-level scan finds
     * nothing. The gutter's numbers were always a level down, which is why that one already recursed by
     * hand.</p>
     */
    /** Every descendant of the editor, in tree order. */
    protected java.util.List<UINode> allDescendants() {
        java.util.List<UINode> out = new java.util.ArrayList<>();
        collectAll(editor, out);
        return out;
    }

    protected static void collectAll(UINode from, java.util.List<UINode> out) {
        for (UINode child : from.children()) {
            out.add(child);
            collectAll(child, out);
        }
    }

    /**
     * Everything under {@code from}, at any depth.
     *
     * <p>For asking "is this decoration in the gutter" without also asserting how many elements deep it
     * sits. The gutter's numbers, the fold column's arrows and the text's rows all live inside a
     * {@code __scroll-layer__} now — see {@code TextEditor#linesLayer()} — so a walk over direct
     * children answers zero for all of them while every one is on screen.</p>
     */
    protected static java.util.List<UINode> descendantsOf(UINode from) {
        java.util.List<UINode> out = new java.util.ArrayList<>();
        collectAll(from, out);
        return out;
    }

    protected java.util.List<UINode> allWithClass(String name) {
        java.util.List<UINode> out = new java.util.ArrayList<>();
        collectWithClass(editor, name, out);
        return out;
    }

    protected static void collectWithClass(UINode from, String name, java.util.List<UINode> out) {
        for (UINode child : from.children()) {
            if (child.hasClass(name)) out.add(child);
            collectWithClass(child, name, out);
        }
    }

    // ── 6.1.7b: ported mouse selection ───────────────────────────

    /** A press at a document offset, with a click count, through the real input handler. */
    protected void pressWithClicks(int offset, int clicks) {
        settle();
        editor.updateWindow();
        settle();
        float scale = document.boxes().uiScale();
        var point = editor.buffer().offsetToPoint(offset);
        float y = editor.box().y() + point.row() * editor.lineHeight() + 2f;
        // X FROM THE EDITOR'S OWN HIT TESTING, not from a per-character guess.
        //
        // This was `gutterWidth + 4 + column * 4f`, a 4px advance tuned to whatever face happened to be
        // the default -- so changing the default font moved every click by a character or more and
        // `doubleClickSelectsTheWordUnderIt` selected `alpha` while claiming to click in `beta`. Asking
        // offsetAt where the offset actually is makes the coordinate true for any font, any size and any
        // gutter width, and it is the same function the click itself will go through.
        float x = editor.box().x() + editor.gutterWidth() + 2f;
        float limit = x + editor.box().width();
        for (float probe = x; probe < limit; probe += 0.5f) {
            if (editor.offsetAt(probe * scale, y * scale) >= offset) {
                x = probe;
                break;
            }
        }
        for (int i = 0; i < clicks; i++) {
            input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                    Math.round(x * scale), Math.round(y * scale), 0, 0, 0, true, 0f, 10L + i));
            input.beginFrame();
            input.endFrame();
            input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                    Math.round(x * scale), Math.round(y * scale), 0, 0, 0, false, 0f, 11L + i));
            input.beginFrame();
            input.endFrame();
        }
        settle();
    }

    // ── Scrollbars ───────────────────────────────────────────────

    /**
     * Builds an editor whose content overflows on <b>both</b> axes, so both bars are showing.
     *
     * <p>The user-agent sheet is installed deliberately: without it the scrollers have no size, and a
     * test about what the scrollbars cover would pass by there being no scrollbars.</p>
     */
    protected void buildOverflowing() {
        // Named `text`, not `document`: the base's field is called `document` now, and a local of that
        // name shadows it -- so the stylesheet install below went to a StringBuilder.
        StringBuilder text = new StringBuilder();
        text.append("a line long enough to force horizontal scrolling xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx").append(NL);
        for (int i = 0; i < 80; i++) text.append("line ").append(i).append(NL);
        build(text.toString());
        document.styleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        settle();
        editor.updateWindow();
        settle();
    }

    protected UINode childWithClass(String name) {
        java.util.List<UINode> found = allWithClass(name);
        if (found.isEmpty()) throw new AssertionError("no child with class " + name);
        return found.get(0);
    }

    protected boolean caretVisible() {
        for (UINode child : allDescendants()) {
            if (child.hasClass(TextEditor.CARET_CLASS)) {
                return child.getStyle().getGeneralGroup().opacity() > 0.5f;
            }
        }
        throw new AssertionError("no caret element");
    }

    protected void pressAt(int x, int y) {
        float scale = document.boxes().uiScale();
        int px = Math.round((editor.box().x() + x) * scale);
        int py = Math.round((editor.box().y() + y) * scale);
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(px, py, 0, 0, 0, true, 0f, 10L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(px, py, 0, 0, 0, false, 0f, 11L));
        input.beginFrame();
        input.endFrame();
        settle();
    }

    /** Measures painted text in the editor's own font against the box it goes in. */
    protected void assertEveryPaintedLineFits() {
        var general = editor.getStyle().getGeneralGroup();
        var family = com.crystalgui.render.text.FontFamilyCache.resolve(
                general.fontFamily(), Math.round(general.fontSize()));
        float limit = editor.box().clientWidth();

        for (UINode line : linesOf()) {
            String text = ((UIText) line.children().get(0)).getText();
            if (text.isEmpty()) continue;
            float width = com.crystalgraphics.api.text.CgTextLayout.of(text, family).build().totalWidth();
            assertTrue("a view line measuring " + width + " does not fit in " + limit + ": '" + text + "'",
                    width <= limit);
        }
    }

    // ── 6.1.7b §G: indent guides, visible whitespace, rulers ─────

    protected int countOf(String className) {
        int n = 0;
        for (UINode child : allWithClass(className)) {
            // hide() collapses an unused pooled element to zero height, so a laid-out height is what
            // separates "drawn this frame" from "pooled and idle".
            if (child.box().contentBoxHeight() > 0f) n++;
        }
        return n;
    }

    /** A triple-click on the row containing {@code needle}, through the real press path. */
    protected void tripleClickOn(String needle) {
        int offset = editor.getText().indexOf(needle);
        assertTrue("needle must exist", offset >= 0);
        editor.setCaret(offset);
        showEditor();
        UINode caret = childWithClass(TextEditor.CARET_CLASS);
        // SCALED SCREEN COORDINATES. MouseEvent positions are what consumeMouseEvent produces, which is
        // layout * uiScale -- see pressAt. Passing editor-relative layout coordinates lands the press on
        // whatever row that happens to be, which is how this helper first "proved" the wrong thing.
        float scale = document.boxes().uiScale();
        float x = (caret.box().x() + 2f) * scale;
        float y = (caret.box().y() + 1f) * scale;
        var press = new com.crystalgui.ui.event.MouseEvent.Down(editor,
                new com.crystalgui.core.data.ReadOnlyVec2f(new org.joml.Vector2f(x, y)), 0, 3);
        press.setPhase(com.crystalgui.ui.event.PropagationPhase.TARGET);
        editor.onMouseDown.emitTarget(press);
        // RELEASE IT. A press with no release leaves `selecting` true, and the next frame synthesises a
        // mouse-move from the pointer's resting position -- so the drag extends from the clicked line to
        // wherever that is. The first version of this helper selected two rows for exactly that reason.
        var release = new com.crystalgui.ui.event.MouseEvent.Up(editor,
                new com.crystalgui.core.data.ReadOnlyVec2f(new org.joml.Vector2f(x, y)), 0, 3, true);
        release.setPhase(com.crystalgui.ui.event.PropagationPhase.TARGET);
        editor.onMouseUp.emitTarget(release);
        showEditor();
    }

    /** The rendered text of each realised line, in order. */
    protected java.util.List<String> renderedLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (UINode line : linesOf()) {
            out.add(((UIText) line.children().get(0)).getText());
        }
        return out;
    }

    protected void showEditor() {
        settle();
        editor.updateWindow();
        settle();
    }

    /**
     * The topmost painted line and the topmost gutter number describe the same row, so they must share a
     * y. A line left outside the viewport is scrolled twice — by the pose translate and by hand — and
     * lands a screenful from its number; one never repositioned stays put while the number moves.
     */
    protected void assertRowsAlign(String when) {
        UINode number = childWithClass(TextEditor.LINE_NUMBER_CLASS);
        UINode line = linesOf().get(0);
        assertEquals(when + ": the text and its gutter number must sit on the same row",
                number.box().y(), line.box().y(), editor.lineHeight());
    }

    /** Spins the wheel over the editor with the given modifiers held through the dispatch. */
    protected void wheel(float notches, int held) {
        float px = editor.box().x() + editor.box().width() / 2f;
        float py = editor.box().y() + editor.box().height() / 2f;
        // The mask must stay held THROUGH the settle. Scroll is accumulated by consumeMouseEvent and
        // dispatched once per frame from endFrame(), so a mask cleared before that is not the one the
        // resolver reads -- it reads CgPlatform's live state at DISPATCH time.
        this.modifiers = held;
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                (int) px, (int) py, 0, 0, 0, false, notches, wheelClock += 20L));
        settle();
        this.modifiers = 0;
        editor.updateWindow();
        settle();
    }

    /** A small file with one nested block, for the active-guide tests. */
    protected void buildBlock() {
        build("class A {" + NL + "    void f() {" + NL + "        body();" + NL + "    }" + NL + "}");
        editor.setIndentGuidesVisible(true);
        showEditor();
    }

    protected int activeGuideCount() {
        int n = 0;
        for (UINode guide : allWithClass(TextEditor.INDENT_GUIDE_CLASS)) {
            if (guide.box().contentBoxHeight() <= 0f) continue;
            if (guide.hasClass(TextEditor.ACTIVE_GUIDE_CLASS)) n++;
        }
        return n;
    }

    /** Names published under {@code ::highlight(...)} by the line holding {@code offset}. */
    protected java.util.Set<String> highlightNamesAt(int offset) {
        int row = 0;
        String text = editor.getText();
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') row++;
        }
        int wanted = row;
        int seen = 0;
        for (UINode line : linesOf()) {
            if (seen++ != wanted) continue;
            return ((UIText) line.children().get(0)).highlights().names();
        }
        return java.util.Set.of();
    }

    // ===================================================================================================
    // Folding
    // ===================================================================================================

    /** A short Java-shaped document with one class holding one method. */
    protected void buildFoldable() {
        build("class A {" + NL
                + "    void f() {" + NL
                + "        a();" + NL
                + "        b();" + NL
                + "    }" + NL
                + "}");
        showEditor();
    }

    /** Arrows whose box is non-zero, i.e. the ones actually on screen. */
    protected java.util.List<UINode> visibleFoldArrows() {
        java.util.List<UINode> shown = new java.util.ArrayList<>();
        for (UINode arrow : allWithClass(TextEditor.FOLD_CLASS)) {
            if (arrow.box().contentBoxHeight() > 0f) shown.add(arrow);
        }
        return shown;
    }

    /** Start rows of every currently collapsed region. */
    protected java.util.List<Integer> collapsedStartRows() {
        java.util.List<Integer> rows = new java.util.ArrayList<>();
        com.crystalgui.text.fold.FoldingRegions regions = editor.foldingModel().regions();
        for (int i = 0; i < regions.length(); i++) {
            if (regions.isCollapsed(i)) rows.add(regions.getStartLineNumber(i));
        }
        return rows;
    }

    /** Resolves a chord through the real KeymapResolver, exactly as the input handler does. */
    protected boolean pressChord(int keyCode, int modifiers) {
        var resolver = new com.crystalgui.ui.input.keymap.KeymapResolver(document.getCommands());
        return resolver.resolve(editor,
                new com.crystalgui.ui.input.keymap.KeyStroke(keyCode, modifiers),
                com.crystalgui.ui.input.keymap.KeyEventType.PRESS, System.currentTimeMillis());
    }

    /**
     * The point a real pointer would have to be at to be over {@code element}.
     *
     * <p><b>Not {@code box().x()}.</b> That is a layout coordinate in the element's own
     * parent space; the screen position comes from walking {@code localToWorld}, which is what the input
     * handler's hit test does. The two differ by every transform between the element and the root — most
     * of all {@code uiScale} — so a probe built from the cached box lands somewhere else entirely and
     * reports a perfectly reachable control as unreachable.</p>
     */
    protected float[] screenCentreOf(UINode element) {
        // SEARCHED, not computed. The layout box, the world matrix and the screen differ by uiScale and by
        // every transform in between, and getting that arithmetic subtly wrong is exactly how a reachable
        // control gets reported as unreachable -- which is the bug this helper exists to detect, so the
        // helper must not depend on the same reasoning. containsScreenPoint IS the question being asked.
        for (float y = 0; y < 2048; y += 2) {
            for (float x = 0; x < 2048; x += 2) {
                if (element.containsSurfacePoint(x, y)) return new float[] { x, y };
            }
        }
        throw new AssertionError("no screen point is over " + element.classes());
    }

    /** The chip's box at a given editor font size. */
    protected float[] chipBoxAt(float fontSize) {
        editor.setFontSize(fontSize);
        showEditor();
        showEditor();
        UINode chip = childWithClass(TextEditor.FOLD_PLACEHOLDER_CLASS);
        assertNotNull("a chip at " + fontSize + "px", chip);
        return new float[] { chip.box().width(), chip.box().height() };
    }

    /** A document of {@code n} four-row blocks, each foldable. */
    protected void buildBlocks(int n) {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < n; i++) {
            document.append("void f").append(i).append("() {").append(NL)
                    .append("    a();").append(NL).append("    b();").append(NL).append("}").append(NL);
        }
        build(document.toString());
        showEditor();
    }

    /** Screen y of the caret, which is what a fold must not move. */
    /**
     * Where something inside a scroll layer is actually DRAWN, vertically.
     *
     * <p>Its laid-out position is in <b>document</b> coordinates — that is the whole point of
     * {@code TextEditor#linesLayer()}, since a position that does not change while scrolling is a
     * position nothing has to rewrite every frame — and the layer's transform is what puts it on
     * screen. So a test that wants the screen position has to apply the same offset the layer does.</p>
     *
     * <p>Reading the layout position alone still <em>looks</em> right at the top of a document, which is
     * where most fixtures start, so this is worth going through rather than open-coding.</p>
     */
    protected float drawnY(UINode inLayer) {
        return inLayer.box().y() - editor.scrollTop();
    }

    /** The horizontal half of {@link #drawnY}. */
    protected float drawnX(UINode inLayer) {
        return inLayer.box().x() - editor.scrollLeft();
    }

    protected float caretScreenY() {
        UINode caret = childWithClass(TextEditor.CARET_CLASS);
        assertNotNull("the caret is on screen", caret);
        return drawnY(caret);
    }
}
