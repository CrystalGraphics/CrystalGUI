package com.crystalgui.widget.text;

import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextDecoration;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.HighlightStyle;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.text.TextDecorationLine;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.box.Measurable;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.text.HighlightRegistry;
import com.crystalgui.ui.text.TextRange;
import dev.vfyjxf.taffy.style.TaffyDimension;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A run of text — the engine's one text leaf, and the widget layer's label.
 *
 * <p>{@code <text>}. Wrapping, ellipsis, {@code ::highlight()} bands, {@code text-decoration},
 * synthetic bold/italic and a text shadow, over a retained {@link CgShapedParagraph} that is rebuilt
 * only when something it was shaped for has actually changed.</p>
 *
 * <h3>It ANSWERS the layout engine; it does not argue with it</h3>
 *
 * <p>This is the merge of the old {@code UIText} with M5's {@code UIText} (D15), and the whole of
 * the difference is one interface. The old one could not be measured — the layout engine's flex-wrap
 * path passed {@code NaN} for a leaf's own width — so it laid out, re-measured against the box that
 * had settled, and pushed a width and a height back into the cascade at {@code IMPORTANT} origin,
 * hoping to converge. About four hundred lines of that file existed to make the loop terminate, and
 * three separate escape hatches existed for when it did not.</p>
 *
 * <p>The fork fixed the measure path ({@code taffy/MODIFICATIONS.md} #1), so this implements
 * {@link Measurable} and is asked the question directly: <em>given this width, how tall are you?</em>
 * One pass, nothing written back. What that deletes is worth naming, because each was a real defect
 * with a real invariant behind it:</p>
 *
 * <ul>
 *   <li><b>{@code selfSizesWidth}</b>, decided once from whether the box measured zero on the first
 *       post-attachment pass — a race against an ancestor's not-yet-converged layout, latched for the
 *       element's life. A graph node's title latched {@code false} against a placeholder width and
 *       truncated for good; an activity badge latched {@code true} against an unmeasured box, pushed
 *       {@code width: 0} at IMPORTANT, and had never once been visible.</li>
 *   <li><b>{@code forceSelfSizeWidth()} and {@code neverSelfSizeWidth()}</b>, the two hatches for
 *       callers who knew the answer and could not state it. Min-content and max-content are questions
 *       the engine asks per layout now, so there is no latch to pre-empt.</li>
 *   <li><b>{@code invalidateMeasurement()}</b> and the deadlock it was named for: withdrawing the
 *       pushed size made the box resolve to zero, and zero-in-zero-out is not a geometry change, so
 *       nothing ever asked again.</li>
 * </ul>
 *
 * <h3>Ordinary labels stay on the unspanned path, and that is load-bearing</h3>
 *
 * <p>A span boundary is a shaping-run boundary, so styled text loses the kerning across it and sits a
 * fraction of a pixel differently. {@link #shape} therefore takes {@code CgTextLayout.of(text,
 * family)} verbatim whenever nothing needs a span — no highlight resolves to anything, no decoration,
 * no synthetic face — which is every label in the engine that is not code or a search result.</p>
 */
public final class UIText extends UINode implements Measurable {

    public static final Name NAME = Name.of("text");

    public static final State<UIText, String> TEXT =
            State.<UIText, String>of("text", StateTypes.STRING, UIText::getText, UIText::setText, "")
                    .omittedWhen("");

    /** Registered by {@link com.crystalgui.widget.Widgets}. @see com.crystalgui.ui.dom.NodeKinds */
    public static final WidgetContract<UIText> CONTRACT = WidgetContracts.register(
            WidgetContract.of(UIText.class, "text")
                    .state(TEXT)
                    .primary(TEXT)
                    .build());

