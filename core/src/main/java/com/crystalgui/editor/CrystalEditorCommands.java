package com.crystalgui.editor;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.keymap.Keymap;

/**
 * The editor's own commands — saving a file, and saving or restoring the pane arrangement.
 *
 * <h3>Commands, not key handling</h3>
 *
 * <p>These were a {@code switch} on raw scan codes in a harness scene. As commands they are rebindable,
 * they appear in the palette with their accelerators, and they can be greyed out when they do not apply —
 * none of which a scan-code switch can offer, and all of which the palette already knows how to render.</p>
 *
 * <h3>{@code Mod+S} is the FILE; the layout is {@code Mod+Shift+S}</h3>
 *
 * <p>The layout had {@code Mod+S} first, back when there were no files behind the editor. Once there
 * were, {@code Mod+S} had exactly one obvious meaning and it was not "serialise the pane arrangement" — a
 * key that writes the wrong thing is worse than one that does nothing, and between a document and a demo
 * the document wins.</p>
 *
 * <h3>The {@code workbench.} prefix, deliberately</h3>
 *
 * <p>Not {@code editor.}, which {@code EditorCommands} already owns for the text editor's own actions.
 * Two command sets sharing a namespace would collide on the first name they both wanted, and ids are what
 * every binding, sheet and user remapping refers to.</p>
 */
public final class CrystalEditorCommands {

    public static final String SAVE_FILE = "workbench.saveFile";
    public static final String SAVE_LAYOUT = "workbench.saveLayout";
    public static final String RESTORE_LAYOUT = "workbench.restoreLayout";

    private CrystalEditorCommands() {
    }

    public static void register(CommandRegistry registry, UIWindow window, CrystalEditor editor) {
        if (registry.contains(SAVE_FILE)) return;

        registry.register(Command.of(SAVE_FILE, "Save File")
                .run(context -> editor.workbench().saveActiveFile())
                // Greyed when the active tab is not a file, so the palette says so rather than offering a
                // command that would report "no file tab active" after the fact.
                .enabledWhen(context -> editor.workbench().activeFilePath() != null));

        registry.register(Command.of(SAVE_LAYOUT, "Save Window Layout")
                .run(context -> editor.saveLayout(PlainOps.INSTANCE,
                        window.getScreenWidth(), window.getScreenHeight())));

        registry.register(Command.of(RESTORE_LAYOUT, "Restore Window Layout")
                .run(context -> editor.restoreLayout(PlainOps.INSTANCE)));
    }

    public static void bindDefaults(Keymap keymap) {
        keymap.bind("Mod+S", SAVE_FILE);
        keymap.bind("Mod+Shift+S", SAVE_LAYOUT);
        keymap.bind("Mod+O", RESTORE_LAYOUT);
    }

    public static void install(UIWindow window, CrystalEditor editor) {
        install(window.getCommands(), window, editor, window.ui.rootElement);
    }

    public static void install(CommandRegistry registry, UIWindow window,
                               CrystalEditor editor, UIElement root) {
        register(registry, window, editor);
        bindDefaults(root.keymap());
    }
}
