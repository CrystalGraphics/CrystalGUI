package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.editor.TextEditor;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * <b>An editor whose height comes from its parent must still settle.</b>
 *
 * <p>Scrollbar sizing is mutually recursive by construction: a bar's presence changes the viewport, and
 * the viewport decides the bar. With a definite height the two converge and nobody notices. With a
 * <em>parent-derived</em> height nothing pins the viewport, the answers never agree, and
 * {@code calculateLayout}'s {@code while (isLayoutDirty())} never exits — the window hangs on the first
 * layout of that page with a stack that is pure Taffy and names nothing in {@code TextEditor}.</p>
 *
 * <p>Measured before the fix: a fixed size or {@code height: 300px} was fine at ~4 ms a frame;
 * {@code height: 100%} and {@code height: 0; flex-grow: 1} both hung outright. Those two shapes are what
 * this file is.</p>
 *
 * <p>Every test carries a <b>timeout</b>, because the failure is a hang and no assertion can catch one.</p>
 *
 * <p><b>These do NOT reproduce the original hang.</b> Reverting the latch and running them passes, so they
 * pin that these shapes settle rather than proving why the real one did not — the live case also had a
 * GraphView in the sibling pane and a preview system mutating the tree, and neither is reconstructible
 * here without a GL context. Kept because the shapes are the documented failure modes and a future change
 * that breaks them should say so; not to be mistaken for coverage of the bug they are named after.
 */
public class EditorIndefiniteHeightTest extends UiTestBase {

    private static final String TEXT =
            "public class Main {\n    void go() {\n        // a fairly long line so the content is wider "
                    + "than the viewport and a horizontal bar is genuinely wanted\n    }\n}\n";

    private static UIWindow windowOver(UIElement root) {
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        return window;
    }

    private static void settle(UIWindow window) {
        for (int i = 0; i < 6; i++) window.updateWithoutPainting();
    }

    /** {@code height: 100%} against a parent that has no definite height of its own. */
    @Test(timeout = 20_000)
    public void percentHeightAgainstAnIndefiniteParentSettles() {
        TextEditor editor = new TextEditor(TEXT);
        editor.layout(l -> l.widthPercent(100f).heightPercent(100f));

        UIElement indefinite = new UIElement().layout(l -> l.width(400)
                .flexDirection(FlexDirection.COLUMN));
        indefinite.addChild(editor);

        UIElement root = new UIElement().layout(l -> l.width(500)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(indefinite);

        UIWindow window = windowOver(root);
        settle(window);
        assertTrue(editor.getText().length() > 0);
    }

    /** {@code height: 0; flex-grow: 1} — the other row of the table, and the one a dock pane produces. */
    @Test(timeout = 20_000)
    public void growToFillSettles() {
        TextEditor editor = new TextEditor(TEXT);
        editor.layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));

        UIElement column = new UIElement().layout(l -> l.width(400).height(300)
                .flexDirection(FlexDirection.COLUMN));
        column.addChild(editor);

        UIWindow window = windowOver(column);
        settle(window);
        assertTrue(editor.getText().length() > 0);
    }

    /**
     * The shape that actually shipped broken: an editor filling one pane of a {@code SplitView} whose own
     * height is grown rather than given.
     */
    @Test(timeout = 20_000)
    public void anEditorFillingASplitPaneSettles() {
        TextEditor editor = new TextEditor(TEXT);
        editor.layout(l -> l.widthPercent(100f).heightPercent(100f));

        SplitView split = new SplitView();
        split.setPercentage(80f);
        split.setLimits(20f, 95f);
        split.first().addChild(new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)));
        split.second().addChild(editor);
        split.layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));

        UIElement wrapper = new UIElement().layout(l -> l.widthPercent(100f)
                .flexGrow(1f).flexBasis(0).flexDirection(FlexDirection.COLUMN));
        wrapper.addChild(split);

        UIElement pane = new UIElement().layout(l -> l.width(900).height(600)
                .flexDirection(FlexDirection.COLUMN));
        pane.addChild(wrapper);

        UIWindow window = windowOver(pane);
        settle(window);
        assertTrue(editor.getText().length() > 0);
    }

    /** The rows of the table that always worked, kept so a fix cannot break the case that was fine. */
    @Test(timeout = 20_000)
    public void aDefiniteHeightStillSettles() {
        TextEditor editor = new TextEditor(TEXT);
        editor.layout(l -> l.width(400).height(300));

        UIElement root = new UIElement().layout(l -> l.width(500).height(400));
        root.addChild(editor);

        UIWindow window = windowOver(root);
        settle(window);
        assertTrue(editor.getText().length() > 0);
    }
}