    /**
     * Marks a run as <b>coloured by a syntax scheme</b> — what every {@code ::highlight(<capture>)}
     * rule in a scheme is scoped to.
     *
     * <p>Here rather than on the editor, which is no longer the only thing that draws code: a
     * documentation popup's declaration line carries it, and so does a sample inside a rendered doc
     * comment. The class marks a TEXT element, so this is the type that should name it — and a
     * general-purpose widget must not import the editor to say that its text is code.</p>
     */
    public static final String SYNTAX_CLASS = "__syntax__";

    /** Narrow enough that every break opportunity breaks; the widest unbreakable run is the answer. */
    private static final float MIN_CONTENT_WIDTH = 1f;

    private static final int ELLIPSIS_CODE_POINT = 0x2026;
    private static final String ELLIPSIS = String.valueOf((char) ELLIPSIS_CODE_POINT);
    private static final String ELLIPSIS_FALLBACK = "...";

    /** Text content — bindable, reusing the data binding the rest of the engine uses. */
    public final Property<String> text = new Property<>("");

    /**
     * Named ranges, styled from CSS via {@code ::highlight(name)}.
     *
     * <p>Empty for ordinary labels, which is the common case and the one that must stay on the
     * unspanned shaping path.</p>
     */
    private final HighlightRegistry highlights =
            new HighlightRegistry(registry -> invalidateShaping());

    // ── The retained paragraph, and everything it was shaped for ─────────────
    //
    // The memo key is every input to shaping, and each entry earned its place. Text and family are
    // obvious. DECORATIONS became a span the day `text-decoration-line` started painting, so an
    // underline appearing on :hover would otherwise never re-shape and simply never draw. HIGHLIGHTS
    // are their RESOLVED styles rather than their names, because a highlight's colour comes from the
    // cascade -- a theme switch or a `:hover` on this element changes it with nothing here touched.

    @Nullable
    private CgShapedParagraph paragraph;
    @Nullable
    private String shapedText;
    @Nullable
    private CgFontFamily shapedFamily;
    private Map<String, HighlightStyle> shapedHighlights = Collections.emptyMap();
    private Set<TextDecorationLine> shapedDecorations = Collections.emptySet();
    private boolean shapedBold;
    private boolean shapedItalic;

    /**
     * The highlight that won each character, or null.
     *
     * <p>Built by {@link #toCgSpans} rather than recomputed, so a band and the glyph colour under it
     * cannot disagree about which range won where two overlap.</p>
     */
    @Nullable
    private HighlightStyle[] highlightPerChar;

    /** The shadow pass's twin, built only when a highlight sets a colour. @see #shadowLayoutFor */
    @Nullable
    private CgShapedParagraph shadowParagraph;

    // ── The ellipsis memo ────────────────────────────────────────────────────
    //
    // Every input the answer depends on, because the binary search below is genuinely expensive and
    // runs EVERY FRAME a label is truncating: unlike the wrapping path there is no retained paragraph
    // to memoise it, so it is a full set of shaping passes per label per frame.

    @Nullable
    private String truncated;
    @Nullable
    private String truncatedForText;
    @Nullable
    private CgFontFamily truncatedForFamily;
    private float truncatedForWidth = Float.NaN;

    /**
     * The last few widths this node was asked to measure at, oldest first — a diagnostic.
     *
     * <p><b>Bounded</b>, and that is not tidiness: a measure runs per layout pass and a layout pass
     * runs per frame, so an unbounded list is a {@code Float} per frame per label retained for the
     * life of the tree. A window is all any reader wants — the questions asked of it are "was it
     * measured at all" and "what was it last asked", and the several entries in between are the
     * min-content/max-content pair the engine asks for on one pass.</p>
     */
    private final Deque<Float> measuredAt = new ArrayDeque<>();

    /** How many widths {@link #measuredAt} keeps. One layout pass contributes at most two. */
    private static final int MEASURE_HISTORY = 8;

    public UIText() {
        this("");
    }

