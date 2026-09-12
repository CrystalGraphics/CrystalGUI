package com.crystalgui.style.property.visual.text;

/**
 * CSS {@code paint-order}, restricted to the single-keyword forms this engine can express.
 *
 * <p>The spec's grammar is {@code normal | [ fill || stroke || markers ]}, and a keyword left out
 * follows in its default position — so {@code paint-order: stroke} already means "stroke, then
 * fill", which is the whole of what anyone writes it for on text. {@code markers} is SVG-only and
 * has no meaning here.</p>
 *
 * <p>The multi-keyword forms are not accepted. They are all either a synonym for one of these or a
 * reordering of {@code markers}, and a parser that silently accepted {@code fill stroke markers}
 * while ignoring a third of it would be worse than one that says no.</p>
 */
public enum PaintOrder {
    /** Fill, then stroke. The initial value, and what SVG has always done. */
    NORMAL,
    /** Same as {@link #NORMAL}; spelled out because {@code paint-order: fill} is legal CSS. */
    FILL,
    /**
     * Stroke first, so the fill covers its inner half.
     *
     * <p>This is the one authors actually reach for. A centred stroke eats into the letterform, and
     * painting it underneath hides exactly the half that was doing the damage — which is why the web
     * pairs it with {@code -webkit-text-stroke} almost every time. With
     * {@code stroke-align: outset} available there is nothing left to hide, so this becomes a real
     * ordering choice rather than a workaround.</p>
     */
    STROKE
}
