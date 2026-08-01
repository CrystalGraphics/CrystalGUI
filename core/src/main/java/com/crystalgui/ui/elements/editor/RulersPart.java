package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * A vertical rule at each configured column — VS Code's {@code editor.rulers}.
 *
 * <p>Ported from {@code browser/viewParts/rulers/rulers.ts} (MIT), with one divergence: Monaco spans each
 * rule the whole {@code scrollHeight}, while these span the viewport. Both are right for their own
 * scrolling model — Monaco's rulers ride inside the scrolled content, ours are scroll-exempt chrome, so
 * a document-tall rule would simply extend past the box it is drawn in.</p>
 *
 * <p>Full viewport height and scroll-exempt: a ruler marks a <em>column</em>, which does not move when the
 * document scrolls vertically. It does move when the document scrolls sideways, which is why the
 * horizontal offset is subtracted and the vertical one is not.</p>
 */
final class RulersPart extends EditorViewPart {

    private final DecorationPool pool;

    RulersPart(TextEditor editor) {
        super(editor);
        this.pool = new DecorationPool(editor::textViewport, TextEditor.RULER_CLASS, false);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        int[] columns = editor.rulerColumns();
        if (columns.length == 0) {
            pool.hideAll();
            return;
        }
        float advance = editor.spaceAdvance();
        if (!(advance > 0f)) {
            pool.hideAll();
            return;
        }

        final float height = editor.viewportHeight();
        final float pad = editor.codeLeftPad();
        pool.beginPass();
        for (int column : columns) {
            if (column <= 0) continue;
            final float left = pad + column * advance - editor.getScrollLeft();
            if (left < pad || left > editor.textViewportWidth()) continue;
            UIElement rule = pool.next();
            StyleGroup.defaultPipeline(rule.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE)
                            .left(left).top(0f).width(1f).height(height));
        }
        pool.endPass();
    }
}
