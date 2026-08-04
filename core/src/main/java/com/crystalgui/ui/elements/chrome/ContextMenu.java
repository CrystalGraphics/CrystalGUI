package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * A right-click menu built from commands.
 *
 * <h3>Commands, not handlers — and this is the whole design</h3>
 *
 * <p>An item names a {@link Command} id and nothing else. Its label, whether it is enabled, what it does
 * and which keystroke it advertises all come from the registry, so <b>the menu, the palette and the
 * keyboard cannot disagree</b> — they are three views of one list. A menu built from lambdas is a fourth
 * place to keep in sync, and it is the one that goes stale, because nothing enumerates it.</p>
 *
 * <p>That is the same argument {@code UndoCommands} makes in its own javadoc, and the reason
 * {@code edit.undo} appearing in a context menu costs nothing here.</p>
 *
 * <h3>Dim, do not hide</h3>
 *
 * <p>Unavailable items are shown disabled, never omitted. IntelliJ's rule, and this repo has already paid
 * for the alternative once: the command palette copied VS Code's hide-disabled behaviour and listed
 * <b>1 of 9</b> commands, because every {@code enabledWhen} here resolves outward from the focused element
 * and "nothing focused" answers no to everything. A menu that changes shape depending on what is
 * available is also a menu whose items are never in the same place twice.</p>
 *
 * <h3>Where it opens</h3>
 *
 * <p>{@link Menu#showAt} — anchored to the pointer, which {@code Popover} and {@code AnchoredPlacement}
 * already do, including flipping when there is no room below or to the right.</p>
 */
public final class ContextMenu {

    /** One row: a command id, or a separator, or a submenu. */
    private sealed interface Entry permits CommandEntry, SeparatorEntry, SubmenuEntry {
    }

    private record CommandEntry(String commandId, @Nullable String labelOverride) implements Entry {
    }

    private record SeparatorEntry() implements Entry {
    }

    private record SubmenuEntry(String label, ContextMenu menu) implements Entry {
    }

    private final List<Entry> entries = new ArrayList<>();

    private ContextMenu() {
    }

    public static ContextMenu builder() {
        return new ContextMenu();
    }

    /** Adds a command, labelled from the registry. */
    public ContextMenu item(String commandId) {
        entries.add(new CommandEntry(commandId, null));
        return this;
    }

    /** Adds a command under a label of your own — for an item whose menu wording differs from its
     * command name ("Delete" in a menu, "Delete File" in the palette, one id). */
    public ContextMenu item(String commandId, String label) {
        entries.add(new CommandEntry(commandId, label));
        return this;
    }

    /** A rule between groups. Leading, trailing and doubled separators are dropped when the menu is
     * built, so a caller may add one after every group without counting. */
    public ContextMenu separator() {
        entries.add(new SeparatorEntry());
        return this;
    }

    public ContextMenu submenu(String label, Consumer<ContextMenu> build) {
        ContextMenu nested = new ContextMenu();
        build.accept(nested);
        entries.add(new SubmenuEntry(label, nested));
        return this;
    }

    // ── Building ────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds a live {@link Menu} against a registry and the element the menu is for.
     *
     * <p>{@code source} is what every command resolves against — its {@code enabledWhen}, its
     * {@code run}, and the keymap lookup for its accelerator. It is the element that was
     * <em>right-clicked</em>, not the focused one: right-clicking a row in a tree must act on that row
     * even when focus is somewhere else entirely, which is the single most common way a context menu is
     * used and the one a focus-derived context gets wrong.</p>
     */
    public Menu build(CommandRegistry registry, UIElement source) {
        Menu menu = new Menu();
        boolean pendingSeparator = false;
        boolean anyItem = false;

        for (Entry entry : entries) {
            if (entry instanceof SeparatorEntry) {
                // Deferred rather than added: a separator is only real once something follows it, which is
                // what makes leading and doubled ones disappear without the caller tracking groups.
                pendingSeparator = anyItem;
                continue;
            }
            if (pendingSeparator) {
                menu.addSeparator();
                pendingSeparator = false;
            }
            if (entry instanceof SubmenuEntry sub) {
                menu.addSubmenu(sub.label(), sub.menu().build(registry, source));
            } else if (entry instanceof CommandEntry command) {
                addCommand(menu, registry, source, command);
            }
            anyItem = true;
        }
        return menu;
    }

    private static void addCommand(Menu menu, CommandRegistry registry, UIElement source,
                                   CommandEntry entry) {
        Command command = registry.get(entry.commandId());
        CommandContext context = CommandContext.of(source);
        // A command that is not registered still gets a row, disabled. Silently dropping it would make a
        // menu that is missing an item look exactly like a menu that never listed one -- and the reason it
        // is absent is always the same bug, an install that never happened. GraphCommands twice.
        String label = entry.labelOverride() != null ? entry.labelOverride()
                : command != null ? command.getLabel() : entry.commandId();

        MenuItem item = menu.addItem(label);
        item.setEnabled(command != null && command.isEnabled(context));

        KeyChord chord = Keymap.acceleratorFor(source, entry.commandId());
        item.setAccelerator(chord == null ? null : chord.toString());

        if (command != null) {
            item.attachListener(() -> {
                // RE-CHECKED at activation, and run THROUGH THE REGISTRY. The menu may have been open
                // while something changed under it, and going through the registry is what a rebind or a
                // replaced command needs -- holding the Command found at build time would run the old one.
                if (command.isEnabled(context)) registry.run(entry.commandId(), context);
            });
        }
    }

    // ── Attaching ───────────────────────────────────────────────────────────────────────────────

    /**
     * Opens {@code menu} on a secondary press anywhere inside {@code on}.
     *
     * <p>Returns the element so a caller can chain. The listener is attached in the <b>bubble</b> phase,
     * so an inner element that wants its own context menu can attach one and stop propagation — a tree row
     * overriding the tree's default, which is how every file manager behaves.</p>
     *
     * @param builder called per press with the element actually under the pointer, so a menu can be built
     *                for <em>that row</em> rather than for the container
     */
    public static UIElement attach(UIElement on, CommandRegistry registry,
                                   java.util.function.Function<UIElement, ContextMenu> builder) {
        on.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            if (event.getButtonId() != com.crystalgraphics.platform.input.CgMouseCodes.RIGHT_BUTTON) return;
            UIWindow window = on.getAttachedWindow();
            if (window == null) return;
            UIElement target = event.getTarget() == null ? on : event.getTarget();
            ContextMenu spec = builder.apply(target);
            if (spec == null) return;

            Menu menu = spec.build(registry, target);
            // Attached to the ROOT, not to `on`: a Popover promotes itself to the top layer, and a menu
            // parented inside a scrolling tree would otherwise be clipped by it before promotion happens.
            window.ui.rootElement.addChild(menu);
            menu.showAt(event.getPosition().x(), event.getPosition().y(), target);
            // CONSUMED, so a right-click does not also fall through to whatever the left button does --
            // selecting a row, starting a marquee, panning a canvas.
            event.stopPropagation();
        }, false, true);
        return on;
    }
}
