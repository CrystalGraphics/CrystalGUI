package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.Commands;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.command.MenuSection;
import com.crystalgraphics.storage.config.CgInMemoryConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.desktop.window.SystemMenu;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import org.joml.Vector2f;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Applications pinned to the taskbar: they come back after a restart, a pin's button becomes its window's
 * entry and back, and dragging reorders pins without ever pinning a window.
 */
public class TaskbarPinsTest extends UiDocumentTestBase {

    private Desktop desktop;

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

    /** A standalone application whose launch opens one window that names it. */
    private static ApplicationKind kindOf(String id, String name) {
        ApplicationKind kind = ApplicationKind.of(id, name).standalone();
        return kind.launch(context -> {
            WindowFrame frame = new WindowFrame(name);
            frame.setApplication(kind);
            context.desktop().addWindow(frame);
            return new Stub(kind, frame);
        });
    }

    private Taskbar taskbar() {
        Taskbar taskbar = desktop.taskbar();
        assertNotNull(taskbar);
        return taskbar;
    }

    @Test
    public void pinsComeBackAfterARestart() {
        CgInMemoryConfigStorage storage = new CgInMemoryConfigStorage();
        ApplicationKind lab = kindOf("test:lab", "Lab");
        ApplicationKind cues = kindOf("test:cues", "Cues");
        desktop.applications().install(lab);
        desktop.applications().install(cues);
        desktop.persistTo(storage, "pins");
        desktop.pins().pin(cues);
        desktop.pins().pin(lab);

        // A second desktop over the same store, as the next launch is
        Desktop next = Desktop.of(new UIDocument());
        next.applications().install(lab);
        next.applications().install(cues);
        next.persistTo(storage, "pins");

        assertEquals("the pins were not saved as they changed, in their order",
                List.of("test:cues", "test:lab"), next.pins().ids());
        assertNotNull("a pin with nothing running has no button", next.taskbar().pinEntryFor(cues));
    }

    @Test
    public void launchingFromAPinAndClosingBackKeepsOneButton() {
        ApplicationKind lab = kindOf("test:lab", "Lab");
        desktop.applications().install(lab);
        // Somewhere to write, or a launch is refused
        desktop.persistTo(new CgInMemoryConfigStorage(), "pins");
        desktop.pins().pin(lab);
        Button pin = taskbar().pinEntryFor(lab);
        assertNotNull(pin);
        assertTrue("a pin with nothing running is not a launch button", pin.hasClass(Taskbar.LAUNCH_CLASS));

        DesktopCommands.open(desktop, lab);
        WindowFrame window = desktop.registry().windows().get(0);
        assertSame("the window took a new entry instead of the pin's", pin, taskbar().entryFor(window));
        assertFalse("a running entry still reads as a launch button", pin.hasClass(Taskbar.LAUNCH_CLASS));
        assertEquals("the pin and its window are drawn twice", 1, taskbar().entries().children().size());

        window.destroy();
        assertSame("closing the last window did not give the button back to its pin",
                pin, taskbar().pinEntryFor(lab));
        assertTrue(pin.hasClass(Taskbar.LAUNCH_CLASS));
    }

    /**
     * With animations on and the application set AFTER the window opens, as a workbench does: the refresh in
     * between builds the window an arriving entry beside the pin, and the pin's button must still be the one
     * that stays.
     */
    @Test
    public void aWindowNamedAfterItOpensStillTakesThePinsButton() {
        Desktop.setAnimationsEnabled(true);
        ApplicationKind lab = ApplicationKind.of("test:late", "Late").standalone();
        lab.launch(context -> {
            WindowFrame frame = context.desktop().addWindow(new WindowFrame("Late"));
            frame.setApplication(lab);
            return new Stub(lab, frame);
        });
        desktop.applications().install(lab);
        desktop.persistTo(new CgInMemoryConfigStorage(), "pins");
        desktop.pins().pin(lab);
        Button pin = taskbar().pinEntryFor(lab);

        DesktopCommands.open(desktop, lab);
        WindowFrame window = desktop.registry().windows().get(0);
        assertSame("the pin collapsed while a new entry opened beside it", pin, taskbar().entryFor(window));
        assertEquals(1, taskbar().entries().children().size());
    }

