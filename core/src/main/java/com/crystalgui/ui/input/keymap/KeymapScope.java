package com.crystalgui.ui.input.keymap;

import com.crystalgui.core.data.CommandTarget;
import javax.annotation.Nullable;

/**
 * A {@link CommandTarget} that may also carry a {@link Keymap}.
 *
 * <h3>Why it is a second interface and not one</h3>
 *
 * <p>{@code CommandTarget} lives in {@code core.data}, which may not name {@code ui.input.keymap} —
 * the dependency only points one way, and putting {@code keymapOrNull()} on it would have inverted
 * that to say something only the keymap cares about. So the keymap declares its own extension where
 * it can name its own type, and {@code DataContext} goes on taking the narrower one.</p>
 *
 * <p>{@link #commandParent()} is narrowed covariantly, so the resolver's walk stays typed and needs
 * no cast: every scope it reaches can be asked for a keymap.</p>
 */
public interface KeymapScope extends CommandTarget {

    @Override
    @Nullable
    KeymapScope commandParent();

    /** This scope's own keymap, or null when it has none — which is most nodes. */
    @Nullable
    default Keymap keymapOrNull() {
        return null;
    }

    /**
     * Whether typing into this scope means typing rather than invoking.
     *
     * <p>On the seam because it is a KEYMAP question, not a widget one: it decides whether a binding
     * marked {@code allowWhileTyping} is the only kind that may fire. The standing rows are the
     * argument — {@code TextField} refuses ALT chords, and a menu mnemonic must not fire while a text
     * field has focus — and both are about the FOCUSED thing rather than about any particular
     * class.</p>
     */
    default boolean consumesTextInput() {
        return false;
    }
}
