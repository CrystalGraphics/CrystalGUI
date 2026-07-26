package com.crystalgui.ui.elements;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.text.CgTextLayoutRequest;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.geometry.FloatSize;
import dev.vfyjxf.taffy.style.AvailableSpace;
import dev.vfyjxf.taffy.util.MeasureFunc;

/**
 * Plain-text element — CrystalGUI's first concrete widget. Renders {@link #text} using
 * {@code font-family}/{@code font-size}/{@code color}, wraps within its content box, and reports
 * its own intrinsic (measured) size to Taffy layout via {@link #measureFunc()} so an element with
 * no explicit {@code width}/{@code height} auto-sizes to its content — see
 * {@code UIElement#measureFunc()}/{@code UIWindow#registerElement}.
 *
 * <p>Deliberately plain text only — no rich formatting (bold/italic/color spans), no markup
 * parsing. CrystalGraphics' rich-text engine (`CgStyledText`/`CgStyleSpan`/`CgFontFamilyGroup`) is
 * mostly built already but its BiDi ∩ style-span splitting integration is still an open gap; wiring
 * rich text into this element is future work once that's resolved, not attempted here.</p>
 *
 * <h3>Shaping/layout reuse</h3>
 * <p>Holds a retained {@link CgShapedParagraph} (from {@link CgTextLayoutRequest#shape()}),
 * rebuilt only when {@link #text} or the resolved {@link CgFontFamily} actually changes — never on
 * a pure width/height change. {@link CgShapedParagraph#layout(float, float)} already memoizes the
 * last {@code (maxWidth, maxHeight)} pair internally, so calling it every {@link #measureFunc()}/
 * {@link #paintOverlay} pass (as Taffy reflows on resize/flex-recompute) only re-runs real
 * line-breaking work on the frame where the width genuinely changed.</p>
 */
public final class UIText extends UIElement {

    static {
        ElementRegistry.register("text", () -> new UIText(""));
    }

    /** Text content — bindable via {@link #bindTextTo(Property)}, reusing the same data-binding
     * infrastructure the rest of CrystalGUI uses (see {@code Property.bindTo}). */
    public final Property<String> text = new Property<>("");

    private CgShapedParagraph shapedParagraph;
    private String shapedForText;
    private CgFontFamily shapedForFamily;

    public UIText(String initialText) {
        text.set(initialText == null ? "" : initialText);
        text.changed.connect((oldVal, newVal) -> shapedParagraph = null);
    }

    public String getText() {
        return text.get();
    }

    public UIText setText(String value) {
        text.set(value == null ? "" : value);
        return this;
    }

    /** One-way binds {@link #text} to {@code source} — re-shapes/re-measures/re-wraps automatically
     * whenever {@code source} changes, via {@link #text}'s own {@code changed} listener. */
    public Connection bindTextTo(Property<String> source) {
        return text.bindTo(source);
    }

    private CgFontFamily resolveFamily() {
        var general = getStyle().getGeneralGroup();
        return FontFamilyCache.resolve(general.fontFamily(), Math.round(general.fontSize()));
    }

    /** Rebuilds the retained {@link CgShapedParagraph} only when {@link #text} or the resolved
     * {@link CgFontFamily} instance has actually changed since the last call — reference equality
     * on the family is intentional and correct: {@link FontFamilyCache#resolve} caches by
     * {@code (paths, targetPx)}, so an unchanged font-family/font-size always returns the exact
     * same instance. */
    private CgShapedParagraph ensureShaped() {
        String currentText = text.get();
        CgFontFamily currentFamily = resolveFamily();
        if (shapedParagraph == null || !currentText.equals(shapedForText) || currentFamily != shapedForFamily) {
            shapedParagraph = CgTextLayoutRequest.of(currentText, currentFamily).shape();
            shapedForText = currentText;
            shapedForFamily = currentFamily;
        }
        return shapedParagraph;
    }

    @Override
    protected MeasureFunc measureFunc() {
        return (knownDimensions, availableSpace) -> {
            float maxWidth = resolveMaxWidth(knownDimensions.width, availableSpace.width);
            CgTextLayout layout = ensureShaped().layout(maxWidth, 0f);
            return new FloatSize(layout.totalWidth(), layout.totalHeight());
        };
    }

    /** Maps Taffy's constraint for this axis to a {@code maxWidth} for
     * {@link CgShapedParagraph#layout}. {@code MIN_CONTENT}/{@code MAX_CONTENT} are both treated as
     * unbounded — a known v1 simplification; true min-content (wrap at the narrowest unbreakable
     * word) would need a measurement mode {@code CgShapedParagraph} doesn't expose today, and matters
     * far more for table-column-style sizing than typical flex/UI text. */
    private static float resolveMaxWidth(float knownWidth, AvailableSpace availableSpaceWidth) {
        if (!Float.isNaN(knownWidth)) return knownWidth;
        if (availableSpaceWidth.isDefinite()) return availableSpaceWidth.getValue();
        return 0f;
    }

    @Override
    protected void paintOverlay(CgUiPaintContext ctx) {
        super.paintOverlay(ctx); // still draws the `overlay:` CSS drawable, unchanged from any other element

        var layout = getTaffyLayout();
        float contentX = getRuntimeCache().getX() + layout.border().left + layout.padding().left;
        float contentY = getRuntimeCache().getY() + layout.border().top + layout.padding().top;
        float contentWidth = layout.contentBoxWidth();
        float contentHeight = layout.contentBoxHeight();

        CgFontFamily family = resolveFamily();
        CgTextLayout textLayout = ensureShaped().layout(contentWidth, contentHeight);

        ctx.setColor(0xFFFFFFFF);
        ctx.text().draw().layout(textLayout).family(family)
                .at(contentX, contentY)
                .color(getStyle().getGeneralGroup().color())
                .pose(ctx.getPoseStack())
                .submit();
    }
}
