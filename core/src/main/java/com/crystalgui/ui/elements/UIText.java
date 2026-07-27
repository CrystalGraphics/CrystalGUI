package com.crystalgui.ui.elements;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.text.CgTextLayoutRequest;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;

/**
 * Plain-text element — CrystalGUI's first concrete widget. Renders {@link #text} using
 * {@code font-family}/{@code font-size}/{@code color} and wraps within its content box.
 *
 * <p>Deliberately plain text only — no rich formatting (bold/italic/color spans), no markup
 * parsing. CrystalGraphics' rich-text engine (`CgStyledText`/`CgStyleSpan`/`CgFontFamilyGroup`) is
 * mostly built already but its BiDi ∩ style-span splitting integration is still an open gap; wiring
 * rich text into this element is future work once that's resolved, not attempted here.</p>
 *
 * <h3>Sizing: no Taffy {@code MeasureFunc} — a post-layout recompute instead</h3>
 * <p>An earlier design reported intrinsic size via a Taffy {@code MeasureFunc} (see
 * {@code UIElement#measureFunc()}). That hits a real bug in Taffy 1.1.4's flex-wrap cross-size
 * algorithm: {@code FlexboxComputer.java:1469} passes {@code NaN} instead of an item's resolved
 * column width when determining its auto cross-size specifically under {@code flex-wrap: wrap} (the
 * {@code nowrap} path correctly passes the resolved width) — so a measured leaf wraps at the wrong
 * width and reports a wrong height whenever its ancestor chain has wrapping enabled. Not something
 * fixable without forking a third-party Maven dependency.
 *
 * <p>Following the pattern LDLib2's own {@code TextElement} uses: this element is an ordinary
 * (non-measured) Taffy leaf. {@link #onLayoutChanged()} fires after every settled layout pass;
 * {@link #recompute()} re-wraps {@link #text} against the box's own just-resolved
 * {@code contentBoxWidth()} (a real, settled value — never a live mid-algorithm callback argument,
 * so the wrap-path bug above has no vector to reach it) and writes the resulting height (and, only
 * when nothing else would give this element a width, the width too) back as {@code !important}
 * style candidates via {@link StyleGroup#importantPipeline}, forcing another ordinary layout pass.
 * {@link com.crystalgui.style.ElementStyle#replaceOrPutCandidate} already no-ops when the pushed
 * value is unchanged, so this settles (stops re-triggering) once the wrap result stabilizes —
 * typically within 2-3 passes for a fixed-width ancestor, all within the same
 * {@code UIWindow.calculateLayout()} frame (its {@code while (isLayoutDirty())} loop keeps
 * re-running until nothing's dirty).
 *
 * <h3>Shaping/layout reuse</h3>
 * <p>Holds a retained {@link CgShapedParagraph} (from {@link CgTextLayoutRequest#shape()}),
 * rebuilt only when {@link #text} or the resolved {@link CgFontFamily} actually changes — never on
 * a pure width/height change. {@link CgShapedParagraph#layout(float, float)} already memoizes the
 * last {@code (maxWidth, maxHeight)} pair internally, so {@link #recompute()} and
 * {@link #paintOverlay} calling it with the same width re-runs no real line-breaking work.</p>
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

    /** null = not yet determined. Decided ONCE, on the first {@link #recompute()} call after this
     * element is genuinely attached to a window, then never re-derived — see {@link #recompute()}. */
    private Boolean selfSizesWidth;

    public UIText(String initialText) {
        text.set(initialText == null ? "" : initialText);
        text.changed.connect((oldVal, newVal) -> {
            shapedParagraph = null;
            // Must call recompute() directly, not just markTreeDirty(): with no MeasureFunc, a mere
            // dirty-mark just makes Taffy recompute using whatever !important width/height recompute()
            // last pushed (a sticky style candidate, unaffected by markTreeDirty() alone) — same
            // geometry in, same geometry out, so onLayoutChanged() (which only fires on a genuine
            // geometry change) never re-fires and the box silently keeps its old size. recompute()'s
            // own importantPipeline write already triggers the normal style-dirty chain when the
            // pushed value actually changes, so no separate markTreeDirty() call is needed here.
            recompute();
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
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        recompute();
    }

    /** Re-wraps {@link #text} against this element's own content width and writes the resulting
     * height (and, when self-sizing width — see below — the width too) back as {@code !important}
     * style candidates, forcing Taffy to revisit this node with real dimensions on the next pass.
     * See the class javadoc for why this replaces a {@code MeasureFunc}.
     *
     * <p><b>Why "self-sizes width" is decided once, not re-derived every call:</b> a naive "push a
     * width only when {@code contentBoxWidth() <= 0}, otherwise remove it" check is self-referential
     * and oscillates forever — once we push our own width, Taffy reflects it straight back to us as
     * {@code contentBoxWidth()} on the very next read, indistinguishable from "a real ancestor gave
     * me this width". That makes the next call remove what we just pushed, dropping
     * {@code contentBoxWidth()} back to 0, which pushes it again — an infinite add/remove loop, not
     * a settle. Deciding {@link #selfSizesWidth} exactly once, on the first {@link #recompute()}
     * after genuine attachment (when {@code contentBoxWidth()} still reflects only real ancestors,
     * never our own influence), and never re-deriving it afterward, breaks that cycle. A speculative
     * pre-attachment push (e.g. from the constructor, before this element ever joined a window) is
     * cleaned up exactly once, right when that first real determination concludes we're not actually
     * self-sizing after all — not on every subsequent pass. */
    private void recompute() {
        var layout = getTaffyLayout();

        if (getAttachedWindow() != null && selfSizesWidth == null) {
            selfSizesWidth = layout.contentBoxWidth() <= 0f;
            if (!selfSizesWidth) {
                getStyle().removeCandidates(LayoutProperties.WIDTH, slot -> slot.origin() == StyleOrigin.IMPORTANT);
            }
        }
        // Still unattached (selfSizesWidth genuinely undetermined): provisionally self-size — the
        // best guess available before any ancestor has ever had a chance to constrain us.
        boolean selfSize = selfSizesWidth == null || selfSizesWidth;

        float contentWidth = layout.contentBoxWidth();
        float maxWidthForWrap = selfSize ? 0f : contentWidth; // 0f == CrystalGraphics' documented "unbounded" convention
        CgTextLayout textLayout = ensureShaped().layout(maxWidthForWrap, 0f); // 0f maxHeight — see class javadoc / paintOverlay

        float chromeWidth = getRuntimeCache().getWidth() - contentWidth;         // border+padding this element itself owns (normally 0)
        float chromeHeight = getRuntimeCache().getHeight() - layout.contentBoxHeight();

        StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> {
            if (selfSize) {
                l.width(textLayout.totalWidth() + chromeWidth);
            }
            l.height(textLayout.totalHeight() + chromeHeight);
        });
    }

    @Override
    protected void paintOverlay(CgUiPaintContext ctx) {
        super.paintOverlay(ctx); // still draws the `overlay:` CSS drawable, unchanged from any other element

        var layout = getTaffyLayout();
        float contentX = getRuntimeCache().getX() + layout.border().left + layout.padding().left;
        float contentY = getRuntimeCache().getY() + layout.border().top + layout.padding().top;
        float contentWidth = layout.contentBoxWidth();

        CgFontFamily family = resolveFamily();
        // Deliberately 0f (unbounded) maxHeight, matching recompute()'s call — NOT
        // layout.contentBoxHeight(). UIText has no max-height/max-lines feature yet, so there's no
        // legitimate bounded height to constrain against; passing the measured content height back in
        // as maxHeight is a no-op when CgLineBreaker's height accounting agrees with itself, but
        // CgLineBreaker's line-fitting budget (a single uniform per-paragraph lineHeight) can disagree
        // with the real per-line combined metrics that produced this contentHeight in the first place
        // — e.g. a multi-font-family line (fallback glyphs) followed by a plain line. When that
        // happens the budget check trips early and CgLineBreaker.breakLines returns before appending
        // the trailing line at all — not clipped, just silently dropped. Known upstream CrystalGraphics
        // issue; revisit once UIText actually needs a bounded maxHeight (max-lines/ellipsis support).
        CgTextLayout textLayout = ensureShaped().layout(contentWidth, 0f);

        ctx.setColor(0xFFFFFFFF);
        ctx.text().draw().layout(textLayout).family(family)
                .at(contentX, contentY)
                .color(getStyle().getGeneralGroup().color())
                .pose(ctx.getPoseStack())
                .submit();
    }
}
