package com.crystalgui.core.command;

import com.crystalgui.core.CrystalGuiCore;

import com.crystalgui.ui.input.keymap.Keymap;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
        version++;
        if (previous != null && previous != command) {
            CrystalGuiCore.LOGGER.info("Command '{}' was replaced by a later registration", command.getId());
        }
        return this;
    }

    /**
     * The registry everything registers into.
     *
     * <h3>Commands are global; context is local</h3>
     *
     * <p>That split is the design, and having it backwards is what this fixes. A registry lived on each
     * {@code UIWindow}, so every window re-registered everything and a widget had to find "its" window
     * before it could contribute — which is why widgets grew {@code installCommands()} methods,
     * {@code commandsInstalled} guards, and in one case a call from a <b>frame ticker</b>: a graph's
     * commands did not exist until a frame after it attached, so a palette opened before that was
     * missing them.</p>
     *
     * <p>Both references keep exactly one registry and scope the <em>context</em> instead. A command is
     * a fact about the application; what varies per window is what is focused, and that is
     * {@link com.crystalgui.core.data.DataContext}'s job.</p>
     *
     * <p>Registration is still <b>explicit</b>. A static initialiser would make a command's existence
     * depend on class-loading order, and this engine already refuses that — see
     * {@code CrystalEditorCommands}: "a registry that quietly acquired commands nobody registered
     * surprises anything that enumerates it", which is exactly what a palette does.</p>
     */
    public static CommandRegistry global() {
        return GLOBAL;
    }

    private static final CommandRegistry GLOBAL = new CommandRegistry();

    /**
     * This registry's command, falling through to {@link #global()}.
     *
     * <p>An instance is a place to put <em>overrides</em>; almost nothing needs one. The fall-through is
     * what lets a window keep a local registry — which tests use for isolation — without every global
     * command having to be registered into it as well.</p>
     */
    @Nullable
    public Command get(String id) {
        Command local = byId.get(id);
        if (local != null) return local;
        return this == GLOBAL ? null : GLOBAL.byId.get(id);
    }

    public boolean contains(String id) {
        return byId.containsKey(id) || (this != GLOBAL && GLOBAL.byId.containsKey(id));
    }

    /** Local commands first, then every global one not shadowed by a local. */
    public Collection<Command> all() {
        if (this == GLOBAL) return Collections.unmodifiableCollection(byId.values());
        Map<String, Command> merged = new LinkedHashMap<>(byId);
        for (Command command : GLOBAL.byId.values()) merged.putIfAbsent(command.getId(), command);
        return Collections.unmodifiableCollection(merged.values());
    }

    /**
     * What belongs in {@code menu} here, in group then order.
     *
     * <p><b>Disabled commands are omitted, not greyed.</b> A context menu is built for one position and
     * an entry that cannot apply there is noise; a palette wants the opposite and asks {@link #all()},
     * rendering enablement itself. Two callers, two needs, so the filtering lives at the call.</p>
     */
    public List<Command> menu(MenuId menu, CommandContext context) {
        List<Command> found = new ArrayList<>();
        for (Command command : all()) {
            if (!command.isEnabled(context)) continue;
            for (MenuId.Placement placement : command.menus()) {
                if (placement.menu() == menu) {
                    found.add(command);
                    break;
                }
            }
        }
        found.sort(Comparator
                .comparing((Command command) -> placementIn(command, menu).group())
                .thenComparingInt(command -> placementIn(command, menu).order()));
        return Collections.unmodifiableList(found);
    }

    private static MenuId.Placement placementIn(Command command, MenuId menu) {
        for (MenuId.Placement placement : command.menus()) {
            if (placement.menu() == menu) return placement;
        }
        throw new IllegalStateException(command.getId() + " is not in " + menu);
    }

    /**
     * Every {@link Command#binding} declared on a registered command, as a keymap.
     *
     * <h3>Why the resolver consults this last</h3>
     *
     * <p>A binding and the command it invokes are one fact, so commands declare their own defaults. But
     * a declaration is not a <em>scope</em> — it says "this chord means this command", not "in this
     * widget". The resolver therefore walks the element chain first, so an element-scoped binding still
     * wins and one chord can mean different things in different widgets, and falls back to this only
     * when no scope claimed the stroke.</p>
     *
     * <p>That is what let {@code bindDefaults(root.keymap())} and every {@code keymap().bind(...)} call
     * inside a widget go away: an application-wide default is now a property of the command, and needs
     * nobody to install it on anything.</p>
     *
     * <p>Rebuilt when the registration set changes, which is at startup and then never.</p>
     */
    public synchronized Keymap declaredBindings() {
        if (declared == null || declaredVersion != version) {
            Keymap built = new Keymap();
            for (Command command : all()) {
                for (String spec : command.bindings()) built.bind(spec, command.getId());
            }
            declared = built;
            declaredVersion = version;
        }
        return declared;
    }

    private Keymap declared;
    private int declaredVersion = -1;
    private int version;

    /**
     * Runs {@code registration} the first time this registry is asked for {@code contributor}, and never
     * again.
     *
     * <h3>Why once-ness belongs here and not at the call site</h3>
     *
     * <p>Every bundle used to open with {@code if (registry.contains(SAVE_FILE)) return;} — one arbitrary
     * command id standing in for a whole set. That is wrong in both directions: add a command to the
     * bundle and it never registers, because the sentinel is already there; unregister the sentinel alone
     * and the whole bundle re-runs. It also cannot say what it means, since "are my commands present" and
     * "has this contributor run" are different questions that happen to agree on the day it was written.</p>
     *
     * <p>Keying on the contributor <em>class</em> answers the real question exactly once, and — because
     * the record lives on the registry rather than in a static — {@link #resetForTesting()} clears it too.
     * A static latch does not: it outlives the reset, so the next test resets the registry, builds an
     * element whose class has already been seen, and gets no commands at all. That is the failure this
     * method exists to make impossible.</p>
     */
    public CommandRegistry contribute(Class<?> contributor, Consumer<CommandRegistry> registration) {
        if (contributors.add(contributor)) registration.accept(this);
        return this;
    }

    private final Set<Class<?>> contributors = ConcurrentHashMap.newKeySet();

    /** Empties this registry, contributor record included. For tests that need isolation, never for production. */
    public void resetForTesting() {
        byId.clear();
        // Without this, a contributor that already ran stays "done" forever and the very next element of
        // that class registers nothing -- silently, since a missing command only shows up as a key that
        // does nothing.
        contributors.clear();
        version++;
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
        // get(), not byId -- a globally registered command must be runnable through a window's registry,
        // and reading the local map directly is how "the command exists but nothing happens" appears.
        Command command = get(id);
        if (command == null) {
            CrystalGuiCore.LOGGER.warn("No command registered as '{}'", id);
            return false;
        }
        return command.execute(context);
    }
}