    public UIText(@Nullable String initialText) {
        super(NAME);
        text.set(initialText == null ? "" : initialText);
        text.changed.connect((from, to) -> {
            invalidateShaping();
            // The text IS state, and notifyStateChanged walks out of every enclosing shadow tree -- so
            // a composite's label dirties the COMPOSITE, whose contract carries the text, rather than a
            // node no peer has heard of.
            notifyStateChanged();
        });
    }

    // ── Content ──────────────────────────────────────────────────────────────

    public String getText() {
        return text.get();
    }

    /** {@link #getText()}, spelled the way a node reads. Both, because both are used. */
    public String text() {
        return text.get();
    }

    public UIText setText(@Nullable String value) {
        text.set(value == null ? "" : value);
        return this;
    }

    /** Follows {@code source}; the returned {@link Connection} unbinds. */
    public Connection bindTextTo(Property<String> source) {
        return text.bindTo(source);
    }

    /** Named ranges to style through {@code ::highlight(name)}. */
    public HighlightRegistry highlights() {
        return highlights;
    }

    // ── Measuring ────────────────────────────────────────────────────────────

    /**
     * Given a width, how big is this text.
     *
     * <p>{@code max-width} is folded in because the layout engine clamps the RESULT of a measure
     * against it and does not pass it in — for an ordinary box those are the same thing, and for a
     * text leaf they are not: measured unbounded the paragraph is one line however long, the clamp
     * makes the BOX narrow, and the glyphs run out of it. A tooltip laid its whole sentence on one
     * line with {@code max-width: 170px} and {@code white-space: normal} both perfectly correct.</p>
     *
     * <p>A PERCENT max-width is ignored: it resolves against a containing block a measure does not
     * have, and guessing is worse than the long line.</p>
     */
    @Override
    public Size measure(Constraints constraints) {
        float width = constraints.wrapWidth();
        if (Float.isNaN(width)) width = constraints.wantsMinContentWidth() ? MIN_CONTENT_WIDTH : 0f;
        boolean wraps = wraps();
        if (!wraps) width = 0f;   // one line however long; the ellipsis happens at paint

        // ONLY WHILE WRAPPING. `max-width` caps the BOX; it is not a wrap width, and applying it to
        // a `nowrap` run undoes the line above -- zero means "one line", and the clamp read zero as
        // "unconstrained, so use the max" and wrapped at it. The text is then supposed to overflow a
        // box the cap still holds at 80px, which is what the covering test asserts on both counts.
        TaffyDimension max = computedStyle().get(LayoutProperties.MAX_WIDTH);
        if (wraps && max != null && max.isLength() && max.getValue() > 0f
                && (width <= 0f || width > max.getValue())) {
            width = max.getValue();
        }

        measuredAt.addLast(width);
        while (measuredAt.size() > MEASURE_HISTORY) measuredAt.removeFirst();

        if (text.get().isEmpty()) return Size.ZERO;
        CgTextLayout laid = ensureShaped().layout(width, 0f);
        return new Size(laid.totalWidth(), laid.totalHeight());
    }

    /** The last {@value #MEASURE_HISTORY} widths this node was measured at, oldest first. */
    public List<Float> measuredAt() {
        return List.copyOf(measuredAt);
    }

    /**
     * A font change has to re-measure, and nothing else would ask.
     *
     * <p>{@code font-size}, {@code font-family}, {@code font-weight} and {@code font-style} are visual
     * properties — {@code BoxStyle} never writes them, so a change to one marks no layout dirty and
     * the box keeps whatever the previous face measured. The old engine carried four static property
     * listeners and a deadlock workaround for this; here it is one hook and a dirty mark, because the
     * engine asks for the measurement itself on the next pass.</p>
     *
     * <p>All four, not just size. A runtime theme switch changes the FAMILY (one sheet's
     * {@code font-family} stops applying and another's wider glyphs take over) and left a label's box
     * sized for the previous face, truncating text that now fits. Weight and style are carried on the
     * SPANS rather than on the resolved family — synthesis is per span, and a bold label resolves the
     * same family instance as a regular one — so the paragraph's own "has the family changed" check
     * answers no, and synthetic bold is <em>wider</em> than regular.</p>
     */
    @Override
    public void computedChanged(StyleProperty<?> property, @Nullable Object oldValue,
                                @Nullable Object newValue) {
        super.computedChanged(property, oldValue, newValue);
        if (property == StylePropertyRegistry.FONT_SIZE
                || property == StylePropertyRegistry.FONT_FAMILY
                || property == StylePropertyRegistry.FONT_WEIGHT
                || property == StylePropertyRegistry.FONT_STYLE) {
            invalidateShaping();
        }
    }

