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
import java.util.concurrent.CopyOnWriteArrayList;
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
     * Everything in {@code menu} right now, <b>grouped</b>, in group then order.
     *
     * <h3>The one query every menu renderer should use</h3>
     *
     * <p>It answers all four questions a row needs — where it sits, whether it applies, whether it is a
     * checkmark and which way it points — and it keeps the grouping, which is the fact a separator is
     * drawn from. {@link #menu} kept none of it, so {@code ContextMenu} re-derived the lot by walking
     * {@link #all()} itself, and a menu bar would have been a third derivation of the same thing. See
     * {@link MenuSection}.</p>
     *
     * <h3>Disabled rows are INCLUDED, carrying {@code enabled}</h3>
     *
     * <p>The registry states the answer and does not act on it. That is the difference between the two
     * renderers this feeds: a context menu is built for one position and dims what cannot apply, a menu
     * bar must keep a stable shape so File ▸ Save is always the fourth row whether or not there is
     * anything to save, and a palette hides. Filtering here would force two of the three to work around
     * it — as {@link #menu} did, which is why nothing but a test ever called it.</p>
     *
     * <p>Submenus are listed but <b>not expanded</b>; see {@link MenuEntry.Submenu} for why. Contributed
     * rows from {@link #contributeMenu} are merged in and sort by the same {@code (group, order)} pair, so
     * a computed row is an ordinary participant rather than something pinned to one end.</p>
     */
    public List<MenuSection> sections(MenuId menu, CommandContext context) {
        List<MenuEntry> entries = new ArrayList<>();

        for (Command command : all()) {
            for (MenuId.Placement placement : command.menus()) {
                if (placement.menu() != menu) continue;
                entries.add(new MenuEntry.Item(command, placement.group(), placement.order(),
                        command.isEnabled(context), command.isCheckable(), command.isToggled(context)));
                break;
            }
        }

        for (MenuId.Submenu nested : menu.submenus()) {
            entries.add(new MenuEntry.Submenu(nested.menu(), nested.title(), nested.group(), nested.order()));
        }

        for (MenuContributor contributor : contributorsFor(menu)) {
            List<MenuEntry> computed = contributor.itemsFor(menu, context);
            if (computed != null) entries.addAll(computed);
        }

        // STABLE within a group: sort() is stable and `all()` is insertion-ordered, so two contributors
        // that pick the same (group, order) come out in registration order rather than arbitrarily. That
        // is weaker than picking distinct orders and stronger than nothing, which is what a menu needs.
        entries.sort(Comparator.comparing(MenuEntry::group).thenComparingInt(MenuEntry::order));

        List<MenuSection> sections = new ArrayList<>();
        String group = null;
        List<MenuEntry> current = null;
        for (MenuEntry entry : entries) {
            if (current == null || !group.equals(entry.group())) {
                group = entry.group();
                current = new ArrayList<>();
                sections.add(new MenuSection(group, current));
            }
            current.add(entry);
        }
        return Collections.unmodifiableList(sections);
    }

    /**
     * Registers rows to be computed whenever {@code menu} opens. @see MenuContributor
     *
     * <p>Keyed by menu rather than kept in one list, so opening the File menu does not ask the Window
     * menu's contributor whether it has anything to say. Additive: several contributors may serve one
     * menu, and they merge by {@code (group, order)} like everything else.</p>
     */
    public CommandRegistry contributeMenu(MenuId menu, MenuContributor contributor) {
        menuContributors.computeIfAbsent(menu, key -> new CopyOnWriteArrayList<>()).add(contributor);
        return this;
    }

    /** Local contributors, then global ones — the same fall-through {@link #all()} makes. */
    private List<MenuContributor> contributorsFor(MenuId menu) {
        List<MenuContributor> local = menuContributors.get(menu);
        if (this == GLOBAL) return local == null ? List.of() : local;
        List<MenuContributor> shared = GLOBAL.menuContributors.get(menu);
        if (local == null) return shared == null ? List.of() : shared;
        if (shared == null) return local;
        List<MenuContributor> merged = new ArrayList<>(local);
        merged.addAll(shared);
        return merged;
    }

    private final Map<MenuId, List<MenuContributor>> menuContributors = new ConcurrentHashMap<>();

    /**
     * The flat, enabled-only view of {@code menu}.
     *
     * @deprecated {@link #sections} instead — this drops the grouping a separator is drawn from and
     * filters out disabled rows, and both of those are the renderer's call. Kept only because the shape
     * is occasionally what a caller assembling a list from scratch wants.
     */
    @Deprecated
    public List<Command> menu(MenuId menu, CommandContext context) {
        List<Command> found = new ArrayList<>();
        for (MenuSection section : sections(menu, context)) {
            for (MenuEntry entry : section.entries()) {
                if (entry instanceof MenuEntry.Item item && item.enabled()) found.add(item.command());
            }
        }
        return Collections.unmodifiableList(found);
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
        // Same argument one level out: a computed menu row survives a reset unless this is cleared, and it
        // survives holding a lambda that closes over the PREVIOUS test's widgets.
        menuContributors.clear();
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
