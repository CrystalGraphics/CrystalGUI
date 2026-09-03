package com.crystalgui.widget.text;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.markup.MarkupBlock;
import com.crystalgui.text.markup.MarkupDocument;
import com.crystalgui.text.markup.MarkupSpan;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.text.TextRange;
import com.crystalgui.widget.scroll.ScrollerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

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
 * already speaks their intermediate form; this engine's equivalent is nodes and styled runs.</b></p>
 *
 * <p>So the emitter is ported ({@code JavaDocs} follows {@code JavaDocInfoGenerator}'s rules and says so)
 * and the renderer is ours. It is small, because the text engine already has the two things it needs: a
 * wrapping text node, and the CSS Custom Highlight API for styling ranges inside one.</p>
 *
 * <h3>One text run per block, with ranges over it — never a sibling per span</h3>
 *
 * <p>{@code DocumentationPopup} argues this for its signature line, where the reason is truncation. Here
 * the reason is <b>wrapping</b>, and it is stronger. Prose has to re-flow to the popup's width, and a
 * sentence split into a text node per style is a row of flex items: the line can only break where one
 * node ends, so a bold word mid-sentence becomes a break opportunity and a long word inside one fragment
 * overflows rather than wrapping. One run wraps at every word, exactly as text does.</p>
 *
 * <p>{@link MarkupSpan#styles()} is a bitset, so a run that is both {@code <b>} and {@code <code>}
 * registers in both bands and the cascade composes them — which is the reason the model does not carry an
 * enum.</p>
 *
 * <h3>No shadow tree, and the sheets are what decide</h3>
 *
 * <p>The blocks are ordinary LIGHT children. Encapsulation is opt-in per widget (D1, reversed at M6.1)
 * and a widget may host a shadow root only if nothing in the shipped sheets reaches <em>through</em> its
 * structure — {@code tools/port/classify.py} answers {@code LIGHT} here, on one rule:
 * {@code documentationpopup .__markup-code__ > text}, an outer widget reaching past a block into the run
 * inside it. {@code ::part()} has no spelling for that, so a shadow tree would silently unstyle it.</p>
 *
 * <p>The old engine reached the same conclusion by a different route — its blocks had to be public
 * because {@code clearAllChildren()} skipped internal children, so an internal build accumulated every
 * document the view had ever been given, one on top of the last. That hazard is gone with the flag it
 * came from; the sheet's is not, and it is the one that decides.</p>
 *
 * <h3>Every band is cleared before any is set</h3>
 *
 * <p>On every run, every time. A band that is only assigned where there is something to say leaves the
 * previous document's ranges live everywhere else — and those are offsets into a string that has since
 * been replaced, so the band lands on whatever characters moved into them. {@link UIText}'s own note
 * records this as the failure that is not a stale colour but a colour over the wrong text entirely.</p>
 */
public class MarkupView extends UIElement {

    public static final Name NAME = Name.of("markupview");

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

    /** A {@code <table>}. */
    public static final String TABLE_CLASS = "__markup-table__";

    /** A {@code <caption>}. */
    public static final String TABLE_CAPTION_CLASS = "__markup-caption__";

    /** One {@code <tr>}. */
    public static final String TABLE_ROW_CLASS = "__markup-row__";

    /** One {@code <td>} or {@code <th>}. */
    public static final String TABLE_CELL_CLASS = "__markup-cell__";

    /** On a cell that came from {@code <th>}. */
    public static final String TABLE_HEADER_CLASS = "__markup-header-cell__";

    /**
     * The quote's rule, as a <b>node</b> rather than a border.
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
     * <p>A second band rather than a {@code :hover} rule, and it has to be: a paragraph is ONE text node,
     * so {@code :hover} on it is true whenever the pointer is anywhere in the sentence and would
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
     * bands of its own on a text node it owns — search matches on a filtered row are the live example. A
     * blanket clear would take those with it.</p>
     */
    /**
     * The chip's padding, as a real character.
     *
     * <p>U+00A0 and never U+0020 — see the note in {@link #text}. Written as an escape rather than the
     * glyph, because the two are indistinguishable in every editor and a plain space here is a bug that
     * only shows as a stub of plate dangling off the end of the previous line.</p>
     */
    private static final String NBSP = String.valueOf((char) 0x00A0);

    private static final String[] BANDS =
            {CODE_RANGE, STRONG_RANGE, EMPHASIS_RANGE, LINK_RANGE, LINK_HOVER_RANGE};

    /**
     * Where each run's links are, so a press can be turned back into a target.
     *
     * <p>Kept beside the runs rather than on them: a {@link UIText} has nowhere to put an arbitrary
     * payload, and a parallel map keyed by the node is what this view can clear wholesale when it
     * rebuilds. Cleared in {@link #setDocument}, or a document's links would answer for the next
     * document's text.</p>
     */
    private final Map<UIText, List<LinkSpan>> links = new LinkedHashMap<>();

    /** Which link each run is currently showing as hovered, so a move only writes on a change. */
    private final Map<UIText, LinkSpan> hovered = new LinkedHashMap<>();

    /** Every label cell in the document, so they can be given one shared width. @see #alignTerms */
    private final List<UIText> terms = new ArrayList<>();

    /**
     * Every table's cells, by row, so each COLUMN can be given one shared width.
     *
     * <p>Per table rather than per document: two tables in one comment are two grids, and a column in one
     * says nothing about a column in the other. @see #alignTables</p>
     */
    private final List<List<List<Placed>>> tables = new ArrayList<>();

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
     * {@code {@link}} arrives as {@code java:java.util.List} and only something holding an engine can turn
     * that into anything. The same shape the popup's footer pencil already uses — the widget states the
     * intent and its host decides.</p>
     */
    public final Signal.Value<String> onLinkActivated = new Signal.Value<>();

    public MarkupView() {
        this(MarkupDocument.EMPTY);
    }

    public MarkupView(MarkupDocument document) {
        super(NAME);
        setDocument(document);
    }

    /**
     * A standing post-layout hook, for the two column equalisations.
     *
     * <p>{@code onLayoutChanged} has no counterpart here: layout is one pass with no feedback into it,
     * so anything that must READ a measured box goes after it. Owned by this node, so it is dropped for
     * free when the view leaves the tree or is frozen.</p>
     */
    @Override
    protected void connected() {
        document().animation().afterLayout(this, this::alignAfterLayout);
    }

    private boolean alignAfterLayout(float deltaSeconds) {
        alignTerms();
        alignTables();
        return true;
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
     * that — and the nodes it would be preserving are a handful of text runs.</p>
     */
    public MarkupView setDocument(MarkupDocument document) {
        this.document = document == null ? MarkupDocument.EMPTY : document;
        links.clear();
        hovered.clear();
        terms.clear();
        tables.clear();
        // TORN DOWN AND REBUILT, EVERY TIME. On the OLD engine `doc.setDocument` measured at 40,086us on
        // the frame that shows a hover popup, for about 1,700 characters of javadoc -- while parsing that
        // markup cost 3.3ms and rendering the signature 500us. So the cost was here, and the split below
        // is what says whether it is the teardown, the number of nodes, or what each one costs to build.
        // Kept through the port as the baseline it is to be re-measured against.
        long timed = FrameProfile.begin();
        removeAll();
        FrameProfile.step(timed, "markup.removeAll");
        timed = FrameProfile.begin();
        int built = 0;
        for (MarkupBlock block : this.document.blocks()) {
            // PER BLOCK, AND BY KIND. Seven blocks cost 21,567us on the old engine -- about 3ms each,
            // far past what registering a node costs, so the expense is inside building ONE of them and
            // the kinds do completely different work.
            long one = FrameProfile.begin();
            UIElement node = build(block);
            FrameProfile.step(one, "markup.block " + block.kind() + " spans=" + block.spans().size()
                    + " kids=" + block.children().size());
            if (node != null) {
                append(node);
                built++;
            }
        }
        FrameProfile.step(timed, "markup.build " + built + " blocks");
        return this;
    }

    /** True when there is nothing to draw — the caller's cue to hide the band entirely. */
    public boolean isEmpty() {
        return document.blocks().isEmpty();
    }

    // ── Links ────────────────────────────────────────────────────────────────

    /**
     * Wires one run's link affordances: the press that follows a link, and the hover that marks it.
     *
     * <p><b>The press is target-phase and stops there</b>, because an ancestor may read the same press
     * as something else — {@code DocumentationPopup} begins a MOVE on any press that reaches it, so a
     * link click would drag the box a few pixels while following the link. A press on ordinary prose is
     * left alone and still reaches that ancestor.</p>
     *
     * <p><b>The hover is a MOVE listener, which is new.</b> The old engine could not do this: a move
     * listener there never fired — mine was the only {@code onMouseMove.attachListener} in the engine —
     * so this was a {@code UIFrameTicker} that asked the window for the hovered element once a frame and
     * walked every run. {@code Input.endFrame} synthesises a {@code Move} to the hover target on every
     * frame here, so the ticker, its registration in {@code onLayoutChanged}, and its
     * return-false-when-detached contract all disappear — and what replaces them is strictly cheaper,
     * because only the run actually under the pointer is ever asked.</p>
     *
     * <p>{@code Leave} is the other half and is not optional: a {@code Move} can only ever say where the
     * pointer IS, so a run the pointer has left would keep its underline and its cursor forever.</p>
     */
    private void attachLinkListeners(UIText run) {
        run.onMouseDown.attachListener((node, event) -> {
            LinkSpan span = linkAt(run,
                    run.offsetAtScreen(event.getPosition().x(), event.getPosition().y()));
            if (span == null) return;
            onLinkActivated.emit(span.target());
            event.stopPropagation();
        }, false, false);

        run.onMouseMove.attachListener((node, event) -> hoverAt(run,
                run.offsetAtScreen(event.getPosition().x(), event.getPosition().y())), false, false);

        run.onMouseLeave.attachListener((node, event) -> hoverAt(run, -1), false, false);
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
     * <p>Public because it is the seam a test drives. The pointer path needs a settled layout and a
     * dispatched frame before it can answer anything, so a test going that way would be exercising the
     * hit test rather than the band. A host with its own pointer model has the same need.</p>
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
        // answer, and only a band is range-sized; `cursor` is a property of a node and can only ever be
        // node-wide.
        //
        // A class rather than a style write, because "prose is an I-beam" is a decision the sheet should
        // own. It is also what the engine already says about state a widget flips from its own listener:
        // a class, re-evaluated on your terms, never a pseudo-class.
        if (span == null) run.removeClass(LINK_HOVER_CLASS);
        else run.addClass(LINK_HOVER_CLASS);
    }

    // ── Building ─────────────────────────────────────────────────────────────

    @Nullable
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
            case TABLE:
                return table(block);
            default:
                return null;
        }
    }

    /**
     * A {@code <pre>} sample.
     *
     * <p>Its own node rather than a {@link #CODE_RANGE} band, and the difference is not decoration: a
     * sample needs a box — a background behind the whole run including the ends of short lines, padding
     * inside it, and its own width to scroll in. A highlight band paints behind the glyphs and stops
     * there, which is right for a word in a sentence and wrong for a block.</p>
     *
     * <p>The text is set verbatim. {@code MarkupParser} does not collapse whitespace inside {@code <pre>},
     * so the indentation here is the author's and the stylesheet must not re-wrap it.</p>
     */
    private UIElement codeBlock(MarkupBlock block) {
        // A SCROLLERVIEW, so a sample wider than the popup gets a BAR rather than being cut off at the
        // edge with nothing to say it continues. A plain node with `overflow: auto` scrolls too --
        // scrolling is a node capability here, not a widget feature -- but it scrolls invisibly, and a
        // code sample that silently ends mid-token reads as the renderer having truncated it.
        ScrollerView box = new ScrollerView();
        box.addClass(CODE_BLOCK_CLASS);
        UIText sample = new UIText(block.text());
        colourSample(sample, block.text());
        box.append(sample);
        return box;
    }

    /**
     * Colours a {@code <pre>} sample from the grammar, through {@link SyntaxHighlighting#bandsFor}.
     *
     * <p>LEXED, NOT RESOLVED. A grammar knows a keyword from an identifier from a string, which is the
     * whole of what a sample in a doc comment needs — it is an illustration rather than part of the
     * program, so there is nothing to resolve it against and nothing that would be true if there were.
     * The capture names are the ones the editor's scheme already defines, so a sample is coloured by the
     * same rules as the code it describes, with no second vocabulary to keep in step.</p>
     *
     * <p>The band map is taken rather than {@code SyntaxHighlighting.highlight}, which types its run as
     * the OLD engine's {@code UIText}. {@code bandsFor} is the engine-agnostic half of that class and
     * needed no change; applying its answer is four lines. <b>{@link UIText#SYNTAX_CLASS} is not
     * optional</b> — every rule in every scheme is scoped to it, so a run carrying ranges without it
     * resolves all of them to nothing and draws as plain text with the colouring apparently broken.</p>
     */
    private void colourSample(UIText run, String source) {
        if (codeLanguage == null || source.isEmpty()) return;
        run.addClass(UIText.SYNTAX_CLASS);
        Map<String, List<TextRange>> bands = SyntaxHighlighting.bandsFor(
                SyntaxHighlighting.tokenize(source, codeLanguage), 0, source.length());
        for (Map.Entry<String, List<TextRange>> band : bands.entrySet()) {
            run.highlights().set(band.getKey(), band.getValue());
        }
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
            box.append(item(child, marker));
        }
        return box;
    }

    private UIElement item(MarkupBlock block, String marker) {
        UIElement row = new UIElement();
        row.addClass(ITEM_CLASS);

        UIText bullet = new UIText(marker);
        bullet.addClass(BULLET_CLASS);
        row.append(bullet);

        UIElement body = new UIElement();
        body.addClass(ITEM_BODY_CLASS);
        if (!block.spans().isEmpty()) body.append(text(block.spans(), PARAGRAPH_CLASS));
        for (MarkupBlock child : block.children()) {
            UIElement built = build(child);
            if (built != null) body.append(built);
        }
        row.append(body);
        return row;
    }

    /**
     * A {@code <dl>} as a stack of two-column rows.
     *
     * <p>Terms and details are <b>siblings</b> in HTML, not nested — so pairing them is this method's job,
     * and a stray {@code <dd>} with no {@code <dt>} before it gets an empty label rather than being
     * dropped. A malformed list still shows what it says.</p>
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
                if (built != null) detail.append(built);
            }
        }
        return box;
    }

    /**
     * Starts a row and answers its value column, empty and ready to be filled.
     *
     * <p>Terms and details are <b>siblings</b> in HTML rather than nested, so pairing them is this view's
     * job: the row is the thing that owns both, and it has to exist before either does.</p>
     */
    private UIElement openRow(UIElement box, List<MarkupSpan> label) {
        UIElement row = new UIElement();
        row.addClass(DEFINITION_ROW_CLASS);
        box.append(row);

        UIText term = term(label);
        terms.add(term);
        row.append(term);

        UIElement detail = new UIElement();
        detail.addClass(DETAIL_CLASS);
        row.append(detail);
        return detail;
    }

    /**
     * A {@code <table>} — a caption at most, then a stack of rows.
     *
     * <p>Rows of cells rather than a real grid, for the reason {@link #definitions} already gives:
     * {@code display: grid} is non-functional in this engine, measured twice — the value cell comes back
     * at the container's full width and the first track is never reserved. So the columns are lined up
     * after layout instead, by {@link #alignTables}.</p>
     *
     * <p><b>A spanning cell claims its slots up front.</b> {@code colspan} and {@code rowspan} decide
     * which COLUMN each following cell lands in, so a table containing one cannot be aligned by counting
     * cells from the left — every column after the span would be compared against the wrong one, which is
     * a table whose rules and text disagree rather than a table missing a feature. The grid is built
     * here, once, and {@link #alignTables} reads the slots rather than the list positions.</p>
     */
    private UIElement table(MarkupBlock block) {
        UIElement box = new TableGrid();
        box.addClass(TABLE_CLASS);

        List<List<Placed>> rows = new ArrayList<>();
        for (MarkupBlock child : block.children()) {
            if (child.kind() == MarkupBlock.Kind.CAPTION) {
                box.append(text(spansOf(child), TABLE_CAPTION_CLASS));
                continue;
            }
            if (child.kind() != MarkupBlock.Kind.ROW) continue;

            UIElement row = new UIElement();
            row.addClass(TABLE_ROW_CLASS);
            box.append(row);

            List<Placed> cells = new ArrayList<>();
            for (MarkupBlock cellBlock : child.children()) {
                UIElement cell = cell(cellBlock);
                row.append(cell);
                cells.add(new Placed(cell, 0, cellBlock.colspan(), cellBlock.rowspan()));
            }
            if (!cells.isEmpty()) rows.add(cells);
        }
        if (!rows.isEmpty()) tables.add(place(rows));
        return box;
    }

    /**
     * One cell and the column it starts in.
     *
     * <p>{@code column} is what a span makes non-obvious: without one it is the cell's index in its row,
     * and with one it is not, in that row or in any row a {@code rowspan} reaches into.</p>
     */
    private record Placed(UIElement cell, int column, int colspan, int rowspan) {
    }

    /**
     * Assigns every cell the column it actually starts in.
     *
     * <p>The HTML table model, in the one form this needs: walk the rows in order, and for each cell take
     * the leftmost slot nothing has already claimed. A {@code colspan} claims the slots beside it and a
     * {@code rowspan} claims the same slots in the rows below, so a cell that follows one is pushed right
     * exactly as a browser pushes it.</p>
     *
     * <p><b>A {@code rowspan} is honoured for PLACEMENT and not for painting.</b> The cell keeps the
     * height of its own row rather than growing down through the rows it covers, because a row here is a
     * flex box and a child cannot escape one — a real vertical merge means abandoning row boxes for
     * absolute placement, which would also take the grid drawing with it. Stated rather than quietly
     * dropped: the columns line up, and a cell that says it covers three rows is drawn covering one.</p>
     */
    private static List<List<Placed>> place(List<List<Placed>> rows) {
        // WHAT EACH ROW BELOW HAS ALREADY HAD TAKEN FROM IT, by column. A rowspan writes into these.
        List<Set<Integer>> taken = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) taken.add(new HashSet<>());

        List<List<Placed>> placed = new ArrayList<>(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            List<Placed> out = new ArrayList<>(rows.get(r).size());
            int column = 0;
            for (Placed cell : rows.get(r)) {
                while (taken.get(r).contains(column)) column++;
                out.add(new Placed(cell.cell(), column, cell.colspan(), cell.rowspan()));
                for (int c = column; c < column + cell.colspan(); c++) {
                    for (int down = r + 1; down < r + cell.rowspan() && down < rows.size(); down++) {
                        taken.get(down).add(c);
                    }
                }
                column += cell.colspan();
            }
            placed.add(out);
        }
        return placed;
    }

    /**
     * A table that draws its own grid.
     *
     * <h3>Strokes rather than nodes</h3>
     *
     * <p>A hairline is usually a node here, because a one-sided {@code border-width-*} draws all four
     * edges or none depending on which edge is named. That works for one rule under a heading and scales
     * badly to a grid: a line between every pair of rows AND every pair of columns is one box per line,
     * and {@code java.util.Formatter}'s tables run to dozens of rows each. Column lines are worse than
     * that — they would have to be boxes inside every row.</p>
     *
     * <p>One paint pass costs no nodes, no layout and no state: the lines are read off the live boxes of
     * the rows and cells at the moment of drawing, so they follow a column that {@link #alignTables} has
     * just resized without being told.</p>
     *
     * <p>Drawn in {@code paintDecoration} — after the children, so a line is never hidden under a cell's
     * background, and before the outline, which belongs to the box rather than to its contents.</p>
     */
    private static final class TableGrid extends UIElement {

        /**
         * How wide a grid line is drawn, in LOGICAL pixels.
         *
         * <p>Thinner than the 1px a border would be. A stroke is not a border: it is centred on its line
         * and feathered at both edges, so a width of 1 lands about two device pixels wide at the default
         * scale and reads as a rule rather than as the hairline a table wants.</p>
         */
        private static final float LINE = 0.6f;

        /** Enough to keep the edge crisp; a stroke with no ramp at all aliases into a dotted line. */
        private static final float FEATHER = 0.4f;

        @Override
        public void paintDecoration(CgUiPaintContext ctx, Box box) {
            super.paintDecoration(ctx, box);
            List<UIElement> rows = new ArrayList<>();
            for (UIElement child : children()) {
                if (child.classes().contains(TABLE_ROW_CLASS)) rows.add(child);
            }
            if (rows.isEmpty()) return;

            // FROM THE CASCADE, never a number here -- something has to hand `CgVectorRenderer` an ARGB
            // int, and the node already knows one. Same trick `NodePort.typeColor` uses to keep a palette
            // in the sheet. ONE colour for every line: an interior rule and the outer edge are the same
            // object seen from different sides, and drawing the head's darker made the table read as two
            // tables stacked.
            int colour = getStyle().getGeneralGroup().borderColor();

            UIElement widest = rows.get(0);
            for (UIElement row : rows) {
                if (row.children().size() > widest.children().size()) widest = row;
            }
            List<UIElement> columns = widest.children();
            if (columns.isEmpty()) return;

            // EVERY BOX IS NULLABLE, and here that is not defensive noise: a row is a live node whose box
            // exists only while it is connected, unfrozen and displayed, and this pass runs from a paint
            // that a hidden subtree can still be reached through. Nothing to draw is the honest answer --
            // never a zero, which would put the whole grid at the origin.
            Box firstRow = rows.get(0).box();
            Box lastRow = rows.get(rows.size() - 1).box();
            Box firstCell = columns.get(0).box();
            Box lastCell = columns.get(columns.size() - 1).box();
            if (firstRow == null || lastRow == null || firstCell == null || lastCell == null) return;
            if (firstRow.width() <= 0f || firstCell.width() <= 0f) return;

            // HALF A LINE INSIDE THE EDGE, all four of them. A stroke is CENTRED on the line it is given,
            // so an outer edge drawn exactly on the content's boundary puts half its width outside it --
            // and outside is where the scroller's clip is. The left border came back visibly fainter than
            // every interior line because half of it had been sheared off, which reads as an inconsistent
            // stroke width rather than as a clipped one.
            //
            // The interior lines need no such nudge: they are already a whole cell away from any edge.
            //
            // LOCAL TO THIS NODE. `paintDecoration` draws in the box's own space with the pose already
            // set from `localToWorld`, and a child's box is in the same space -- so a row's y is used as
            // it stands. On the old engine these came off `getRuntimeCache()`, which was absolute.
            float inset = LINE * 0.5f;
            float top = localY(box, firstRow) + inset;
            float bottom = localY(box, lastRow) + lastRow.height() - inset;
            float left = localX(box, firstCell) + inset;
            float right = localX(box, lastCell) + lastCell.width() - inset;

            // THE BOX, then the lines inside it. The cells carry their own padding, so every boundary is
            // a single coordinate rather than a gap to guess a midpoint in -- which is what lets the outer
            // edge and an interior line be drawn by exactly the same call.
            stroke(ctx, left, top, right, top, colour);
            stroke(ctx, left, bottom, right, bottom, colour);
            stroke(ctx, left, top, left, bottom, colour);
            stroke(ctx, right, top, right, bottom, colour);

            for (int i = 0; i + 1 < rows.size(); i++) {
                Box row = rows.get(i).box();
                if (row == null) continue;
                float y = localY(box, row) + row.height();
                stroke(ctx, left, y, right, y, colour);
            }
            // A COLUMN LINE IS DRAWN PER ROW, not once down the whole table.
            //
            // Full-height lines are one stroke each and are wrong the moment anything spans: a cell with
            // `colspan="2"` has no internal boundary, so a line down the table draws a rule THROUGH it --
            // which reads as the header being two cells whose divider someone forgot to remove, rather
            // than as one cell. Asking each row for its own boundaries needs no placement data at all: a
            // spanning cell simply has one fewer edge to report, and a table with no spans draws exactly
            // what it drew before, in as many strokes as it has rows.
            //
            // Clamped to the inset edges so an interior line meets the outer rule instead of poking half
            // a stroke past it at the top and bottom.
            for (UIElement row : rows) {
                Box rowBox = row.box();
                if (rowBox == null) continue;
                float y0 = Math.max(localY(box, rowBox), top);
                float y1 = Math.min(localY(box, rowBox) + rowBox.height(), bottom);
                List<UIElement> cells = row.children();
                for (int i = 0; i + 1 < cells.size(); i++) {
                    Box cell = cells.get(i).box();
                    if (cell == null) continue;
                    float x = localX(box, cell) + cell.width();
                    stroke(ctx, x, y0, x, y1, colour);
                }
            }
            ctx.flush();
        }

        /** A descendant's x in this box's own space — what {@code paintDecoration} draws in. */
        private static float localX(Box self, Box descendant) {
            return descendant.worldX() - self.worldX();
        }

        private static float localY(Box self, Box descendant) {
            return descendant.worldY() - self.worldY();
        }

        /**
         * One straight hairline.
         *
         * <p>A cubic whose control points equal its endpoints degenerates to a segment, which is how
         * {@code PortDefaultEditor} draws its stub — reusing {@code ctx.curve()} rather than inventing a
         * second draw path. The width is in LOGICAL pixels: {@code CgUiRenderer} applies the
         * {@code PoseStack} to the stroke, so one pixel stays one pixel at any {@code uiScale}.</p>
         */
        private static void stroke(CgUiPaintContext ctx, float x0, float y0, float x1, float y1,
                                   int color) {
            ctx.curve()
                    .cubic(x0, y0, x0, y0, x1, y1, x1, y1)
                    .width(LINE)
                    .feather(FEATHER)
                    .colors(color, color)
                    .submit();
        }
    }

    /**
     * One cell — <b>content-sized when it is a label</b>, which is what lets a column be measured.
     *
     * <p>A cell holding one run of spans is built as a label, and that is nearly every cell a javadoc
     * table has. A cell with real blocks in it — a list, a code sample — is an ordinary box and simply
     * does not contribute a width. Its column is then sized by the other rows, which is the right answer:
     * a paragraph in a table cell should wrap, not set the column.</p>
     */
    private UIElement cell(MarkupBlock block) {
        UIElement built;
        List<MarkupSpan> spans = spansOf(block);
        boolean simple = !spans.isEmpty() && block.children().size() <= 1;
        if (simple) {
            built = contentSized(text(spans, TABLE_CELL_CLASS));
        } else {
            built = new UIElement();
            built.addClass(TABLE_CELL_CLASS);
            for (MarkupBlock child : block.children()) {
                UIElement content = build(child);
                if (content != null) built.append(content);
            }
        }
        if (block.level() == 1) built.addClass(TABLE_HEADER_CLASS);
        return built;
    }

    /**
     * Sizes a run to its own text rather than to the box it lands in — {@code width: max-content}.
     *
     * <p><b>This replaces {@code UIText.forceSelfSizeWidth()}, and the difference is the whole of D15.</b>
     * On the old engine a text leaf could not be measured, so it decided <em>once</em> whether it sized
     * itself, from whether the box it landed in already had a width — and a block built here is added to
     * a tree that has not laid out yet, so it read zero and latched. The two hatches existed for callers
     * who knew the answer and had no way to state it, and this was one of them: a label routed through
     * the ordinary path reported a content width of zero, so every column measured nothing, equalised to
     * nothing, and each label drew underneath its own value.</p>
     *
     * <p>Max-content is a question the engine asks per layout now, so there is nothing to latch and
     * nothing to pre-empt — the answer is simply stated in the cascade, where a theme can see it. At
     * DEFAULT origin, the lowest there is, so any rule naming the cell still wins.</p>
     *
     * <p>Prose needs no counterpart. Wrapping to whatever box it is given is what a text node does when
     * nobody says otherwise, which is why {@code neverSelfSizeWidth()} has no replacement rather than a
     * renamed one.</p>
     */
    private static UIText contentSized(UIText run) {
        StyleGroup.defaultPipeline(run.getStyle().getLayoutGroup(), l -> l.widthMaxContent());
        return run;
    }

    /**
     * Gives every column of every table the width of its widest cell.
     *
     * <p>{@link #alignTerms} with an index: a definition list is a table of one column, and this is the
     * same measurement over N.</p>
     *
     * <p>MIN-width rather than width, so the cap the sheet puts on a cell still applies and a cell that
     * genuinely needs to be wider than its column is not clipped to it.</p>
     */
    private void alignTables() {
        for (List<List<Placed>> rows : tables) {
            int columns = 0;
            for (List<Placed> row : rows) {
                for (Placed cell : row) columns = Math.max(columns, cell.column() + cell.colspan());
            }

            // ONLY SINGLE-COLUMN CELLS MEASURE A COLUMN. A cell covering three of them says nothing about
            // how wide any ONE of them is, and letting it vote makes every column under a wide header as
            // wide as the header -- which is the table three times too wide, from a construct that was
            // supposed to be cosmetic. Browsers resolve this the same way round.
            float[] width = new float[columns];
            for (List<Placed> row : rows) {
                for (Placed cell : row) {
                    if (cell.colspan() != 1) continue;
                    Box box = cell.cell().box();
                    if (box == null) continue;
                    width[cell.column()] = Math.max(width[cell.column()], box.width());
                }
            }

            for (List<Placed> row : rows) {
                for (Placed cell : row) {
                    // A SPANNING CELL TAKES THE SUM of what it covers, so the columns beneath it stay
                    // where the rest of the table put them.
                    float shared = 0f;
                    for (int c = cell.column(); c < cell.column() + cell.colspan() && c < columns; c++) {
                        shared += width[c];
                    }
                    // NOTHING MEASURED YET is the first layout of a freshly built document, where every
                    // box is zero. Equalising to zero would latch it.
                    if (shared <= 0f) continue;
                    final float min = shared;
                    StyleGroup.inlinePipeline(cell.cell().getStyle().getLayoutGroup(),
                            l -> l.minWidth(min));
                }
            }
        }
    }

    /**
     * Gives every label cell the width of the widest one.
     *
     * <p>What a table does, and what makes the values line up while the gap stays the same everywhere.
     * The alternative is a fixed width, which cannot be both wide enough for the longest label and tight
     * against the shortest — that is the whole of the complaint this answers.</p>
     *
     * <p><b>It settles, and takes one extra frame to.</b> The width is written as an INLINE candidate and
     * {@code replaceOrPutCandidate} no-ops when the value is unchanged, so the second pass writes nothing
     * and the loop stops. What is new is <em>when</em>: the old engine ran this inside
     * {@code calculateLayout}'s own {@code while (isLayoutDirty())} loop and converged within the frame,
     * while here layout is one pass with no feedback into it — a width written after it applies on the
     * next. So a freshly built document draws one frame with its columns unequal. Stated rather than
     * hidden, because the alternative is participating in layout, which means a real grid.</p>
     *
     * <p>Skipped entirely until something has a width — the first layout of a freshly built document
     * reports zero for everything, and equalising to zero would latch it.</p>
     *
     * <p><b>INLINE, not IMPORTANT.</b> The new engine may not write into the cascade at IMPORTANT at all
     * — {@code EngineBoundaryTest} reads the constant pool to say so — and the reason the old one had to
     * is gone with it: a label used to push its own measured width at IMPORTANT on every pass, so
     * {@code width} here would have been two IMPORTANT candidates on one property racing each frame.
     * Nothing pushes anything now.</p>
     */
    private void alignTerms() {
        if (terms.isEmpty()) return;
        float widest = 0f;
        for (UIText label : terms) {
            Box box = label.box();
            if (box != null) widest = Math.max(widest, box.width());
        }
        if (widest <= 0f) return;
        final float shared = widest;
        for (UIText label : terms) {
            StyleGroup.inlinePipeline(label.getStyle().getLayoutGroup(), l -> l.minWidth(shared));
        }
    }

    /**
     * A label cell — <b>content-sized</b>, which is what lets {@link #alignTerms} measure it.
     *
     * <p>A {@code <dt>} is a label rather than a block, so its own spans are enough and the styling on
     * them survives. The sheet caps its width, so a very long heading still wraps instead of pushing the
     * value column off the popup.</p>
     */
    private UIText term(List<MarkupSpan> spans) {
        return contentSized(text(spans, TERM_CLASS));
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
        box.append(rule);

        UIElement body = new UIElement();
        body.addClass(ITEM_BODY_CLASS);
        if (!block.spans().isEmpty()) body.append(text(block.spans(), PARAGRAPH_CLASS));
        for (MarkupBlock child : block.children()) {
            UIElement built = build(child);
            if (built != null) body.append(built);
        }
        box.append(body);
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
            String pad = chip ? NBSP : "";
            assembled.append(pad).append(span.text()).append(pad);
            int end = assembled.length();
            if (end == start) continue;
            TextRange range = TextRange.of(start, end);
            if (chip) {
                // THE PLATE COVERS THE TEXT AND NOT THE PADS, and the split is the whole point of having
                // both. A band's padding inflates the painted rect WITHOUT moving a glyph, so whatever it
                // adds is taken from the gap to the neighbouring word -- with the pads inside the plate,
                // raising the padding pushed the plate out until `StringBuffer` and the comma after it
                // were touching.
                //
                // So the two are given different jobs. The PADDING is the plate's own breathing room,
                // measured from the glyphs it surrounds. The PAD CHARACTER is separation from the text
                // either side, and it is a real character, so layout honours it and the plate can never
                // grow into it.
                bands.computeIfAbsent(CODE_RANGE, k -> new ArrayList<>())
                        .add(TextRange.of(start + pad.length(), end - pad.length()));
            }
            if (span.has(MarkupSpan.STRONG)) {
                bands.computeIfAbsent(STRONG_RANGE, k -> new ArrayList<>()).add(range);
            }
            if (span.has(MarkupSpan.EMPHASIS)) {
                bands.computeIfAbsent(EMPHASIS_RANGE, k -> new ArrayList<>()).add(range);
            }
            if (span.has(MarkupSpan.LINK)) {
                bands.computeIfAbsent(LINK_RANGE, k -> new ArrayList<>()).add(range);
                if (span.target() != null) found.add(new LinkSpan(start, end, span.target()));
            }
        }

        // NOTHING SAYS "WRAP TO YOUR BOX" ANY MORE, and nothing has to. On the old engine this line was
        // `run.neverSelfSizeWidth()`, and without it every paragraph latched its natural single-line
        // width at IMPORTANT on its first recompute -- outranking the sheet permanently, so `width: 100%`
        // was silently lost and prose ran out of the popup in one long line. A text node wraps to the box
        // it is given here because that is what being ASKED for a height at a width means.
        UIText run = new UIText(assembled.toString());
        run.addClass(styleClass);
        // CLEARED FIRST, ALL OF THEM. See the class comment: a band left over from a previous document is
        // not a stale colour, it is a colour over whatever text moved into those offsets.
        for (String band : BANDS) run.highlights().set(band, List.of());
        for (Map.Entry<String, List<TextRange>> entry : bands.entrySet()) {
            run.highlights().set(entry.getKey(), entry.getValue());
        }
        if (!found.isEmpty()) {
            links.put(run, found);
            attachLinkListeners(run);
        }
        return run;
    }
}