    /** Drops the retained paragraph and asks for a fresh layout. */
    private void invalidateShaping() {
        paragraph = null;
        shadowParagraph = null;
        truncated = null;
        markTreeDirty();
    }

    // ── Shaping ──────────────────────────────────────────────────────────────

    private CgFontFamily resolveFamily() {
        var general = getStyle().getGeneralGroup();
        return FontFamilyCache.resolve(general.fontFamily(), Math.round(general.fontSize()));
    }

    private CgFontFamilyGroup resolveGroup() {
        var general = getStyle().getGeneralGroup();
        return FontFamilyCache.resolveGroup(general.fontFamily(), Math.round(general.fontSize()));
    }

    private boolean wraps() {
        // THE COMPUTED STYLE, not the authored group. `white-space` INHERITS -- a container sets it
        // for a whole subtree -- and the group answers only for what was written on this node, so an
        // inherited `nowrap` never arrived and the text went on wrapping with the rule plainly there.
        WhiteSpace whiteSpace = computedStyle().get(StylePropertyRegistry.WHITE_SPACE);
        return whiteSpace == null || whiteSpace.wraps();
    }

    /**
     * The retained paragraph, rebuilt only when an input to shaping has changed.
     *
     * <p>Reference equality on the family is intentional and correct: {@link FontFamilyCache#resolve}
     * caches by {@code (paths, targetPx)}, so an unchanged font always returns the same instance.</p>
     */
    private CgShapedParagraph ensureShaped() {
        String currentText = text.get();
        CgFontFamily family = resolveFamily();
        Map<String, HighlightStyle> styles = resolveHighlightStyles();
        Set<TextDecorationLine> decorations = ownDecorations();
        var general = getStyle().getGeneralGroup();
        boolean bold = general.fontWeight().isBold();
        boolean italic = general.fontStyle().isItalic();

        if (paragraph == null
                || !currentText.equals(shapedText)
                || family != shapedFamily
                || !styles.equals(shapedHighlights)
                || !decorations.equals(shapedDecorations)
                || bold != shapedBold
                || italic != shapedItalic) {
            paragraph = shape(currentText, family, styles, false);
            shapedText = currentText;
            shapedFamily = family;
            shapedHighlights = styles;
            shapedDecorations = decorations;
            shapedBold = bold;
            shapedItalic = italic;
            shadowParagraph = null;
        }
        return paragraph;
    }

    /**
     * The one place plain and highlighted shaping diverge.
     *
     * @param limit  characters the ranges may cover — the truncation path paints a prefix
     * @param shadow darken every highlight colour, for the {@code text-shadow} pass
     */
    private CgShapedParagraph shape(String content, CgFontFamily family,
                                    Map<String, HighlightStyle> styles, boolean shadow) {
        List<CgStyleSpan> spans = toCgSpans(styles, content.length(), shadow);
        if (spans.isEmpty()) return CgTextLayout.of(content, family).shape();
        return CgTextLayout.of(new CgStyledText(content, spans), resolveGroup()).shape();
    }

