package com.crystalgui.widget.texteditor.fold;

import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.texteditor.EditorTestBase;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.TextEditor;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Folding — the arrows, the placeholder chips, what a collapsed region hides, and where the caret and the
 * viewport end up afterwards.
 *
 * <p>Sits beside {@code EditorFolding}. The viewport tests are the load-bearing ones: folding removes rows
 * above the viewport as readily as below it, and {@code scrollTop} is a pixel count.</p>
 */
public class EditorFoldingTest extends EditorTestBase {

    /**
     * <b>A fold arrow appears on every row that starts a region, and nowhere else.</b>
     *
     * <p>Two here — the class and the method — and specifically <em>not</em> on the body rows, which are
     * inside a region but do not begin one. An arrow on every row inside a block is the obvious mistake and
     * turns the gutter into a column of arrows that mostly toggle their parent.</p>
     */
    @Test
    public void aFoldArrowSitsOnEachRegionHeader() {
        buildFoldable();
        assertEquals("one for the class, one for the method", 2, visibleFoldArrows().size());
    }

    /** A document with no indentation has nothing to fold and shows no arrows. */
    @Test
    public void aFlatDocumentShowsNoArrows() {
        build("a();" + NL + "b();" + NL + "c();");
        showEditor();
        assertEquals(0, visibleFoldArrows().size());
    }

    /**
     * <b>Collapsing a region removes its rows from the view.</b>
     *
     * <p>Asserted through the realised lines rather than through the model, because that is the thing the
     * user sees: the model can be perfectly right while the widget still paints the rows.</p>
     */
    @Test
    public void collapsingARegionRemovesItsLinesFromTheView() {
        buildFoldable();
        int before = linesOf().size();

        editor.toggleFoldAt(1); // the method
        showEditor();

        // THREE, not two: the region swallows its closing row, so a collapsed block reads as
        // one collapsed line ending in the closing brace, rather than leaving it behind.
        assertEquals("body and closing row went away", before - 3, linesOf().size());
    }

    /** And unfolding brings them back — folding is a view state that is fully reversible. */
    @Test
    public void unfoldingRestoresTheLines() {
        buildFoldable();
        int before = linesOf().size();

        editor.toggleFoldAt(1);
        showEditor();
        editor.toggleFoldAt(1);
        showEditor();

        assertEquals(before, linesOf().size());
    }

    /**
     * <b>The header of a collapsed region is still on screen.</b>
     *
     * <p>It carries the arrow that reopens the block. Hiding it makes a collapsed region unreachable: the
     * rows are gone and so is the only handle on them.</p>
     */
    @Test
    public void theHeaderOfACollapsedRegionStaysVisible() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();

