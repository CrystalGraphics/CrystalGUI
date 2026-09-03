package com.crystalgui.app.editor;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;

import com.crystalgui.workbench.chrome.palette.CommandPalette;
import javax.annotation.Nullable;

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

    /**
     * Registers the editor's commands. Global — nothing is captured.
     *
     * <p>These held a {@code CrystalEditor} <em>and</em> a {@code UIDocument}, so they could not be
     * registered once: the second editor would have driven the first. Both now come from the data
     * context — {@link CrystalEditor#CRYSTAL_EDITOR} and {@link CommandPalette#SURFACE} — which answers with
     * the editor and window the <b>focused</b> element is in. Two windows on one screen therefore save
     * the right layout each, which the captured version could not have done at all.</p>
     *
     * <p>The chords are declared with the commands: all three are modifier chords, so application scope
     * is what they want, and nothing has to bind them on a root.</p>
     */
    public static void register() {
        CommandRegistry.global().contribute(CrystalEditorCommands.class, CrystalEditorCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(SAVE_FILE, "Save File")
                .binding("Mod+S")
                .menu(MenuId.MAIN_FILE, "3_save", 10)
                .run(context -> editorFor(context).workbench().saveActiveFile())
                // Greyed when the active tab is not a file, so the palette says so rather than offering a
                // command that would report "no file tab active" after the fact.
                .enabledWhen(context -> {
                    CrystalEditor editor = editorFor(context);
                    return editor != null && editor.workbench().activeFilePath() != null;
                }));

        registry.register(Command.of(SAVE_LAYOUT, "Save Window Layout")
                .binding("Mod+Shift+S")
                .menu(MenuId.MAIN_WINDOW, "3_layout", 10)
                .run(context -> {
                    UIDocument window = context.data().get(CommandPalette.SURFACE);
                    Box surface = window == null ? null : window.box();
                    if (surface == null) return;
                    editorFor(context).saveLayout(PlainOps.INSTANCE,
                            (int) surface.width(), (int) surface.height());
                })
                .enabledWhen(context -> editorFor(context) != null
                        && context.data().get(CommandPalette.SURFACE) != null));

        registry.register(Command.of(RESTORE_LAYOUT, "Restore Window Layout")
                .binding("Mod+O")
                .menu(MenuId.MAIN_WINDOW, "3_layout", 20)
                .run(context -> editorFor(context).restoreLayout(PlainOps.INSTANCE))
                .enabledWhen(context -> editorFor(context) != null));
    }

    @Nullable
    private static CrystalEditor editorFor(CommandContext context) {
        return context.data().get(CrystalEditor.CRYSTAL_EDITOR);
    }
}
