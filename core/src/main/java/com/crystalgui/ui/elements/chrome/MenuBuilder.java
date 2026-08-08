package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.command.MenuSection;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns what {@link CommandRegistry#sections} says into rows on a {@link Menu}.
 *
 * <h3>One builder, because two would disagree</h3>
 *
 * <p>The rules for rendering a command as a menu row are not obvious and there are six of them —
 * separators between sections but never leading, trailing or doubled; an unregistered command still gets a
 * row; enablement re-checked at activation; the command re-resolved through the registry when it runs;
 * accelerators read live from the keymap; an empty submenu dropped but a disabled one kept. Every one was
 * learned from a bug, and every one lived in {@code ContextMenu} where only a right-click could reach
 * it.</p>
 *
 * <p>A menu bar written against {@link Menu} directly would re-derive all six and get some subset of them
 * right, and the two would drift within a release — the same argument {@link MenuSection} makes about the
 * grouping one layer down. So this exists first and <b>{@code ContextMenu} is one of its callers</b>,
 * not its owner.</p>
 *
 * <h3>Unavailable rows are dimmed, never hidden</h3>
 *
 * <p>The registry deliberately carries {@code enabled} rather than filtering, so the choice is here — and
 * this repo has already paid for the alternative: the command palette copied VS Code's hide-disabled
 * behaviour and listed <b>1 of 9</b> commands, because every {@code enabledWhen} resolves outward from the
 * focused element and "nothing focused" answers no to everything. A menu whose rows appear and vanish is
 * also a menu whose rows are never in the same place twice, which is IntelliJ's stated reason and the
 * stronger one for a menu bar, where File ▸ Save must be the fourth row whether or not it applies.</p>
 */
public final class MenuBuilder {

    private MenuBuilder() {
    }

    /**
     * A whole menu built from {@code id} — the menu bar's entire implementation of "open File".
     *
     * @param source what every command resolves against: its enablement, its handler, and the keymap
     *               lookup for its accelerator
     */
    public static Menu build(MenuId id, CommandRegistry registry, UIElement source) {
        Menu menu = new Menu();
        appendSections(menu, id, registry, source);
        return menu;
    }

    /**
     * Appends everything contributed to {@code id}, with a separator between sections.
     *
     * <p>Appends rather than replaces, so a caller may put fixed rows above or below contributed ones —
     * which is what {@code ContextMenu}'s builder does.</p>
     *
     * @return whether anything was added
     */
    public static boolean appendSections(Menu menu, MenuId id, CommandRegistry registry, UIElement source) {
        CommandContext context = CommandContext.of(source);

        // RESOLVED FIRST, EMITTED SECOND, and the two-pass shape is what makes the separator rule true by
        // construction rather than by bookkeeping. A separator has to go BEFORE the first row of a section
        // -- but whether a section produces any row is only knowable by trying, since an empty submenu is
        // dropped. Emitting as we go therefore means either adding a rule that may turn out to be trailing,
        // or inserting one behind a row already added. Deciding first removes the question.
        List<List<Runnable>> sections = new ArrayList<>();
        for (MenuSection section : registry.sections(id, context)) {
            List<Runnable> rows = new ArrayList<>();
            for (MenuEntry entry : section.entries()) {
                Runnable row = emitterFor(menu, entry, registry, source);
                if (row != null) rows.add(row);
            }
            if (!rows.isEmpty()) sections.add(rows);
        }

        boolean any = false;
        for (List<Runnable> rows : sections) {
            if (any) menu.addSeparator();
            for (Runnable row : rows) row.run();
            any = true;
        }
        return any;
    }

    /** How to add {@code entry}, or null if it would add nothing. */
    @Nullable
    private static Runnable emitterFor(Menu menu, MenuEntry entry, CommandRegistry registry,
                                       UIElement source) {
        if (entry instanceof MenuEntry.Submenu nested) {
            Menu built = build(nested.menu(), registry, source);
            // A submenu with NOTHING contributed to it is dropped -- not one whose rows are merely
            // disabled, which still opens and shows them dimmed. An empty submenu is a registration that
            // never happened; a disabled one is an answer.
            if (built.getItemCount() == 0) return null;
            return () -> menu.addSubmenu(nested.title(), built);
        }
        MenuEntry.Item item = (MenuEntry.Item) entry;
        return () -> row(menu, registry, source, item.command().getId(), null, item.enabled(),
                item.checkable(), item.checked(), item.command());
    }

    // ── Presenting ──────────────────────────────────────────────────────────────────────────────

    /**
     * Adds {@code root} and every submenu beneath it to the window, ready to be shown.
     *
     * <h3>Why the whole chain, and why not the DOM parent</h3>
     *
     * <p>{@link Menu#addSubmenu} deliberately does <b>not</b> adopt its child — the relationship that
     * matters for dismissal is the invoker link, not parentage — so a submenu built here and never
     * attached throws {@code "A Popover must be attached to a window before it can be shown"} the moment
     * the pointer rests on its row. From inside the submenu ticker, which is nowhere near the press that
     * caused it.</p>
     *
     * <p>The host is {@code window.overlayHost(site)} — the nearest ancestor that <em>accepts</em>
     * children, resolved from the attachment site rather than the clicked element. The root may itself be
     * a composite that refuses them, and a clicked element can be anything the pointer happened to be
     * over, while the site is always in the tree.</p>
     *
     * @return the chain, to be handed to {@link #discard} when it is finished with
     */
    public static List<Menu> present(Menu root, UIElement site, UIWindow window) {
        List<Menu> chain = new ArrayList<>();
        collect(root, chain);
        UIElement host = window.overlayHost(site);
        for (Menu menu : chain) host.addChild(menu);
        return chain;
    }

    /**
     * Closes and detaches a chain from {@link #present}.
     *
     * <p><b>Close before remove, never the other way round.</b> Closing demotes a promoted popover, which
     * restores its Taffy node to its DOM parent — removing it while still promoted leaves the engine
     * reconciling a node parented to the root against a DOM parent that no longer lists it.</p>
     *
     * <p>Clears {@code live} <em>first</em>, so an {@code onClosed} hook that calls back into this does
     * not recurse.</p>
     */
    public static void discard(List<Menu> live) {
        if (live.isEmpty()) return;
        List<Menu> going = new ArrayList<>(live);
        live.clear();
        for (Menu menu : going) {
            if (menu.isOpen()) menu.hide();
            if (menu.getParent() != null) menu.getParent().removeChild(menu);
        }
    }

    /** Whether {@code element} is any of {@code menus} or sits beneath one. */
    public static boolean isInsideAny(@Nullable UIElement element, List<Menu> menus) {
        for (UIElement walk = element; walk != null; walk = walk.getParent()) {
            if (menus.contains(walk)) return true;
        }
        return false;
    }

    /** Every menu in a chain, root first — a submenu needs attaching exactly as its parent does. */
    private static void collect(Menu menu, List<Menu> out) {
        out.add(menu);
        for (MenuItem item : menu.getItems()) {
            Menu sub = item.getSubmenu();
            if (sub != null) collect(sub, out);
        }
    }

    /**
     * One command row, by id — for a caller naming its items rather than querying a {@link MenuId}.
     *
     * <p>Everything is read from the registry: the label, whether it is enabled, whether it is a toggle
     * and which way it points, and the accelerator. That is what keeps the menu, the palette and the
     * keyboard three views of one list rather than three lists.</p>
     */
    public static MenuItem row(Menu menu, CommandRegistry registry, UIElement source,
                               String commandId, @Nullable String labelOverride) {
        Command command = registry.get(commandId);
        CommandContext context = CommandContext.of(source);
        boolean enabled = command != null && command.isEnabled(context);
        boolean checkable = command != null && command.isCheckable();
        boolean checked = command != null && command.isToggled(context);
        return row(menu, registry, source, commandId, labelOverride, enabled, checkable, checked, command);
    }

    private static MenuItem row(Menu menu, CommandRegistry registry, UIElement source,
                                String commandId, @Nullable String labelOverride, boolean enabled,
                                boolean checkable, boolean checked, @Nullable Command resolved) {
        // A command that is not registered still gets a row, disabled. Silently dropping it would make a
        // menu that is missing an item look exactly like a menu that never listed one -- and the reason it
        // is absent is always the same bug, an install that never happened. GraphCommands twice.
        String label = labelOverride != null ? labelOverride
                : resolved != null ? resolved.getLabel() : commandId;

        MenuItem item = checkable ? menu.addCheckableItem(label) : menu.addItem(label);
        item.setEnabled(enabled);
        if (checkable) item.setSelected(checked);

        KeyChord chord = Keymap.acceleratorFor(source, commandId);
        item.setAccelerator(chord == null ? null : chord.toString());

        if (resolved == null) return item;
        CommandContext context = CommandContext.of(source);
        item.attachListener(() -> {
            // RE-CHECKED at activation, and run THROUGH THE REGISTRY where the id is known. The menu may
            // have been open while something changed under it, and going through the registry is what a
            // rebind or a replaced command needs -- holding the Command found at build time would run the
            // old one.
            //
            // The fallback runs the held command directly, and that is not a shortcut: a MenuContributor
            // row (a recent file, an open editor) is a Command that exists for as long as the menu does
            // and is deliberately registered nowhere, so there is no id to look up. Without this branch
            // every computed row would be inert.
            Command live = registry.get(commandId);
            Command target = live != null ? live : resolved;
            if (target.isEnabled(context)) target.execute(context);
        });
        return item;
    }
}
