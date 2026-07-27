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
        text.changed.connect((oldVal, newVal) -> {
            shapedParagraph = null;
            // A content-only change never touches a StyleProperty, so nothing else marks the Taffy
            // node dirty (see TaffyBridge/ElementStyle.markTaffyStyleDirty — that chain only fires on
            // style writes). Without this, isDirty() stays false forever and measureFunc() is never
            // re-invoked after the text changes, silently freezing the box at its old size.
            markTreeDirty();
        });
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

    /** Forced probe width for {@code MIN_CONTENT} queries — see {@link #resolveMaxWidth}. */
    private static final float MIN_CONTENT_PROBE_WIDTH = 1f;

    /** Maps Taffy's constraint for this axis to a {@code maxWidth} for
     * {@link CgShapedParagraph#layout}. {@code MAX_CONTENT} maps to {@code 0f} (unbounded — the
     * natural single-line width). {@code MIN_CONTENT} must NOT reuse that same unbounded value: real
     * CSS min-content for text is the width of the longest unbreakable token, and Taffy's flex
     * algorithm uses exactly that (its automatic-minimum-size pass, the {@code min-width:auto}
     * equivalent) to decide how far a flex item is allowed to shrink before overflowing — answering
     * it with the full natural width instead corrupts flex-shrink/wrap space distribution across
     * sibling rows. Forcing an extremely narrow probe width here makes the line-breaker place every
     * unbreakable token on its own line (nothing fits within {@link #MIN_CONTENT_PROBE_WIDTH}), so
     * the resulting {@code totalWidth} — the widest of those forced single-token lines — is exactly
     * the true min-content width, by construction. */
    private static float resolveMaxWidth(float knownWidth, AvailableSpace availableSpaceWidth) {
        if (!Float.isNaN(knownWidth)) return knownWidth;
        if (availableSpaceWidth.isMinContent()) return MIN_CONTENT_PROBE_WIDTH;
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

        CgFontFamily family = resolveFamily();
        // Deliberately 0f (unbounded), matching measureFunc()'s call — NOT layout.contentBoxHeight().
        // UIText has no max-height/max-lines feature yet, so there's no legitimate bounded height to
        // constrain against; passing the measured content height back in as maxHeight is a no-op when
        // CgLineBreaker's height accounting agrees with itself, but CgLineBreaker's line-fitting budget
        // (a single uniform per-paragraph lineHeight) can disagree with the real per-line combined
        // metrics that produced this contentHeight in the first place — e.g. a multi-font-family line
        // (fallback glyphs) followed by a plain line. When that happens the budget check trips early
        // and CgLineBreaker.breakLines returns before appending the trailing line at all — not
        // clipped, just silently dropped. Known upstream CrystalGraphics issue; revisit once UIText
        // actually needs a bounded maxHeight (max-lines/ellipsis support).
        CgTextLayout textLayout = ensureShaped().layout(contentWidth, 0f);

        ctx.setColor(0xFFFFFFFF);
        ctx.text().draw().layout(textLayout).family(family)
                .at(contentX, contentY)
                .color(getStyle().getGeneralGroup().color())
                .pose(ctx.getPoseStack())
                .submit();
    }
}