        boolean found = false;
        for (UINode line : linesOf()) {
            String text = ((UIText) line.children().get(0)).getText();
            if (text.contains("void f()")) found = true;
        }
        assertTrue("the method's signature is still painted", found);
    }

    /** A collapsed region gets a placeholder after its header, so it does not read as an empty body. */
    @Test
    public void aCollapsedRegionGetsAPlaceholder() {
        buildFoldable();
        assertEquals("nothing is folded yet", 0, countOf(TextEditor.FOLD_PLACEHOLDER_CLASS));

        editor.toggleFoldAt(1);
        showEditor();

        assertEquals(1, countOf(TextEditor.FOLD_PLACEHOLDER_CLASS));
    }

    /** The arrow flips its state class, which is what the sheet turns into a sideways arrow. */
    @Test
    public void aCollapsedArrowCarriesTheStateClass() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();

        int collapsed = 0;
        for (UINode arrow : visibleFoldArrows()) {
            if (arrow.hasClass(TextEditor.FOLD_COLLAPSED_CLASS)) collapsed++;
        }
        assertEquals("exactly the folded one", 1, collapsed);
    }

    /**
     * <b>Folding a block the caret is inside moves the caret to the block's header.</b>
     *
     * <p>Not cosmetic. A caret on a hidden row has no view line at all, so it cannot be painted, scrolled
     * to, or typed at — the editor looks focused and silently does nothing.</p>
     */
    @Test
    public void foldingLiftsTheCaretOutOfTheHiddenRows() {
        buildFoldable();
        editor.setCaret(editor.getText().indexOf("a();"));
        showEditor();

        editor.toggleFoldAt(1);
        showEditor();

        int caretRow = editor.getText().substring(0, editor.getCaret()).split("\n", -1).length - 1;
        assertEquals("the caret came up to the method's signature", 1, caretRow);
    }

    /**
     * <b>A fold survives a resize.</b>
     *
     * <p>A width change reprojects every row, and a reprojection that reset visibility would silently open
     * every collapsed block whenever the document moved.</p>
     */
    @Test
    public void aFoldSurvivesAResize() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();
        int folded = linesOf().size();

        editor.layout(l -> l.width(520f));
        showEditor();
        showEditor();

        assertEquals("still folded after the width changed", folded, linesOf().size());
    }

    /** Fold-all closes every region; unfold-all opens them. */
    @Test
    public void foldAllAndUnfoldAllWork() {
        buildFoldable();
        int open = linesOf().size();

        editor.foldAll();
        showEditor();
        assertTrue("fewer rows on screen", linesOf().size() < open);

        editor.unfoldAll();
        showEditor();
        assertEquals(open, linesOf().size());
    }

    /**
     * <b>Clicking the arrow toggles the row it is currently showing, not the one it was created for.</b>
     *
     * <p>The arrows are pooled and recycled as the view scrolls, so a listener that captured its row at
     * creation would keep toggling whatever row the slot was first used for — and would keep working for
     * exactly as long as nobody scrolled, which is why it survives a naive test.</p>
     */
    @Test
    public void aRecycledArrowTogglesTheRowItNowShows() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            document.append("void f").append(i).append("() {").append(NL)
                    .append("    a();").append(NL).append("    b();").append(NL).append("}").append(NL);
        }
        build(document.toString());
        showEditor();

        editor.box().setScroll(0f, 40f * editor.lineHeight());
        showEditor();

        // Every arrow on screen now belongs to a row far down the document. Note the assertion is NOT on
        // the realised line count: the viewport shows the same number of rows whatever is folded, so a
        // count that far from the document's end proves nothing.
        java.util.List<UINode> arrows = visibleFoldArrows();
        assertFalse("some arrows are on screen", arrows.isEmpty());
        assertEquals("nothing folded yet", 0, collapsedStartRows().size());

        com.crystalgui.ui.event.MouseEvent.Down press = new com.crystalgui.ui.event.MouseEvent.Down(
                arrows.get(0), new com.crystalgui.core.data.ReadOnlyVec2f(new org.joml.Vector2f(0f, 0f)), 0, 1);
        // The phase defaults to CAPTURE, and emitTarget dispatches to whichever phase the event names --
        // so an unset phase silently delivers to the capture listeners and this reads as the handler not
        // being attached at all.
        press.setPhase(com.crystalgui.ui.event.PropagationPhase.TARGET);
        arrows.get(0).onMouseDown.emitTarget(press);
        showEditor();

        java.util.List<Integer> collapsed = collapsedStartRows();
        assertEquals("exactly one block folded", 1, collapsed.size());
        assertTrue("and it is one that is ON SCREEN, not the row the slot was created for ("
                        + collapsed.get(0) + ")",
                collapsed.get(0) > 20);
    }

    /**
     * <b>Folding works through the command registry, not just through the public methods.</b>
     *
     * <p>The gap the widget tests left: they called {@code foldAll()} directly, which proves the model and
     * the view agree but says nothing about whether the key that is supposed to reach it does. A command
     * that is registered but never bound, or bound to a chord that does not resolve, fails exactly here and
     * nowhere else.</p>
     */
    @Test
    public void theFoldCommandsAreRegisteredAndReachTheEditor() {
        buildFoldable();
        int open = linesOf().size();

        assertNotNull("editor.foldAll must be registered",
                document.getCommands().get("editor.foldAll"));

        // WITH A SOURCE. Every editor command resolves its target from context.source() -- running one
        // with no source finds no editor, does nothing, and still returns true, so a context-free call
        // asserts only that the id exists.
        assertTrue("and running it must return true", document.getCommands().run("editor.foldAll",
                com.crystalgui.core.command.CommandContext.of(editor)));
        showEditor();

        assertTrue("the command actually folded something", linesOf().size() < open);
    }

    /** Every fold command is registered under the id its key binding names. */
    @Test
    public void everyFoldCommandIdResolves() {
        buildFoldable();
        for (String id : new String[] { "editor.fold", "editor.unfold", "editor.foldRecursively",
                "editor.foldAll", "editor.unfoldAll", "editor.foldLevel1", "editor.foldLevel7" }) {
            assertNotNull(id + " is not registered", document.getCommands().get(id));
        }
    }

    /** And every fold chord is bound on the editor's own keymap. */
    @Test
    public void everyFoldChordIsBound() {
        buildFoldable();
        java.util.Set<String> bound = new java.util.HashSet<>();
        for (var binding : editor.keymap().bindings()) bound.add(binding.getCommandId());

        for (String id : new String[] { "editor.fold", "editor.unfold", "editor.foldAll",
                "editor.unfoldAll", "editor.foldLevel1" }) {
            assertTrue(id + " has no key binding", bound.contains(id));
        }
    }

    /**
     * <b>Every character the editor draws as furniture must exist in the font.</b>
     *
     * <p>The fold arrows shipped as U+25BE/U+25B8 and were invisible: the bundled fonts cover neither, and
     * a missing glyph here draws a blank advance rather than falling back to anything. The control was laid
     * out, hit-testable and completely present — and looked completely absent, which no other test could
     * see, because nothing about layout or behaviour changes when the paint is empty.</p>
     *
     * <p>So the assertion is on FONT COVERAGE, not on which characters were chosen: a future theme may swap
     * them, and this stays the right question to ask of whatever it picks.</p>
     */
    @Test
    public void everyFurnitureGlyphIsDrawableByTheFont() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();

        var family = com.crystalgui.render.text.FontFamilyCache.resolve(
                java.util.List.of("crystalgui:ui/fonts/MinecraftRegular.otf"), 16);

        java.util.List<UINode> furniture = new java.util.ArrayList<>();
        furniture.addAll(allWithClass(TextEditor.FOLD_CLASS));
        furniture.addAll(allWithClass(TextEditor.FOLD_PLACEHOLDER_CLASS));
        assertFalse("there is furniture to check", furniture.isEmpty());

        for (UINode element : furniture) {
            for (UINode child : element.children()) {
                if (!(child instanceof UIText label)) continue;
                String text = label.getText();
                for (int i = 0; i < text.length(); i++) {
                    int cp = text.codePointAt(i);
                    var source = family.resolveSourceForCodePoint(cp);
                    assertTrue(String.format("U+%04X is not in the font, so it paints nothing", cp),
                            source != null && source.canDisplayCodePoint(cp));
                }
            }
        }
    }

    /**
     * <b>The fold chords resolve through the keymap resolver.</b>
     *
     * <p>The gap every earlier test left: they ran the command by id. That proves registration and it
     * proves the action, and it says nothing about whether the CHORD reaches it — which is the half the
     * user actually presses.</p>
     */
    @Test
    public void theFoldChordsResolve() {
        buildFoldable();
        int open = linesOf().size();

        int ctrlShift = com.crystalgraphics.platform.input.CgModifiers.CTRL
                | com.crystalgraphics.platform.input.CgModifiers.SHIFT;
        assertTrue("Ctrl+Shift+Minus must resolve",
                pressChord(com.crystalgraphics.platform.input.CgKeyCodes.KEY_MINUS, ctrlShift));
        showEditor();
        assertTrue("and must actually fold", linesOf().size() < open);
    }

    /**
     * <b>A real pointer can reach a fold arrow.</b>
     *
     * <p>The test the first version needed and did not have. It dispatched to the arrow with
     * {@code emitTarget}, which proves the handler works and asks nothing about whether a click could ever
     * arrive — and the arrows shipped parented to the gutter, which is {@code setHitTest(false)}. That
     * applies to the whole SUBTREE, so every handle was painted, correct, and permanently dead. The user
     * saw handles that did nothing; every test was green.</p>
     *
     * <p>So this goes through {@code getHoveredElement}, the same hit test the input handler uses.</p>
     */
    @Test
    public void aPointerCanActuallyHitAFoldArrow() {
        buildFoldable();

        java.util.List<UINode> arrows = visibleFoldArrows();
        assertFalse("there are arrows", arrows.isEmpty());
        UINode arrow = arrows.get(0);

        float[] at = screenCentreOf(arrow);
        UINode hit = hit(at[0], at[1]);

        assertNotNull("the pointer hits something at the arrow", hit);
        assertTrue("and it is the arrow, not the editor behind it -- got " + hit.classes(),
                hit.hasClass(TextEditor.FOLD_CLASS));
    }

    /** And a press delivered through that hit test folds the block. */
    @Test
    public void clickingAFoldArrowFoldsTheBlock() {
        buildFoldable();
        int open = linesOf().size();

        UINode arrow = visibleFoldArrows().get(0);
        float[] at = screenCentreOf(arrow);
        UINode hit = hit(at[0], at[1]);
        assertNotNull(hit);

        var press = new com.crystalgui.ui.event.MouseEvent.Down(hit,
                new com.crystalgui.core.data.ReadOnlyVec2f(new org.joml.Vector2f(at[0], at[1])), 0, 1);
        press.setPhase(com.crystalgui.ui.event.PropagationPhase.TARGET);
        hit.onMouseDown.emitTarget(press);
        showEditor();

        assertTrue("the block folded", linesOf().size() < open);
    }

    /**
     * <b>The collapsed chip is a real control: hoverable, pointer-cursored, and it unfolds.</b>
     *
     * <p>All three go through hit testing, which is the part that was broken twice — first the arrows under
     * the gutter, then the chip under the text viewport, both {@code setHitTest(false)} whose effect covers
     * the entire subtree. A chip that paints correctly and cannot be hovered is indistinguishable from one
     * that works, in every test that does not ask the hit test itself.</p>
     */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void theCollapsedChipBehavesLikeAButton() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();

        UINode chip = childWithClass(TextEditor.FOLD_PLACEHOLDER_CLASS);
        assertNotNull("there is a chip", chip);

        float[] at = screenCentreOf(chip);
        UINode hit = hit(at[0], at[1]);
        assertNotNull(hit);
        assertTrue("the pointer reaches the chip, not the text behind it -- got " + hit.classes(),
                hit.hasClass(TextEditor.FOLD_PLACEHOLDER_CLASS));

        assertEquals("and it declares a pointer cursor",
                com.crystalgraphics.platform.input.CgCursor.POINTER,
                hit.getStyle().getGeneralGroup().cursor());

        var press = new com.crystalgui.ui.event.MouseEvent.Down(hit,
                new com.crystalgui.core.data.ReadOnlyVec2f(new org.joml.Vector2f(at[0], at[1])), 0, 1);
        press.setPhase(com.crystalgui.ui.event.PropagationPhase.TARGET);
        hit.onMouseDown.emitTarget(press);
        showEditor();

        assertEquals("clicking it unfolds", 0, countOf(TextEditor.FOLD_PLACEHOLDER_CLASS));
    }

    /**
     * <b>The chip reads as the whole construct, opener included.</b>
     *
     * <p>IntelliJ collapses to a single control spanning {@code {...}}; a chip holding only {@code ...} sits
     * beside a brace the line still owns and reads as two things. The opener is absorbed by placing the chip
     * OVER it rather than by editing the row, so the text the row measures is untouched — see the note at
     * the call site.</p>
     */
    @Test
    public void theChipCoversTheOpeningBraceToo() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();

        UINode chip = childWithClass(TextEditor.FOLD_PLACEHOLDER_CLASS);
        assertNotNull(chip);
        String text = ((UIText) chip.children().get(0)).getText();

        assertEquals("the chip is the whole collapsed construct", "{...}", text);
    }

    /** A row with no trailing bracket keeps the chip to what it can honestly stand for. */
    @Test
    public void aRowWithNoTrailingBracketGetsAPlainChip() {
        build("def f():" + NL + "    a()" + NL + "    b()" + NL + "done()");
        showEditor();
        editor.toggleFoldAt(0);
        showEditor();

        UINode chip = childWithClass(TextEditor.FOLD_PLACEHOLDER_CLASS);
        assertNotNull(chip);
        assertEquals("nothing to absorb, and nothing invented",
                "...", ((UIText) chip.children().get(0)).getText());
    }

    /**
     * <b>A collapsed header stops painting the bracket the chip took over.</b>
     *
     * <p>The first attempt left the row drawing it and covered it with the chip's background. It showed:
     * the chip's rounded corners let the brace's corners through, and its left padding put the chip's own
     * brace a few pixels right of the real one — a gap that grew with the font size, so it drifted visibly
     * on zoom. Two braces at slightly different positions is not something a layout assertion notices;
     * only asking what the row actually paints does.</p>
     */
    @Test
    public void aCollapsedHeaderStopsPaintingItsOpeningBrace() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();

        String painted = null;
        for (UINode line : linesOf()) {
            String text = ((UIText) line.children().get(0)).getText();
            if (text.contains("void f()")) painted = text;
        }
        assertNotNull("the header row is on screen", painted);
        assertFalse("the row must not draw the brace the chip now owns: " + painted, painted.contains("{"));
        assertTrue("but keeps everything before it", painted.contains("void f()"));
    }

    /** An expanded row is untouched — the truncation is a property of being collapsed, not of the text. */
    @Test
    public void anExpandedHeaderKeepsItsBrace() {
        buildFoldable();

        boolean found = false;
        for (UINode line : linesOf()) {
            String text = ((UIText) line.children().get(0)).getText();
            if (text.contains("void f()")) {
                assertTrue("an open block keeps its brace", text.contains("{"));
                found = true;
            }
        }
        assertTrue(found);
    }

    /**
     * <b>The chip starts where the bracket was, so the space before it survives as a gap.</b>
     *
     * <p><b>This reverses an earlier decision.</b> The box used to be shifted left by its own padding so
     * the bracket stayed on the exact pixel the row would have drawn it at — which preserved the line's
     * rhythm and ate the gap, leaving the chip touching the {@code )} before it. IntelliJ insets the
     * bracket inside the chip instead, and it reads better: the chip is one object, not a box drawn around
     * a character. The old rule had its own test; this is that test, rewritten rather than deleted, so the
     * reversal is on the record.</p>
     */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void theChipStartsWhereTheBracketWasSoTheGapSurvives() {
        buildFoldable();
        int brace = editor.getText().indexOf("{", editor.getText().indexOf("void f()"));
        editor.setCaret(brace);
        editor.toggleFoldAt(1);
        editor.setCaret(brace);
        showEditor();

        UINode chip = childWithClass(TextEditor.FOLD_PLACEHOLDER_CLASS);
        UINode caret = childWithClass(TextEditor.CARET_CLASS);
        assertNotNull("the chip is on screen", chip);
        assertNotNull("and so is the caret", caret);

        float braceX = caret.box().x() + caret.box().width();
        assertEquals("the box begins at the bracket, not before it",
                braceX, chip.box().x(), 1.5f);
        assertTrue("and the bracket itself is inset within the box",
                chip.box().padding().left > 0f);
    }

    /**
     * <b>The chip hugs its text and sits centred in the row.</b>
     *
     * <p>A box as tall as the line makes the text look shrunken inside a slab, and it is not even centred
     * on the code beside it — the line's leading sits below the glyphs, so a full-height box has more space
     * under its text than over it. Hugging the text and centring the result is what IntelliJ draws.</p>
     */
    @Test
    public void theChipHugsItsTextAndCentresInTheRow() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();

        UINode chip = childWithClass(TextEditor.FOLD_PLACEHOLDER_CLASS);
        assertNotNull(chip);
        float box = chip.box().height();
        float line = editor.lineHeight();

        assertTrue("the box must be shorter than the row it sits in: " + box + " vs " + line, box < line);
        assertTrue("but still tall enough to hold the text", box > line * 0.5f);

        // Centred: the space above the chip within its row equals the space below. Against the chip's OWN
        // row -- the collapsed header -- not just any line, or the comparison is off by a whole line.
        UINode ownRow = null;
        for (UINode candidate : linesOf()) {
            if (((UIText) candidate.children().get(0)).getText().contains("void f()")) ownRow = candidate;
        }
        assertNotNull("found the collapsed header row", ownRow);
        float rowTop = ownRow.box().y();
        float rowHeight = line;
        float above = chip.box().y() - rowTop;
        float below = (rowTop + rowHeight) - (chip.box().y() + box);
        assertEquals("equal space above and below", above, below, 1f);
    }

    /**
     * <b>The chip keeps its proportions across zoom.</b>
     *
     * <p>It kept not doing so, and the reason was never the font — the glyphs inside are provably the same
     * size as the line's at every zoom. It was the <em>padding</em>: a fixed {@code 5px} is half a line's
     * height at 8px and a rounding error at 31px, so the same chip reads as fat at one zoom and cramped at
     * the other. Nothing about the text differs, which is exactly why looking at the text kept coming up
     * empty.</p>
     *
     * <p>The assertion is on the RATIO of the box to the line height, not on pixels: the ratio is the thing
     * that must hold, and pinning pixels would just re-encode one zoom level as the answer again.</p>
     */
    @Test
    public void theChipKeepsItsProportionsAcrossZoom() {
        buildFoldable();
        editor.toggleFoldAt(1);
        showEditor();

        float[] small = chipBoxAt(8f);
        float smallLine = editor.lineHeight();
        float[] large = chipBoxAt(31f);
        float largeLine = editor.lineHeight();

        assertTrue("the zoom actually changed the line height", largeLine > smallLine * 2f);

        float smallRatio = small[0] / smallLine;
        float largeRatio = large[0] / largeLine;
        assertEquals("the chip must occupy the same share of a line at any zoom",
                smallRatio, largeRatio, 0.15f);
    }

    /**
     * And the glyphs themselves track the editor's font — the half that was never broken, pinned so a
     * future change to the chip's styling cannot quietly detach it.
     */
    @Test
    public void theChipTextIsTheEditorsOwnFont() {
        buildFoldable();
        editor.setFontSize(31f);
        editor.toggleFoldAt(1);
        showEditor();
        showEditor();

        UINode chip = childWithClass(TextEditor.FOLD_PLACEHOLDER_CLASS);
        UIText glyph = (UIText) chip.children().get(0);
        assertEquals("the chip is set in the editor's own size", 31f,
                glyph.getStyle().getGeneralGroup().fontSize(), 0.01f);
    }

    /**
     * <b>Folding must not make the rest of the editor unclickable.</b>
     *
     * <p>The chips first lived in a hit-testable container spanning the whole text area, so every press
     * that was not on a chip landed on that container and the editor never saw it — no caret, no
     * selection, no focus, for as long as anything was folded. Every folding test passed: the chips
     * worked, the rows hid, the model was right. What broke was everything <em>else</em>, which is exactly
     * what a feature's own tests do not look at.</p>
     */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void theEditorStillTakesClicksWhileSomethingIsFolded() {
        buildFoldable();
        UINode beforeHit = hit(screenCentreOf(editor)[0], screenCentreOf(editor)[1]);
        assertNotNull(beforeHit);

        editor.toggleFoldAt(1);
        showEditor();

        // A point in the text area, well clear of the gutter and of the collapsed row's chip.
        UINode line = linesOf().get(0);
        float[] at = screenCentreOf(line);
        UINode hit = hit(at[0], at[1]);

        assertNotNull("a press over the text must reach something", hit);
        assertFalse("and it must not be swallowed by folding furniture: " + hit.classes(),
                hit.hasClass(TextEditor.FOLD_PLACEHOLDER_CLASS) || hit.hasClass(TextEditor.FOLD_CLASS));
        assertSame("the editor itself takes the press, as it does with nothing folded", editor, hit);
    }

    /** And the caret genuinely moves, which is the thing the user actually lost. */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void clickingTheTextMovesTheCaretWhileFolded() {
        buildFoldable();
        // Parked at the far end, because screenCentreOf finds the first point INSIDE the box -- the
        // line's top-left -- which maps to offset 0. Starting there would assert nothing.
        editor.setCaret(editor.getText().length());
        editor.toggleFoldAt(1);
        showEditor();

        UINode line = linesOf().get(0);
        float[] at = screenCentreOf(line);
        UINode hit = hit(at[0], at[1]);
        assertSame(editor, hit);

        var press = new com.crystalgui.ui.event.MouseEvent.Down(hit,
                new com.crystalgui.core.data.ReadOnlyVec2f(new org.joml.Vector2f(at[0], at[1])), 0, 1);
        press.setPhase(com.crystalgui.ui.event.PropagationPhase.TARGET);
        hit.onMouseDown.emitTarget(press);
        showEditor();

        assertTrue("the caret moved to where the press landed",
                editor.getCaret() < editor.getText().length());
    }

    /**
     * <b>The bracket fold chords resolve and act.</b>
     *
     * <p>Pinned separately from the others because they are the two that cannot easily be checked by hand:
     * the debug harness binds bare {@code [} and {@code ]} to uiScale and consumes them before the editor
     * keymap runs, so pressing {@code Ctrl+Shift+[} there proves nothing either way. This asserts the
     * binding itself, through the real resolver.</p>
     */
    @Test
    public void theBracketFoldChordsResolveAndAct() {
        buildFoldable();
        int open = linesOf().size();
        int ctrlShift = com.crystalgraphics.platform.input.CgModifiers.CTRL
                | com.crystalgraphics.platform.input.CgModifiers.SHIFT;

        assertTrue("Ctrl+Shift+[ must resolve",
                pressChord(com.crystalgraphics.platform.input.CgKeyCodes.KEY_LBRACKET, ctrlShift));
        showEditor();
        assertTrue("and must fold the block at the caret", linesOf().size() < open);

        assertTrue("Ctrl+Shift+] must resolve",
                pressChord(com.crystalgraphics.platform.input.CgKeyCodes.KEY_RBRACKET, ctrlShift));
        showEditor();
        assertEquals("and must put it back", open, linesOf().size());
    }

    /** Fold-to-level, the other family a bare-key harness binding could shadow. */
    @Test
    public void theLevelFoldChordsResolve() {
        buildFoldable();
        int ctrlShift = com.crystalgraphics.platform.input.CgModifiers.CTRL
                | com.crystalgraphics.platform.input.CgModifiers.SHIFT;
        assertTrue("Ctrl+Shift+2 must resolve",
                pressChord(com.crystalgraphics.platform.input.CgKeyCodes.KEY_2, ctrlShift));
        showEditor();
        assertTrue("and must fold the inner block",
                editor.foldingModel().getRegionStartingAt(1).isCollapsed());
    }

    /**
     * <b>Fold-all and unfold-all answer to the numpad as well as the top row.</b>
     *
     * <p>The numeric keypad is a different key code, not a different character, so a binding on the top-row
     * key does nothing there. IntelliJ's own collapse-all/expand-all are the numpad pair specifically,
     * which makes it the spelling a user coming from that editor will reach for first.</p>
     */
    @Test
    public void foldAllAndUnfoldAllAnswerToTheNumpadToo() {
        buildFoldable();
        int open = linesOf().size();
        int ctrlShift = com.crystalgraphics.platform.input.CgModifiers.CTRL
                | com.crystalgraphics.platform.input.CgModifiers.SHIFT;

        assertTrue("Ctrl+Shift+NumPad- must resolve",
                pressChord(com.crystalgraphics.platform.input.CgKeyCodes.KEY_SUBTRACT, ctrlShift));
        showEditor();
        assertTrue("and must fold everything", linesOf().size() < open);

        assertTrue("Ctrl+Shift+NumPad+ must resolve",
                pressChord(com.crystalgraphics.platform.input.CgKeyCodes.KEY_ADD, ctrlShift));
        showEditor();
        assertEquals("and must open it all back up", open, linesOf().size());
    }

    /** The top-row spellings keep working — bindAll adds, it does not replace. */
    @Test
    public void theTopRowFoldAllChordsStillWork() {
        buildFoldable();
        int open = linesOf().size();
        int ctrlShift = com.crystalgraphics.platform.input.CgModifiers.CTRL
                | com.crystalgraphics.platform.input.CgModifiers.SHIFT;

        assertTrue(pressChord(com.crystalgraphics.platform.input.CgKeyCodes.KEY_MINUS, ctrlShift));
        showEditor();
        assertTrue(linesOf().size() < open);

        assertTrue(pressChord(com.crystalgraphics.platform.input.CgKeyCodes.KEY_EQUALS, ctrlShift));
        showEditor();
        assertEquals(open, linesOf().size());
    }

    /**
     * <b>Folding keeps the line you are on exactly where it is.</b>
     *
     * <p><b>The caret, not the top of the viewport</b>, and the distinction is the whole bug. The two only
     * differ when rows change ABOVE the caret — which is precisely what folding does. Anchoring the top row
     * holds the first visible line still and lets everything below slide up to meet it, so collapsing the
     * blocks above your cursor walks your line up the screen while the test that watched the top row stayed
     * green. IntelliJ keeps the line under the cursor pinned and lets the top of the viewport move
     * instead.</p>
     *
     * <p>These tests replace an earlier set that asserted the top row was unchanged. They were not wrong
     * about the code — they were wrong about the requirement, and they are rewritten rather than deleted so
     * the reversal is on the record.</p>
     */
    @Test
    public void foldingAboveTheCaretKeepsTheCaretLineStill() {
        buildBlocks(60);
        editor.setCaret(editor.getText().indexOf("void f30()"));
        editor.box().setScroll(0f, 100f * editor.lineHeight());
        showEditor();
        float before = caretScreenY();

        editor.toggleFoldAt(0);
        showEditor();
        showEditor();

        assertEquals("the caret's line did not move", before, caretScreenY(), 1f);
    }

    /** And unfolding above it, the mirror case. */
    @Test
    public void unfoldingAboveTheCaretKeepsTheCaretLineStill() {
        buildBlocks(60);
        editor.toggleFoldAt(0);
        showEditor();
        editor.setCaret(editor.getText().indexOf("void f30()"));
        editor.box().setScroll(0f, 100f * editor.lineHeight());
        showEditor();
        float before = caretScreenY();

        editor.toggleFoldAt(0);
        showEditor();
        showEditor();

        assertEquals("the caret's line did not move", before, caretScreenY(), 1f);
    }

    /** Checked a few frames on, so a deferred scroll would still be caught. */
    @Test
    public void theCaretLineStaysStillOnLaterFramesToo() {
        buildBlocks(60);
        editor.setCaret(editor.getText().indexOf("void f30()"));
        editor.box().setScroll(0f, 100f * editor.lineHeight());
        showEditor();
        float before = caretScreenY();

        editor.toggleFoldAt(0);
        showEditor();
        showEditor();
        showEditor();

        assertEquals("still unchanged three frames on", before, caretScreenY(), 1f);
    }

    /**
     * <b>Fold-all keeps the line you are on where it is.</b>
     *
     * <p>The reported case, and the one the top-row anchor could never satisfy: collapsing every block
     * above the caret removes most of the rows between it and the top of the file, so holding the top row
     * still necessarily drags the caret up. Reproduced with the caret two thirds of the way down.</p>
     */
    @Test
    public void foldAllKeepsTheCaretLineStill() {
        buildBlocks(60);
        editor.setCaret(editor.getText().indexOf("void f40()"));
        editor.box().setScroll(0f, 140f * editor.lineHeight());
        showEditor();
        float before = caretScreenY();

        editor.foldAll();
        showEditor();
        showEditor();

        assertTrue("the editor must not go blank", linesOf().size() > 0);
        assertEquals("the caret's line did not move", before, caretScreenY(), 1f);
    }

    /** With soft wrap on, where a row occupies several view lines. */
    @Test
    public void theCaretLineStaysStillWithSoftWrap() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            document.append("void f").append(i).append("() {").append(NL)
                    .append("    ").append("x".repeat(200)).append(";").append(NL)
                    .append("    b();").append(NL).append("}").append(NL);
        }
        build(document.toString());
        editor.setSoftWrap(true);
        showEditor();
        showEditor();

        editor.setCaret(editor.getText().indexOf("void f20()"));
        editor.box().setScroll(0f, 60f * editor.lineHeight());
        showEditor();
        float before = caretScreenY();

        editor.toggleFoldAt(0);
        showEditor();
        showEditor();

        assertEquals("the caret's line did not move", before, caretScreenY(), 1f);
    }

    /** At a large font size, which is how it was reported. */
    @Test
    public void theCaretLineStaysStillZoomedIn() {
        buildBlocks(60);
        editor.setFontSize(27f);
        showEditor();
        showEditor();

        editor.setCaret(editor.getText().indexOf("void f30()"));
        editor.box().setScroll(0f, 100f * editor.lineHeight());
        showEditor();
        float before = caretScreenY();

        editor.toggleFoldAt(0);
        showEditor();
        showEditor();

        assertEquals("the caret's line did not move", before, caretScreenY(), 1f);
    }

    /**
     * <b>Zooming keeps the line you were on.</b>
     *
     * <p>{@code scrollTop} is a PIXEL count, so leaving it alone across a font change silently
     * reinterprets it: 440px is line 44 at a ten-pixel line and line 7 at sixty. Zooming in from line 44
     * used to land the viewport on line 5.</p>
     *
     * <p>VS Code's {@code StableViewport}, ported: capture the <b>model</b> position of the viewport's
     * first line, recover it afterwards. A model position and not a view line, because the font change
     * also reprojects — with soft wrap on, the same text occupies a different number of view lines
     * afterwards.</p>
     */
    @Test
    public void zoomingKeepsTheTopLineInPlace() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        showEditor();

        editor.box().setScroll(0f, 44f * editor.lineHeight());
        showEditor();
        int topRowBefore = editor.rowAtTopOfViewport();

        editor.setFontSize(editor.getFontSize() * 3f);
        showEditor();

        assertEquals("the same row must still be at the top after a 3x zoom",
                topRowBefore, editor.rowAtTopOfViewport());
    }
}
