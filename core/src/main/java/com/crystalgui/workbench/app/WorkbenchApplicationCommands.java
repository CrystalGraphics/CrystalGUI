package com.crystalgui.workbench.app;

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
 * <b>What every workbench application offers</b> - saving the active file, and saving or restoring the
 * pane arrangement.
 *
 * <p>Registered once by {@link WorkbenchApplication}, so any product built on it has Save without
 * declaring anything. Each resolves its subject from the data context rather than from a captured
 * object, which is what lets two applications on one desktop each save their own.</p>
 *
 * <ul>
 *   <li>{@code workbench.saveFile} - {@code Mod+S}</li>
 *   <li>{@code workbench.saveLayout} - {@code Mod+Shift+S}</li>
 *   <li>{@code workbench.restoreLayout}</li>
 * </ul>
 *
 * <h3>Commands rather than key handling</h3>
 *
 * <p>As commands they are rebindable, they appear in the palette with their accelerators, and they grey
 * out when they do not apply - none of which a scan-code switch can offer.</p>
 *
 * <h3>{@code Mod+S} is the FILE</h3>
 *
 * <p>The layout had it first, back when there were no files behind the editor. Once there were,
 * {@code Mod+S} had exactly one obvious meaning and it was not "serialise the pane arrangement": a key
 * that writes the wrong thing is worse than one that does nothing.</p>
 *
 * <h3>The {@code workbench.} prefix, deliberately</h3>
 *
 * <p>Not {@code editor.}, which the text editor's own actions own. Two command sets sharing a namespace
 * collide on the first name they both want, and ids are what every binding and user remapping refers
 * to.</p>
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
