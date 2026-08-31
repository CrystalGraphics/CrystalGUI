package com.crystalgui.core.data;

import java.util.List;
import javax.annotation.Nullable;

/**
 * <b>What a command resolves its subject through</b> — the seam that stopped the command layer being
 * welded to one engine.
 *
 * <h3>Why this exists</h3>
 *
 * <p>{@link DataContext} and {@code CommandContext} were {@code record …(UIElement source, …)}, and
 * {@code Keymap} and {@code KeymapResolver} took one too: 771 lines across five files, called from
 * fifty-five. Every one of them wants the same two things and neither is about being an element —
 * walk OUTWARD, and ask each step whether it knows something. Naming {@code UIElement} to say that
 * made the whole command layer old-engine-only, which is what blocked {@code ContextMenu},
 * {@code MenuBuilder} and the inspector's four out of M6.2 and moved them into 6.3.</p>
 *
 * <h3>The walk is the whole interface</h3>
 *
 * <p>Two methods, and the second has a default, because that is genuinely all of it. A context walks
 * {@link #commandParent()} from wherever the gesture came from, tests each step for
 * {@link DataProvider}, and falls back to {@link #scopeProviders()} once nothing has answered. Both
 * engines supply it in a line: the old one's is {@code getParent()}, the new one's is
 * {@code parent()}.</p>
 *
 * <p><b>Not {@code Styleable}</b>, which is the other seam of this shape and is too narrow: it
 * carries the cascade's questions and has no parent to walk. And not a generic parameter, which
 * would have put a type variable on {@code DataKey}, {@code Command} and every {@code enabledWhen}
 * in the application to say something none of them cares about.</p>
 */
public interface CommandTarget {

    /**
     * The next step outward, or null at the top.
     *
     * <p>The LIGHT parent on either engine, not the composed one: a command's subject is what the
     * author built, and a widget's internal parts are not subjects.</p>
     */
    @Nullable
    CommandTarget commandParent();

    /**
     * Providers that answer for the whole surface, asked only once the walk has found nothing.
     *
     * <p>IntelliJ's frame-level {@code DataProvider} and VS Code's window-scoped context keys.
     * {@link DataContext} explains at length why the chain alone is not enough — a workbench is a
     * DESCENDANT of the root, so with nothing focused the walk starts at the root and never reaches
     * it, which is exactly how a window looks the moment it opens.</p>
     *
     * <p>Empty by default, so a node that is in no surface yet answers honestly rather than
     * throwing.</p>
     */
    default List<DataProvider> scopeProviders() {
        return List.of();
    }
}
