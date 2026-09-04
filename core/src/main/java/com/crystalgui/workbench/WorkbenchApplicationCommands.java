package com.crystalgui.workbench;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.chrome.palette.CommandPalette;

/**
 * What every workbench application offers — saving the active file, and saving or restoring the pane
 * arrangement.
 *
 * <h3>Commands, not key handling</h3>
 *
 * <p>These were a {@code switch} on raw scan codes in a harness scene. As commands they are rebindable,
 * they appear in the palette with their accelerators, and they can be greyed out when they do not apply —
 * none of which a scan-code switch can offer.</p>
 *
 * <h3>{@code Mod+S} is the FILE; the layout is {@code Mod+Shift+S}</h3>
 *
 * <p>The layout had {@code Mod+S} first, back when there were no files behind the editor. Once there
 * were, {@code Mod+S} had exactly one obvious meaning and it was not "serialise the pane arrangement" — a
 * key that writes the wrong thing is worse than one that does nothing.</p>
 *
 * <h3>They belong to the ENGINE, not to one product</h3>
 *
 * <p>They were {@code CrystalEditorCommands} and keyed on a {@code CrystalEditor}, which made "Save
 * File" a thing the editor happened to have rather than a thing a workbench does. A second workbench
 * application would have had to call the editor's registration to get its own Save.</p>
 *
 * <h3>The {@code workbench.} prefix, deliberately</h3>
 *
 * <p>Not {@code editor.}, which {@code EditorCommands} already owns for the text editor's own actions.
 * Two command sets sharing a namespace collide on the first name they both want, and ids are what every
 * binding, sheet and user remapping refers to. The ids are unchanged, so no keymap moves.</p>
 */
public final class WorkbenchApplicationCommands {

    public static final String SAVE_FILE = "workbench.saveFile";
    public static final String SAVE_LAYOUT = "workbench.saveLayout";
    public static final String RESTORE_LAYOUT = "workbench.restoreLayout";

    private WorkbenchApplicationCommands() {
    }

    /**
     * Registers them. Global — nothing is captured.
     *
     * <p>They used to hold an application <em>and</em> a window, so they could not be registered once:
     * the second application would have driven the first. Both come from the data context now —
     * {@link WorkbenchApplication#APPLICATION} and {@link CommandPalette#SURFACE} — which answer with
     * the ones the <b>focused</b> element is in. Two applications on one desktop therefore save the
     * right layout each, which the captured version could not have done at all.</p>
     */
    public static void register() {
        CommandRegistry.global().contribute(WorkbenchApplicationCommands.class,
                WorkbenchApplicationCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(SAVE_FILE, "Save File")
                .binding("Mod+S")
                .menu(MenuId.MAIN_FILE, "3_save", 10)
                .run(context -> applicationFor(context).workbench().saveActiveFile())
                // Greyed when the active tab is not a file, so the palette says so rather than offering a
                // command that would report "no file tab active" after the fact.
                .enabledWhen(context -> {
                    WorkbenchApplication application = applicationFor(context);
                    return application != null && application.workbench().activeFilePath() != null;
                }));

        registry.register(Command.of(SAVE_LAYOUT, "Save Window Layout")
                .binding("Mod+Shift+S")
                .menu(MenuId.MAIN_WINDOW, "3_layout", 10)
                .run(context -> {
                    UIDocument surface = context.data().get(CommandPalette.SURFACE);
                    Box box = surface == null ? null : surface.box();
                    if (box == null) return;
                    applicationFor(context).saveLayout(PlainOps.INSTANCE,
                            (int) box.width(), (int) box.height());
                })
                .enabledWhen(context -> applicationFor(context) != null
                        && context.data().get(CommandPalette.SURFACE) != null));

        registry.register(Command.of(RESTORE_LAYOUT, "Restore Window Layout")
                .binding("Mod+O")
                .menu(MenuId.MAIN_WINDOW, "3_layout", 20)
                .run(context -> {
                    // THE REPORT IS THE COMMAND'S, not the application's. `restoreLayout` answers a
                    // boolean because "there is nothing saved yet" and "the blob was refused" are both
                    // ordinary outcomes; only a user who just pressed the key needs telling.
                    if (!applicationFor(context).restoreLayout(PlainOps.INSTANCE)) {
                        Notifications.show(Notification.info("No saved layout")
                                .withDetail("nothing to restore yet"));
                    }
                })
                .enabledWhen(context -> applicationFor(context) != null));
    }

    @Nullable
    private static WorkbenchApplication applicationFor(CommandContext context) {
        return context.data().get(WorkbenchApplication.APPLICATION);
    }
}
