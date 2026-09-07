package com.crystalgui.core.attribute;

/**
 * Something whose properties can be copied off it and pasted onto another of its kind.
 *
 * <p>Premiere's and DaVinci Resolve's <em>Paste Attributes</em>, and Excel's Paste Special before them:
 * copy one object, then choose which of its properties land on another. The engine owns the transfer and
 * the window; a consumer owns what its attributes ARE.</p>
 *
 * <pre>{@code
 * public AttributeSet copyAttributes() {
 *     return AttributeSet.builder(DOMAIN, "#save")
 *             .add(new AttributeSlot("opacity", "Appearance", "Opacity"), style.opacity())
 *             .build();
 * }
 *
 * public void pasteAttributes(AttributeSet chosen) {
 *     for (AttributeSet.Entry entry : chosen.entries()) apply(entry.slot().id(), entry.value());
 * }
 * }</pre>
 *
 * <h3>The domain is what stops nonsense</h3>
 *
 * <p>Attributes copied from a graph node cannot be pasted onto a widget, and the check is a string rather
 * than a Java type so that two unrelated consumers can still agree to share a vocabulary if they want to.
 * {@link AttributeClipboard} refuses a mismatch; a carrier never has to.</p>
 *
 * <h3>Undo is the consumer's</h3>
 *
 * <p>{@code pasteAttributes} just does it. Wrapping the change in an {@code Edit} needs a document and a
 * history, and an engine that guessed at either would be wrong for somebody — the caller records the
 * before and after it already knows how to record.</p>
 */
public interface AttributeCarrier {

    /** Everything this object is willing to hand over. */
    AttributeSet copyAttributes();

    /** Applies a chosen subset. Already filtered and already domain-checked. */
    void pasteAttributes(AttributeSet chosen);
}
