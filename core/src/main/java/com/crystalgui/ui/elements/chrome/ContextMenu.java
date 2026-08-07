package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import com.crystalgraphics.platform.input.CgMouseCodes;

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

    /** One row: a command id, a separator, a submenu, or everything contributed to a {@link MenuId}. */
    private sealed interface Entry
            permits CommandEntry, SeparatorEntry, SubmenuEntry, ContributedEntry {
    }

    /** Expanded at build time from whatever is registered against {@code menu}. */
    private record ContributedEntry(MenuId menu) implements Entry {
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

    /**
     * Everything contributed to {@code menu} — the whole menu, written nowhere.
     *
     * <h3>Why this exists beside the builder</h3>
     *
     * <p>A hand-written builder says exactly which items a menu has, which means <b>only its author can
     * add one</b>. That is the coupling {@link MenuId} was introduced to remove, and until this method
     * existed the id had no users at all: {@code Command.menu(...)} recorded placements that nothing ever
     * read, so the explorer's menu was still a literal list in {@code ExplorerCommands}.</p>
     *
     * <p>Items come out in {@code group} then {@code order}, with a separator between groups. Groups sort
     * lexicographically, so VS Code's {@code "1_new"}, {@code "2_clipboard"} convention is what orders
     * them; a contributor picks its group and needs to know nothing about the rest.</p>
     *
     * <p><b>Disabled commands are included, dimmed</b> — this class's rule, stated at the top, and not
     * {@link CommandRegistry#menu}'s. That method filters, which is right for a caller assembling a list
     * from scratch and wrong here for the reason the header gives: a menu whose items move depending on
     * what happens to apply is a menu whose items are never in the same place twice.</p>
     *
     * <p>Composable with the builder: {@code ContextMenu.builder().item(...).contributions(id)} puts a
     * fixed item above everything contributed.</p>
     */
    public static ContextMenu of(MenuId menu) {
        return new ContextMenu().contributions(menu);
    }

    /** Splices everything contributed to {@code menu} in at this point. */
    public ContextMenu contributions(MenuId menu) {
        entries.add(new ContributedEntry(menu));
        return this;
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

        for (Entry entry : resolve(registry, source)) {
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

    /** Declared entries, with every {@link ContributedEntry} replaced by what is registered for it. */
    private List<Entry> resolve(CommandRegistry registry, UIElement source) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry instanceof ContributedEntry contributed) {
                out.addAll(contributionsOf(contributed.menu(), registry, source));
            } else {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * What is registered for {@code menu}, in group-then-order, separated between groups.
     *
     * <p>Commands and submenus are interleaved by the same {@code (group, order)} pair, so a submenu is
     * an ordinary participant rather than something pinned to one end.</p>
     *
     * <p><b>A submenu with nothing contributed to it at all is dropped</b> — not one whose items are
     * merely disabled, which still opens and shows them dimmed. An empty submenu is a registration that
     * never happened; a disabled one is an answer.</p>
     */
    private static List<Entry> contributionsOf(MenuId menu, CommandRegistry registry, UIElement source) {
        record Row(String group, int order, Entry entry) {
        }
        List<Row> rows = new ArrayList<>();

        for (Command command : registry.all()) {
            for (MenuId.Placement placement : command.menus()) {
                if (placement.menu() != menu) continue;
                rows.add(new Row(placement.group(), placement.order(),
                        new CommandEntry(command.getId(), null)));
                break;
            }
        }

        for (MenuId.Submenu nested : menu.submenus()) {
            ContextMenu built = ContextMenu.of(nested.menu());
            if (built.resolve(registry, source).isEmpty()) continue;
            rows.add(new Row(nested.group(), nested.order(),
                    new SubmenuEntry(nested.title(), built)));
        }

        rows.sort(Comparator.comparing(Row::group).thenComparingInt(Row::order));

        List<Entry> out = new ArrayList<>();
        String previousGroup = null;
        for (Row row : rows) {
            // Emitted freely between groups: build() drops leading, trailing and doubled separators, so
            // there is nothing to count here.
            if (previousGroup != null && !previousGroup.equals(row.group())) out.add(new SeparatorEntry());
            out.add(row.entry());
            previousGroup = row.group();
        }
        return out;
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
        // ONE LIVE MENU PER ATTACHMENT SITE, and this is a correctness requirement rather than tidiness.
        //
        // Building a fresh Menu per press and leaving the last one in the tree crashed Taffy outright:
        //   Index (is 2) should be < child_count (1)
        // Promotion REPARENTS a popover's Taffy node to the root, so a promoted sibling is still a DOM
        // child of the host but no longer one of its Taffy children. registerElement inserts the new menu
        // at its DOM sibling index, which by then is past the end of a child list that has been quietly
        // emptied underneath it. Every extra menu widened the gap.
        //
        // Discarding the previous one is also simply what a second right-click means.
        // EVERY menu in the chain, root first. Menu.addSubmenu deliberately does not parent its child --
        // PopoverTest adds both to the tree by hand -- so a submenu built here and never attached threw
        // "A Popover must be attached to a window before it can be shown" the moment the pointer rested
        // on its row, from inside the submenu ticker rather than from anything the user could connect to
        // a right-click.
        List<Menu> live = new ArrayList<>();

        on.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            UIWindow window = on.getAttachedWindow();
            if (window == null) return;
            UIElement target = event.getTarget() == null ? on : event.getTarget();
            // A press landing ON THE OPEN MENU is not a request for a menu about the menu. Without this,
            // the second right-click resolved its target inside the popup that discard() was about to
            // detach -- so both the command context and the host were computed from an element no longer
            // in the tree, and Popover.show refused it as unattached.
            if (!live.isEmpty() && isInsideAny(target, live)) target = on;

            discard(live);

            ContextMenu spec = builder.apply(target);
            if (spec == null) return;

            Menu menu = spec.build(registry, target);
            // The nearest ancestor that ACCEPTS children, not the root -- the root may itself be a
            // composite that refuses them, which is exactly how right-clicking the Project panel threw
            // out of the mouse-down dispatch. See Popover.hostFor.
            // Resolved from the ATTACHMENT SITE, not the clicked element: `on` is always in the tree,
            // while a target can be anything the pointer happened to be over.
            UIElement host = window.overlayHost(on);
            collect(menu, live);
            for (Menu open : live) host.addChild(open);
            // Dropped from the tree when the ROOT closes by any route -- light dismiss, Escape, or
            // choosing an item. Left in place they are invisible display:none elements accumulating one
            // set per press.
            menu.onClosed.connect(() -> {
                discard(live);
                // FOCUS GOES BACK TO WHAT WAS RIGHT-CLICKED, and this is not cosmetic.
                //
                // A menu takes focus for its rows, and a right-click is often the ONLY thing that happened
                // before the command runs — so without this, focus is left on a row of a menu that has
                // already been detached. Everything that resolves outward from the focused element then
                // finds nothing: Ctrl+Z did not undo a paste, and the panel's own Delete and F2 were dead
                // too, because a keymap and an UndoScope both walk up from focus and there was no longer a
                // path from there back to the panel.
                //
                // `on` rather than the clicked element: it is the attachment site, so it is always still
                // in the tree, while the row under the pointer is quite often the thing the command just
                // deleted or moved. Pointer-sourced, so dismissing a menu does not leave a focus ring.
                if (on.getAttachedWindow() != null && on.focusable()) {
                    on.getAttachedWindow().getInputHandler().requestPointerFocus(on);
                }
            });

            // CONVERTED, and this is the whole reason the framework does it rather than each caller.
            //
            // showAt takes ROOT-SPACE coordinates -- its parameters are named rootX/rootY -- while
            // MouseEvent.getPosition() reports PHYSICAL pointer pixels. At uiScale 2 that puts the menu at
            // twice its distance from the top-left corner, which looks like a placement bug in the popover
            // and is really a units mismatch two layers up. screenToLocal on the root is the one
            // conversion that stays correct under uiScale and any ancestor transform.
            //
            // NULL INVOKER, deliberately. The invoker carve-out exists so pressing a toggle BUTTON does
            // not have light dismiss close the menu underneath the click that is about to reopen it. A
            // right-click toggles nothing -- and passing the clicked element made light dismiss treat
            // every press anywhere inside it as a press on the invoker, so clicking the tree's empty space
            // could never close the tree's own menu.
            var local = window.ui.rootElement.screenToLocal(
                    event.getPosition().x(), event.getPosition().y());
            menu.showAt(local.x(), local.y(), null);
            // CONSUMED, so a right-click does not also fall through to whatever the left button does --
            // selecting a row, starting a marquee, panning a canvas.
            event.stopPropagation();
        }, false, true);
        return on;
    }

    /**
     * Closes and detaches the live menu, if there is one.
     *
     * <p><b>Close before remove, never the other way round.</b> Closing demotes it, which restores its
     * Taffy node to its DOM parent — removing it while still promoted leaves the engine reconciling a node
     * that has been parented to the root against a DOM parent that no longer lists it.</p>
     */
    /** Every menu in a chain, root first — a submenu needs attaching exactly as its parent does. */
    private static void collect(Menu menu, List<Menu> out) {
        out.add(menu);
        for (MenuItem item : menu.getItems()) {
            Menu sub = item.getSubmenu();
            if (sub != null) collect(sub, out);
        }
    }

    /** Whether {@code element} is any of {@code menus} or sits beneath one. */
    private static boolean isInsideAny(@Nullable UIElement element, List<Menu> menus) {
        for (UIElement walk = element; walk != null; walk = walk.getParent()) {
            if (menus.contains(walk)) return true;
        }
        return false;
    }

    private static void discard(List<Menu> live) {
        if (live.isEmpty()) return;
        List<Menu> going = new ArrayList<>(live);
        live.clear();                         // FIRST, so the onClosed hook below does not recurse
        for (Menu menu : going) {
            if (menu.isOpen()) menu.hide();
            if (menu.getParent() != null) menu.getParent().removeChild(menu);
        }
    }
}
