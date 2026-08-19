package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A recycled run of identical decoration elements — one guide, marker, ruler or number per use.
 *
 * <p>Every {@code layOut*} method wrote the same four lines: grow a {@code List<UIElement>} on demand,
 * count how many it used, then hide the tail. That idiom is what this is, named once.</p>
 *
 * <h3>Hiding clears the text as well as collapsing the box</h3>
 * <p>Zero size hides a <em>fill</em> and nothing else — a {@code UIText} inside has no clipping of its own
 * and keeps painting its glyph where the box used to be. The line numbers appear immune only because the
 * gutter around them sets {@code overflow: hidden}; a whitespace marker has nothing around it, so turning
 * the feature off left every dot on screen. Both scars are in the original methods and both live here now.
 * </p>
 *
 * <h3>A recycled element must also stop answering the mouse</h3>
 * <p>A zero-sized element is still hit-testable at its origin, so a pooled control left pointing at a row
 * it no longer shows would still act on a stray click. Pools whose elements are interactive — the fold
 * arrows — clear their row mapping alongside {@link #endPass()}; see {@code FoldArrowsPart}.</p>
 */
final class DecorationPool {

    private final List<UIElement> elements = new ArrayList<>();
    private final Supplier<UIElement> parent;
    private final String className;
    private final boolean withText;
    private int used;

    /**
     * @param parent    where a newly grown element is attached. A {@link Supplier} rather than an element
     *                  because the text viewport is created lazily, and a pool built in a field
     *                  initialiser would otherwise force it into existence before the editor is ready.
     * @param className the CSS class the sheet styles this decoration by
     * @param withText  whether each element carries a {@link UIText} child
     */
    DecorationPool(Supplier<UIElement> parent, String className, boolean withText) {
        this.parent = parent;
        this.className = className;
        this.withText = withText;
    }

    /** Starts a pass. Everything handed out afterwards is counted as used. */
    void beginPass() {
        used = 0;
    }

    /** How many elements this pass has taken so far. */
    int used() {
        return used;
    }

    /** The next element of the pass, growing the pool if it has run out. Shown; the caller places it. */
    UIElement next() {
        while (elements.size() <= used) {
            UIElement element = new UIElement();
            element.addClass(className);
            element.setHitTest(false);
            element.markAsInternal();
            if (withText) element.addChild(new UIText(""));
            parent.get().addInternalChild(element);
            elements.add(element);
        }
        return show(elements.get(used++));
    }

    /** Retires everything this pass did not use. */
    void endPass() {
        for (int i = used; i < elements.size(); i++) hide(elements.get(i));
    }

    /** Retires the whole pool — what a part does when its feature is switched off. */
    void hideAll() {
        used = 0;
        endPass();
    }

    /** Every element ever created, for the parts that need to reach them directly. */
    List<UIElement> all() {
        return elements;
    }

    /**
     * Retires one element — out of layout, box collapsed, any text cleared.
     *
     * <h3>{@code display: none} as well as a zero box, and the pair is not belt-and-braces</h3>
     *
     * <p>The zero box is written at DEFAULT origin, which is <b>below the user-agent sheet</b>. So the
     * moment any pooled decoration is given a size in CSS — as the squiggle and the error-stripe mark now
     * are, so that the part can read the value instead of owning it — the sheet outranks the collapse and
     * a retired element goes on measuring its styled height. That is not a squiggle in the wrong place; it
     * is a squiggle under text with no problem, which reads as the editor being wrong rather than the
     * diagnostic. {@code SquigglesTest} and {@code ErrorStripeTest} both caught it within the same edit.</p>
     *
     * <p>{@link UIElement#setDisplayed} writes at IMPORTANT, so no stylesheet can outrank it, and it takes
     * the element out of layout entirely rather than leaving a zero-sized box in the flow. The zero box
     * stays underneath it because it is what a theme's own sizing is measured against on the way back in,
     * and because clearing the text is a separate scar — a {@code UIText} has no clipping of its own and
     * keeps painting its glyph where the box used to be.</p>
     */
    static void hide(UIElement element) {
        element.setDisplayed(false);
        StyleGroup.defaultPipeline(element.getStyle().getLayoutGroup(), l -> l.width(0f).height(0f));
        for (UIElement child : element.getChildren()) {
            if (child instanceof UIText label) label.setText("");
        }
    }

    /** The other half of {@link #hide} — puts a recycled element back into layout. */
    static UIElement show(UIElement element) {
        element.setDisplayed(true);
        return element;
    }
}
