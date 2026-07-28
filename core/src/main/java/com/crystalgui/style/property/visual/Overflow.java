package com.crystalgui.style.property.visual;

/**
 * CSS-facing {@code overflow:} value — whether an element clips its content, and whether that content
 * can scroll. The author never picks the clip <em>mechanism</em>: {@link OverflowClip#SCISSOR} vs
 * {@link OverflowClip#MASK} is auto-detected from the element's resolved shape (corner radius,
 * explicit {@code mask:}, sprite background) — see {@code UIElement#resolveOverflowClip()}.
 *
 * <p>Deliberately CrystalGUI's own enum rather than Taffy's, even though Taffy ships one and
 * {@code LayoutProperties} otherwise uses Taffy types directly. Taffy's is
 * {@code VISIBLE/CLIP/HIDDEN/SCROLL} with <b>no {@code AUTO}</b> — correct for a pure layout engine,
 * since for layout {@code auto} is indistinguishable from {@code scroll}/{@code hidden} and the only
 * layout-visible difference is whether a scrollbar gutter is reserved. But "bars only when needed" is
 * the most common overflow value in real CSS, so adopting Taffy's set wholesale would make it
 * inexpressible. {@code TaffyBridge.setOverflow} maps this onto Taffy's set instead.</p>
 */
public enum Overflow {
    /** Content is not clipped and may spill outside the element. Not a scroll container. */
    VISIBLE,
    /** Clipped, and <b>not</b> scrollable — not even programmatically. CSS's {@code clip}. */
    CLIP,
    /**
     * Clipped and scrollable, but scrollbars are never drawn.
     *
     * <p>Being scrollable is not an oversight: in CSS {@code overflow: hidden} <em>is</em> a scroll
     * container and {@code scrollTop} works on it — {@link #CLIP} above is the value that genuinely
     * cannot scroll. This is the one people most often expect to behave like {@code clip}.</p>
     */
    HIDDEN,
    /** Clipped, scrollable, scrollbars always drawn. */
    SCROLL,
    /** Clipped, scrollable, scrollbars drawn only when the content actually overflows. */
    AUTO;

    /** Whether this establishes a scroll container — i.e. whether {@code scrollTop}/{@code scrollLeft}
     * do anything <em>programmatically</em>. Mirrors Taffy's own {@code Overflow.isScrollContainer()},
     * extended with {@link #AUTO}. */
    public boolean isScrollContainer() {
        return this == HIDDEN || this == SCROLL || this == AUTO;
    }

    /**
     * Whether the <em>user</em> may scroll this — wheel, drag, touch.
     *
     * <p>Deliberately narrower than {@link #isScrollContainer()}, and the distinction is exactly what
     * makes {@link #HIDDEN} confusing. CSS Overflow 3 says of {@code hidden}: "like {@code clip},
     * except the box is still a scroll container, and thus programmatically scrollable… <b>UAs must
     * not provide a scrolling mechanism</b>". So an {@code overflow: hidden} box responds to
     * {@code scrollTop} and to {@code scrollIntoView}, but ignores the mouse wheel — which is what
     * everyone has actually observed in a browser without necessarily knowing why.</p>
     */
    public boolean allowsUserScrolling() {
        return this == SCROLL || this == AUTO;
    }

    /** Whether content is clipped to the padding box — everything except {@link #VISIBLE}. */
    public boolean clips() {
        return this != VISIBLE;
    }

    /** Whether a scrollbar should be shown, given whether the content actually overflows. */
    public boolean showsScrollbar(boolean overflowing) {
        return this == SCROLL || (this == AUTO && overflowing);
    }
}
