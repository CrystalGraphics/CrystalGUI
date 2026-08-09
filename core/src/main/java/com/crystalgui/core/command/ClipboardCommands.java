package com.crystalgui.core.command;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.ui.ClipboardActions;
import com.crystalgui.ui.UiDataKeys;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * {@code Edit ▸ Cut/Copy/Paste} — one row each, acting on whatever you are in.
 *
 * <h3>Why these exist beside {@code editor.cut} and {@code explorer.cut}</h3>
 *
 * <p>They are not replacements. Each widget keeps its own command, its own binding on its own element
 * keymap, and its own place in the palette — that is what makes {@code Mod+X} mean the right thing
 * without anything resolving anything. What the specific commands cannot do is fill <b>one</b> menu row:
 * a menu bar has a single Cut, and naming {@code editor.cut} there is what left it greyed out over the
 * file tree while {@code explorer.cut} sat unreachable two lines away in the same registry.</p>
 *
 * <p>So the menu names these, and these ask {@link UiDataKeys#CLIPBOARD}. IntelliJ's {@code $Cut}
 * exactly. @see ClipboardActions</p>
 */
public final class ClipboardCommands {

    private ClipboardCommands() {
    }

    public static final String CUT = "edit.cut";
    public static final String COPY = "edit.copy";
    public static final String PASTE = "edit.paste";

    /** Registers into {@link CommandRegistry#global()}. Idempotent. */
    public static void register() {
        CommandRegistry.global().contribute(ClipboardCommands.class, ClipboardCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(CUT, "Cut")
                .menu(MenuId.MAIN_EDIT, "2_clipboard", 10)
                .enabledWhereData(where(ClipboardActions::canCut))
                .runWithData(data -> {
                    ClipboardActions actions = data.get(UiDataKeys.CLIPBOARD);
                    // RE-ASKED, not assumed from enablement. The menu may have been open while the
                    // selection changed under it -- the same reason MenuBuilder re-checks at activation.
                    if (actions != null && actions.canCut()) actions.cut();
                }));

        registry.register(Command.of(COPY, "Copy")
                .menu(MenuId.MAIN_EDIT, "2_clipboard", 20)
                .enabledWhereData(where(ClipboardActions::canCopy))
                .runWithData(data -> {
                    ClipboardActions actions = data.get(UiDataKeys.CLIPBOARD);
                    if (actions != null && actions.canCopy()) actions.copy();
                }));

        registry.register(Command.of(PASTE, "Paste")
                .menu(MenuId.MAIN_EDIT, "2_clipboard", 30)
                .enabledWhereData(where(ClipboardActions::canPaste))
                .runWithData(data -> {
                    ClipboardActions actions = data.get(UiDataKeys.CLIPBOARD);
                    if (actions != null && actions.canPaste()) actions.paste();
                }));
    }

    /** Greyed when nothing here provides a clipboard at all, which is an honest answer rather than a
     * missing one — there genuinely is nothing to cut when focus is on chrome. */
    private static Predicate<DataContext> where(Predicate<ClipboardActions> test) {
        return data -> {
            @Nullable ClipboardActions actions = data.get(UiDataKeys.CLIPBOARD);
            return actions != null && test.test(actions);
        };
    }
}
