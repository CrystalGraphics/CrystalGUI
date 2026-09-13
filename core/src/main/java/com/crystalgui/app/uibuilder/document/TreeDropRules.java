package com.crystalgui.app.uibuilder.document;

import java.util.Collection;

import javax.annotation.Nullable;

import com.crystalgui.ui.box.Measurable;
import com.crystalgui.ui.dom.UIElement;

/**
 * What may be moved, and where to — asked before {@link TreeMoves} builds a drop's edits.
 *
 * <pre>{@code
 * if (TreeDropRules.isSource(document.root(), node)) ...                // node may be picked up at all
 * if (TreeDropRules.canMove(document.root(), sources, container)) ...   // container may take them
 * }</pre>
 *
 * <p>GrapesJS's {@code Components.canMove} (BSD-3-Clause), read for the builder's tree: the source must be
 * draggable, the target droppable, and the target not the source nor inside it. What "droppable" means
 * here is the engine's own answer — {@link UIElement#acceptsPublicChildren} — plus one case it does not
 * cover: a node that measures itself draws its own content and lays out no children, so a text leaf is
 * not a container however willing its tree is.</p>
 */
public final class TreeDropRules {

    private TreeDropRules() {
    }

    /** Whether {@code node} may be picked up: a document node other than the root. */
    public static boolean isSource(UIElement root, @Nullable UIElement node) {
        return node != null && node != root && isInside(node, root);
    }

    /** Whether {@code target} may take children at all. */
    public static boolean isContainer(UIElement root, @Nullable UIElement target) {
        return target != null && isInside(target, root)
                && !(target instanceof Measurable) && target.acceptsPublicChildren();
    }

    /** Whether every source may land in {@code target}: a container, and neither one of them nor inside one. */
    public static boolean canMove(UIElement root, Collection<UIElement> sources, @Nullable UIElement target) {
        if (!isContainer(root, target)) return false;
        for (UIElement source : sources) {
            if (isInside(target, source)) return false;
        }
        return true;
    }

    /** Whether {@code node} is {@code ancestor} or somewhere in its light subtree. */
    public static boolean isInside(UIElement node, UIElement ancestor) {
        for (UIElement at = node; at != null; at = at.parentElement()) {
            if (at == ancestor) return true;
        }
        return false;
    }
}
