package com.crystalgui.widget.texteditor.part;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.texteditor.DiffDecorations;
import com.crystalgui.widget.texteditor.TextEditor;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code »} chevrons that push one side of a difference onto the other.
 *
 * <p>Shaped after {@code DiffGutterOperation} / {@code DiffGutterRenderer} in
 * <a href="https://github.com/JetBrains/intellij-community">JetBrains/intellij-community</a>, Apache 2.0,
 * and {@code features/revertButtonsFeature.ts} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>, MIT. <b>Modified:</b> both hosts hang
 * these off a gutter-renderer abstraction this engine has no equivalent of, so they are placed directly,
 * the way every other decoration here is.</p>
 *
 * <h3>Why these are a view part and not the diff view's own overlay</h3>
 *
 * <p>They have to sit at a <em>row</em>, and everything that knows where a row is — the projection, the
 * scroll offset, the fold state, the screen origin — lives in the editor. An overlay outside it would have
 * to be handed all four and would drift from the text the moment any of them changed. That is the same
 * reason the fold arrows are a part rather than something the workbench draws.</p>
 *
 * <h3>Hit-testable, so not a {@link DecorationPool}</h3>
 *
 * <p>Every other decoration here is {@code setHitTest(false)} — a band that swallowed clicks would stop the
 * caret being placed through it. A chevron is the one decoration that <em>is</em> a control, so it keeps
 * its own pool.</p>
 */
public final class DiffChevronPart extends EditorViewPart {

    private final List<UINode> chevrons = new ArrayList<>();
    /** Which difference each pooled chevron currently stands for, parallel to {@link #chevrons}. */
    private final List<Integer> bandOf = new ArrayList<>();

    public DiffChevronPart(TextEditor editor) {
        super(editor);
    }

    @Override
    public void render(int firstViewLine, int lastViewLine) {
        int used = 0;
        if (editor.diffRevertHandler() != null && !editor.diffDecorations().isEmpty()
                && hasWindow(firstViewLine, lastViewLine)) {
            List<DiffDecorations.Band> bands = editor.diffDecorations().bands();
            for (int i = 0; i < bands.size(); i++) {
                if (place(bands.get(i), i, used, firstViewLine, lastViewLine)) used++;
            }
        }
        for (int i = used; i < chevrons.size(); i++) DecorationPool.hide(chevrons.get(i));
    }

    private boolean place(DiffDecorations.Band band, int index, int slot,
            int firstViewLine, int lastViewLine) {
        int row = Math.min(Math.max(band.fromLine(), 0),
                Math.max(editor.buffer().document().lineCount() - 1, 0));
        int viewLine = editor.projections().firstViewLineOfRow(row);
        if (viewLine < firstViewLine || viewLine > lastViewLine) return false;

        UINode chevron = chevronAt(slot);
        bandOf.set(slot, index);
        DecorationPool.show(chevron);

        final float height = editor.lineHeight();

        // INSIDE the gutter, in the column the gutter reserved for it -- not floating over the code, so
        // the gutter's own edge separates the control from the text it acts on and a long line cannot run
        // underneath it.
        //
        // SMALLER THAN ITS COLUMN, and pushed to the far side of it so it sits against the numbers. The
        // icon fills whatever box it is given, so the box IS the icon's size: a full-row square reads as
        // a button rather than as a mark, and one left at the column's near edge reads as belonging to
        // the code instead of to the line beside it.
        final float column = Math.max(1f, editor.gutterChevronWidth());
        // Just under three quarters of a row. The mark is a STROKED icon, and small strokes are where a
        // rasteriser runs out of samples -- the arrows read as broken rather than as small well before
        // they read as too big.
        final float size = Math.max(8f, height * 0.72f);
        // HARD AGAINST THE NUMBERS, which means reaching INTO the gutter's own left padding rather than
        // stopping at the edge of the reserved column. That padding exists to keep the widest number off
        // the gutter's border; nothing is drawn in it, and leaving the chevron on the far side of it put
        // a whole character's gap between the control and the line it acts on.
        final float left = Math.max(editor.gutterLeft(),
                editor.gutterLeft() + column + editor.gutterPadLeft() - size + 1f);
        final float top = editor.screenTopOfViewLine(viewLine) + (height - size) / 2f;

        StyleGroup.defaultPipeline(chevron.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).top(top).width(size).height(size));
        return true;
    }

    private UINode chevronAt(int slot) {
        while (chevrons.size() <= slot) {
            UINode chevron = new UINode();
            chevron.addClass(TextEditor.DIFF_CHEVRON_CLASS);
            // SCROLL-EXEMPT, like the gutter and the fold column it sits between. It is a child of the
            // EDITOR, which scrolls its own children, while its y comes from screenTopOfViewLine -- which
            // already has the scroll in it. Without this the offset is applied twice and the chevrons
            // slide away at double speed, vanishing after a row or two of scrolling while looking
            // perfectly placed at the top of the document.
            chevron.setScrollExempt(true);
            // NO TEXT CHILD: the mark is icons/general/action/doubleArrowRight.svg, applied as a
            // background by the sheet. Spelling it as a glyph would depend on the bundled font having
            // U+00BB, and MinecraftRegular.otf is already known to be missing characters that look
            // safe -- a missing one draws tofu rather than failing.
            // READ AT PRESS TIME, never captured. These are pooled and a listener may only be attached
            // once, so a chevron that closed over its band index would keep reverting whatever difference
            // its slot was first used for -- and would keep working right up until something scrolled.
            // The same trap the editor's own gutter arrows document.
            final int slotIndex = chevrons.size();
            chevron.onMouseDown.attachListener((element, event) -> {
                int band = bandOf.get(slotIndex);
                if (band >= 0 && editor.diffRevertHandler() != null) {
                    editor.diffRevertHandler().accept(band);
                    // The press must not also place the caret behind the control it landed on.
                    event.stopPropagation();
                }
            }, false, false);
            // ON THE EDITOR, not on the text viewport, and the viewport was wrong for two independent
            // reasons. It is CLIPPED to the code area -- which mirrored stops before the gutter, so a
            // chevron in the gutter's own column was cut away entirely. And it is
            // `setHitTest(false)`, which applies to the whole SUBTREE, so the chevron was never
            // clickable even while it was visible: it looked like a control and was a picture.
            editor.append(chevron);
            chevrons.add(chevron);
            bandOf.add(-1);
        }
        return chevrons.get(slot);
    }
}
