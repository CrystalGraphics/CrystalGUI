package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.view.IndentLevels;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * One vertical line per indent level, on every visual row.
 *
 * <p>Ported in shape from {@code browser/viewParts/indentGuides/indentGuides.ts} (MIT). Placed at whole
 * multiples of the indent width rather than at measured text, because a guide marks a <b>level</b> and
 * must sit at the same x on a blank line as on the code around it — exactly the case {@link IndentLevels}
 * exists to answer, and where a guide derived from a row's own characters would have nothing to derive
 * from.</p>
 */
final class IndentGuidesPart extends EditorViewPart {

    /**
     * Cap on guide elements.
     *
     * <p>Each is a Taffy node, and a deeply indented wide file is thousands of them — VS Code has
     * {@code stopRenderingLineAfter} for the same reason. The cap degrades the decoration rather than the
     * editor, which is the right way round: guides missing off the far edge of a very long line are barely
     * noticeable, and a layout pass that takes a second is not.</p>
     */
    private static final int MAX_GUIDES = 512;

    private final DecorationPool pool;

    IndentGuidesPart(TextEditor editor) {
        super(editor);
        this.pool = new DecorationPool(editor::textViewport, TextEditor.INDENT_GUIDE_CLASS, false);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        if (!editor.isIndentGuidesVisible() || lastViewLine < firstViewLine) {
            pool.hideAll();
            return;
        }
        float advance = editor.spaceAdvance();
        if (!(advance > 0f)) {
            pool.hideAll();
            return;
        }

        int firstLine = Math.max(0, firstViewLine);
        int lastLine = Math.min(lastViewLine, editor.viewLineCount() - 1);
        if (lastLine < firstLine) {
            pool.hideAll();
            return;
        }

        float height = editor.lineHeight();
        float step = advance * Math.max(1, editor.getIndentWidth());

        // ONE call for the whole visible RANGE. Asking row by row -- guidesFor(doc, row, row, ...) --
        // threw away the carry-forward the algorithm is built around: every blank row restarted the search
        // for the nearest content line above and below and rescanned the document, once per row and once
        // per frame. The range form is what the port was written for.
        int firstRow = editor.modelAt(firstLine).row();
        int lastRow = editor.modelAt(lastLine).row();
        int[] levelsByRow = IndentLevels.guidesFor(editor.buffer().document(), firstRow, lastRow,
                editor.getIndentWidth(), editor.getTabSize(), editor.isOffSideLanguage());

        // WHICH BLOCK THE CARET IS IN, once for the whole pass. Bounded by the visible rows, so a long
        // file costs the viewport rather than the document -- the same reason guidesFor takes a range.
        //
        // Scoped from the CARET and not from the pointer: it answers "where am I editing", which is the
        // question the current-line band answers too, and hovering must not move it.
        IndentLevels.ActiveGuide active = IndentLevels.activeGuideFor(editor.buffer().document(),
                editor.buffer().offsetToPoint(editor.getCaret()).row(), firstRow, lastRow,
                editor.getIndentWidth(), editor.getTabSize(), editor.isOffSideLanguage());

        final float pad = editor.codeLeftPad();
        pool.beginPass();
        for (int viewLine = firstLine; viewLine <= lastLine; viewLine++) {
            int row = editor.modelAt(viewLine).row();
            int levels = levelsByRow[Math.max(0, Math.min(row - firstRow, levelsByRow.length - 1))];
            final float top = editor.topOfViewLine(viewLine);

            // Half the CODE MARGIN left of the indent stop -- never half a space. A space is wider than
            // the margin, so nudging by one put the level-0 guide underneath the gutter, which has a
            // higher z-index and painted straight over it: the guides simply vanished from every
            // unindented row. Half the margin keeps the guide in the gap that exists for it.
            final float nudge = pad * 0.5f;
            // FROM LEVEL 1. Level 0 is not an indent guide at all -- it is the gutter's right edge, drawn
            // once for the whole viewport by GutterEdgePart. Drawing it per row was what made it break at
            // every unindented line: such a line has zero guides, so the run stopped wherever the code
            // reached column 0, and the letter there looked like it had cut the line.
            for (int level = 1; level < levels && pool.used() < MAX_GUIDES; level++) {
                final float left = pad + level * step - nudge - editor.getScrollLeft();
                // Past the right edge there is nothing to guide, and the elements are better spent on
                // rows that are visible.
                if (left > editor.textViewportWidth()) break;
                UIElement guide = pool.next();
                // Pooled, so the class has to be re-decided every frame rather than set once: this
                // element described a different row and level a moment ago. addClass/removeClass no-op on
                // an unchanged set, so a frame where nothing moved writes nothing.
                if (active.covers(row, level)) guide.addClass(TextEditor.ACTIVE_GUIDE_CLASS);
                else guide.removeClass(TextEditor.ACTIVE_GUIDE_CLASS);
                StyleGroup.defaultPipeline(guide.getStyle().getLayoutGroup(),
                        l -> l.positionType(TaffyPosition.ABSOLUTE)
                                .left(left).top(top).width(1f).height(height));
            }
        }
        pool.endPass();
    }
}
