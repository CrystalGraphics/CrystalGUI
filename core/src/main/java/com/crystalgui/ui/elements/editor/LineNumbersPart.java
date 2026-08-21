package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.wrap.ProjectedLines;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * The gutter box and one number per visible row.
 *
 * <p>Monaco splits this across {@code browser/viewParts/margin/} (the strip itself) and
 * {@code browser/viewParts/lineNumbers/} (the digits). They are one part here because our gutter's width
 * is computed <em>from</em> the digits — the number column, the fold column and the margins are a single
 * measurement — so splitting them would put the two halves of one arithmetic in different files.</p>
 *
 * <h3>Scroll-exempt, with the numbers positioned by hand</h3>
 * <p>The gutter must hold still horizontally while scrolling vertically with the text, and a scroll offset
 * in this engine is a pose translate applied to every non-exempt child — it cannot apply on one axis only.
 * So the gutter opts out of both and subtracts {@code scrollTop} itself. Letting it scroll normally would
 * slide the numbers sideways the moment a line is wider than the viewport.</p>
 */
final class LineNumbersPart extends EditorViewPart {

    private final UIElement gutter;
    /**
     * The numbers, pooled — {@link DecorationPool} is this idiom, and this part hand-rolled it beside
     * the class that names it.
     */
    private final DecorationPool numbers;

    LineNumbersPart(TextEditor editor, UIElement gutter) {
        super(editor);
        this.gutter = gutter;
        // Into the GUTTER'S scroll layer, not the gutter: the numbers follow the rows, so they are
        // positioned in document coordinates and moved by one transform. @see TextEditor#linesLayer()
        this.numbers = new DecorationPool(editor::gutterLayer, TextEditor.LINE_NUMBER_CLASS, true);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        if (!editor.isGutterVisible()) {
            DecorationPool.hide(gutter);
            numbers.hideAll();
            return;
        }
        // FROM THE EDITOR'S EDGE, not from past its padding. The editor's own padding-left is a strip the
        // gutter did not cover and scrolled text painted into, visible as fragments to the LEFT of the
        // gutter when zoomed in. The gutter's width absorbs it; the numbers carry it themselves below so
        // they stay where they were.
        //
        // FULL CLIENT HEIGHT. It used to stop at the viewport so it would not paint over the horizontal
        // bar's left end -- and that left the corner between the two uncovered, which scrolled text showed
        // through. The bars now sit ABOVE the gutter in z, so there is nothing left to paint over: the
        // gutter can run the whole height and the bar draws on top of its bottom edge.
        DecorationPool.show(gutter);
        final float width = editor.paddingLeft() + editor.gutterWidth();
        final float gutterHeight = editor.getClientHeight();
        StyleGroup.defaultPipeline(gutter.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0f).top(0f).width(width).height(gutterHeight));

        float height = editor.lineHeight();
        numbers.beginPass();
        int last = Math.min(lastViewLine, editor.viewLineCount() - 1);
        for (int viewLine = Math.max(0, firstViewLine); viewLine <= last; viewLine++) {
            // ONE NUMBER PER DOCUMENT ROW, on the row's first view line. Numbering every view line would
            // report line counts the file does not have, and repeating the row's number down its
            // continuations reads as the editor being stuck. Both are what a blank continuation avoids.
            ProjectedLines.ModelPosition model = editor.modelAt(viewLine);
            if (model.viewLineInRow() != 0) continue;
            int row = model.row();
            UIElement number = numbers.next();
            ((UIText) number.getChildren().get(0)).setText(numberFor(row));
            editor.pushEditorFontTo(number.getChildren().get(0));
            // Scroll-exempt, so the offset has to be subtracted by hand -- see the class note.
            final float top = editor.topOfViewLine(viewLine);
            // The NUMBERS' column, not the whole gutter. Spanning the full width right-aligns the digits
            // against the code instead of against the fold column, which is what put them a few pixels
            // from the first glyph.
            //
            // The inset is applied BY HAND. An absolute inset here turns out to be border-box relative,
            // not padding-box -- left(0) put the digits exactly on the gutter's border, which is the
            // "numbers kissing the edge" report. The value still comes from the sheet; only the placing is
            // the widget's.
            //
            // The width comes from the CACHED measurement and is never recomputed here. gutterWidth is a
            // field updated only when it moves, so measuring the digits afresh at layout time meant the
            // two could disagree on any frame where the font had not resolved: the numbers got a
            // zero-width box and every one of them piled up in the same place.
            final float numberLeft = editor.paddingLeft() + editor.gutterPadLeft();
            final float numberWidth = editor.gutterNumbersWidth();
            StyleGroup.defaultPipeline(number.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE)
                            .left(numberLeft).top(top).width(numberWidth).height(height));
        }
        numbers.endPass();
    }

    /**
     * What this row's number reads as — absolute, or the distance from the caret.
     *
     * <h3>The caret's own row keeps its ABSOLUTE number</h3>
     *
     * <p>Which is what makes relative numbering usable rather than merely clever: {@code 12j} needs the
     * distances, and "which line am I on" needs the number, and a column of relative numbers with a zero
     * in the middle answers only the first. Vim's {@code number relativenumber} pair does exactly this and
     * VS Code's {@code lineNumbers: "relative"} follows it.</p>
     *
     * <p>The distance is measured in <b>document rows</b>, not view lines: a motion key moves by lines of
     * the file, so counting the halves of a wrapped row would print a number that no keystroke matches.</p>
     */
    private String numberFor(int row) {
        if (!editor.isRelativeLineNumbers()) return String.valueOf(row + 1);
        int caretRow = editor.buffer().offsetToPoint(editor.getCaret()).row();
        return row == caretRow ? String.valueOf(row + 1) : String.valueOf(Math.abs(row - caretRow));
    }

}
