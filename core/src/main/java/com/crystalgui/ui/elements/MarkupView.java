package com.crystalgui.ui.elements;

import com.crystalgui.text.markup.MarkupBlock;
import com.crystalgui.text.markup.MarkupDocument;
import com.crystalgui.text.markup.MarkupSpan;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.text.SyntaxHighlighting;
import com.crystalgui.ui.text.TextRange;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>A {@link MarkupDocument}, drawn</b> — the renderer half of the documentation stack.
 *
 * <h3>There was no renderer to port, and finding that out is the answer</h3>
 *
 * <p>The obvious move was to port IntelliJ's, since it is the most refined of the references.
 * {@code JavaDocInfoGenerator} turns out to be an <em>emitter</em> only: it writes HTML into a builder
 * and hands the string to a Swing {@code JEditorPane}, which does the layout, the code-block background
 * and the links. So IntelliJ's renderer is Swing's HTML view — a general HTML layout engine, under the
 * JDK's own licence, and far more than a documentation popup needs. VS Code's is `marked` plus its
 * `MarkdownRenderer`, which is the same shape one format over. <b>Both references end at a renderer that
 * already speaks their intermediate form; this engine's equivalent is elements and styled runs.</b></p>
 *
 * <p>So the emitter is ported ({@code JavaDocs} follows {@code JavaDocInfoGenerator}'s rules and says so)
 * and the renderer is ours. It is small, because the text engine already has the two things it needs: a
 * wrapping text element, and the CSS Custom Highlight API for styling ranges inside one.</p>
 *
 * <h3>One text run per block, with ranges over it — never a sibling per span</h3>
 *
 * <p>{@code DocumentationPopup} argues this for its signature line, where the reason is truncation. Here
 * the reason is <b>wrapping</b>, and it is stronger. Prose has to re-flow to the popup's width, and a
 * sentence split into a text element per style is a row of flex items: the line can only break where one
 * element ends, so a bold word mid-sentence becomes a break opportunity and a long word inside one
 * fragment overflows rather than wrapping. One run wraps at every word, exactly as text does.</p>
 *
 * <p>{@link MarkupSpan#styles()} is a bitset, so a run that is both {@code <b>} and {@code <code>}
 * registers in both bands and the cascade composes them — which is the reason the model does not carry an
 * enum.</p>
 *
 * <h3>Two things that are easy to get backwards</h3>
 *
 * <p><b>The blocks are PUBLIC children of this view, not internal ones.</b> Internal is the instinct —
 * they are structure the widget owns, and every other composite here marks its parts that way. It is
 * wrong for a view that is <em>rebuilt</em>: {@link UIElement#clearAllChildren()} skips internal children
 * by design, so an internal build silently accumulates every document the view has ever been given, one
 * on top of the last. The parts of a widget that are built once are internal; content that is replaced is
 * not.</p>
 *
 * <p><b>Every highlight name is cleared before any is set</b>, on every block. A band that is only
 * assigned where there is something to say leaves the previous document's ranges live everywhere else —
 * and those are offsets into a string that has since been replaced, so the band lands on whatever
 * characters moved into them. {@code UIText}'s own note records this as the failure that is not a stale
 * colour but a colour over the wrong text entirely.</p>
 *
 * <h3>What it does not do yet</h3>
 *
 * <p>{@link MarkupSpan#target()} is carried by the model and dropped here: links are styled and are not
 * clickable. The target survives to this layer so that adding navigation is a listener rather than a
 * re-parse, which is the whole reason the emitter bothers to carry it.</p>
 */
public class MarkupView extends UIElement implements UIFrameTicker {

    /** One paragraph of running text. */
    public static final String PARAGRAPH_CLASS = "__markup-paragraph__";

    /** A {@code <pre>} sample — its own box, because it needs a background and keeps its whitespace. */
    public static final String CODE_BLOCK_CLASS = "__markup-code__";

    /** A heading; the level rides along as {@link #HEADING_LEVEL_PREFIX} + n. */
    public static final String HEADING_CLASS = "__markup-heading__";

    /** {@code __markup-h__} + the level, so a theme can size {@code h1} apart from {@code h3}. */
    public static final String HEADING_LEVEL_PREFIX = "__markup-h";

    /** A {@code <ul>} or {@code <ol>} — its items stacked. */
    public static final String LIST_CLASS = "__markup-list__";

    /** A {@code <li>} — the bullet and the item's own blocks, side by side. */
    public static final String ITEM_CLASS = "__markup-item__";

    /** The bullet or number in front of an item. */
    public static final String BULLET_CLASS = "__markup-bullet__";

    /** The column holding an item's content, so a nested list indents against it. */
    public static final String ITEM_BODY_CLASS = "__markup-item-body__";

    /** A {@code <blockquote>}. */
    public static final String QUOTE_CLASS = "__markup-quote__";

    /** A {@code <dl>} — the two-column block a documentation section table is drawn as. */
    public static final String DEFINITIONS_CLASS = "__markup-definitions__";

    /** One {@code <dt>}/{@code <dd>} pair, side by side. */
    public static final String DEFINITION_ROW_CLASS = "__markup-definition__";

    /** The label column of a row. */
    public static final String TERM_CLASS = "__markup-term__";

    /** The value column of a row. */
    public static final String DETAIL_CLASS = "__markup-detail__";

    /**
     * The quote's rule, as an <b>element</b> rather than a border.
     *
     * <p>A one-sided {@code border-width-*} in this engine either draws all four edges or none, depending
     * on which edge is named — so a single edge is spelled as a 1px-wide box. {@code statusbarview}'s
     * separators are the same shape.</p>
     */
    public static final String QUOTE_RULE_CLASS = "__markup-quote-rule__";

    /** {@code ::highlight()} band for inline {@code <code>}. */
    public static final String CODE_RANGE = "markup-code";

    /** {@code ::highlight()} band for {@code <b>}/{@code <strong>}. */
    public static final String STRONG_RANGE = "markup-strong";

    /** {@code ::highlight()} band for {@code <i>}/{@code <em>}. */
    public static final String EMPHASIS_RANGE = "markup-emphasis";

    /** {@code ::highlight()} band for {@code <a>}. */
    public static final String LINK_RANGE = "markup-link";

    /**
     * The one link the pointer is currently over.
     *
     * <p>A second band rather than a {@code :hover} rule, and it has to be: a paragraph is ONE text
     * element, so {@code :hover} on it is true whenever the pointer is anywhere in the sentence and would
     * underline every link in it at once. A link is a RANGE, and only a range-sized answer can say which
     * one.</p>
     */
    public static final String LINK_HOVER_RANGE = "markup-link-hover";

    /**
     * On the run whose link the pointer is over, for the {@code cursor} the band cannot carry.
     *
     * <p>A class and not a pseudo-class: {@code :hover} is true anywhere in a paragraph, and this has to
     * mean "over one of its links".</p>
     */
    public static final String LINK_HOVER_CLASS = "__markup-link-hover__";

    /**
     * Every band this view writes, cleared on each run before any is set.
     *
     * <p>Named as a list rather than reset by clearing the whole registry, because a caller may register
     * bands of its own on a text element it owns — search matches on a filtered row are the live example.
     * A blanket clear would take those with it.</p>
     */
    private static final String[] BANDS =
            {CODE_RANGE, STRONG_RANGE, EMPHASIS_RANGE, LINK_RANGE, LINK_HOVER_RANGE};

    /**
     * Where each run's links are, so a press can be turned back into a target.
     *
     * <p>Kept beside the runs rather than on them: a {@code UIText} has nowhere to put an arbitrary
     * payload, and a parallel map keyed by the element is what {@code MarkupView} can clear wholesale
     * when it rebuilds. Cleared in {@link #setDocument}, or a document's links would answer for the
     * next document's text.</p>
     */
    private final Map<UIText, List<LinkSpan>> links = new LinkedHashMap<>();

    /** Which link each run is currently showing as hovered, so a move only writes on a change. */
    private final Map<UIText, LinkSpan> hovered = new LinkedHashMap<>();

    /** Every label cell in the document, so they can be given one shared width. @see #alignTerms */
    private final List<UIText> terms = new ArrayList<>();

    /** One link's extent within its run, and where it points. */
    private record LinkSpan(int start, int end, String target) {
    }

    private MarkupDocument document = MarkupDocument.EMPTY;

    /**
     * What a {@code <pre>} sample is written in, or null for no colouring.
     *
     * <p><b>The consumer says, because only the consumer knows.</b> A code sample in a doc comment is in
     * the language of the document that carries it — Java in a javadoc, JavaScript in a JSDoc — and this
     * view has never seen the document. Guessing from the sample's own text is the alternative and is the
     * thing every renderer that tries it gets wrong on short samples.</p>
     *
     * <p>Null rather than {@code Language.PLAIN} so "nobody has said" and "explicitly plain" stay
     * different: a caller that never sets one gets uncoloured samples rather than a lookup per block.</p>
     */
    @Nullable
    private Language codeLanguage;

    /**
     * Fired when a link is pressed, with the {@code href} the markup carried.
     *
     * <p>A signal rather than an action, because this view has no idea what a target means: a javadoc
     * {@code {@link}} arrives as {@code java:java.util.List} and only something holding an engine can
     * turn that into anything. The same shape the popup's footer pencil already uses — the widget states
     * the intent and its host decides.</p>
     */
    public final Signal.Value<String> onLinkActivated = new Signal.Value<>();

    public MarkupView() {
        this(MarkupDocument.EMPTY);
    }

    public MarkupView(MarkupDocument document) {
        setDocument(document);
    }

    /** What is currently drawn. */
    public MarkupDocument getDocument() {
        return document;
    }

    /**
     * Colours {@code <pre>} samples as {@code language}.
     *
     * <p>Takes effect on the next {@link #setDocument}, not retroactively — a view is rebuilt whenever
     * its content changes anyway, and re-tokenizing what is already on screen would mean holding every
     * sample's source to do it with.</p>
     */
    public MarkupView setCodeLanguage(@Nullable Language language) {
        this.codeLanguage = language;
        return this;
    }

    /** @see #setCodeLanguage */
    @Nullable
    public Language getCodeLanguage() {
        return codeLanguage;
    }

    /**
     * Replaces the content.
     *
     * <p>Rebuilds outright rather than diffing. A documentation popup shows one symbol at a time and the
     * next symbol shares nothing with this one, so a diff would compare two unrelated trees to discover
     * that — and the elements it would be preserving are a handful of text nodes.</p>
     */
    public MarkupView setDocument(MarkupDocument document) {
        this.document = document == null ? MarkupDocument.EMPTY : document;
        links.clear();
        hovered.clear();
        terms.clear();
        clearAllChildren();
        for (MarkupBlock block : this.document.blocks()) {
            UIElement built = build(block);
            if (built != null) addChild(built);
        }
        return this;
    }

    /** True when there is nothing to draw — the caller's cue to hide the band entirely. */
    public boolean isEmpty() {
        return document.blocks().isEmpty();
    }

    /**
     * Turns a press on a run into the link under it, if there is one.
     *
     * <p><b>Target phase and no bubbling</b>: the run IS what was pressed, and stopping there matters
     * because an ancestor may read the same press as something else — {@code DocumentationPopup} begins a
     * MOVE on any press that reaches it, so a link click would drag the box a few pixels while following
     * the link. A press on ordinary prose is left alone and still reaches that ancestor.</p>
     *
     * <p>The offset comes from {@link UIText#offsetAt}, which resolves to a shaped run — and a link is
     * its own span, so it is its own run. That makes "which link" exact even though "which letter" is
     * not.</p>
     */
    private void attachLinkPress(UIText run) {
        run.onMouseDown.attachListener((element, event) -> {
            LinkSpan span = linkAt(run,
                    run.offsetAtScreen(event.getPosition().x(), event.getPosition().y()));
            if (span == null) return;
            onLinkActivated.emit(span.target());
            event.stopPropagation();
        }, false, false);
    }

    /** The link covering {@code offset} in this run, or {@code null} — including for {@code -1}. */
    @Nullable
    private LinkSpan linkAt(UIText run, int offset) {
        if (offset < 0) return null;
        for (LinkSpan span : links.getOrDefault(run, List.of())) {
            if (offset >= span.start() && offset < span.end()) return span;
        }
        return null;
    }

    /**
     * Marks the link covering {@code offset} in this run, or none.
     *
     * <p>Public because it is the seam a test drives. The pointer path runs through
     * {@link #tickFrame}, which needs a live window, a settled layout and a painted frame before it can
     * answer anything — so a test going that way would be exercising the hit test rather than the band.
     * A host with its own pointer model has the same need.</p>
     */
    public void hoverAt(UIText run, int offset) {
        hover(run, linkAt(run, offset));
    }

    private void hover(UIText run, @Nullable LinkSpan span) {
        LinkSpan previous = hovered.get(run);
        if (previous == span) return;
        hovered.put(run, span);
        run.highlights().set(LINK_HOVER_RANGE,
                span == null ? List.of() : List.of(TextRange.of(span.start(), span.end())));
        // THE CURSOR IS A CLASS; the underline is a BAND. Not an inconsistency -- they are different
        // granularities and each is at the only one it can be. Which link is underlined is a RANGE
        // answer, and only a band is range-sized; `cursor` is a property of an element and can only ever
        // be element-wide.
        //
        // A class rather than an IMPORTANT style write, because an IMPORTANT write has to name a resting
        // value to undo itself with -- and "prose is an I-beam" is a decision the sheet should own, not
        // one Java should hard-code. It is also what the engine already says about state a widget flips
        // from its own listener: a class, re-evaluated on your terms, never a pseudo-class.
        if (span == null) run.removeClass(LINK_HOVER_CLASS);
        else run.addClass(LINK_HOVER_CLASS);
    }

    private UIElement build(MarkupBlock block) {
        switch (block.kind()) {
            case PARAGRAPH:
                return text(block.spans(), PARAGRAPH_CLASS);
            case HEADING: {
                UIText heading = text(block.spans(), HEADING_CLASS);
                heading.addClass(HEADING_LEVEL_PREFIX + Math.max(1, Math.min(6, block.level())) + "__");
                return heading;
            }
            case CODE:
                return codeBlock(block);
            case LIST:
                return list(block);
            case ITEM:
                return item(block, "•");
            case QUOTE:
                return quote(block);
            case DEFINITIONS:
                return definitions(block);
            default:
                return null;
        }
    }

    /**
     * A {@code <pre>} sample.
     *
     * <p>Its own element rather than a {@link #CODE_RANGE} band, and the difference is not decoration: a
     * sample needs a box — a background behind the whole run including the ends of short lines, padding
     * inside it, and its own width to scroll in. A highlight band paints behind the glyphs and stops
     * there, which is right for a word in a sentence and wrong for a block.</p>
     *
     * <p>The text is set verbatim. {@code MarkupParser} does not collapse whitespace inside {@code <pre>},
     * so the indentation here is the author's and the stylesheet must not re-wrap it.</p>
     */
    private UIElement codeBlock(MarkupBlock block) {
        // A SCROLLERVIEW, so a sample wider than the popup gets a BAR rather than being cut off at the
        // edge with nothing to say it continues. A plain element with `overflow: auto` scrolls too --
        // scrolling is an element capability here, not a widget feature -- but it scrolls invisibly, and
        // a code sample that silently ends mid-token reads as the renderer having truncated it.
        ScrollerView box = new ScrollerView();
        box.addClass(CODE_BLOCK_CLASS);
        UIText sample = new UIText(block.text());
        // LEXED, NOT RESOLVED. A grammar knows a keyword from an identifier from a string, which is the
        // whole of what a sample in a doc comment needs -- it is an illustration rather than part of the
        // program, so there is nothing to resolve it against and nothing that would be true if there were.
        // The capture names are the ones the editor's scheme already defines, so a sample is coloured by
        // the same rules as the code it describes, with no second vocabulary to keep in step.
        SyntaxHighlighting.highlight(sample, block.text(), codeLanguage);
        box.addChild(sample);
        return box;
    }

    private UIElement list(MarkupBlock block) {
        UIElement box = new UIElement();
        box.addClass(LIST_CLASS);
        boolean ordered = block.level() == 1;
        int index = 1;
        for (MarkupBlock child : block.children()) {
            // THE MARKER IS DECIDED HERE, not by the item, because it is a property of the LIST: the same
            // `<li>` is a bullet in a `<ul>` and a number in an `<ol>`, and only its parent knows which.
            String marker = ordered ? (index++) + "." : "•";
            box.addChild(item(child, marker));
        }
        return box;
    }

    private UIElement item(MarkupBlock block, String marker) {
        UIElement row = new UIElement();
        row.addClass(ITEM_CLASS);

        UIText bullet = new UIText(marker);
        bullet.addClass(BULLET_CLASS);
        row.addChild(bullet);

        UIElement body = new UIElement();
        body.addClass(ITEM_BODY_CLASS);
        if (!block.spans().isEmpty()) body.addChild(text(block.spans(), PARAGRAPH_CLASS));
        for (MarkupBlock child : block.children()) {
            UIElement built = build(child);
            if (built != null) body.addChild(built);
        }
        row.addChild(body);
        return row;
    }

    /**
     * A {@code <dl>} as a stack of two-column rows.
     *
     * <p>Terms and details are <b>siblings</b> in HTML, not nested — so pairing them is this
     * method's job, and a stray {@code <dd>} with no {@code <dt>} before it gets an empty label rather
     * than being dropped. A malformed list still shows what it says.</p>
     *
     * <p>Rows rather than a real two-column grid, because a label column that wraps is the whole point:
     * "Implementation Requirements:" is longer than the column and must break inside it while its value
     * stays beside it. Two independent columns would let the two drift apart vertically.</p>
     */
    private UIElement definitions(MarkupBlock block) {
        UIElement box = new UIElement();
        box.addClass(DEFINITIONS_CLASS);

        UIElement detail = null;
        for (MarkupBlock child : block.children()) {
            if (child.kind() == MarkupBlock.Kind.TERM) {
                detail = openRow(box, spansOf(child));
                continue;
            }
            // A `<dd>` with no `<dt>` before it opens its own row with an empty label rather than being
            // dropped: malformed markup still shows what it says.
            if (detail == null) detail = openRow(box, List.of());
            // APPENDED, never replaced. HTML lets several `<dd>`s share one `<dt>` -- `@throws` with two
            // exceptions is the everyday case -- and replacing meant every value but the last vanished
            // with nothing to show it had.
            for (MarkupBlock content : child.children()) {
                UIElement built = build(content);
                if (built != null) detail.addChild(built);
            }
        }
        return box;
    }

    /**
     * Starts a row and answers its value column, empty and ready to be filled.
     *
     * <p>Terms and details are <b>siblings</b> in HTML rather than nested, so pairing them is this
     * view's job: the row is the thing that owns both, and it has to exist before either does.</p>
     */
    private UIElement openRow(UIElement box, List<MarkupSpan> label) {
        UIElement row = new UIElement();
        row.addClass(DEFINITION_ROW_CLASS);
        box.addChild(row);

        UIText term = term(label);
        terms.add(term);
        row.addChild(term);

        UIElement detail = new UIElement();
        detail.addClass(DETAIL_CLASS);
        row.addChild(detail);
        return detail;
    }

    /**
     * Gives every label cell the width of the widest one.
     *
     * <p>What a table does, and what makes the values line up while the gap stays the same everywhere.
     * The alternative is a fixed width, which cannot be both wide enough for the longest label and tight
     * against the shortest — that is the whole of the complaint this answers.</p>
     *
     * <p><b>It settles</b>, for the reason {@code UIText}'s own sizing does: the width is pushed as an
     * IMPORTANT candidate and {@code replaceOrPutCandidate} no-ops when the value is unchanged, so the
     * second pass writes nothing and the loop stops. Once written, every label reports the shared width,
     * so the maximum is stable rather than creeping.</p>
     *
     * <p>Skipped entirely until something has a width — the first layout of a freshly built document
     * reports zero for everything, and equalising to zero would latch it.</p>
     */
    private void alignTerms() {
        if (terms.isEmpty()) return;
        float widest = 0f;
        for (UIText label : terms) widest = Math.max(widest, label.getRuntimeCache().getWidth());
        if (widest <= 0f) return;
        float shared = widest;
        for (UIText label : terms) {
            // MIN-WIDTH, NOT WIDTH, and that is not a style choice. A label is `forceSelfSizeWidth`, so
            // `UIText.recompute` pushes its own measured width at IMPORTANT on every pass -- writing
            // `width` here puts two IMPORTANT candidates on ONE property and whichever ran last wins the
            // frame, so the labels never equalised and could have flickered. A floor is a different
            // property: nothing else writes it, the natural width is never larger than the widest, and
            // `flex-shrink: 0` stops the row squeezing it back.
            StyleGroup.importantPipeline(label.getStyle().getLayoutGroup(), l -> l.minWidth(shared));
        }
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        alignTerms();
        // THE TICKER REGISTERS HERE, which is the idiom four other widgets already use: it is the first
        // moment this element is known to be in a window, and registration is idempotent.
        UIWindow window = getAttachedWindow();
        if (window != null && !links.isEmpty()) window.registerTicker(this);
    }

    /**
     * Follows the pointer, so the link under it can be underlined and given a cursor.
     *
     * <p><b>A ticker rather than {@code onMouseMove}, and that is not a preference.</b> A move listener
     * is the obvious build and was the first one; it never fired. Mine was the only
     * {@code onMouseMove.attachListener} in the engine — the same smell {@code display: grid} had,
     * and that turned out to be non-functional too — and it cannot be tested from here either, because
     * {@code updateWithoutPainting} deliberately skips input handling, which is presumably WHY nothing
     * else consumes move events. A frame ticker is a path four widgets already depend on.</p>
     *
     * <p>What CSS would do with {@code :hover} on an anchor, and there is no anchor: a paragraph is ONE
     * text element, so {@code :hover} is true anywhere in the sentence and cannot say which of its links
     * the pointer is on. A link is a RANGE, and only a range-sized answer names one.</p>
     *
     * <p>Cheap by construction. It runs only for a view that has links at all, asks the window for the
     * hovered element once, and {@link #hoverAt} writes nothing unless the answer CHANGED — so a
     * pointer sitting still, or travelling across prose, costs one hit test and a reference comparison
     * per run.</p>
     */
    @Override
    public boolean tickFrame(float deltaSeconds) {
        UIWindow window = getAttachedWindow();
        // FALSE DROPS THE TICKER, which is what a detached view wants: it re-registers on its next
        // layout, and a rebuilt document brings new runs anyway.
        if (window == null) return false;
        if (links.isEmpty()) return true;

        var pointer = window.getInputHandler().pointerPosition();
        UIElement hoveredElement = window.getHoveredElement(pointer.x(), pointer.y());
        for (UIText run : links.keySet()) {
            hoverAt(run, run == hoveredElement
                    ? run.offsetAtScreen(pointer.x(), pointer.y())
                    : -1);
        }
        return true;
    }

    /**
     * A label cell — <b>self-sizing</b>, which is what lets {@link #alignTerms} measure it.
     *
     * <p>A label routed through the ordinary paragraph path gets {@code neverSelfSizeWidth()}, which is
     * right for prose (it wraps to whatever box it is given) and reports a content width of <b>zero</b>.
     * Every label then measured as nothing, the shared width came out zero, and each label was drawn
     * underneath its own value — a cell refusing to answer the one question the layout asks it.</p>
     *
     * <p>A {@code <dt>} is a label rather than a block, so its own spans are enough and the styling on
     * them survives. The sheet caps its width, so a very long heading still wraps instead of pushing the
     * value column off the popup.</p>
     */
    private UIText term(List<MarkupSpan> spans) {
        UIText label = text(spans, TERM_CLASS);
        label.forceSelfSizeWidth();
        return label;
    }

    /** A row's spans — its own, or its first block's, since {@code closeItem} nests the content. */
    private static List<MarkupSpan> spansOf(MarkupBlock block) {
        if (!block.spans().isEmpty()) return block.spans();
        for (MarkupBlock child : block.children()) {
            if (!child.spans().isEmpty()) return child.spans();
        }
        return List.of();
    }

    private UIElement quote(MarkupBlock block) {
        UIElement box = new UIElement();
        box.addClass(QUOTE_CLASS);

        UIElement rule = new UIElement();
        rule.addClass(QUOTE_RULE_CLASS);
        box.addChild(rule);

        UIElement body = new UIElement();
        body.addClass(ITEM_BODY_CLASS);
        if (!block.spans().isEmpty()) body.addChild(text(block.spans(), PARAGRAPH_CLASS));
        for (MarkupBlock child : block.children()) {
            UIElement built = build(child);
            if (built != null) body.addChild(built);
        }
        box.addChild(body);
        return box;
    }

    /**
     * The spans of one block, as a single wrapping run with a {@code ::highlight()} band per style.
     *
     * <p>Offsets are counted as the string is assembled, so they are exact by construction rather than by
     * a search after the fact — a search would find the wrong occurrence of any word that repeats, which
     * in prose is most of them.</p>
     */
    private UIText text(List<MarkupSpan> spans, String styleClass) {
        StringBuilder assembled = new StringBuilder();
        Map<String, List<TextRange>> bands = new LinkedHashMap<>();
        List<LinkSpan> found = new ArrayList<>();

        for (MarkupSpan span : spans) {
            int start = assembled.length();
            // A CHIP'S PADDING IS A NON-BREAKING SPACE INSIDE THE BAND, not `padding` on the highlight.
            //
            // `HighlightStyle` does permit horizontal padding and it does paint -- but it inflates the
            // painted RECT and cannot move a glyph, which is the whole reason CSS forbids box properties
            // on a highlight. So the plate grew OUTWARDS into the single space either side of the chip and
            // ate it: `The String class` came out with the plate touching both neighbours, which reads as
            // the chip being badly aligned rather than as padding with nowhere to go.
            //
            // A space inside the band is the same padding with real advance behind it, so layout pushes
            // the neighbours away exactly as an HTML `<code>` box would -- one space outside the plate as
            // the gap, one inside it as the padding.
            //
            // U+00A0 AND NOT U+0020, because the padding is part of the chip and a line may not break
            // inside one. With an ordinary space it could and did: the leading pad wrapped to the end of
            // the previous line and painted its band there, so a chip at the start of a line left a stub
            // of plate dangling off the line above it. `CgLineBreaker` asks `BreakIterator.getLineInstance`
            // for the root locale, which is UAX #14, which classes U+00A0 as GL (Glue) -- so the break
            // moves to the real space BEFORE the chip and the whole chip travels together, which is what
            // it should have done from the start.
            //
            // Not a thin space: the bundled faces are already known to be missing U+2026 and U+22EE, and
            // U+00A0 is Latin-1 rather than a typographic extra, so it is present wherever a space is.
            boolean chip = span.has(MarkupSpan.CODE);
            String pad = chip ? "\u00A0" : "";
            assembled.append(pad).append(span.text()).append(pad);
            int end = assembled.length();
            if (end == start) continue;
            TextRange range = TextRange.of(start, end);
            if (chip) {
                // THE PLATE COVERS THE TEXT AND NOT THE PADS, and the split is the whole point of
                // having both. A band's padding inflates the painted rect WITHOUT moving a glyph, so
                // whatever it adds is taken from the gap to the neighbouring word -- with the pads
                // inside the plate, raising the padding pushed the plate out until `StringBuffer` and
                // the comma after it were touching.
                //
                // So the two are given different jobs. The PADDING is the plate's own breathing room,
                // measured from the glyphs it surrounds. The PAD CHARACTER is separation from the text
                // either side, and it is a real character, so layout honours it and the plate can never
                // grow into it.
                bands.computeIfAbsent(CODE_RANGE, k -> new ArrayList<>())
                        .add(TextRange.of(start + pad.length(), end - pad.length()));
            }
            if (span.has(MarkupSpan.STRONG)) bands.computeIfAbsent(STRONG_RANGE, k -> new ArrayList<>()).add(range);
            if (span.has(MarkupSpan.EMPHASIS)) bands.computeIfAbsent(EMPHASIS_RANGE, k -> new ArrayList<>()).add(range);
            if (span.has(MarkupSpan.LINK)) {
                bands.computeIfAbsent(LINK_RANGE, k -> new ArrayList<>()).add(range);
                if (span.target() != null) found.add(new LinkSpan(start, end, span.target()));
            }
        }

        UIText run = new UIText(assembled.toString());
        // SIZED BY ITS BOX, STATED RATHER THAN DETECTED. `UIText` decides this once, on its first
        // recompute, from whether the box it landed in already has a width -- and a block built here is
        // added to a tree that has not laid out yet, so it reads zero, latches "self-sizing", and pushes
        // its natural single-line width back at IMPORTANT. That outranks the sheet permanently, so
        // `width: 100%` in the stylesheet is silently lost and every paragraph renders as one long line
        // running out of the popup. Prose is the definition of box-sized: it wraps to whatever it is
        // given. The `<pre>` sample deliberately does NOT get this -- there the text's own width is the
        // answer, and the box scrolls.
        run.neverSelfSizeWidth();
        run.addClass(styleClass);
        // CLEARED FIRST, ALL OF THEM. See the class comment: a band left over from a previous document is
        // not a stale colour, it is a colour over whatever text moved into those offsets.
        for (String band : BANDS) run.highlights().set(band, List.of());
        for (Map.Entry<String, List<TextRange>> entry : bands.entrySet()) {
            run.highlights().set(entry.getKey(), entry.getValue());
        }
        if (!found.isEmpty()) {
            links.put(run, found);
            attachLinkPress(run);
        }
        return run;
    }
}
