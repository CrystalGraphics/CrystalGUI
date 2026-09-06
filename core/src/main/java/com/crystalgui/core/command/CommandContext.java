package com.crystalgui.core.command;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.CommandTarget;

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
public record CommandContext(@Nullable CommandTarget source, @Nullable Object args) {

    public static CommandContext of(@Nullable CommandTarget source) {
        return new CommandContext(source, null);
    }

    /**
     * What is being acted on — the answers reachable from {@link #source}.
     *
     * <p>This is how a command finds its subject without naming the widget that supplies it. Prefer it
     * to walking {@code source().getParent()} by hand: three such walks existed before
     * {@link DataContext} did, one per type, and each was a place where a new widget silently failed to
     * participate.</p>
     *
     * <p><b>Built fresh each call, deliberately.</b> A context caches within one pass and is only valid
     * for that pass — see {@code DataContext}. A command that asks several keys should hold the result
     * of one call rather than calling this repeatedly, and must not keep it past the invocation.</p>
     */
    public DataContext data() {
        return DataContext.from(source);
    }
}
