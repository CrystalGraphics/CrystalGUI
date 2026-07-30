package com.crystalgui.core.command;

import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;

/**
 * What a {@link Command} is told when it runs: who triggered it, and any arguments the binding carried.
 *
 * <p>{@code args} is VS Code's {@code "args"} field — an opaque payload attached to a binding rather than
 * to the command, so one command can be bound twice with different parameters
 * ({@code "view.zoom"} at +1 and at −1) instead of becoming two commands. Cheap to have now and awkward
 * to add later, because adding it means changing every handler signature.</p>
 *
 * @param source the element the command was invoked from — the focused element for a keystroke, the
 *               clicked item for a menu. Null for a programmatic {@code CommandRegistry.run(id)}.
 * @param args   binding-supplied payload, or null
 */
public record CommandContext(@Nullable UIElement source, @Nullable Object args) {

    public static CommandContext of(@Nullable UIElement source) {
        return new CommandContext(source, null);
    }
}
