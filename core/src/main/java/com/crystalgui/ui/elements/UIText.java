package com.crystalgui.ui.elements;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyDimension;

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
 * <p>Holds a retained {@link CgShapedParagraph} (from {@link CgTextLayout.Request#shape()}),
 * rebuilt only when {@link #text} or the resolved {@link CgFontFamily} actually changes — never on
 * a pure width/height change. {@link CgShapedParagraph#layout(float, float)} already memoizes the
 * last {@code (maxWidth, maxHeight)} pair internally, so {@link #recompute()} and
 * {@link #paintOverlay} calling it with the same width re-runs no real line-breaking work.</p>
 */
public final class UIText extends UIElement {

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
            // Covers Button, Checkbox and Tab too: their labels are internal UIText children, and
            // notifyStateChanged attributes the change to the nearest non-internal ancestor — the
            // composite whose own writeState actually carries the text.
            notifyStateChanged();
        });
    }

    @Override
    protected <T> void writeState(StateMap<T> out) {
        out.putStringIfNot("text", getText(), "");
    }

    @Override
    protected <T> void readState(StateMap<T> in) {
        setText(in.getString("text", ""));
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
            shapedParagraph = CgTextLayout.of(currentText, currentFamily).shape();
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
        // Detached means there is no layout to feed and — on a dedicated server — no font stack to
        // measure with: FontFamilyCache goes straight to CgFont.load, and CrystalGraphics isn't on
        // the runtime classpath there at all. Bailing is also simply correct, because the !important
        // width/height this pushes only means anything once a Taffy node exists; attaching to a
        // window re-drives it. Without this, `setText` on a detached tree is a NoClassDefFoundError.
        if (getAttachedWindow() == null) return;

        var layout = getTaffyLayout();

        if (selfSizesWidth == null) {
            selfSizesWidth = layout.contentBoxWidth() <= 0f;
            if (!selfSizesWidth) {
                getStyle().removeCandidates(LayoutProperties.WIDTH, slot -> slot.origin() == StyleOrigin.IMPORTANT);
            }
        }
        // Still unattached (selfSizesWidth genuinely undetermined): provisionally self-size — the
        // best guess available before any ancestor has ever had a chance to constrain us.
        boolean selfSize = selfSizesWidth == null || selfSizesWidth;

        float contentWidth = layout.contentBoxWidth();
        // 0f == CrystalGraphics' documented "unbounded" convention.
        // Self-sizing is NOT the same as unbounded: `width: auto` with a `max-width` is CSS
        // shrink-to-fit, which wraps at the max. Without this a max-width could only ever clip the
        // BOX — the text kept its full unwrapped run and spilled straight out of it, which is what a
        // max-width'd tooltip did (and it then defeated edge-clamping too, since placement was
        // reasoning about a box far narrower than the glyphs actually drawn).
        float maxWidthForWrap = selfSize ? selfMaxWidthForWrap() : contentWidth;
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

    /**
     * This element's own {@code max-width} as a wrap bound, or {@code 0f} (unbounded) when it has
     * none.
     *
     * <p>Only a definite {@code length} counts. A percentage would have to resolve against the
     * containing block, and if this element is self-sizing then by definition no ancestor has given
     * it a definite width to resolve against — so honouring one here would be inventing a number.
     * {@code auto}, {@code min-content} and friends are likewise not bounds we can hand the shaper.</p>
     *
     * <p>The bound is the <em>content</em> box, so this element's own border and padding come off
     * first — otherwise a padded text element wraps that much too late and overflows by exactly its
     * horizontal padding.</p>
     */
    private float selfMaxWidthForWrap() {
        // Read from the LIVE Taffy style, not from the cascade. It is the same value the layout pass
        // itself uses, so the wrap bound cannot drift out of agreement with the box that gets
        // measured — which is the failure mode this whole method exists to prevent.
        TaffyDimension maxWidth = getStyle().getTaffyBridge().style.maxSize.width;
        if (maxWidth == null || maxWidth.getType() != TaffyDimension.Type.LENGTH) return 0f;

        var layout = getTaffyLayout();
        float chrome = layout.border().left + layout.border().right
                + layout.padding().left + layout.padding().right;
        return Math.max(0f, maxWidth.getValue() - chrome);
    }

    @Override
    protected void paintOverlay(CgUiPaintContext ctx) {
        super.paintOverlay(ctx); // still draws the `overlay:` CSS drawable, unchanged from any other element

        var layout = getTaffyLayout();
        var general = getStyle().getGeneralGroup();
        float contentWidth = layout.contentBoxWidth();
        // Paint-time only, and applied AFTER contentWidth is read so it can never affect wrapping —
        // `text-offset-*` moves glyphs, never geometry. Percentages resolve per-axis against this
        // element's own box, the same convention outline-offset and mask-offset use.
        float contentX = getRuntimeCache().getX() + layout.border().left + layout.padding().left
                + general.textOffsetX().resolve(getRuntimeCache().getWidth());
        float contentY = getRuntimeCache().getY() + layout.border().top + layout.padding().top
                + general.textOffsetY().resolve(getRuntimeCache().getHeight());

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
