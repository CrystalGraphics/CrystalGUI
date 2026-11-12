package com.crystalgui.core.command;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.Contributions;
import com.crystalgui.core.async.Owner;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.input.keymap.Keymap;

import com.crystalgraphics.job.CgHostThread;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Every command, as contributions: id → {@link Command}, the menus they appear in, and the keys they answer to.
 *
 * <pre>{@code
 * // A module or application declares; the handle withdraws what it declared
 * Disposable done = Commands.process().register(Command.of("mymod.build", "Build")
 *         .binding("Mod+B")
 *         .menu(MenuId.BUILD, "1_build", 10)
 *         .runWithData(data -> build(data.get(PROJECT))));
 *
 * Commands.process().run("mymod.build", CommandContext.of(focused));    // on the invoking document's sequence
 * }</pre>
 *
 * <h3>One process-wide list, and a scope per document</h3>
 * <p>{@link #process()} holds what the process and its applications contribute. A document (and a test) may have a
 * scope of its own, {@code new Commands()}, which holds overrides and falls through to the process list. Both are a
 * {@link Contributions} list, so each change is one generation: the lookup by id, the rows of every menu and the
 * keymap are indices built once per generation and shared by every reader, and a document's frame sees the generation
 * it pinned.</p>
 *
 * <h3>A command reads its context and captures nothing</h3>
 * <p>A handler is given the {@link CommandContext} of the invocation, and {@link Command#runWithData} its
 * {@code DataContext}: it runs on the sequence of the document the key or menu was in, with that document's data. A
 * command with no document (a global chord over the game, a host's key binding) runs on the services sequence and may
 * only post; a handler that needs an element finds none and is disabled.</p>
 *
 * <p>Easy to get wrong: a handler that closes over a workbench or an element keeps it alive for the life of the
 * process and acts on a document that may be gone.</p>
 */
public final class Commands {

    private record MenuHook(MenuId menu, MenuContributor contributor) {
    }

    /** A command with the placement it takes in one menu. */
    private record Placed(Command command, MenuId.Placement placement) {
    }

    /** What one generation of this scope and of the process list make of the commands. */
    private record View(List<Command> all, Map<String, Command> byId, Map<MenuId, List<Placed>> byMenu,
                        Keymap keymap) {
    }

    private record Built(long localGeneration, long processGeneration, View view) {
    }

    private static final Commands PROCESS = new Commands(null);

    /** The process-wide list, which every document's scope falls through to. */
    public static Commands process() {
        return PROCESS;
    }

    @Nullable
    private final Commands parent;
    private final Owner owner = Owner.of("commands");
    private final Contributions<Command> commands = Contributions.of("commands", Command::getId);
    private final Contributions<MenuHook> menuHooks = Contributions.of("menu-contributors");
    private final Set<Class<?>> contributors = ConcurrentHashMap.newKeySet();
    private volatile Built built;

    /** A scope of its own, for a document or a test: its commands shadow the process list's. */
    public Commands() {
        this(PROCESS);
    }

    private Commands(@Nullable Commands parent) {
        this.parent = parent;
    }

    // ── Declaring ────────────────────────────────────────────────────────────────────────────────

    /**
     * Registers {@code command}, replacing any previous one with the same id, and hands back the way to withdraw it.
     *
     * <p>Replacement is allowed rather than rejected, because that is how a theme or a mod overrides a built-in
     * action. It is logged naming both owners, because silently shadowing somebody else's command is otherwise
     * undiagnosable. Disposing removes the command only if it is still the one registered, so withdrawing a feature
     * cannot take a later override with it.</p>
     */
    public Disposable register(Command command) {
        return commands.add(owner, command);
    }

    /** As {@link #register}, for an owner that ends: {@link #removeOwner} withdraws everything it declared. */
    public Disposable contribute(Owner by, Command command) {
        return commands.add(by, command);
    }

    /** Withdraws every command and menu row {@code by} contributed. */
    public void removeOwner(Owner by) {
        commands.removeOwner(by);
        menuHooks.removeOwner(by);
    }

    public Commands unregister(String id) {
        commands.removeIf(command -> command.getId().equals(id));
        return this;
    }

    /**
     * Runs {@code registration} the first time this scope is asked for {@code contributor}, and never again.
     *
     * <p>Once-ness belongs here and not at the call site: a bundle of commands declared from a widget's attach must
     * register once however many widgets of that class attach, and a sentinel command id standing in for "my
     * commands are present" is wrong in both directions. The record lives on the scope, so {@link #resetForTesting}
     * clears it too.</p>
     */
    public Commands contribute(Class<?> contributor, Consumer<Commands> registration) {
        if (contributors.add(contributor)) registration.accept(this);
        return this;
    }

    /**
     * Registers rows to be computed whenever {@code menu} opens. @see MenuContributor
     *
     * <p>Additive: several contributors may serve one menu, and they merge by {@code (group, order)} like every other
     * row.</p>
     */
    public Disposable contributeMenu(MenuId menu, MenuContributor contributor) {
        return menuHooks.add(owner, new MenuHook(menu, contributor));
    }

    // ── Asking ───────────────────────────────────────────────────────────────────────────────────

    /** This scope's command, falling through to the process list. */
    @Nullable
    public Command get(String id) {
        return view().byId().get(id);
    }

    public boolean contains(String id) {
        return view().byId().containsKey(id);
    }

    /** Local commands first, then every process-wide one not shadowed by a local. */
    public Collection<Command> all() {
        return view().all();
    }

    /**
     * Everything in {@code menu} right now, <b>grouped</b>, in group then order.
     *
     * <p>The one query every menu renderer uses: it answers where a row sits, whether it applies, whether it is a
     * checkmark and which way it points, and it keeps the grouping a separator is drawn from. Disabled rows are
     * <b>included</b>, carrying {@code enabled}: a context menu dims what cannot apply, a menu bar keeps a stable
     * shape, and a palette hides, so the registry states the answer and does not act on it. Submenus are listed but
     * not expanded; see {@link MenuEntry.Submenu}.</p>
     */
    public List<MenuSection> sections(MenuId menu, CommandContext context) {
        List<MenuEntry> entries = new ArrayList<>();

        List<Placed> placed = view().byMenu().get(menu);
        if (placed != null) {
            for (Placed row : placed) {
                Command command = row.command();
                if (!command.isShown(context)) continue;
                entries.add(new MenuEntry.Item(command, row.placement().group(), row.placement().order(),
                        command.isEnabled(context), command.isCheckable(), command.isToggled(context)));
            }
        }

        for (MenuId.Submenu nested : menu.submenus()) {
            entries.add(new MenuEntry.Submenu(nested.menu(), nested.title(), nested.group(), nested.order()));
        }

        for (MenuContributor contributor : contributorsFor(menu)) {
            List<MenuEntry> computed = contributor.itemsFor(menu, context);
            if (computed != null) entries.addAll(computed);
        }

        // Stable within a group: sort() is stable and the lists are in contribution order, so two contributors that
        // pick one (group, order) come out in the order they were contributed.
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

    /** Local contributors, then the process ones: the same fall-through {@link #all()} makes. */
    private List<MenuContributor> contributorsFor(MenuId menu) {
        List<MenuContributor> found = new ArrayList<>();
        for (Commands scope = this; scope != null; scope = scope.parent) {
            for (MenuHook hook : scope.menuHooks.all()) {
                if (hook.menu() == menu) found.add(hook.contributor());
            }
        }
        return found;
    }

    /**
     * Every {@link Command#binding} declared on a registered command, as a keymap.
     *
     * <p>A binding and the command it invokes are one fact, so commands declare their own defaults. The resolver
     * walks the element chain first, so an element-scoped binding still wins and one chord can mean different things
     * in different widgets, and falls back to this only when no scope claimed the stroke. Rebuilt once per
     * generation, this scope's or the process list's: an extension registers its commands when it activates, long
     * after the first keystroke.</p>
     */
    public Keymap declaredBindings() {
        return view().keymap();
    }

    /** Empties this scope, contributor record included. For tests that need isolation, never for production. */
    public void resetForTesting() {
        commands.clearForTesting();
        menuHooks.clearForTesting();
        // A bundle that already ran stays "done" forever otherwise, and the next element of that class registers
        // nothing: a missing command only shows up as a key that does nothing.
        contributors.clear();
    }

    // ── Running ──────────────────────────────────────────────────────────────────────────────────

    /** Runs a command by id with no source element. Returns false if unknown or disabled. */
    public boolean run(String id) {
        return run(id, CommandContext.of(null));
    }

    /**
     * Runs a command by id with {@code context}.
     *
     * <p>From a document's sequence or a host thread this runs at once and answers whether the command ran. A command
     * with no source invoked from any other thread (a worker, a native callback) is posted to the services sequence
     * and answers whether it exists; it runs there with no document, so it may only post.</p>
     */
    public boolean run(String id, CommandContext context) {
        Command command = get(id);
        if (command == null) {
            CrystalGuiCore.LOGGER.warn("No command registered as '{}'", id);
            return false;
        }
        if (context.source() == null && UiSequence.current() == null && CgHostThread.here() == null) {
            UiSequence.services().execute(() -> command.execute(context));
            return true;
        }
        return command.execute(context);
    }

    // ── The generation's indices ─────────────────────────────────────────────────────────────────

    private View view() {
        long local = commands.generation();
        long process = parent == null ? 0 : parent.commands.generation();
        Built have = built;
        if (have != null && have.localGeneration() == local && have.processGeneration() == process) {
            return have.view();
        }
        View view = build();
        built = new Built(local, process, view);
        return view;
    }

    private View build() {
        Map<String, Command> byId = new LinkedHashMap<>();
        for (Command command : commands.all()) byId.put(command.getId(), command);
        if (parent != null) {
            for (Command command : parent.commands.all()) byId.putIfAbsent(command.getId(), command);
        }
        List<Command> all = Collections.unmodifiableList(new ArrayList<>(byId.values()));

        Map<MenuId, List<Placed>> byMenu = new HashMap<>();
        Keymap keymap = Keymap.sharingChords();
        for (Command command : all) {
            // One row per command per menu: its first placement there.
            List<MenuId> seen = null;
            for (MenuId.Placement placement : command.menus()) {
                if (seen != null && seen.contains(placement.menu())) continue;
                if (seen == null) seen = new ArrayList<>(2);
                seen.add(placement.menu());
                byMenu.computeIfAbsent(placement.menu(), key -> new ArrayList<>()).add(new Placed(command, placement));
            }
            // Shared chords are by design here (Mod+N's presets, F2, Alt+Enter): enablement picks one.
            for (String spec : command.bindings()) keymap.bind(spec, command.getId());
        }
        return new View(all, Collections.unmodifiableMap(byId), byMenu, keymap);
    }
}
