package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.fold.FoldingRegions;
import com.crystalgui.text.wrap.ProjectedLines;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * Folding's two on-screen controls: the gutter arrows, and the {@code {...}} chip on a collapsed header.
 *
 * <p>Kept as one part because VS Code keeps them as one too — {@code contrib/folding/browser/
 * foldingDecorations.ts} (MIT) owns both the margin affordance and the collapsed-region decoration, and
 * they share a single question: which regions start on a visible row, and are they open.</p>
 *
 * <h3>A pooled control's row is read per frame, never captured in its listener</h3>
 * <p>Both pools recycle as the view scrolls, and a listener may only be attached <b>once</b>, at creation,
 * or every frame would add another to the same element. So each listener captures its immutable
 * <em>slot index</em> and reads the row from a parallel list this pass rewrites. Capturing the row itself
 * freezes the control on whatever row its slot was first used for — and keeps working for exactly as long
 * as nobody scrolls.</p>
 *
 * <h3>A retired control must stop answering, not merely stop showing</h3>
 * <p>{@link DecorationPool#hide} zeroes the box, and a zero-sized element is still hit-testable at its
 * origin — so an arrow left pointing at a row it no longer shows would toggle that row on a stray click in
 * the gutter's corner. That is why the row mapping is cleared alongside the box.</p>
 */
final class FoldingDecorationsPart extends EditorViewPart {

    private final List<UIElement> arrows = new ArrayList<>();
    private final List<Integer> arrowRows = new ArrayList<>();
    private final List<UIElement> chips = new ArrayList<>();
    private final List<Integer> chipRows = new ArrayList<>();

    FoldingDecorationsPart(TextEditor editor) {
        super(editor);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        renderArrows(firstViewLine, lastViewLine);
        renderChips(firstViewLine, lastViewLine);
    }

    // ── Gutter arrows ───────────────────────────────────────────────────────────────────────────

    /**
     * Places a fold arrow on every visible row that starts a region.
     *
     * <p>The arrow lives in the gutter's <b>right</b> column — the clear strip between the numbers and the
     * code, which {@code gutterFoldWidth} already reserves and which is where every editor puts it.</p>
     */
    private void renderArrows(int firstViewLine, int lastViewLine) {
        UIElement column = editor.foldColumn();
        if (!editor.isGutterVisible() || !editor.isFoldingEnabled()) {
            DecorationPool.hide(column);
            for (UIElement arrow : arrows) DecorationPool.hide(arrow);
            return;
        }
        final float columnLeft = editor.paddingLeft() + editor.gutterNumberWidth();
        final float columnWidth = editor.gutterFoldWidth();
        final float columnHeight = editor.getClientHeight();
        StyleGroup.defaultPipeline(column.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(columnLeft).top(0f).width(columnWidth).height(columnHeight));

        float height = editor.lineHeight();
        int used = 0;
        int last = Math.min(lastViewLine, editor.viewLineCount() - 1);
        for (int viewLine = Math.max(0, firstViewLine); viewLine <= last; viewLine++) {
            ProjectedLines.ModelPosition model = editor.modelAt(viewLine);
            // The arrow belongs to the ROW, so a wrapped row carries it on its first view line only --
            // the same rule the line number follows, and for the same reason.
            if (model.viewLineInRow() != 0) continue;
            FoldingRegions.Region region = editor.foldingModel().getRegionStartingAt(model.row());
            if (region == null) continue;

            int slot = used++;
            UIElement arrow = arrowAt(slot);
            arrowRows.set(slot, model.row());
            // The triangle's direction is driven entirely by this class — default.css's
            // texteditor .__fold__(.__collapsed__) rules swap the shape, the same way TreeView's
            // own twisty state classes do. No Java decision about the glyph any more.
            if (region.isCollapsed()) arrow.addClass(TextEditor.FOLD_COLLAPSED_CLASS);
            else arrow.removeClass(TextEditor.FOLD_COLLAPSED_CLASS);

            // Relative to the COLUMN, so left is 0 rather than the gutter offset.
            final float top = editor.textOriginY() + viewLine * height - editor.getScrollTop();
            StyleGroup.defaultPipeline(arrow.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE)
                            .left(0f).top(top).width(columnWidth).height(height));
        }
        for (int i = used; i < arrows.size(); i++) {
            DecorationPool.hide(arrows.get(i));
            arrowRows.set(i, -1);
        }
    }

    private UIElement arrowAt(int index) {
        while (arrows.size() <= index) {
            final int slot = arrows.size();
            UIElement arrow = new UIElement();
            arrow.addClass(TextEditor.FOLD_CLASS);
            arrow.markAsInternal();
            // A real vector triangle drawn directly by `overlay:` (see default.css's
            // texteditor .__fold__ rules) — no child glyph needed, since a shape needs nowhere to
            // hide from hit-testing the way a click-through UIText child used to require.
            arrow.onMouseDown.attachListener((el, event) -> {
                int row = slot < arrowRows.size() ? arrowRows.get(slot) : -1;
                if (row >= 0) editor.toggleFoldAt(row);
                event.stopPropagation();
            }, false, false);
            editor.foldColumn().addInternalChild(arrow);
            arrows.add(arrow);
            arrowRows.add(-1);
        }
        return arrows.get(index);
    }

    // ── Collapsed-region chips ──────────────────────────────────────────────────────────────────

    /**
     * Draws the {@code {...}} chip after the header of each collapsed region on screen.
     *
     * <p>The only thing distinguishing a collapsed block from a block with nothing in it. The glyphs are
     * ASCII dots, not U+22EF or U+2026 — neither is in the bundled fonts, and a missing glyph draws a
     * blank advance rather than falling back. This is text, unlike the fold arrow itself, because a
     * chip's content varies (it repeats the region's own closing bracket) rather than being one of a
     * fixed set of marks a shape can express.</p>
     */
    private void renderChips(int firstViewLine, int lastViewLine) {
        if (!editor.isFoldingEnabled() || !editor.foldingModel().hasCollapsedRegions()) {
            hideChipsFrom(0);
            return;
        }
        float height = editor.lineHeight();
        int used = 0;
        int last = Math.min(lastViewLine, editor.viewLineCount() - 1);
        for (int viewLine = Math.max(0, firstViewLine); viewLine <= last; viewLine++) {
            ProjectedLines.ModelPosition model = editor.modelAt(viewLine);
            FoldingRegions.Region region = editor.foldingModel().getRegionStartingAt(model.row());
            if (region == null || !region.isCollapsed()) continue;
            // The header's LAST view line, so a wrapped header puts the marker after its final fragment.
            int inRow = editor.projections().projectionOf(model.row()).viewLineCount() - 1;
            if (model.viewLineInRow() != inRow) continue;

            int slot = used++;
            UIElement marker = chipAt(slot);
            chipRows.set(slot, model.row());
            UIText glyph = (UIText) marker.getChildren().get(0);

            // The chip SWALLOWS the row's trailing opener, so it reads "{...}" rather than "...}" beside a
            // brace the line still owns. That is IntelliJ's collapsed form: one control covering the whole
            // construct, not a chip bolted onto the end of a line. The ROW stops painting that bracket --
            // see TextEditor.collapsedHeaderCut for why cutting a suffix is safe.
            String rowText = editor.buffer().document().line(model.row());
            int opener = trailingOpenerIndex(rowText);
            String tail = editor.placeholderTextFor(region);
            glyph.setText(opener >= 0 ? rowText.charAt(opener) + tail : tail);
            pushEditorFont(glyph);

            int endColumn = editor.projections().projectionOf(model.row()).maxColumn(model.viewLineInRow());
            // Back up over the opener and anything after it. Index-to-column is a constant shift on one
            // view line (the carried wrap indent), so subtracting the character count is correct whether
            // or not the row wrapped.
            // From the DISPLAY index, not the raw one: a tab occupies one character and several columns,
            // so subtracting a raw character count would place the chip short on any tab-indented row.
            if (opener >= 0) {
                int cut = editor.rowMetrics(model.row()).line().displayIndexOf(opener);
                int displayLength = editor.rowMetrics(model.row()).line().display().length();
                endColumn -= displayLength - cut;
            }
            final float pad = chipPadding();
            final float box = chipHeight();

            // Centred WITHIN the row rather than filling it. A chip as tall as the line makes its text
            // look shrunken inside a slab, and the line's leading sits below the glyphs, so a full-height
            // box is not centred on the text beside it either. Hugging the text is what IntelliJ draws.
            final float top = editor.textOriginY() + viewLine * height - editor.getScrollTop()
                    + Math.max(0f, (height - box) / 2f);

            // NO left shift, and this reverses an earlier choice. The box begins exactly where the row
            // stopped painting -- at the bracket -- so the space that preceded the bracket survives as a
            // clear gap from the code. Shifting the box left by its own padding to keep the bracket on the
            // pixel the row would have drawn it at ate that gap, and the chip ended up touching the ')'
            // before it. IntelliJ insets the bracket inside the chip rather than aligning it to the
            // character it replaced: the chip is one object, not a box drawn around a bracket.
            final float left = editor.textViewportLeft() + editor.codeLeftPad()
                    + editor.xOfView(viewLine, endColumn) - editor.getScrollLeft();
            // widthAuto() is NOT redundant. hide() parks a recycled element at width 0, and a chip that
            // only ever writes left/top/height keeps that zero when it comes back -- so a pooled slot that
            // was once hidden renders its text overflowing a collapsed box, while a slot that never was
            // looks perfect. Two identical folds, one right and one wrong, depending only on which slot
            // each happened to land in.
            StyleGroup.defaultPipeline(marker.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE)
                            .left(left).top(top).height(box).widthAuto()
                            .paddingHorizontal(pad));
            StyleGroup.defaultPipeline(marker.getStyle().getGeneralGroup(),
                    g -> g.borderRadius(pad * 0.6f));
        }
        hideChipsFrom(used);
    }

    private void hideChipsFrom(int used) {
        for (int i = used; i < chips.size(); i++) {
            DecorationPool.hide(chips.get(i));
            chipRows.set(i, -1);
        }
    }

    private UIElement chipAt(int index) {
        while (chips.size() <= index) {
            final int slot = chips.size();
            UIElement marker = new UIElement();
            marker.addClass(TextEditor.FOLD_PLACEHOLDER_CLASS);
            marker.markAsInternal();
            // The chip itself takes the click; its text does not, so the press lands on the box rather
            // than on a character inside it.
            UIText glyph = new UIText(TextEditor.FOLD_PLACEHOLDER_TEXT);
            glyph.setHitTest(false);
            marker.addChild(glyph);
            marker.onMouseDown.attachListener((el, event) -> {
                int row = slot < chipRows.size() ? chipRows.get(slot) : -1;
                if (row >= 0) editor.toggleFoldAt(row);
                event.stopPropagation();
            }, false, false);
            // A DIRECT child of the editor, deliberately not of a container spanning the text.
            //
            // A full-size hit-testable layer was the first design and it broke clicking entirely: it
            // covered the whole text area, so every press that was not on a chip landed on the layer and
            // the editor never saw it -- no caret, no selection, no focus, for as long as anything was
            // folded. It could not be made transparent either, because setHitTest(false) takes the whole
            // subtree with it and the chips inside would have gone dead again.
            //
            // Nothing was lost by dropping it. The layer existed to CLIP chips against the gutter and the
            // scrollbars, and z-order already does that: the gutter and the bars both sit above the chips,
            // and the editor clips its own subtree to its padding box.
            marker.setScrollExempt(true);
            editor.addInternalChild(marker);
            chips.add(marker);
            chipRows.add(-1);
        }
        return chips.get(index);
    }

    /**
     * Index of the bracket a row ends on, ignoring trailing whitespace, or {@code -1}.
     *
     * <p>Only a bracket counts. A row ending in a word — {@code do}, {@code then}, a Python colon — has no
     * character the chip can absorb without the collapsed line reading as if it were missing something.</p>
     */
    static int trailingOpenerIndex(String rowText) {
        int i = rowText.length() - 1;
        while (i >= 0 && Character.isWhitespace(rowText.charAt(i))) i--;
        if (i < 0) return -1;
        char c = rowText.charAt(i);
        return (c == '{' || c == '(' || c == '[') ? i : -1;
    }

    /**
     * The chip's horizontal breathing room, <b>as a fraction of the font size</b>.
     *
     * <p>Computed rather than declared, and a deliberate exception to the rule that geometry lives in
     * {@code default.css}. The value has to track the editor's own zoom: a fixed {@code 5px} is half a
     * line's height at 8px and a rounding error at 31px, so the chip reads as fat at one zoom and cramped
     * at the other <em>while the glyphs inside it are provably the same size</em> — measured, not assumed.
     * This stylesheet has no font-relative unit to express a fraction of a character with, and a pixel in
     * the sheet encodes an answer that is only correct at one font size.</p>
     *
     * <p>What is authored here is the RATIO, which is the real design decision. The sheet keeps the
     * colours; the corner radius follows the same scale so the chip's whole shape is proportional.</p>
     */
    private float chipPadding() {
        return Math.max(1f, editor.getFontSize() * 0.28f);
    }

    /**
     * The chip's box height — the text plus a little, never the whole line.
     *
     * <p>Proportional for the same reason {@link #chipPadding} is, and clamped to the line so a theme with
     * unusually tight leading cannot make the chip overflow the row it belongs to.</p>
     */
    private float chipHeight() {
        // Two bounds, and both are load-bearing. The font term hugs the TEXT when the theme's leading is
        // generous; the line term keeps the chip strictly INSIDE its row when the leading is tight, where
        // a font-only rule clamps to exactly the line height and the box stops looking like a chip at all.
        return Math.min(editor.lineHeight() * 0.88f, Math.max(1f, editor.getFontSize() * 1.35f));
    }

    private void pushEditorFont(UIText glyph) {
        StyleGroup.importantPipeline(glyph.getStyle().getGeneralGroup(),
                g -> g.fontSize(editor.getStyle().getGeneralGroup().fontSize())
                        .fontFamily(editor.getStyle().getGeneralGroup().fontFamily()));
    }
}