    /**
     * The style of every highlight name registered here, as the cascade resolves it.
     *
     * <p>A name with no matching rule resolves to empty and is dropped, so a highlighter emitting
     * names the current theme ignores costs one lookup each and produces no spans at all — which is
     * what keeps such an element on the fast path.</p>
     */
    private Map<String, HighlightStyle> resolveHighlightStyles() {
        if (highlights.isEmpty() || styleEngine() == null) return Collections.emptyMap();
        Map<String, HighlightStyle> resolved = new LinkedHashMap<>();
        for (String name : highlights.names()) {
            HighlightStyle style = styleEngine().highlightStyle(this, name);
            if (!style.isEmpty()) resolved.put(name, style);
        }
        return resolved;
    }

    private Set<TextDecorationLine> ownDecorations() {
        return computedStyle().get(StylePropertyRegistry.TEXT_DECORATION_LINE);
    }

    private int ownDecorationColor() {
        Integer color = computedStyle().get(StylePropertyRegistry.TEXT_DECORATION_COLOR);
        return color == null ? 0 : color;
    }

    /**
     * Flattens registered ranges plus their resolved styles into backend spans, clipped to
     * {@code limit}.
     *
     * <p>The clip is what makes truncation safe: it leaves ranges pointing past the end of the string
     * that will actually be shaped, and {@code CgStyledText} rejects that outright — from inside a
     * paint, on a later frame, with no caller code on the stack.</p>
     *
     * <p><b>Overlapping highlights resolve by registration order</b>, last one winning; non-overlap
     * within one name is the registry's own rule. The web layers them by priority instead, and this
     * is the simpler rule that a single shaped run per character can actually express.</p>
     */
    private List<CgStyleSpan> toCgSpans(Map<String, HighlightStyle> styles, int limit, boolean shadow) {
        Set<CgTextDecoration> base = toCgDecorations(ownDecorations());
        var general = getStyle().getGeneralGroup();
        boolean bold = general.fontWeight().isBold();
        boolean italic = general.fontStyle().isItalic();

        // Whether an UNHIGHLIGHTED stretch still needs a span. A decoration belongs to the range it was
        // asked for; a weight belongs to the whole label -- so a bold title with a search match in it
        // must not go thin for the matched characters, which means the uncovered stretches need spans
        // of their own the moment any of the three is set.
        boolean baseSpanNeeded = !base.isEmpty() || bold || italic;

        // CLEARED FIRST, because the two early returns are the case where a label that USED to carry a
        // band no longer does -- and a leftover array is not a stale style, it is a band over the wrong
        // text entirely. paintHighlightBands reads perChar[run.sourceStart()] and an unhighlighted label
        // shapes as ONE run starting at 0, so a single stale entry at index 0 paints the whole string.
        // Pooled rows made that routine: every explorer row that had ever shown a match went on banding
        // whatever filename landed on it next, full width, while the count still said "1 of 1".
        if (!shadow) this.highlightPerChar = null;

        if (styles.isEmpty() && !baseSpanNeeded) return Collections.emptyList();
        if (styles.isEmpty()) {
            return limit <= 0 ? Collections.emptyList()
                    : List.of(new CgStyleSpan(0, limit, bold, italic, base, 0, null, 0f,
                            ownDecorationColor()));
        }

        // Winner per character, then run-length encoded -- the only way to get disjoint spans out of
        // ranges that may overlap across names.
        HighlightStyle[] perChar = new HighlightStyle[limit];
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
                if (baseSpanNeeded && runStart > uncoveredFrom) {
                    out.add(new CgStyleSpan(uncoveredFrom, runStart, bold, italic, base, 0, null, 0f,
                            ownDecorationColor()));
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

    private CgStyleSpan toCgSpan(HighlightStyle style, int start, int end, boolean shadow,
                                 boolean bold, boolean italic) {
        int inherited = getStyle().getGeneralGroup().color();
        int color = style.color(inherited);
        if (shadow) color = shadowColorFor(color);
        return new CgStyleSpan(start, end,
                style.isBold(bold), style.isItalic(italic),
                toCgDecorations(style.decorations()),
                color, null, 0f, style.decorationColor());
    }

    private static Set<CgTextDecoration> toCgDecorations(Set<TextDecorationLine> lines) {
        if (lines == null || lines.isEmpty()) return Collections.emptySet();
        Set<CgTextDecoration> out = EnumSet.noneOf(CgTextDecoration.class);
        for (TextDecorationLine line : lines) {
            switch (line) {
                case UNDERLINE -> out.add(CgTextDecoration.UNDERLINE);
                case LINE_THROUGH -> out.add(CgTextDecoration.STRIKETHROUGH);
                default -> { }
            }
        }
        return out;
    }

    // ── The three observables ────────────────────────────────────────────────
    //
    // Truncation changes no geometry, a decoration changes no computed style a test can distinguish,
    // and a band changes neither -- so all three are invisible to any assertion about the cascade, and
    // each shipped broken at least once with every observable around it correct.

    /**
     * What will actually be painted — the truncated string when an ellipsis fired, the text otherwise.
     *
     * <p>The ONLY way to observe that truncation happened, and the answer to "tooltip only when the
     * label is ellipsized".</p>
     */
    public String displayedText() {
        Box box = box();
        if (box == null || !isEllipsized()) return text.get();
        return truncatedStringFor(resolveFamily(), box.contentBoxWidth());
    }

    /** How many characters currently carry a highlight BAND — the only observable of the band pass. */
    public int highlightBandCount() {
        HighlightStyle[] perChar = highlightPerChar;
        if (perChar == null) return 0;
        int count = 0;
        for (HighlightStyle style : perChar) {
            if (style != null && (style.backgroundColor() >>> 24) != 0) count++;
        }
        return count;
    }

    /** How many style spans the last shaping used — zero meaning the plain, unspanned path. */
    public int styleSpanCount() {
        return toCgSpans(resolveHighlightStyles(), text.get().length(), false).size();
    }

    // ── Hit testing ──────────────────────────────────────────────────────────

    /**
     * The character offset under a point in this node's own space, or {@code -1}.
     *
     * <p>Walks the shaped runs the paint walks, deliberately: if the two disagreed about where a run
     * sits, a click would land on a different character than the one under the cursor — the same
     * reason hit-testing and the pose share one matrix.</p>
     */
    public int offsetAt(float localX, float localY) {
        Box box = box();
        if (box == null || text.get().isEmpty()) return -1;
        CgTextLayout layout = ensureShaped().layout(wraps() ? box.contentBoxWidth() : 0f, 0f);
        List<List<CgShapedRun>> lines = layout.lines();
        if (lines.isEmpty()) return -1;

        float contentX = box.border().left + box.padding().left;
        float contentY = box.border().top + box.padding().top;
        float y = localY - contentY;
        if (y < 0f) return -1;
        float lineHeight = layout.totalHeight() / lines.size();
        int lineIndex = (int) (y / Math.max(1f, lineHeight));
        if (lineIndex < 0 || lineIndex >= lines.size()) return -1;

        float x = localX - contentX;
        float at = 0f;
        for (CgShapedRun run : lines.get(lineIndex)) {
            at += run.totalAdvance();
            if (x < at) return run.sourceStart();
        }
        return -1;
    }

    /** {@link #offsetAt} from a point in surface pixels. */
    public int offsetAtScreen(float surfaceX, float surfaceY) {
        var local = toLocal(surfaceX, surfaceY);
        return offsetAt(local.x(), local.y());
    }

    // ── Painting ─────────────────────────────────────────────────────────────

    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        var general = getStyle().getGeneralGroup();
        float contentWidth = box.contentBoxWidth();
        float contentX = box.border().left + box.padding().left
                + general.textOffsetX().resolve(box.width());
        float contentY = box.border().top + box.padding().top
                + general.textOffsetY().resolve(box.height());

        CgFontFamily family = resolveFamily();
        boolean wraps = wraps();
        CgTextLayout layout = wraps
                ? ensureShaped().layout(contentWidth, 0f)
                : truncatedIfNeeded(family, contentWidth);

        // text-align is a distribution of what is LEFT OVER, which is why it is applied here rather
        // than inside the layout: the paragraph does not know how wide its box is.
        float leftover = Math.max(0f, contentWidth - layout.totalWidth());
        contentX += leftover * general.textAlign().leadingFraction();

        paintHighlightBands(ctx, layout, contentX, contentY);
        if (layout.lines().isEmpty() || text.get().isEmpty()) return;

        int color = general.color();
        if (general.textShadow()) {
            ctx.text().draw()
                    .layout(shadowLayoutFor(layout, family, contentWidth, wraps))
                    .family(family)
                    .at(contentX + 1f, contentY + 1f)
                    .color(shadowColorFor(color))
                    .pose(ctx.getPoseStack())
                    .submit();
        }
        ctx.text().draw().layout(layout).family(family)
                .at(contentX, contentY)
                .color(color)
                .pose(ctx.getPoseStack())
                .submit();
    }

    /**
     * The band behind a highlighted range.
     *
     * <p>Returns immediately for every ordinary label: {@link #highlightPerChar} is null unless
     * something resolved a highlight.</p>
     *
     * <p><b>Adjacent runs of one highlight are ONE band</b>, accumulated and flushed when the style
     * changes. Per-run was indistinguishable while a band was a plain rectangle — two abutting rects
     * of one colour look like one — and stops being so the moment a band has geometry: per-run padding
     * opens a gap inside a single highlighted phrase and per-run rounding rounds every interior
     * boundary, so one phrase draws as several pills. Shaping breaks a run for reasons of its own (a
     * font fallback, a script change), so "one highlight" and "one run" were never the same thing.</p>
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
            HighlightStyle pending = null;
            float pendingX = x;
            float pendingWidth = 0f;
            for (CgShapedRun run : line) {
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
                pendingWidth += run.totalAdvance();
                x += run.totalAdvance();
            }
            paintBand(ctx, pending, pendingX, y, pendingWidth, lineHeight);
            y += lineHeight;
        }
    }

    private void paintBand(CgUiPaintContext ctx, @Nullable HighlightStyle style,
                           float x, float y, float width, float height) {
        if (style == null || width <= 0f) return;
        int background = style.backgroundColor();
        if ((background >>> 24) == 0) return;
        ctx.fillRect(x, y, width, height, background);
    }

    // ── Ellipsis ─────────────────────────────────────────────────────────────

    private boolean isEllipsized() {
        return getStyle().getGeneralGroup().textOverflow() == TextOverflow.ELLIPSIS;
    }

    private CgTextLayout truncatedIfNeeded(CgFontFamily family, float contentWidth) {
        String display = truncatedStringFor(family, contentWidth);
        if (display.equals(text.get())) return ensureShaped().layout(0f, 0f);
        // A different, shorter string, so the retained paragraph cannot serve. Spans come along
        // clipped, which is what keeps a half-highlighted token from failing validation.
        //
        // Known and accepted: the search that chose this cut measured UNSPANNED, while what is painted
        // here is spanned, and separately-shaped runs lose the kerning across their boundary. The
        // painted width can differ by a fraction of a pixel per span. Making the probe span-aware means
        // shaping styled text on every step of the search -- the exact cost the memo exists to avoid --
        // for an error smaller than the ellipsis glyph.
        return shape(display, family, shapedHighlights, false).layout(0f, 0f);
    }

    /**
     * The string that fits, with an ellipsis, memoised on everything the answer depends on.
     *
     * <p>Truncates the STRING and re-shapes; it never drops glyphs from a shaped run, because shaping
     * is not a per-character mapping and cutting the glyph array splits clusters.</p>
     */
    private String truncatedStringFor(CgFontFamily family, float contentWidth) {
        String source = text.get();
        if (!isEllipsized()) return source;
        if (contentWidth <= 0f || ensureShaped().layout(0f, 0f).totalWidth() <= contentWidth) {
            return source;
        }
        if (truncated != null && source.equals(truncatedForText) && family == truncatedForFamily
                && contentWidth == truncatedForWidth) {
            return truncated;
        }

        String ellipsis = ellipsisFor(family);
        int low = 0;
        int high = source.length();
        String best = ellipsis;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidate = source.substring(0, mid) + ellipsis;
            if (probeWidth(candidate, family) <= contentWidth) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        truncated = best;
        truncatedForText = source;
        truncatedForFamily = family;
        truncatedForWidth = contentWidth;
        return best;
    }

    /**
     * The width of one probe, <b>measured at this element's own weight</b>.
     *
     * <p>The search is a binary search over widths, so it is only correct if what it probes is what
     * will be painted — and synthetic bold is wider than regular. Measured thin and painted bold,
     * every truncation cuts a character or two late and the ellipsis sits outside the box it was
     * computed to fit. Ordinary labels stay on the unspanned shaper, which is a fraction of a pixel
     * away from the styled one and a fraction of a pixel this method compares against a box.</p>
     */
    private float probeWidth(String candidate, CgFontFamily family) {
        var general = getStyle().getGeneralGroup();
        boolean bold = general.fontWeight().isBold();
        boolean italic = general.fontStyle().isItalic();
        if (!bold && !italic) return CgTextLayout.of(candidate, family).build().totalWidth();
        return CgTextLayout.of(
                new CgStyledText(candidate, List.of(
                        new CgStyleSpan(0, candidate.length(), bold, italic, null, 0, null, 0f, 0))),
                resolveGroup()).build().totalWidth();
    }

    /**
     * {@code …} when the font stack can draw it, {@code ...} when it cannot.
     *
     * <p>WebKit and Blink's own rule, and not hypothetical here: the bundled {@code
     * MinecraftRegular.otf} has no U+2026, so without the fallback a truncated label draws a blank
     * advance and is indistinguishable from {@code clip}.</p>
     */
    private static String ellipsisFor(CgFontFamily family) {
        // resolveSourceForCodePoint AND canDisplayCodePoint: the resolve alone cannot tell "found it"
        // from "gave up and handed back the primary".
        var source = family.resolveSourceForCodePoint(ELLIPSIS_CODE_POINT);
        return source != null && source.canDisplayCodePoint(ELLIPSIS_CODE_POINT)
                ? ELLIPSIS
                : ELLIPSIS_FALLBACK;
    }

    // ── Text shadow ──────────────────────────────────────────────────────────

    /**
     * The layout for the shadow pass, which is the ordinary one unless a highlight sets a colour.
     *
     * <p>A shadow is drawn by re-submitting the same layout one pixel down in a darker colour, and a
     * per-span colour survives that: a highlighted word would keep its bright colour in the shadow and
     * read as a second, offset copy of itself. Only then is a darkened twin worth shaping.</p>
     */
    private CgTextLayout shadowLayoutFor(CgTextLayout ordinary, CgFontFamily family,
                                         float contentWidth, boolean wraps) {
        if (shapedHighlights.isEmpty()) return ordinary;
        if (shadowParagraph == null) {
            shadowParagraph = shape(text.get(), family, shapedHighlights, true);
        }
        return shadowParagraph.layout(wraps ? contentWidth : 0f, 0f);
    }

    private static int shadowColorFor(int color) {
        int a = color >>> 24;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (a << 24) | ((r / 4) << 16) | ((g / 4) << 8) | (b / 4);
    }

    @Override
    public String toString() {
        return "UIText(" + Objects.toString(text.get(), "") + ")";
    }
}
