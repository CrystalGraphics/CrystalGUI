package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.Commands;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.command.MenuSection;
import com.crystalgraphics.storage.config.CgInMemoryConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgui.desktop.app.AppWindow;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.fs.Resource;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowCommands;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** An application's windows on one taskbar button, Windows' "Combine taskbar buttons": always, when full, never. */
public class TaskbarCombineTest extends UiDocumentTestBase {

    private Desktop desktop;
    private final ApplicationKind cues = ApplicationKind.of("test:cues", "Cues").standalone();

    @Before
    public void openADesktop() {
        Desktop.setAnimationsEnabled(false);
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document);
        document.update(800f, 600f);
    }

    @After
    public void animationsBackOn() {
        Desktop.setAnimationsEnabled(true);
    }

    private WindowFrame open(String title) {
        return desktop.addWindow(new WindowFrame(title).setApplication(cues));
    }

    @Test
    public void alwaysPutsAnApplicationsWindowsOnOneButton() {
        WindowFrame first = open("Cues");
        WindowFrame second = open("Cues");
        WindowFrame loose = desktop.addWindow(new WindowFrame("Loose"));
        Taskbar taskbar = desktop.taskbar();

        assertSame("an application's windows have a button each", taskbar.entryFor(first), taskbar.entryFor(second));
        assertNotSame("a window with no application was combined", taskbar.entryFor(first), taskbar.entryFor(loose));
        assertEquals(2, taskbar.entryRow().size());
        assertTrue(taskbar.entryFor(first).hasClass(Taskbar.MULTIPLE_CLASS));
    }

    @Test
    public void aCombinedButtonOffersCloseAllInPlaceOfClose() {
        open("Cues");
        WindowFrame second = open("Cues");
        List<String> rows = rowsOf(desktop.taskbar().entryFor(second));

        assertTrue("no Close all windows on a button of two: " + rows, rows.contains(WindowCommands.CLOSE_ALL));
        assertTrue("Close beside Close all: " + rows, !rows.contains(WindowCommands.CLOSE));
    }

    @Test
    public void neverGivesEachWindowItsOwnButtonAgain() {
        WindowFrame first = open("Cues");
        WindowFrame second = open("Cues");
        desktop.desktopSettings().setTaskbarCombine(TaskbarCombine.NEVER);
        Taskbar taskbar = desktop.taskbar();

        assertNotSame(taskbar.entryFor(first), taskbar.entryFor(second));
        assertEquals(2, taskbar.entryRow().size());
    }

    /** Uncombined, one of an application's buttons moves among its siblings without taking them along. */
    @Test
    public void anUncombinedWindowReordersAmongItsSiblingsAlone() {
        desktop.desktopSettings().setTaskbarCombine(TaskbarCombine.NEVER);
        WindowFrame first = open("One");
        WindowFrame second = open("Two");
        WindowFrame third = open("Three");
        Taskbar taskbar = desktop.taskbar();

        taskbar.move(taskbar.entryFor(third), taskbar.entryFor(first));
        assertEquals(List.of(taskbar.entryFor(third), taskbar.entryFor(first), taskbar.entryFor(second)),
                taskbar.entryRow());
        taskbar.move(taskbar.entryFor(third), null);
        assertEquals(List.of(taskbar.entryFor(first), taskbar.entryFor(second), taskbar.entryFor(third)),
                taskbar.entryRow());
    }

    /** Uncombined, a window past another application's moves alone: its siblings stay where they are. */
    @Test
    public void anUncombinedWindowMovesPastAnotherApplicationAlone() {
        desktop.desktopSettings().setTaskbarCombine(TaskbarCombine.NEVER);
        ApplicationKind editor = ApplicationKind.of("test:editor", "Editor").standalone();
        WindowFrame cuesOne = open("One");
        WindowFrame edit = desktop.addWindow(new WindowFrame("Edit").setApplication(editor));
        WindowFrame cuesTwo = open("Two");
        Taskbar taskbar = desktop.taskbar();
        // A new window goes after its application's other buttons
        assertEquals(List.of(taskbar.entryFor(cuesOne), taskbar.entryFor(cuesTwo), taskbar.entryFor(edit)),
                taskbar.entryRow());

        taskbar.move(taskbar.entryFor(cuesTwo), null);
        assertEquals("its sibling moved with it", List.of(taskbar.entryFor(cuesOne), taskbar.entryFor(edit),
                taskbar.entryFor(cuesTwo)), taskbar.entryRow());
        taskbar.move(taskbar.entryFor(cuesOne), taskbar.entryFor(cuesTwo));
        assertEquals(List.of(taskbar.entryFor(edit), taskbar.entryFor(cuesOne), taskbar.entryFor(cuesTwo)),
                taskbar.entryRow());
    }

    /** Pinned too: one window past another pinned application moves alone, and the pins keep their order. */
    @Test
    public void aPinnedApplicationsWindowMovesPastAnotherPinAlone() {
        ApplicationKind editor = ApplicationKind.of("test:editor", "Editor").standalone();
        desktop.desktopSettings().setTaskbarCombine(TaskbarCombine.NEVER);
        desktop.pins().pin(cues);
        desktop.pins().pin(editor);
        WindowFrame one = open("One");
        WindowFrame two = open("Two");
        WindowFrame edit = desktop.addWindow(new WindowFrame("Edit").setApplication(editor));
        Taskbar taskbar = desktop.taskbar();
        assertEquals(List.of(taskbar.entryFor(one), taskbar.entryFor(two), taskbar.entryFor(edit)),
                taskbar.entryRow());

        taskbar.move(taskbar.entryFor(two), null);
        assertEquals("its pinned sibling moved with it",
                List.of(taskbar.entryFor(one), taskbar.entryFor(edit), taskbar.entryFor(two)), taskbar.entryRow());
        assertEquals(List.of("test:cues", "test:editor"), desktop.pins().ids());
    }

    /** A pin moved, then launched: its window takes the pin's place, though it joins before it is named. */
    @Test
    public void aLaunchedPinKeepsThePlaceItWasMovedTo() {
        ApplicationKind late = ApplicationKind.of("test:late", "Late").standalone();
        late.launch(context -> {
            WindowFrame frame = context.desktop().addWindow(new WindowFrame("Late"));
            frame.setApplication(late);
            return new Running(late, frame);
        });
        desktop.applications().install(late);
        desktop.persistTo(new CgInMemoryConfigStorage(), "combine");
        desktop.desktopSettings().setTaskbarCombine(TaskbarCombine.NEVER);
        desktop.pins().pin(late);
        WindowFrame one = open("One");
        Taskbar taskbar = desktop.taskbar();
        taskbar.move(taskbar.pinEntryFor(late), taskbar.entryFor(one));
        assertSame(taskbar.pinEntryFor(late), taskbar.entryRow().get(0));

        DesktopCommands.open(desktop, late);
        // Built behind its starting window, a frame or two later
        frame();
        frame();
        frame();
        WindowFrame window = desktop.windowManager().registry().windows().get(1);
        assertEquals("the launched pin went back to the end", List.of(taskbar.entryFor(window), taskbar.entryFor(one)),
                taskbar.entryRow());
    }

    private record Running(ApplicationKind kind, WindowFrame window) implements Application {
        @Override public AppWindow mainWindow() { return AppWindow.of(window); }
        @Override public boolean open(Resource resource) { return false; }
        @Override public void activate() { }
        @Override public void dispose() { }
    }

    @Test
    public void theSettingComesBackAfterARestart() {
        CgInMemoryConfigStorage storage = new CgInMemoryConfigStorage();
        desktop.persistTo(storage, "combine");
        desktop.desktopSettings().setTaskbarCombine(TaskbarCombine.WHEN_FULL);

        Desktop next = Desktop.of(new UIDocument());
        next.persistTo(storage, "combine");
        assertEquals(TaskbarCombine.WHEN_FULL, next.desktopSettings().taskbarCombine());
    }

    private static List<String> rowsOf(Button entry) {
        List<String> ids = new ArrayList<>();
        CommandContext context = CommandContext.of(entry);
        for (MenuSection section : Commands.process().sections(MenuId.TASKBAR_ENTRY, context)) {
            for (MenuEntry each : section.entries()) {
                if (each instanceof MenuEntry.Item item) ids.add(item.command().getId());
            }
        }
        return ids;
    }
}
