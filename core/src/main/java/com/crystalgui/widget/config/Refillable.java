package com.crystalgui.widget.config;

import com.crystalgui.ui.dom.UIElement;

/**
 * An element a refilled form may keep from the fill before, instead of placing the one this fill built.
 *
 * <pre>{@code
 * final class KindHeader extends UIElement implements Refillable<KindHeader> {
 *     public boolean adopt(KindHeader fresh) {
 *         node.set(fresh.node.get());   // everything it shows follows node
 *         return true;
 *     }
 * }
 *
 * form.custom(new KindHeader(node));   // the second fill keeps the first header
 * }</pre>
 *
 * <p>Offered to a {@link ConfigForm#custom} placement, and to the control of a {@link ConfigForm#control} one, when
 * the fill before placed an element of the same class in the same place. Without it that element is removed and
 * {@code fresh} placed, which is always correct and costs a new subtree.</p>
 *
 * <ul>
 *   <li>{@code adopt} takes over what {@code fresh} would have shown and answers true — or answers false having
 *       changed nothing, and {@code fresh} is placed instead.</li>
 *   <li>Use what the form returns: it is this element when it adopted, and {@code fresh} is then never shown.</li>
 * </ul>
 */
public interface Refillable<E extends UIElement> {

    boolean adopt(E fresh);
}
