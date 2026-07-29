package com.crystalgui.style.property.visual;

/**
 * CSS-facing {@code resize:} value — whether the user may drag an element's corner to resize it.
 *
 * <p>A port of <a href="https://www.w3.org/TR/css-ui-4/#resize">CSS Basic User Interface L4</a>, not an
 * invention. Two things about that spec are worth knowing before touching the implementation, because
 * both are easy to get wrong in opposite directions.</p>
 *
 * <h3>The resulting size is written at INLINE origin, not IMPORTANT</h3>
 * <p>Spec: "the user agent sets the width and height properties to px unit length values of the size
 * indicated by the user in the element's style attribute DOM, replacing existing property
 * declaration(s), if any, <b>without {@code !important}</b>."</p>
 *
 * <p>Every other user- or widget-driven geometry write in this engine goes in at
 * {@code StyleOrigin.IMPORTANT}. This one must not: at IMPORTANT a user resize would outrank an
 * author's {@code !important} rule, which the spec explicitly forbids. {@code StyleGroup.inlinePipeline}
 * is the right pipeline, and it is the single most likely thing to be "tidied" into the wrong one.</p>
 *
 * <h3>We deliberately ignore the scroll-container restriction</h3>
 * <p>Spec: "the resize property applies to elements that are scroll containers." <b>We apply it
 * regardless of {@code overflow}.</b></p>
 *
 * <p>Part of that restriction is an artifact of <em>where browsers put the widget</em>: the resizer is
 * drawn in the scrollbar corner, so an element with no scrollbar gutter had nowhere to render it. That
 * part is a rendering accident, it is not semantic, and it produces one of the web's more irritating
 * gotchas ("why does resize do nothing?"). This engine draws its own grabber as an internal child, so
 * it buys nothing and costs expressiveness — a resizable panel in a UI toolkit is very often not
 * scrollable.</p>
 *
 * <p><b>But the restriction has a second, better justification, and diverging means inheriting the
 * problem it solved.</b> A scroll container by definition contains its content; a
 * {@code overflow: visible} box does not. Shrink a resizable element below its content and the content
 * spills out of it — correct CSS, and visually broken. Browsers never have to face that because
 * {@code resize} implies a scroll container.</p>
 *
 * <p>The trade is still worth it, because clipping is one declaration and inexpressiveness is
 * forever. <b>An element that opts into {@code resize} should normally also set {@code overflow}</b>,
 * and anything shipping a resizable panel wants that in its stylesheet. Recorded here rather than
 * enforced: there is no selector for "has resize set", so a user-agent rule cannot express it, and
 * silently forcing {@code overflow} in Java would be exactly the kind of invisible magic this codebase
 * avoids.</p>
 *
 * <h3>Divergence: no {@code block} / {@code inline}</h3>
 * <p>The spec also defines writing-mode-relative {@code block} and {@code inline}. This engine has no
 * writing-mode support at all, so they would be silent aliases of {@link #VERTICAL} and
 * {@link #HORIZONTAL} that quietly become wrong the day it gains one. Omitted rather than faked.</p>
 */
public enum Resize {
    /** No resizing mechanism is offered. The initial value. */
    NONE,
    /** Both axes — the grabber adjusts width and height together. */
    BOTH,
    /** Width only. */
    HORIZONTAL,
    /** Height only. */
    VERTICAL;

    public boolean isResizable() {
        return this != NONE;
    }

    public boolean allowsWidth() {
        return this == BOTH || this == HORIZONTAL;
    }

    public boolean allowsHeight() {
        return this == BOTH || this == VERTICAL;
    }
}
