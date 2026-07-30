package com.crystalgui.core.command;

import com.crystalgui.core.CrystalGuiCore;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * id → {@link Command}. One per {@link com.crystalgui.ui.UIWindow}.
 *
 * <h3>Per window, not a global static</h3>
 * <p>The obvious implementation is a static map, and VS Code effectively has one. Two reasons not to:
 * a server-driven UI can have two windows open whose {@code "edit.save"} legitimately mean different
 * things, and — more immediately — a global mutable registry leaks between tests, so one test registering
 * a command changes what another test resolves. Ownership by the window costs one field and removes both
 * problems.</p>
 *
 * <p>Insertion-ordered so {@link #all()} is stable, which a command palette wants and a test needs.</p>
 */
public final class CommandRegistry {

    private final Map<String, Command> byId = new LinkedHashMap<>();

    /**
     * Registers {@code command}, replacing any previous one with the same id.
     *
     * <p>Replacement is allowed rather than rejected, because that is how a theme or a mod overrides a
     * built-in action — the same way re-adding a stylesheet is allowed. It is logged, because silently
     * shadowing somebody else's command is otherwise undiagnosable.</p>
     */
    public CommandRegistry register(Command command) {
        Command previous = byId.put(command.getId(), command);
        if (previous != null && previous != command) {
            CrystalGuiCore.LOGGER.info("Command '{}' was replaced by a later registration", command.getId());
        }
        return this;
    }

    @Nullable
    public Command get(String id) {
        return byId.get(id);
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    public Collection<Command> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public CommandRegistry unregister(String id) {
        byId.remove(id);
        return this;
    }

    /** Runs a command by id with no source element. Returns false if unknown or disabled. */
    public boolean run(String id) {
        return run(id, CommandContext.of(null));
    }

    public boolean run(String id, CommandContext context) {
        Command command = byId.get(id);
        if (command == null) {
            CrystalGuiCore.LOGGER.warn("No command registered as '{}'", id);
            return false;
        }
        return command.execute(context);
    }
}
