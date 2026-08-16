package com.crystalgui.language.run;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;

/**
 * What the console's own controls do, as commands.
 *
 * <h3>The same argument {@link ScriptCommands} makes, arriving from the other side</h3>
 *
 * <p>Run and Stop were commands from the start because a keybinding, a menu row and a toolbar button all
 * have to mean one thing. Clear, Scroll to End and Soft-Wrap were not, because they began as three
 * buttons and nothing else pointed at them — and the moment the console wanted a right-click menu, that
 * was the whole obstacle. A menu row is built from a command; there is no other way to make one, by
 * design, and {@code MenuBuilder} being the single path is an invariant this codebase already paid for.</p>
 *
 * <h3>They close over the panel, and that is deliberate</h3>
 *
 * <p>Not resolved from focus like the editor's own commands. A console command is about <em>this</em>
 * console — the one whose menu you opened — and a workspace has exactly one, so resolving outward would
 * be ceremony that can also fail: right-clicking the transcript resolves a {@code TextEditor}, and every
 * editor in the application is one of those. {@code ScriptCommands} captures its host for the same
 * reason.</p>
 */
public final class ConsoleCommands {

    public static final String CLEAR = "console.clear";
    public static final String SCROLL_TO_END = "console.scrollToEnd";
    public static final String TOGGLE_SOFT_WRAP = "console.toggleSoftWrap";

    /**
     * Where the console's right-click rows are contributed.
     *
     * <p>Its own id rather than {@code MenuId.EDITOR_CONTEXT}, which the transcript would otherwise
     * qualify for by being a {@code TextEditor}: contributing Clear All there would put it in the
     * right-click menu of every source file in the application. The menu the console actually shows
     * splices the editor's own verbs in above these — see {@code RunPanels}.</p>
     */
    public static final MenuId CONTEXT = MenuId.of("run/console/context");

    private ConsoleCommands() {
    }

    /**
     * Registers all three against one panel.
     *
     * <p>No default bindings. Soft wrap already has {@code Alt+Z} through the editor's own keymap, which
     * reaches the transcript because the transcript is an editor; inventing a second chord for it would
     * give one setting two accelerators that can drift. Clear and Scroll to End have no accelerator in
     * either reference, and a binding nobody expects is a binding that collides with one somebody does.</p>
     */
    public static void register(CommandRegistry registry, RunPanel panel) {
        registry.register(Command.of(CLEAR, "Clear All")
                .menu(CONTEXT, "2_transcript", 10)
                // THE SAME QUESTION THE BUTTON ASKS, from the same method -- a menu row that greys on a
                // different rule from the button beside it is two answers to one question.
                .enabledWhen(context -> panel.hasOutput())
                .run(panel.onClearRequested::emit));

        registry.register(Command.of(SCROLL_TO_END, "Scroll to End")
                .menu(CONTEXT, "1_view", 10)
                .run(() -> panel.view().scrollToEnd()));

        registry.register(Command.of(TOGGLE_SOFT_WRAP, "Soft-Wrap")
                .menu(CONTEXT, "1_view", 20)
                // CHECKED IN THE MENU, which is what a toggle owes a row: the button shows its state with
                // a lit background and a row has no equivalent unless it says so.
                .toggledWhen(context -> panel.view().isSoftWrap())
                .run(() -> panel.view().setSoftWrap(!panel.view().isSoftWrap())));
    }

    /** Removes all three — for a host that is torn down, and for a test that must not leak them. */
    public static void unregister(CommandRegistry registry) {
        registry.unregister(CLEAR);
        registry.unregister(SCROLL_TO_END);
        registry.unregister(TOGGLE_SOFT_WRAP);
    }
}
