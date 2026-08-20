package com.crystalgui.ui.elements;

import com.crystalgui.text.markup.MarkupBlock;
import com.crystalgui.text.markup.MarkupDocument;
import com.crystalgui.text.markup.MarkupSpan;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.text.TextRange;

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
public class MarkupView extends UIElement {

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
     * Every band this view writes, cleared on each run before any is set.
     *
     * <p>Named as a list rather than reset by clearing the whole registry, because a caller may register
     * bands of its own on a text element it owns — search matches on a filtered row are the live example.
     * A blanket clear would take those with it.</p>
     */
    private static final String[] BANDS = {CODE_RANGE, STRONG_RANGE, EMPHASIS_RANGE, LINK_RANGE};

    private MarkupDocument document = MarkupDocument.EMPTY;

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
     * Replaces the content.
     *
     * <p>Rebuilds outright rather than diffing. A documentation popup shows one symbol at a time and the
     * next symbol shares nothing with this one, so a diff would compare two unrelated trees to discover
     * that — and the elements it would be preserving are a handful of text nodes.</p>
     */
    public MarkupView setDocument(MarkupDocument document) {
        this.document = document == null ? MarkupDocument.EMPTY : document;
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

        for (MarkupSpan span : spans) {
            int start = assembled.length();
            // A CHIP'S PADDING IS A SPACE INSIDE THE BAND, not `padding` on the highlight.
            //
            // `HighlightStyle` does permit horizontal padding and it does paint -- but it inflates the
            // painted RECT and cannot move a glyph, which is the whole reason CSS forbids box properties
            // on a highlight. So the plate grew OUTWARDS into the single space either side of the chip and
            // ate it: `The String class` came out with the plate touching both neighbours, which reads as
            // the chip being badly aligned rather than as padding with nowhere to go.
            //
            // A space inside the band is the same padding with real advance behind it, so layout pushes
            // the neighbours away exactly as an HTML `<code>` box would -- one space outside the plate as
            // the gap, one inside it as the padding. An ordinary U+0020 rather than a thin space: the
            // bundled faces are already known to be missing U+2026 and U+22EE, and a chip that draws tofu
            // on the one machine without the glyph is a worse trade than three pixels of width.
            boolean chip = span.has(MarkupSpan.CODE);
            assembled.append(chip ? " " + span.text() + " " : span.text());
            int end = assembled.length();
            if (end == start) continue;
            TextRange range = TextRange.of(start, end);
            if (span.has(MarkupSpan.CODE)) bands.computeIfAbsent(CODE_RANGE, k -> new ArrayList<>()).add(range);
            if (span.has(MarkupSpan.STRONG)) bands.computeIfAbsent(STRONG_RANGE, k -> new ArrayList<>()).add(range);
            if (span.has(MarkupSpan.EMPHASIS)) bands.computeIfAbsent(EMPHASIS_RANGE, k -> new ArrayList<>()).add(range);
            if (span.has(MarkupSpan.LINK)) bands.computeIfAbsent(LINK_RANGE, k -> new ArrayList<>()).add(range);
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
        return run;
    }
}
