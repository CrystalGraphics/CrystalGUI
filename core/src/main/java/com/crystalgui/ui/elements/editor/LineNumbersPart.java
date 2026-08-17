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
    private final List<UIElement> numbers = new ArrayList<>();

    LineNumbersPart(TextEditor editor, UIElement gutter) {
        super(editor);
        this.gutter = gutter;
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        if (!editor.isGutterVisible()) {
            DecorationPool.hide(gutter);
            for (UIElement number : numbers) DecorationPool.hide(number);
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
        final float width = editor.paddingLeft() + editor.gutterWidth();
        final float gutterHeight = editor.getClientHeight();
        StyleGroup.defaultPipeline(gutter.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0f).top(0f).width(width).height(gutterHeight));

        float height = editor.lineHeight();
        int used = 0;
        int last = Math.min(lastViewLine, editor.viewLineCount() - 1);
        for (int viewLine = Math.max(0, firstViewLine); viewLine <= last; viewLine++) {
            // ONE NUMBER PER DOCUMENT ROW, on the row's first view line. Numbering every view line would
            // report line counts the file does not have, and repeating the row's number down its
            // continuations reads as the editor being stuck. Both are what a blank continuation avoids.
            ProjectedLines.ModelPosition model = editor.modelAt(viewLine);
            if (model.viewLineInRow() != 0) continue;
            int row = model.row();
            UIElement number = numberAt(used++);
            ((UIText) number.getChildren().get(0)).setText(String.valueOf(row + 1));
            StyleGroup.importantPipeline(number.getChildren().get(0).getStyle().getGeneralGroup(),
                    g -> g.fontSize(editor.getStyle().getGeneralGroup().fontSize())
                            .fontFamily(editor.getStyle().getGeneralGroup().fontFamily()));
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
        for (int i = used; i < numbers.size(); i++) DecorationPool.hide(numbers.get(i));
        insetHorizontalBarPastGutter();
    }

    private UIElement numberAt(int index) {
        while (numbers.size() <= index) {
            UIElement number = new UIElement();
            number.addClass(TextEditor.LINE_NUMBER_CLASS);
            number.setHitTest(false);
            number.markAsInternal();
            number.addChild(new UIText(""));
            gutter.addInternalChild(number);
            numbers.add(number);
        }
        return numbers.get(index);
    }

    /**
     * Starts the horizontal scrollbar after the gutter rather than under it.
     *
     * <p>The gutter is pinned and does not scroll horizontally, so a bar running beneath it offers to
     * scroll something that will not move.</p>
     *
     * <p>Written at {@code IMPORTANT} origin because {@code ScrollerView} rewrites the bar's geometry
     * every frame from {@code refreshScrollers}; a lower-origin write would simply lose to it.</p>
     */
    private void insetHorizontalBarPastGutter() {
        UIElement bar = editor.horizontalScrollerElement();
        if (bar == null) return;
        final float left = editor.paddingLeft() + editor.gutterWidth();
        final float width = Math.max(0f,
                editor.getClientWidth() - left - editor.verticalBarThickness());
        StyleGroup.importantPipeline(bar.getStyle().getLayoutGroup(),
                l -> l.left(left).width(width));
    }
}