    @Test
    public void pinsComeFirstAndAWindowDraggedAmongThemStaysUnpinned() {
        ApplicationKind lab = kindOf("test:lab", "Lab");
        ApplicationKind cues = kindOf("test:cues", "Cues");
        desktop.applications().install(lab);
        desktop.applications().install(cues);
        WindowFrame loose = desktop.addWindow(new WindowFrame("Loose"));
        desktop.pins().pin(lab);
        desktop.pins().pin(cues);

        List<UIElement> row = taskbar().entryRow();
        assertSame("pins are not first", taskbar().pinEntryFor(lab), row.get(0));
        assertSame(taskbar().entryFor(loose), row.get(2));

        // A window dragged before the first pin is refused; a pin dragged to the end goes last among the pins
        taskbar().move(taskbar().entryFor(loose), row.get(0));
        assertSame("an unpinned window crossed into the pins", taskbar().entryFor(loose), taskbar().entryRow().get(2));
        taskbar().move(taskbar().pinEntryFor(lab), null);
        assertEquals("a pin dragged to the end did not reorder the pins",
                List.of("test:cues", "test:lab"), desktop.pins().ids());
    }

    /** Only what a pin could start again offers Pin: an installed, launchable application that does not opt out. */
    @Test
    public void onlyAPinnableWindowOffersPin() {
        ApplicationKind lab = kindOf("test:lab", "Lab");
        ApplicationKind viewer = kindOf("test:viewer", "Viewer").preventPinning();
        desktop.applications().install(lab);
        desktop.applications().install(viewer);
        WindowFrame bare = desktop.addWindow(new WindowFrame("Bare"));
        WindowFrame labWindow = desktop.addWindow(new WindowFrame("Lab").setApplication(lab));
        WindowFrame viewerWindow = desktop.addWindow(new WindowFrame("Viewer").setApplication(viewer));

        assertTrue("a launchable application's window has no Pin row", offersPin(labWindow));
        assertFalse("a window with no application offers Pin", offersPin(bare));
        assertFalse("an application that prevents pinning offers Pin", offersPin(viewerWindow));
        assertEquals("the menu does not open with the application, as Windows' does",
                "Lab", firstLabel(labWindow));
    }

    private String firstLabel(WindowFrame frame) {
        CommandContext context = CommandContext.of(taskbar().entryFor(frame));
        MenuSection first = Commands.process().sections(MenuId.TASKBAR_ENTRY, context).get(0);
        return ((MenuEntry.Item) first.entries().get(0)).command().getLabel();
    }

    private boolean offersPin(WindowFrame frame) {
        CommandContext context = CommandContext.of(taskbar().entryFor(frame));
        for (MenuSection section : Commands.process().sections(MenuId.TASKBAR_ENTRY, context)) {
            for (MenuEntry entry : section.entries()) {
                if (entry instanceof MenuEntry.Item item && item.command().getId().equals(DesktopCommands.PIN_TO_TASKBAR)) return true;
            }
        }
        return false;
    }

    /** Probe: the jump list's application row, clicked, raises a single-instance application behind another. */
    @Test
    public void theApplicationRowRaisesARunningSingleInstance() {
        ApplicationKind single = ApplicationKind.of("test:single", "Single").standalone().singleInstance();
        single.launch(context -> {
            WindowFrame frame = new WindowFrame("Single");
            frame.setApplication(single);
            context.desktop().addWindow(frame);
            return new Stub(single, frame);
        });
        desktop.applications().install(single);
        desktop.persistTo(new CgInMemoryConfigStorage(), "pins");
        DesktopCommands.open(desktop, single);
        // Built behind its starting window, a frame or two later
        for (int i = 0; i < 4; i++) frame();
        WindowFrame window = desktop.registry().windows().get(0);
        WindowFrame other = desktop.addWindow(new WindowFrame("Other"));
        for (int i = 0; i < 3; i++) frame();
        assertSame(other, desktop.activeWindow());

        SystemMenu.showJumpList(window, taskbar().entryFor(window));
        for (int i = 0; i < 4; i++) frame();
        Menu menu = null;
        for (UIElement promoted : document.promotedNodes()) if (promoted instanceof Menu open) menu = open;
        assertNotNull("no jump list opened", menu);
        MenuItem row = menu.getItems().get(0);
        assertEquals("Single", row.getText());
        Vector2f at = Box.originIn(row.box(), document.box());
        click(at.x + row.box().width() / 2f, at.y + row.box().height() / 2f);
        for (int i = 0; i < 4; i++) frame();

        assertFalse("the menu stayed open", menu.isOpen());
        assertSame("the application row did not raise the running instance", window, desktop.activeWindow());
    }

    private record Stub(ApplicationKind kind, WindowFrame window) implements Application {

        @Override
        public WindowFrame mainWindow() {
            return window;
        }

        @Override
        public boolean open(Resource resource) {
            return false;
        }

        @Override
        public void activate() {
            Desktop on = Desktop.ifPresent(window.document());
            if (on != null) on.activate(window, true);
        }

        @Override
        public void dispose() {
        }
    }
}
