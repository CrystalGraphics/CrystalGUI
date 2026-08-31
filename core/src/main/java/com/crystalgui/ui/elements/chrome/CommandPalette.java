package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Fills a {@link QuickPick} from a {@link CommandRegistry} — the command palette.
 *
 * <h3>The focus trap, which is the whole reason this class is not three lines</h3>
 *
 * <p>A {@link Command}'s {@code enabledWhen} predicate is handed a {@link CommandContext} whose
 * {@code source()} is <b>the element the command was invoked from</b>, and every predicate in the codebase
 * resolves its target by walking up from there — {@code DockCommands} looks for an enclosing
 * {@code DockArea}, editor commands look for an editor.</p>
 *
 * <p>Opening a palette <b>moves focus into the palette's own search field.</b> So anything asked after it
 * opens is asked about the palette: no {@code DockArea} above it, no editor above it, every context-scoped
 * command reports disabled, and the palette shows a handful of global commands and nothing else. Worse, a
 * command that <em>did</em> run would run against the wrong element.</p>
 *
 * <p>The fix is to capture the focused element <b>before</b> showing and use it for both halves — the
 * enablement pass and the eventual {@code execute}. It is the same shape as the rule {@code DockCommands}
 * already records about resolving against the active group rather than focus, and it is invisible when
 * wrong: the palette looks like it works, it is simply missing most of what it should list.</p>
 *
 * <h3>Disabled commands are listed and dimmed — <b>corrected</b>, they used to be hidden</h3>
 *
 * <p>Hiding them is VS Code's choice and it was the wrong one to copy. VS Code's {@code when} clauses read
 * declarative context keys; ours walk up the tree from the focused element. Those are not the same
 * question, and when nothing is focused the tree walk answers "no" to everything rather than "unknown".</p>
 *
 * <p><b>Measured in the dock harness:</b> with nothing focused the palette listed <b>one</b> of nine
 * commands; after a click inside the dock it listed seven. Since the one survivor was the palette's own
 * opener, choosing it reopened a palette that looked identical — so the widget read as completely dead
 * while behaving exactly as designed.</p>
 *
 * <p>Listing everything and dimming what is unavailable is IntelliJ's Find Action behaviour. A disabled row
 * still matches the query and still shows its keybinding — you can find out a command exists and what it is
 * bound to — but {@link QuickPick} will not focus or accept it, so it cannot be mistaken for one that ran.
 * Enablement is still evaluated <b>once, at open</b>, against the captured element.</p>
 *
 * <h3>Category comes from the id prefix</h3>
 *
 * <p>{@code dock.splitRight} lists as <i>Dock: Split Right</i>. Every command in the codebase already
 * follows {@code namespace.action}, so the convention holds without a new field, and matching stays correct
 * because the category is a separate {@code SearchMatcher} field rather than a prefix glued to the label.
 * Adding an explicit category to {@code Command} later is purely additive — this becomes its fallback.</p>
 */
public final class CommandPalette {

    public static final String PLACEHOLDER = "Type a command";

    /** The header bar’s text — and the surface the popup is dragged by. */
    public static final String TITLE = "All Commands";

    private CommandPalette() {
    }

    /**
     * Builds and shows a palette over the window's commands.
     *
     * <p>A fresh instance per open, removed from the tree when it closes. There is deliberately no cached
     * per-window palette: the candidate list is captured at open against a specific focused element, so a
     * reused instance would either need that state cleared on every show or would quietly serve a stale
     * one. Allocating a search field and a list once per open is not a cost worth that risk.</p>
     */
    public static QuickPick open(UIWindow window) {
        // BEFORE showing -- see the class javadoc. This is the load-bearing line.
        UIElement invokedFrom = window.getInputHandler().getFocusedElement();
        UIElement source = invokedFrom != null ? invokedFrom : window.ui.rootElement;

        CommandRegistry registry = window.getCommands();
        List<QuickPickItem> items = itemsFor(registry, source);

        QuickPick pick = new QuickPick();
        pick.setPlaceholder(PLACEHOLDER);
        pick.setTitle(TITLE);
        pick.setSource(QuickPickSource.of(items));
        pick.onAccepted.connect(id -> registry.run(id, CommandContext.of(source)));
        pick.onClosed.connect(() -> {
            pick.resultList().dispose();
            pick.removeSelf();
        });
        return pick.open(window);
    }

    /**
     * Every enabled command, as rows, with accelerators resolved from {@code source}'s keymap chain.
     *
     * <p>Public and static so a test can assert the candidate set without a window on screen — the
     * enablement filter is the part worth pinning, and it does not need pixels.</p>
     */
    public static List<QuickPickItem> itemsFor(CommandRegistry registry, @Nullable UIElement source) {
        Map<String, KeyChord> accelerators = Keymap.acceleratorsFrom(source);
        CommandContext here = CommandContext.of(source);
        // "ANYWHERE" IS THE ROOT, NOT NULL, and the difference is the whole measurement.
        //
        // A null source empties the DataContext outright: `fromWindow` returns immediately without one,
        // so the window's own providers go too -- and those are how Go to File and Reload from Disk find
        // their subject with nothing focused. Measured against null, every window-scoped command would
        // report itself unavailable and therefore look contextual, which is the opposite of true.
        //
        // The root is the honest baseline: same window, same window-level providers, no focused widget.
        // It is also exactly what `open` already falls back to when nothing is focused, so "contextual"
        // and "what the palette would offer you before you clicked anything" are the same question.
        CommandContext anywhere = CommandContext.of(rootOf(source));

        List<QuickPickItem> items = new ArrayList<>();
        for (Command command : registry.all()) {
            KeyChord chord = accelerators.get(command.getId());
            boolean enabled = command.isEnabled(here);
            QuickPickItem item = new QuickPickItem(command.getId(), command.getLabel(),
                    categoryOf(command.getId()), chord == null ? null : chord.toString(), enabled);
            // ENABLED HERE AND NOT THERE -- the narrowest true statement, and it is only ever read among
            // rows that are enabled, since availability outranks it. Short-circuited, so a command that
            // is unavailable anyway costs one predicate rather than two.
            items.add(item.withContextual(enabled && !command.isEnabled(anywhere)));
        }
        return items;
    }

    /** The top of {@code source}'s tree — the same element {@link #open} uses when nothing is focused. */
    @Nullable
    private static UIElement rootOf(@Nullable UIElement source) {
        UIElement root = source;
        while (root != null && root.getParent() != null) root = root.getParent();
        return root;
    }

    /** {@code dock.splitRight} → {@code "Dock"}. Null when the id carries no namespace, which lists the
     * command with no category rather than inventing one. */
    @Nullable
    static String categoryOf(String commandId) {
        int dot = commandId.indexOf('.');
        if (dot <= 0) return null;
        String namespace = commandId.substring(0, dot);
        return Character.toUpperCase(namespace.charAt(0)) + namespace.substring(1).toLowerCase(Locale.ROOT);
    }
}
