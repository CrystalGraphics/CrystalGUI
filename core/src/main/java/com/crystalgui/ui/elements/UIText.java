package com.crystalgui.ui.elements;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.text.TextOverflow;
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
 *
 * <p><b>That reuse covers the wrapping path only.</b> {@code text-overflow: ellipsis} cannot use the
 * retained paragraph at all — it re-shapes a <em>different</em> (shortened) string on every probe of its
 * binary search — so {@link #truncatedStringFor} carries its own memo keyed on
 * {@code (text, family, contentWidth)}. Without it a truncating label pays a full set of shaping passes
 * every single frame, forever.</p>
 */
public final class UIText extends UIElement {

    /** Text content — bindable via {@link #bindTextTo(Property)}, reusing the same data-binding
     * infrastructure the rest of CrystalGUI uses (see {@code Property.bindTo}). */
    public final Property<String> text = new Property<>("");

    private CgShapedParagraph shapedParagraph;
    private String shapedForText;
    private CgFontFamily shapedForFamily;

    /** Last ellipsis result and the exact inputs that produced it — see {@link #truncatedStringFor}. */
    private String truncated;
    private String truncatedForText;
    private CgFontFamily truncatedForFamily;
    private float truncatedForWidth = Float.NaN;

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
        // `white-space: nowrap` overrides both: one line, however long. Kept separate from
        // `contentWidth`, which still has to describe the real content box — it feeds the chrome
        // arithmetic below, and zeroing it there would report this element's border+padding as its
        // entire width.
        if (!getStyle().getGeneralGroup().whiteSpace().wraps()) maxWidthForWrap = 0f;
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
        boolean wraps = general.whiteSpace().wraps();
        CgTextLayout textLayout = wraps
                ? ensureShaped().layout(contentWidth, 0f)
                : truncatedIfNeeded(family, contentWidth);

        // `text-align`: distribute the leftover width. Clamped at zero so overflowing text always
        // starts at the leading edge rather than being pushed negative — centring something wider than
        // its box would otherwise hide its beginning, which is the half you need to read.
        float leftover = Math.max(0f, contentWidth - textLayout.totalWidth());
        contentX += leftover * general.textAlign().leadingFraction();

        ctx.setColor(0xFFFFFFFF);
        int color = general.color();

        // `text-shadow`: the same layout drawn once more, offset by a pixel, darkened. Was registered
        // but a no-op until now. Deliberately not a separate colour property — CSS's `text-shadow` is a
        // full offset/blur/colour triple, and inventing a partial one of our own would be a
        // non-web concept to defend forever. A hardcoded 1px drop is what Minecraft's own font renderer
        // does, and it is what a theme expects when it says `text-shadow: true`.
        //
        // Both passes go inside ONE batch. Without it `draw()` auto-wraps each submit in a batch of its
        // own, so a shadowed label pays two material binds and two flushes for the same glyph atlas and
        // the same shader — pure waste, since only the offset and the colour differ and colour is
        // per-instance data. Batched, the two passes coalesce into a single draw call. The `try` matters:
        // an unclosed batch makes the *next* beginBatch throw, so one bad frame would take down all
        // subsequent text rather than just this label.
        boolean shadow = general.textShadow();
        CgTextRenderer renderer = ctx.text();
        if (shadow) renderer.beginBatch();
        try {
            if (shadow) {
                renderer.draw().layout(textLayout).family(family)
                        .at(contentX + 1f, contentY + 1f)
                        .color(shadowColorFor(color))
                        .pose(ctx.getPoseStack())
                        .submit();
            }

            renderer.draw().layout(textLayout).family(family)
                    .at(contentX, contentY)
                    .color(color)
                    .pose(ctx.getPoseStack())
                    .submit();
        } finally {
            if (shadow) renderer.endBatch();
        }
    }

    /** A quarter-brightness copy at the same alpha — Minecraft's own convention for glyph shadows. */
    private static int shadowColorFor(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = ((argb >> 16) & 0xFF) / 4;
        int g = ((argb >> 8) & 0xFF) / 4;
        int b = (argb & 0xFF) / 4;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * The single-line layout, trimmed to fit with an ellipsis if {@code text-overflow: ellipsis} asked
     * for it and the text is genuinely too wide.
     *
     * <p>Truncation is done on the <b>string</b>, then re-shaped — not by dropping glyphs from the
     * shaped run. Shaping is not a per-character mapping: ligatures, marks and cluster reordering mean
     * the last N glyphs are not the last N characters, so cutting the glyph array would split clusters
     * and produce nonsense in exactly the scripts that can least afford it.</p>
     *
     * <p>The search is a binary search over the prefix length, so a long label costs ~log₂(n) shaping
     * passes rather than n. Only runs when the text actually overflows AND the memo in
     * {@link #truncatedStringFor} misses, so a stationary label re-measures nothing after the first
     * frame.</p>
     */
    private CgTextLayout truncatedIfNeeded(CgFontFamily family, float contentWidth) {
        String display = truncatedStringFor(family, contentWidth);
        return display.equals(text.get())
                ? ensureShaped().layout(0f, 0f)          // untouched: reuse the retained paragraph
                : CgTextLayout.of(display, family).build();
    }

    /**
     * The string that will actually be painted at this content width — the source text unchanged unless
     * {@code text-overflow: ellipsis} is genuinely shortening it.
     *
     * <p>One implementation, two callers ({@link #truncatedIfNeeded} for painting and
     * {@link #displayedText()} for asking). They must agree exactly: two searches over the same
     * predicate is how "what it shows" and "what it says it shows" drift apart, and the drift would only
     * ever be visible on screen.</p>
     */
    private String truncatedStringFor(CgFontFamily family, float contentWidth) {
        String source = text.get();
        if (getStyle().getGeneralGroup().textOverflow() != TextOverflow.ELLIPSIS) return source;
        if (contentWidth <= 0f || ensureShaped().layout(0f, 0f).totalWidth() <= contentWidth) return source;

        // Memoised on everything the answer depends on, because the search below is genuinely expensive
        // and this method runs EVERY FRAME a label is truncating: measureEllipsised builds a fresh
        // CgTextLayout per probe, so unlike the wrapping path there is no retained paragraph memoising
        // it — a full set of shaping passes per frame, per label. Reference equality on the family is the
        // same trick ensureShaped uses, and correct for the same reason: FontFamilyCache.resolve caches
        // by (paths, targetPx), so an unchanged font-family/size is literally the same instance.
        if (source.equals(truncatedForText) && family == truncatedForFamily
                && contentWidth == truncatedForWidth) {
            return truncated;
        }

        String ellipsis = ellipsisFor(family);
        int lo = 0, hi = source.length(), keep = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (measureEllipsised(family, source, mid, ellipsis).totalWidth() <= contentWidth) {
                keep = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        truncated = source.substring(0, keep) + ellipsis;
        truncatedForText = source;
        truncatedForFamily = family;
        truncatedForWidth = contentWidth;
        return truncated;
    }

    /**
     * The string this element will actually paint — {@link #getText()} unless {@code text-overflow:
     * ellipsis} is shortening it, in which case the truncated form ending in the ellipsis.
     *
     * <p>Public rather than an internal detail for two reasons:</p>
     * <ul>
     *   <li><b>It is the only way truncation is observable at all.</b> Ellipsising happens at paint time
     *       and changes no geometry, so nothing in the layout tree reveals whether it fired — which is
     *       exactly how an ellipsis that never applied shipped green and only showed up on screen.</li>
     *   <li><b>"Tooltip only when the label is truncated" is a real pattern</b>, and this is the question
     *       it needs answered. The DOM makes you compare {@code scrollWidth} against
     *       {@code clientWidth}; a direct answer is better.</li>
     * </ul>
     *
     * <p>Returns the full text when detached, before first layout, or while wrapping — in none of those
     * cases is there a bounded single line to truncate, so claiming a truncation would invent one.</p>
     */
    public String displayedText() {
        if (getAttachedWindow() == null) return getText();
        if (getStyle().getGeneralGroup().whiteSpace().wraps()) return getText();
        return truncatedStringFor(resolveFamily(), getTaffyLayout().contentBoxWidth());
    }

    /** U+2026 — one glyph, and the one a font actually designs for this. Derived from the code point
     * rather than written twice, so the literal and the coverage check cannot disagree. */
    private static final int ELLIPSIS_CODE_POINT = 0x2026;
    private static final String ELLIPSIS = String.valueOf((char) ELLIPSIS_CODE_POINT);
    /** For the fonts that do not have it — see {@link #ellipsisFor}. */
    private static final String ELLIPSIS_FALLBACK = "...";

    /**
     * {@code "…"} when the font stack can actually draw U+2026, {@code "..."} when it cannot.
     *
     * <p>Straight out of WebKit/Blink, which does exactly this in its text-overflow path — <i>"use the
     * ellipsis character if the font supports it, otherwise use three periods"</i>. Worth having here
     * rather than trusting the glyph: this engine ships pixel fonts and loads arbitrary ones, and a
     * missing U+2026 degrades in the worst possible way — the text is shortened correctly and then a
     * blank advance is drawn in the gap, which is indistinguishable on screen from
     * {@code text-overflow: clip} while every measurement stays right.</p>
     *
     * <p>Checked against the <em>resolved</em> source rather than the primary one, so a fallback font in
     * the stack that does have the glyph still wins it. {@code resolveSourceForCodePoint} returns the
     * primary source as a last resort when nothing covers the code point, hence the second
     * {@code canDisplayCodePoint} — the resolve alone cannot tell "found it" from "gave up".</p>
     */
    private static String ellipsisFor(CgFontFamily family) {
        var source = family.resolveSourceForCodePoint(ELLIPSIS_CODE_POINT);
        return source != null && source.canDisplayCodePoint(ELLIPSIS_CODE_POINT)
                ? ELLIPSIS
                : ELLIPSIS_FALLBACK;
    }

    /** {@code keep} is always within {@code source} — the search bounds it at {@code source.length()}. */
    private CgTextLayout measureEllipsised(CgFontFamily family, String source, int keep, String ellipsis) {
        return CgTextLayout.of(source.substring(0, keep) + ellipsis, family).build();
    }
}
