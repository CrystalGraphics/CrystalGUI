package com.crystalgui.ui.dom;

import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticTag;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.TextEditor;
// The HIGHLIGHT range type, not the document one -- a highlight addresses one view line's UIText.
import com.crystalgui.ui.text.TextRange;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link DiagnosticTag#UNNECESSARY} reaching the text as a highlight rather than an underline.
 *
 * <p>The distinction the tag exists to draw: "this import is never used" is not a lesser warning, it is
 * a different <em>kind</em> of statement, so it fades the text instead of marking it. Every reference
 * implementation does this, and it is what lets them report six kinds of unused thing without a file
 * looking broken.</p>
 *
 * <p>Asserted on the <b>published highlight range</b> rather than on a colour, because the colour is the
 * scheme's and the range is ours. A test that read the resolved colour would be testing
 * {@code dark-plus.css}; this one fails if the wiring is absent, which is the failure that would
 * otherwise be invisible — the tag would be produced, carried, and quietly never drawn.</p>
 */
public class UnnecessaryTagTest extends UiDocumentTestBase {

    private static final String UNNECESSARY = "unnecessary";

    private TextEditor editor;

    private TextEditor build(String text) {
        editor = new TextEditor(text);
        editor.layout(l -> l.width(300).height(160));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));

        UINode root = new UINode().layout(l -> l.width(300).height(200));
        root.append(editor);
        document.append(root);
        // The user-agent sheet, because the rule that fades an unnecessary range lives in it -- without
        // it this would assert against a widget with no stylesheet at all.
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        settle();
        return editor;
    }

    private void settle() {
        for (int i = 0; i < 3; i++) frame();
    }

    /** Every {@code unnecessary} range published on any realised line, read back out of the tree. */
    private List<TextRange> published() {
        List<TextRange> found = new ArrayList<>();
        collect(editor, found);
        return found;
    }

    private static void collect(UINode element, List<TextRange> out) {
        if (element instanceof UIText) {
            out.addAll(((UIText) element).highlights().get(UNNECESSARY));
        }
        for (UINode child : element.children()) collect(child, out);
    }

    private static Diagnostic unusedAt(int row, int fromColumn, int toColumn) {
        return Diagnostic.warning(new TextPoint(row, fromColumn), new TextPoint(row, toColumn), "unused")
                .withTags(DiagnosticTag.UNNECESSARY);
    }

    @Test
    public void anOrdinaryWarningPublishesNothing() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(
                Diagnostic.warning(new TextPoint(1, 0), new TextPoint(1, 4), "plain")));
        settle();
        assertTrue("only a tagged diagnostic fades text", published().isEmpty());
    }

    @Test
    public void aTaggedDiagnosticFadesExactlyItsRange() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(unusedAt(1, 0, 4)));
        settle();

        List<TextRange> ranges = published();
        assertEquals("one tagged diagnostic, one faded range: " + ranges, 1, ranges.size());
        // Line-relative, because a highlight addresses the UIText of one view line -- see refreshHighlights.
        assertEquals(TextRange.of(0, 4), ranges.get(0));
    }

    /**
     * <b>The fade follows the text through an edit.</b>
     *
     * <p>The same rule the squiggles are built on: offsets come from the tracked lane, not from the
     * diagnostic's row/column. Read from the diagnostic they would be right only at the instant the
     * analysis landed, so a moment's typing above would leave the fade over whatever moved into those
     * offsets — and it corrects itself on the next compile, which is what makes it read as the analyser
     * lagging rather than as a broken mark.</p>
     */
    @Test
    public void theFadeMovesWithTheTextItIsAbout() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(unusedAt(1, 0, 4)));
        settle();
        assertEquals(TextRange.of(0, 4), published().get(0));

        editor.setSelection(0, 0);
        editor.insertAtCaret("XX");
        settle();

        List<TextRange> ranges = published();
        assertEquals("the range must survive an edit above it: " + ranges, 1, ranges.size());
        assertEquals("still over 'beta', not over whatever the offsets now name",
                TextRange.of(0, 4), ranges.get(0));
    }

    /**
     * A range whose text was deleted fades nothing.
     *
     * <p>The distinction {@code TrackedRange.collapsedByEdit} exists for: a diagnostic that was born
     * zero-width is a real mark, and one that collapsed because its word was deleted would otherwise be
     * widened into a fade over whatever innocent text moved into its place.
     */
    @Test
    public void aRangeWhoseTextWasDeletedFadesNothing() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(unusedAt(1, 0, 4)));
        settle();
        assertFalse(published().isEmpty());

        editor.setSelection(6, 10);              // "beta", the whole tagged word
        editor.insertAtCaret("");
        settle();

        assertTrue("nothing is left to fade", published().isEmpty());
    }
}
