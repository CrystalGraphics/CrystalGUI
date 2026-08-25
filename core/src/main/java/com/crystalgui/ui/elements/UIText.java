package com.crystalgui.ui.elements;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextDecoration;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiRoundedRect;

import javax.annotation.Nullable;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.style.HighlightStyle;
import com.crystalgui.style.property.visual.text.TextDecorationLine;
import com.crystalgui.ui.text.HighlightRegistry;
import com.crystalgui.ui.text.TextRange;
import dev.vfyjxf.taffy.style.TaffyDimension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Plain-text element — CrystalGUI's first concrete widget. Renders {@link #text} using
 * {@code font-family}/{@code font-size}/{@code color} and wraps within its content box.
 *
 * <h3>Highlights — the CSS Custom Highlight API</h3>
 * <p>{@link #highlights()} registers named {@link TextRange}s, and a stylesheet styles them through the
 * {@code ::highlight(name)} pseudo-element. That is the web's own mechanism for decorating ranges of
 * text <b>without wrapping them in elements</b>, and it exists on the web for exactly our reason: an
 * editor cannot afford a {@code <span>} per token, and here every element is a real Taffy node.</p>
 *
 * <pre>{@code
 * text.highlights().set("keyword", TextRange.of(0, 4));   // Java says WHERE
 * ::highlight(keyword) { color: #C678DD; }                // CSS says WHAT
 * }</pre>
 *
 * <p><b>The property set is restricted on purpose.</b> CSS Pseudo-Elements 4 allows highlight
 * pseudo-elements only properties that cannot affect layout — colour, background, text-decoration,
 * text-shadow — because a highlight must never reflow the text it highlights. No bold, no italic, no
 * font-size. See {@link HighlightStyle}.</p>
 *
 * <p><b>Known divergence: we re-shape, the web overlays.</b> Browsers paint highlights over text that
 * has already been laid out, so a highlight provably cannot change metrics. Here the ranges are turned
 * into {@code CgStyleSpan}s at shape time, because glyph colour is baked into the shaped run by the
 * backend. A span's boundaries are shaping-run boundaries, and separately-shaped runs lose the kerning
 * across them — so a highlight here can shift the measured width by a fraction of a pixel, which on the
 * web it cannot. Closing that gap needs draw-time per-range colour in CrystalGraphics, driven by
 * {@code CgShapedRun.clusterIds}. Until then: <b>un-highlighted text stays on the unspanned path
 * entirely</b>, so ordinary labels measure exactly as they always did.</p>
 *
 * <p>Rich text as <em>content</em> — bold and italic as part of the document — is a different feature
 * and the web answers it differently, with inline child elements in an inline formatting context. Taffy
 * has no inline layout, so that is not attempted here and is not what highlights are for.</p>
 *
 * <p>Markup ({@code <b>}, {@code <color=#RRGGBB>}, {@code §l}) is deliberately <b>not</b> wired up,
 * though {@code CgMarkupParser.HTML}/{@code .MINECRAFT} exist and it would be one call. Markup strips its
 * own tags, so {@link #getText()} and what is painted would stop being the same string — which
 * {@link #displayedText()}, the ellipsis search and every measurement path currently assume.</p>
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

    /**
     * Marks a text run as <b>coloured by a syntax scheme</b> — what every {@code ::highlight(<capture>)}
     * rule in a scheme is scoped to.
     *
     * <p>Here rather than on {@code TextEditor}, which is where it used to live and is no longer the only
     * thing that draws code: a documentation popup's declaration line carries it, and so does a code
     * sample inside a rendered doc comment. The class marks a <em>text element</em>, so this is the type
     * that should name it — and a general-purpose widget must not have to import the editor to say that
     * its text is code.</p>
     */
    public static final String SYNTAX_CLASS = "__syntax__";

    static {
        // `font-size`/`font-family` are GeneralGroup/visual properties, never routed through TaffyBridge
        // the way LayoutProperties are — so nothing marks Taffy dirty when either changes, and
        // `recompute()` (the only thing that re-measures this element) fires ONLY from a text-content
        // change or from `onLayoutChanged()`, which itself only fires on a genuine Taffy GEOMETRY change.
        // A font change alone is neither: it leaves the box's already-pushed !important width/height
        // exactly as they were, so nothing ever asks this element to re-shape against the new font. The
        // glyphs still paint with it (paintOverlay resolves the family fresh every frame), but the box
        // they're wrapped/ellipsized against is stale.
        //
        // Both properties, not just size: a runtime theme switch (the harness's ore.css -> default.css
        // toggle) turned out to change the FAMILY, not the size — ore.css's `* { font-family:
        // MinecraftRegular.otf }` stops applying and default.css's own family (wider glyphs for the same
        // string) takes over — so a font-size-only listener left "Time"'s pushed width sitting at
        // whatever MinecraftRegular measured it at, now too narrow for the same text in the new family,
        // truncating to "Ti…". A page revisit "fixed" it only because it rebuilds every element from
        // scratch, giving each one a first, correctly-sized measurement under whichever font happens to
        // be active at that moment.
        //
        // NOT a direct `recompute()` call here, unlike the `text`/`highlights` listeners above — proven
        // by instrumentation, not assumed. `ElementStyle.resolveTouched` notifies this listener BEFORE
        // the property's own cascade write has settled: `getComputed()`, called synchronously from
        // inside this callback, was still returning the OLD family even though the notification's own
        // `newVal` already reported the new (or removed) one — a real timing hole in the cascade's own
        // read path, not something to paper over by reading `newVal` directly here (paint time calls
        // `resolveFamily()` independently and needs the SAME eventually-consistent value). Clearing the
        // shape cache and the pushed !important size, then `markTreeDirty()`, defers the actual re-shape
        // to `onLayoutChanged()` on the NEXT layout pass — which runs after `calculateStyle()` has fully
        // settled for the frame, by which point `getComputed()` is correct.
        StylePropertyRegistry.FONT_SIZE.addListener((element, prop, oldVal, newVal) -> {
            if (element instanceof UIText t) t.invalidateForFontChange();
        });
        StylePropertyRegistry.FONT_FAMILY.addListener((element, prop, oldVal, newVal) -> {
            if (element instanceof UIText t) t.invalidateForFontChange();
        });
        // AND THE TWO FACE PROPERTIES, for a reason the family listener above does NOT cover. Weight is
        // carried on the SPANS rather than on the resolved family -- synthesis happens per span, and the
        // family a bold label resolves is the same instance as a regular one's. So the retained
        // paragraph's own "has the family changed?" check answers no, and a label switched to bold would
        // keep its old glyphs and its old measured width until something else happened to dirty it.
        //
        // Synthetic bold is WIDER than regular (that is what emboldening does), so the stale measurement
        // is not cosmetic: the box stays sized for the thin version and the text truncates inside it.
        StylePropertyRegistry.FONT_WEIGHT.addListener((element, prop, oldVal, newVal) -> {
            if (element instanceof UIText t) t.invalidateForFontChange();
        });
        StylePropertyRegistry.FONT_STYLE.addListener((element, prop, oldVal, newVal) -> {
            if (element instanceof UIText t) t.invalidateForFontChange();
        });
    }

    /**
     * Throw away the measurement and take it again on the next pass.
     *
     * <h3>Why a caller ever needs this</h3>
     *
     * <p>{@link #recompute()} is the only thing that measures, and it runs from exactly two places: a
     * text change, and {@code onLayoutChanged()} — which itself fires only on a genuine Taffy geometry
     * change. That pair covers a label whose text or box moves, and it has a hole: an element built and
     * populated <em>while nothing about its geometry is settled</em> can measure zero, push zero, and
     * then never be asked again, because zero-in-zero-out is not a geometry change. It is a deadlock
     * rather than a lag — the width stays wrong for the element's whole life.</p>
     *
     * <p>The font listeners above have needed the same three steps since a theme switch left boxes sized
     * for the previous face; this is that operation named and made available, rather than a second copy
     * of it somewhere else.</p>
     */
    public void invalidateMeasurement() {
        invalidateForFontChange();
    }

    private void invalidateForFontChange() {
        shapedParagraph = null;
        shadowParagraph = null;
        getStyle().removeCandidates(LayoutProperties.WIDTH, slot -> slot.origin() == StyleOrigin.IMPORTANT);
        getStyle().removeCandidates(LayoutProperties.HEIGHT, slot -> slot.origin() == StyleOrigin.IMPORTANT);
        markTreeDirty();
        // AND MEASURE AGAIN NOW, for the same reason the text listener says it must: with no MeasureFunc,
        // markTreeDirty() alone re-runs Taffy against whatever this element last pushed -- and having just
        // withdrawn that, it pushes nothing, so the box resolves to zero. Zero in, zero out is not a
        // geometry change, so onLayoutChanged() never fires and nothing ever asks for a measurement again.
        //
        // That is a DEADLOCK, not a lag, and it is reachable whenever the font resolves after the element
        // was first measured -- which is every element built mid-frame, since its cascade has not run when
        // setText measures it. The Quick Documentation popup hit it on the first hover of a process: its
        // signature lines measured against font-size's initial value, the real size arrived from the sheet
        // moments later, and the width stayed at zero for the popup's whole life.
        recompute();
    }

    /** Text content — bindable via {@link #bindTextTo(Property)}, reusing the same data-binding
     * infrastructure the rest of CrystalGUI uses (see {@code Property.bindTo}). */
    public final Property<String> text = new Property<>("");

    /** Named ranges, styled from CSS via {@code ::highlight(name)}. Empty for ordinary labels — the
     * common case, and the one that must stay on the unspanned shaping path (see the class javadoc). */
    private final HighlightRegistry highlights = new HighlightRegistry(registry -> {
        // Same reasoning as the text listener: a bare markTreeDirty() would re-run Taffy against the
        // !important width/height recompute() last pushed, produce identical geometry, and never fire
        // onLayoutChanged() — leaving the box sized for the previous highlights.
        shapedParagraph = null;
        shadowParagraph = null;
        recompute();
    });

    private CgShapedParagraph shapedParagraph;

    /**
     * The highlight that won each character, or null — what {@link #paintHighlightBands} reads.
     *
     * <p>Built by {@code toCgSpans} rather than recomputed, so the band and the glyph colour cannot
     * disagree about which range won where they overlap.</p>
     */
    private HighlightStyle[] highlightPerChar;

    /** The decoration the retained paragraph was shaped for — part of the cache key. */
    private Set<TextDecorationLine> shapedForDecorations = Collections.emptySet();
    private String shapedForText;
    private CgFontFamily shapedForFamily;
    /** The resolved highlight styles the retained paragraph was shaped against.
     *
     * <p>Part of the memo key rather than a separate invalidation hook: a highlight's colour comes from
     * the cascade, so editing a stylesheet, switching theme or a {@code :hover} on the originating
     * element can all change it with nothing on this element having been touched. Comparing the resolved
     * values catches every one of those, and costs a small map comparison per settled layout.</p> */
    private Map<String, HighlightStyle> shapedForHighlights = Collections.emptyMap();

    /** Shadow twin of {@link #shapedParagraph}, built only when a highlight sets a colour — see
     * {@link #shadowLayoutFor}. Null whenever the ordinary paragraph can serve both passes. */
    private CgShapedParagraph shadowParagraph;

    /** Last ellipsis result and the exact inputs that produced it — see {@link #truncatedStringFor}. */
    private String truncated;
    private String truncatedForText;
    private CgFontFamily truncatedForFamily;
    private float truncatedForWidth = Float.NaN;

    /** null = not yet determined. Decided ONCE, on the first {@link #recompute()} call after this
     * element is genuinely attached to a window, then never re-derived — see {@link #recompute()}. */
    private Boolean selfSizesWidth;

    /**
     * Skips the auto-detect and locks {@link #selfSizesWidth} to {@code true} directly, for a caller
     * that already knows — by construction — that this label must always drive its ancestor's growth
     * rather than accept whatever width layout hands it.
     *
     * <p>The auto-detect ({@code contentBoxWidth() <= 0} on the first post-attachment
     * {@link #recompute()}) is a heuristic, and a genuinely racy one: it is reading whatever the
     * ancestor chain's FIRST, not-yet-converged layout pass happens to report at that exact moment. If
     * an ancestor itself grows to fit ITS children (a {@code graphnode}'s title, sized to help drive the
     * node wide enough for a short label like "Time"/"UV") that first reading can land on a small but
     * nonzero placeholder rather than the true {@code <= 0}, latching {@code false} — and because the
     * decision never re-derives, the label is then stuck taking whatever narrow width it's handed for
     * its entire lifetime, silently truncating text that would fit once the ancestor actually settled.
     * The only recovery was destroying and rebuilding the element (e.g. navigating away from a page and
     * back), which is what made the bug look "conditional on first open" rather than a plain miscount.
     * A caller that already knows the answer should not gamble on the race at all.</p>
     */
    public UIText forceSelfSizeWidth() {
        selfSizesWidth = Boolean.TRUE;
        return this;
    }

    /**
     * The other half of {@link #forceSelfSizeWidth()}: this element is <b>sized by its box</b>, so the
     * width must come from the cascade and never from the text.
     *
     * <p>Without it there was no way to say so, and the auto-detect answers the wrong way round for
     * anything whose text is <em>incidental</em> to its size. The activity bar's badge is the case that
     * found it: in its dot form the text is {@code ""} and the sheet gives it {@code width: 10px}, but the
     * first {@code recompute()} read a not-yet-laid-out box, latched "self-sizing", and pushed a width of
     * <b>zero</b> at IMPORTANT — which outranks the sheet permanently. The dot was attached, classed and
     * coloured, and 0px wide, so it had never once been visible; only counted badges worked, because their
     * text happens to be the width you want.</p>
     *
     * <p>Both locks exist so a caller that knows the answer can state it rather than gamble on the race —
     * and the badge needs <em>both</em>, since a count sizes to its text and a dot to its box.</p>
     */
    public UIText neverSelfSizeWidth() {
        selfSizesWidth = Boolean.FALSE;
        return this;
    }

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

    // ── Highlights ──────────────────────────────────────────────────────────

    /**
     * This element's named highlight ranges — {@code CSS.highlights}, scoped to one label.
     *
     * <p>Register <em>where</em> here and style <em>what</em> in CSS:</p>
     * <pre>{@code
     * text.highlights().set("keyword", TextRange.of(0, 4));
     * }</pre>
     * <pre>{@code
     * ::highlight(keyword) { color: #C678DD; }
     * }</pre>
     *
     * <p>Registering a name no stylesheet mentions is legal and does nothing, exactly as on the web — a
     * highlighter should not have to know which theme is loaded.</p>
     */
    public HighlightRegistry highlights() {
        return highlights;
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
        Map<String, HighlightStyle> currentHighlights = resolveHighlightStyles();
        // THE DECORATION IS PART OF THE KEY. It becomes a span, and a span changes the shaping -- so
        // leaving it out means an underline appearing on :hover never re-shapes and simply never draws,
        // because the text, the font and the highlights are all still exactly what they were.
        Set<TextDecorationLine> currentDecorations = ownDecorations();
        if (shapedParagraph == null || !currentText.equals(shapedForText) || currentFamily != shapedForFamily
                || !currentHighlights.equals(shapedForHighlights)
                || !currentDecorations.equals(shapedForDecorations)) {
            shapedParagraph = shape(currentText, currentFamily, currentHighlights, false);
            shapedForText = currentText;
            shapedForFamily = currentFamily;
            shapedForHighlights = currentHighlights;
            shapedForDecorations = currentDecorations;
            shadowParagraph = null;
        }
        return shapedParagraph;
    }

    /**
     * Asks the cascade for the style of every highlight name registered on this element.
     *
     * <p>Names with no matching rule resolve to {@link HighlightStyle#EMPTY} and are dropped, so a
     * highlighter emitting names the current theme ignores costs one map lookup each and produces no
     * spans at all — which is what keeps such an element on the fast unspanned shaping path.</p>
     */
    private Map<String, HighlightStyle> resolveHighlightStyles() {
        if (highlights.isEmpty()) return Collections.emptyMap();
        var window = getAttachedWindow();
        if (window == null) return Collections.emptyMap();

        Map<String, HighlightStyle> resolved = new LinkedHashMap<>();
        for (String name : highlights.names()) {
            HighlightStyle style = window.getStyleEngine().highlightStyle(this, name);
            if (!style.isEmpty()) resolved.put(name, style);
        }
        return resolved;
    }

    /**
     * The one place plain and highlighted shaping diverge.
     *
     * <p>With no effective highlights this takes {@code CgTextLayout.of(text, family)} — the original
     * path, byte for byte, which is what keeps ordinary labels measuring exactly as they always have.
     * With highlights it builds a {@link CgStyledText} over a {@link CgFontFamilyGroup}.</p>
     *
     * @param limit  characters of {@code content} the ranges may cover, for the truncation path where the
     *               painted string is a prefix of the real one
     * @param shadow darken every highlight colour, for the {@code text-shadow} pass
     */
    private CgShapedParagraph shape(String content, CgFontFamily family,
                                    Map<String, HighlightStyle> styles, boolean shadow) {
        List<CgStyleSpan> spans = toCgSpans(styles, content.length(), shadow);
        if (spans.isEmpty()) {
            return CgTextLayout.of(content, family).shape();
        }
        return CgTextLayout.of(new CgStyledText(content, spans), resolveGroup()).shape();
    }

    /**
     * How many characters currently carry a highlight <b>band</b> — the only observable of the band pass.
     *
     * <p>The sibling of {@link #styleSpanCount()} and there for the same reason: a band changes no
     * geometry and no computed style, so a test that asserts on the registered range or the resolved
     * {@code ::highlight()} style passes against a version painting the band over completely the wrong
     * characters. Which is what happened — a recycled row banded its whole label because this array was
     * never cleared when the highlight went away, while the range, the style and the match count were all
     * correct.</p>
     *
     * <p>Reads what the last shaping produced rather than recomputing, because that is what the paint
     * reads. It is therefore only meaningful after a frame.</p>
     */
    public int highlightBandCount() {
        HighlightStyle[] perChar = highlightPerChar;
        if (perChar == null) return 0;
        int count = 0;
        for (HighlightStyle style : perChar) {
            if (style != null && (style.backgroundColor() >>> 24) != 0) count++;
        }
        return count;
    }

    /**
     * How many style spans the last shaping used — zero meaning the plain, unspanned path.
     *
     * <p>Public for the same reason {@link #displayedText()} is: it is the <b>only</b> way to observe
     * that a {@code text-decoration-line} or a {@code ::highlight()} actually reached the paint. Both
     * resolve through the cascade whether or not anything draws them, so a test asserting on the computed
     * value passes against a version that renders nothing — which is precisely how an underline that had
     * never worked went unnoticed.</p>
     *
     * <p>Computed on demand rather than recorded at shaping time, so it answers for the <em>current</em>
     * style rather than for whenever the retained paragraph was last built. A decoration changes no
     * geometry, so it is picked up when something next shapes — which is a paint, not a layout.</p>
     */
    public int styleSpanCount() {
        return toCgSpans(resolveHighlightStyles(), text.get().length(), false).size();
    }

    private CgFontFamilyGroup resolveGroup() {
        var general = getStyle().getGeneralGroup();
        return FontFamilyCache.resolveGroup(general.fontFamily(), Math.round(general.fontSize()));
    }

    /**
     * Flattens registered ranges plus their resolved styles into backend spans, clipped to {@code limit}.
     *
     * <p>The clip is what makes truncation and text-shrinking safe: both leave ranges pointing past the
     * end of the string that will actually be shaped, and {@code CgStyledText} rejects that outright —
     * from inside a paint, on a later frame, with no caller code on the stack.</p>
     *
     * <p><b>Overlapping highlights resolve by registration order</b>, last one winning, and non-overlap
     * within a single name is enforced by {@link HighlightRegistry}. The web layers overlapping
     * highlights by priority instead; ours is the simpler rule that a single shaped run per character
     * can actually express, and it is at least deterministic and explainable.</p>
     */
    private List<CgStyleSpan> toCgSpans(Map<String, HighlightStyle> styles, int limit, boolean shadow) {
        // The ELEMENT'S OWN text-decoration-line, which nothing used to paint. It resolved through the
        // cascade and inherited correctly and then went nowhere, because decorations were only ever read
        // off a ::highlight() style -- so `text-decoration-line: underline` on a label was a no-op that
        // looked like a missing CSS feature. A link is the obvious consumer.
        //
        // Expressed as a span over whatever the highlights do not cover. That does mean a decorated label
        // takes the STYLED shaping path, which AGENTS.md warns shifts text by a fraction of a pixel
        // against the unspanned one -- acceptable here precisely because it happens only when a
        // decoration is actually set, so ordinary labels are untouched.
        Set<CgTextDecoration> base = toCgDecorations(ownDecorations());

        // THE ELEMENT'S OWN font-weight/font-style, and they ride on EVERY span rather than only the
        // uncovered ones. A decoration is a property of the range it was asked for; a weight is a property
        // of the whole label, so a bold title with a search match in it must not go thin for the three
        // matched characters. That is the one way these two differ from `base` below, and it is why they
        // are threaded into `toCgSpan` as well.
        var general = getStyle().getGeneralGroup();
        boolean bold = general.fontWeight().isBold();
        boolean italic = general.fontStyle().isItalic();

        // Whether an UNHIGHLIGHTED stretch still needs a span of its own. It used to be `!base.isEmpty()`
        // -- true when a decoration was set and nothing else. With a weight in play that is no longer the
        // whole question: a bold label with a highlight in the middle would emit a span for the match and
        // none for the text around it, so the two halves would shape at different weights.
        boolean baseSpanNeeded = !base.isEmpty() || bold || italic;

        // CLEARED FIRST, because the two early returns below are the case where a label that USED to carry
        // a band no longer does -- and leaving the old array in place is not a stale style, it is a band
        // over the wrong text entirely. `paintHighlightBands` reads `perChar[run.sourceStart()]` and an
        // unhighlighted label shapes as ONE run starting at 0, so a single leftover entry at index 0 paints
        // across the whole string.
        //
        // Which is exactly what a recycled row does: the explorer's rows are pooled, so every row element
        // that had ever shown a match went on banding whatever name landed on it next, full width. The
        // count said "1 of 1" the whole time -- the model was right and only the paint was wrong.
        if (!shadow) this.highlightPerChar = null;

        if (styles.isEmpty() && !baseSpanNeeded) return Collections.emptyList();
        if (styles.isEmpty()) {
            return limit <= 0 ? Collections.emptyList()
                    : List.of(new CgStyleSpan(0, limit, bold, italic, base, 0, null, 0f,
                            ownDecorationColor()));
        }

        // Winner per character, then run-length encoded — the only way to get disjoint spans out of
        // ranges that may overlap across names.
        HighlightStyle[] perChar = new HighlightStyle[limit];
        // RETAINED for the paint pass, which needs to know which characters carry a band. Only from the
        // ordinary pass: the shadow pass darkens every colour, and a band drawn from those would be a
        // second, dimmer rectangle behind the first.
        if (!shadow) this.highlightPerChar = perChar;
        for (Map.Entry<String, HighlightStyle> entry : styles.entrySet()) {
            for (TextRange range : highlights.get(entry.getKey())) {
                TextRange clipped = range.clippedTo(limit);
                if (clipped == null) continue;
                for (int i = clipped.start(); i < clipped.end(); i++) perChar[i] = entry.getValue();
            }
        }

        List<CgStyleSpan> out = new ArrayList<>();
        int runStart = -1;
        int uncoveredFrom = 0;
        for (int i = 0; i <= limit; i++) {
            HighlightStyle here = i < limit ? perChar[i] : null;
            HighlightStyle previous = runStart < 0 ? null : perChar[runStart];
            if (here == previous) continue;
            if (previous != null) {
                // Anything between the last highlight and this one carries the element's own decoration.
                if (baseSpanNeeded && runStart > uncoveredFrom) {
                    out.add(new CgStyleSpan(uncoveredFrom, runStart, bold, italic, base, 0, null, 0f, 0));
                }
                out.add(toCgSpan(previous, runStart, i, shadow, bold, italic));
                uncoveredFrom = i;
            }
            runStart = here == null ? -1 : i;
        }
        if (baseSpanNeeded && uncoveredFrom < limit) {
            out.add(new CgStyleSpan(uncoveredFrom, limit, bold, italic, base, 0, null, 0f,
                    ownDecorationColor()));
        }
        return out;
    }

    /**
     * This element's own {@code text-decoration-color}, or {@code 0} for "the text's own colour".
     *
     * <p>{@code 0} is the backend's sentinel as well as CSS's {@code currentColor} default, so the two
     * agree without a translation — an underline is the glyphs' colour unless something says otherwise.</p>
     */
    private int ownDecorationColor() {
        Integer color = getStyle().getGeneralGroup()
                .getValueSave(com.crystalgui.style.property.StylePropertyRegistry.TEXT_DECORATION_COLOR);
        return color == null ? 0 : color;
    }

    /** This element's own {@code text-decoration-line}, as the cascade resolved it. */
    private Set<TextDecorationLine> ownDecorations() {
        Set<TextDecorationLine> lines = getStyle().getGeneralGroup()
                .getValueSave(com.crystalgui.style.property.StylePropertyRegistry.TEXT_DECORATION_LINE);
        return lines == null ? Collections.emptySet() : lines;
    }

    private static CgStyleSpan toCgSpan(HighlightStyle style, int start, int end, boolean shadow,
                                        boolean bold, boolean italic) {
        // 0 is the backend's "inherit the draw colour" sentinel, and on the shadow pass the draw colour
        // already IS the shadow — so a background-only highlight needs no help. Only an explicit colour
        // has to be darkened, or a red keyword would paint its shadow bright red.
        int color = style.color(0);
        if (shadow && color != 0) color = shadowColorFor(color);
        // THE HIGHLIGHT'S OWN WEIGHT WINS, AND THE ELEMENT'S CARRIES THROUGH WHERE IT SAYS NOTHING.
        //
        // `::highlight()` may now set bold and italic -- a deliberate divergence from CSS Pseudo-Elements
        // 4, argued at HighlightStyle.ALLOWED. What has not changed is the fallback: a highlight that is
        // silent about weight must not make its range lighter than the text around it, which is what keeps
        // a bold label bold across the three characters a search happened to match.
        // The highlight's own decoration colour, 0 meaning "follow the text" -- CSS's currentColor
        // default and the backend's sentinel are the same value, so there is nothing to translate.
        // This used to be hard-coded 0 on the reasoning that a highlight sets ONE colour; a scheme that
        // underlines a reassigned variable in a different colour from its text is the ordinary
        // counter-example, and CgStyleSpan has carried decorationArgb all along.
        return new CgStyleSpan(start, end, style.isBold(bold), style.isItalic(italic),
                toCgDecorations(style.decorations()), color, null, 0f, style.decorationColor());
    }

    private static Set<CgTextDecoration> toCgDecorations(Set<TextDecorationLine> source) {
        if (source.isEmpty()) return Collections.emptySet();
        EnumSet<CgTextDecoration> out = EnumSet.noneOf(CgTextDecoration.class);
        for (TextDecorationLine line : source) {
            // CSS spells it `line-through`; the backend enum says STRIKETHROUGH. Mapped by hand rather
            // than by name(), because they deliberately do not agree — see TextDecorationLine.
            out.add(line == TextDecorationLine.LINE_THROUGH
                    ? CgTextDecoration.STRIKETHROUGH
                    : CgTextDecoration.valueOf(line.name()));
        }
        return out;
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

        // EVERY FRAME, PER LABEL, and `paint:overlay` -- which is where a UIText draws -- measured at
        // 6.5ms of a 9ms client frame across ~130 of them. That is ~50us each for something whose
        // javadoc says calling it again at the same width "re-runs no real line-breaking work", so the
        // split below is what says whether that is true.
        long timed = FrameProfile.begin();
        CgFontFamily family = resolveFamily();
        FrameProfile.end(timed, "text:resolveFamily");
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
        timed = FrameProfile.begin();
        CgTextLayout textLayout = wraps
                ? ensureShaped().layout(contentWidth, 0f)
                : truncatedIfNeeded(family, contentWidth);
        FrameProfile.end(timed, wraps ? "text:layout(wrap)" : "text:layout(truncate)");

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
        timed = FrameProfile.begin();
        paintHighlightBands(ctx, textLayout, contentX, contentY);
        FrameProfile.end(timed, "text:highlightBands");

        boolean shadow = general.textShadow();
        // THE SWITCH AND THE SUBMIT, apart. ctx.text() flushes the quad path and binds the text
        // material; the submit is glyph work. text:draw measured at 93% of paint:overlay and ~61us per
        // label across ~130 labels a frame, with 73 renderer switches -- and a material bind is exactly
        // that order of cost, so which half this is decides whether the answer is batching or the glyph
        // pipeline. They have nothing in common.
        long switched = FrameProfile.begin();
        CgTextRenderer renderer = ctx.text();
        FrameProfile.end(switched, "text:switchRenderer");
        long drawn = FrameProfile.begin();
        if (shadow) renderer.beginBatch();
        try {
            if (shadow) {
                // A span's own colour BEATS the draw colour downstream
                // (`overrideColor != 0 ? overrideColor : rgba`), so a coloured span would paint its
                // shadow in full brightness — a red keyword with a red shadow, which reads as a blur
                // rather than a shadow. ensureShadowShaped() supplies a twin whose span colours are
                // pre-darkened; it returns the ordinary layout whenever nothing needs darkening.
                renderer.draw().layout(shadowLayoutFor(textLayout, family, contentWidth, wraps))
                        .family(family)
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
            FrameProfile.end(drawn, "text:submit");
        }
    }


    /**
     * The source character at a point in this element's own coordinates, or {@code -1}.
     *
     * <h3>Run granularity, and that is not an approximation</h3>
     *
     * <p>The walk resolves to a shaped RUN and answers that run's first character, which sounds coarse
     * and is exactly right for what asks: <b>a span boundary is a shaping-run boundary</b>. A highlighted
     * range therefore <em>is</em> one or more runs — the property {@code paintHighlightBands} is built on
     * — so "which span was clicked" is answered precisely even though "which letter" is not. A caller
     * needing the letter would need per-glyph advances, which nothing does yet.</p>
     *
     * <p>Same walk as the band painter, deliberately: if the two disagreed about where a run sits, a
     * click would land on text other than the one under the pointer, and both are derived from the same
     * layout for the same reason the hit-test and the pose share {@code UITransform.applyTo}.</p>
     *
     * <p>Answers {@code -1} when the element has never been laid out or the point is above the first
     * line — never a clamped guess, because "nothing here" and "the first character" are different
     * answers and a caller that wants the clamp can say so.</p>
     */
    public int offsetAt(float localX, float localY) {
        var taffy = getTaffyLayout();
        if (taffy == null) return -1;
        float contentWidth = taffy.contentBoxWidth();
        float x = localX - taffy.border().left - taffy.padding().left;
        float y = localY - taffy.border().top - taffy.padding().top;
        if (y < 0f) return -1;

        float maxWidthForWrap = getStyle().getGeneralGroup().whiteSpace().wraps() ? contentWidth : 0f;
        CgTextLayout textLayout = ensureShaped().layout(maxWidthForWrap, 0f);
        List<List<CgShapedRun>> lines = textLayout.lines();
        if (lines.isEmpty()) return -1;

        float lineHeight = textLayout.totalHeight() / lines.size();
        int lineIndex = lineHeight <= 0f ? 0 : (int) (y / lineHeight);
        if (lineIndex < 0 || lineIndex >= lines.size()) return -1;

        float at = 0f;
        for (CgShapedRun run : lines.get(lineIndex)) {
            at += run.totalAdvance();
            if (x < at) return run.sourceStart();
        }
        return -1;
    }

    /**
     * The source character under a raw pointer position, or {@code -1}.
     *
     * <h3>Two "local" spaces, and mixing them is silent</h3>
     *
     * <p><b>{@link #screenToLocal} does not answer this element's own coordinates.</b> It answers the
     * space this element's BOX is expressed in — the one {@code isMouseOverElement} tests a point
     * against {@link RuntimeCache#getX()}/{@link RuntimeCache#getY()} in. {@link #offsetAt} wants
     * coordinates relative to this element's own top-left. The two differ by exactly the box origin,
     * so feeding one to the other is off by however far the element sits from its container's
     * origin.</p>
     *
     * <p>Which is why this exists rather than the two calls at each site. It fails the way coordinate
     * bugs always do — correctly at the origin and wrong everywhere else — so it survives every
     * fixture built around a single element at (0,0) and breaks on the first real layout. Measured on
     * the documentation popup: a link 38px down a paragraph sitting at y=453 resolved to {@code -1},
     * and the same point less the box origin resolved to the link's first character exactly.</p>
     *
     * <p>Both link gestures in {@code MarkupView} — the press that follows a link and the hover that
     * underlines one — had written it out longhand, and both were wrong in the same way.</p>
     */
    public int offsetAtScreen(float screenX, float screenY) {
        var local = screenToLocal(screenX, screenY);
        return offsetAt(local.x() - getRuntimeCache().getX(), local.y() - getRuntimeCache().getY());
    }

    /**
     * Fills the {@code background-color} band behind every highlighted range — CSS Custom Highlight's
     * one genuinely <em>positional</em> property.
     *
     * <h3>The geometry was already in the layout</h3>
     *
     * <p>{@link HighlightStyle} used to list {@code background-color} as allowed-but-unpaintable, on the
     * grounds that a band needs per-range rects and a {@code CgStyleSpan} carries nothing positional.
     * True, and beside the point: <b>shaping breaks a run at every span boundary</b>, so a highlighted
     * range already <em>is</em> one or more {@link CgShapedRun}s — and each one carries its own
     * {@code sourceStart}/{@code sourceEnd} and {@code totalAdvance}. Walking them costs no measurement
     * and no second shaping pass.</p>
     *
     * <p>Drawn <b>before</b> the glyphs, which is the only order that works: this is a background, and
     * the quad path has no depth to sort by — whatever is submitted last is on top.</p>
     *
     * <p>Returns immediately for every ordinary label. {@code highlightPerChar} is null unless something
     * registered a range <em>and</em> a stylesheet styled it, so the fast unspanned path pays one null
     * check.</p>
     */
    private void paintHighlightBands(CgUiPaintContext ctx, CgTextLayout layout,
                                     float contentX, float contentY) {
        HighlightStyle[] perChar = highlightPerChar;
        if (perChar == null || perChar.length == 0) return;
        List<List<CgShapedRun>> lines = layout.lines();
        if (lines.isEmpty()) return;

        float lineHeight = layout.totalHeight() / lines.size();
        float y = contentY;
        for (List<CgShapedRun> line : lines) {
            float x = contentX;
            // ADJACENT RUNS OF ONE HIGHLIGHT ARE ONE BAND, accumulated here and flushed when the style
            // changes. Drawing per run was indistinguishable while a band was a plain rect -- two
            // abutting rects of the same colour look like one -- and stops being so the moment a band has
            // GEOMETRY: per-run padding would open a gap inside a single highlighted phrase and per-run
            // rounding would round every interior boundary, so `{@code a b}` would draw as two pills.
            // Shaping breaks a run for reasons of its own (a font fallback, a script change), so "one
            // highlight" and "one run" were never the same thing.
            HighlightStyle pending = null;
            float pendingX = x;
            float pendingWidth = 0f;
            for (CgShapedRun run : line) {
                float advance = run.totalAdvance();
                int at = run.sourceStart();
                // The run's FIRST character decides, because a run cannot span two highlights: the
                // boundary that made them different styles is also a shaping boundary.
                HighlightStyle style = at >= 0 && at < perChar.length ? perChar[at] : null;
                if (style != pending) {
                    paintBand(ctx, pending, pendingX, y, pendingWidth, lineHeight);
                    pending = style;
                    pendingX = x;
                    pendingWidth = 0f;
                }
                pendingWidth += advance;
                x += advance;
            }
            paintBand(ctx, pending, pendingX, y, pendingWidth, lineHeight);
            y += lineHeight;
        }
    }

    /**
     * One highlight's band on one line.
     *
     * <p>Square and unpadded is the overwhelmingly common case — an editor's selection, a search hit, a
     * bracket match — so it keeps {@code fillRect}, which batches with every other quad in the frame. A
     * band with geometry goes through the SDF rounded-rect material instead, which is a material switch
     * and therefore its own draw call; that is the right price for a few words of inline code and the
     * wrong one for every selected line in a document, which is why the fast path is not merely an
     * optimisation.</p>
     */
    private void paintBand(CgUiPaintContext ctx, @Nullable HighlightStyle style,
                           float x, float y, float width, float height) {
        if (style == null || width <= 0f) return;
        int band = style.backgroundColor();
        // Alpha zero is "no band" and not "a transparent band" — see HighlightStyle.
        if (band == 0 || (band >>> 24) == 0) return;

        float padLeft = style.bandPadLeft(width);
        float padRight = style.bandPadRight(width);
        float bandX = x - padLeft;
        float bandWidth = width + padLeft + padRight;
        float[] radii = style.cornerRadii(bandWidth, height);

        if (radii == null) {
            ctx.fillRect(bandX, y, bandWidth, height, band);
            return;
        }
        if (bandDrawable == null) bandDrawable = new CgUiRoundedRect();
        bandDrawable.setFillColor(band)
                .setCornerRadius(radii[0], radii[1], radii[2], radii[3],
                        radii[4], radii[5], radii[6], radii[7]);
        bandDrawable.draw(ctx, 0f, 0f, bandX, y, bandWidth, height);
    }

    /** Reused across frames — a drawable is a description, and building one per band per frame is churn. */
    @Nullable
    private CgUiRoundedRect bandDrawable;

    /**
     * The layout the {@code text-shadow} pass should draw — the ordinary one unless a span carries an
     * explicit colour that would otherwise paint at full brightness behind its own glyph.
     *
     * <p>Shaped lazily and retained, so the cost is one extra shaping pass per genuine content change and
     * nothing per frame. Skipped entirely when no span sets a colour, which covers bold/italic/underline
     * spans as well as every plain label — those inherit the draw colour, and the draw colour on this
     * pass is already the shadow.</p>
     */
    private CgTextLayout shadowLayoutFor(CgTextLayout normal, CgFontFamily family, float contentWidth,
                                         boolean wraps) {
        if (!anyHighlightIsColoured()) return normal;
        // Truncated text is left alone deliberately: the ellipsis path already re-shapes per width, and
        // a second shadow-coloured re-shape of a *changing* prefix would double that cost every frame for
        // a nearly invisible difference behind an already-clipped label.
        if (!wraps) return normal;
        if (shadowParagraph == null) {
            shadowParagraph = shape(text.get(), family, shapedForHighlights, true);
        }
        return shadowParagraph.layout(contentWidth, 0f);
    }

    private boolean anyHighlightIsColoured() {
        for (HighlightStyle style : shapedForHighlights.values()) {
            if (style.color(0) != 0) return true;
        }
        return false;
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
        if (display.equals(text.get())) {
            return ensureShaped().layout(0f, 0f);        // untouched: reuse the retained paragraph
        }
        // Truncated: a different, shorter string, so the retained paragraph cannot serve. Spans have to
        // come along clipped — TextSpan.clippedTo drops the ones past the cut and shortens the one
        // straddling it, which is what keeps a half-highlighted token from throwing on validation.
        //
        // Known, accepted imprecision: the binary search that chose this cut measured the text UNSPANNED
        // (measureEllipsised builds a plain layout per probe), while what gets painted here is spanned.
        // Span boundaries are shaping-run boundaries and separately-shaped runs lose the kerning across
        // them, so the painted width can differ from the probed one by a fraction of a pixel per span —
        // enough, at worst, for a styled label to overhang its box by a hair. Making the probe span-aware
        // would mean shaping styled text on every step of the search, which is the exact cost the memo
        // above exists to avoid, for an error smaller than the ellipsis glyph. Revisit only if a real
        // label is visibly wrong, not on principle.
        return shape(display, family, shapedForHighlights, false).layout(0f, 0f);
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

    /**
     * {@code keep} is always within {@code source} — the search bounds it at {@code source.length()}.
     *
     * <p><b>Measures at the element's own weight</b>, which is the whole reason this is not a bare
     * {@code CgTextLayout.of(text, family)}. The ellipsis search is a binary search over widths, so it is
     * only correct if the width it probes is the width that will be painted — and synthetic bold is wider
     * than regular. Measured thin and painted bold, every truncation would cut a character or two late
     * and the ellipsis would sit outside the box it was computed to fit.</p>
     *
     * <p>Stays on the plain path when the element is neither bold nor italic, so ordinary labels keep
     * measuring through the unspanned shaper exactly as before — the divergence AGENTS.md records between
     * the two paths is a fraction of a pixel, but it is a fraction of a pixel this method compares
     * against a box.</p>
     */
    private CgTextLayout measureEllipsised(CgFontFamily family, String source, int keep, String ellipsis) {
        String probe = source.substring(0, keep) + ellipsis;
        var general = getStyle().getGeneralGroup();
        boolean bold = general.fontWeight().isBold();
        boolean italic = general.fontStyle().isItalic();
        if (!bold && !italic) return CgTextLayout.of(probe, family).build();
        return CgTextLayout.of(
                new CgStyledText(probe, List.of(
                        new CgStyleSpan(0, probe.length(), bold, italic, null, 0, null, 0f, 0))),
                resolveGroup()).build();
    }
}
