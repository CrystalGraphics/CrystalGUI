package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.view.RenderWhitespace;
import com.crystalgui.ui.elements.editor.TextEditor;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * P6.1.7b — a frame must cost what is <b>on screen</b>, not what is in the document.
 *
 * <h3>Why a ratio and not a millisecond budget</h3>
 * <p>An absolute timing assertion is a flaky test on somebody else's machine. A <em>ratio</em> between two
 * documents measured back to back on the same machine in the same JVM is not: virtualisation means the
 * editor realises a screenful either way, so the large document should cost about the same as the small
 * one. It is the shape of the cost that is being pinned, and that is exactly the shape that broke.</p>
 *
 * <h3>What broke</h3>
 * <p>The harness went unresponsive after the view decorations landed, and two of the three causes were
 * per-frame work that scaled with the document rather than the viewport:</p>
 * <ul>
 *   <li>Indent guides asked {@code IndentLevels.guidesFor(doc, row, row, ...)} <b>once per visible
 *       row</b>. That form throws away the carry-forward the algorithm is built around, so every blank
 *       row restarted the search for the nearest content line above and below and rescanned the
 *       document — once per row, once per frame.</li>
 *   <li>{@code textOriginX()} read three values out of the cascade, and it is called for every line,
 *       guide, marker, caret and band. Measured at <b>78 style lookups per frame</b> on a 32-line
 *       document with every decoration switched off.</li>
 * </ul>
 */
public class EditorFrameCostTest extends UiTestBase {

    private TextEditor editor;
    private UIWindow window;

    private void build(int rows) {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            // Blank lines on purpose: they are the expensive case for indent guides, because a blank line
            // has no indent of its own and has to find the content lines either side of it.
            if (i % 4 == 3) document.append('\n');
            else document.append("    private static final int VALUE_").append(i).append(" = ").append(i).append(";\n");
        }
        editor = new TextEditor(document.toString());
        editor.layout(l -> l.width(400).height(300));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        editor.setIndentGuidesVisible(true);
        editor.setRenderWhitespace(RenderWhitespace.BOUNDARY);

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        settleFrames(5);
    }

    private void settleFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            editor.updateWindow();
            window.updateWithoutPainting();
        }
    }

    /** Steady-state nanoseconds per frame, after a warm-up long enough to be past class loading. */
    private long nanosPerFrame(int frames) {
        settleFrames(200);
        long start = System.nanoTime();
        settleFrames(frames);
        return (System.nanoTime() - start) / frames;
    }

    @Test
    public void aFrameCostsWhatIsOnScreenNotWhatIsInTheDocument() {
        build(40);
        long small = nanosPerFrame(300);

        build(4000);
        long large = nanosPerFrame(300);

        // Deliberately generous. The point is to catch work that scales with the DOCUMENT — a hundredfold
        // more rows here — not to police a few per cent. Before the fix this ratio was unbounded.
        assertTrue("a 4000-row document costs " + (large / 1000) + "us/frame against "
                        + (small / 1000) + "us/frame for 40 rows: per-frame work is scaling with the "
                        + "document, not the viewport",
                large < small * 8L + 200_000L);
    }
}
