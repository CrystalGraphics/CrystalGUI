package com.crystalgui.desktop;

import com.crystalgui.desktop.app.AppWindow;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.launcher.Launcher;
import com.crystalgui.desktop.launcher.LauncherButton;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>Start opens wherever it is pressed, and a press on an app in its menu launches it.</b> The button's mark covered
 * its middle and took the press for itself, and the list took only Enter or a double-click.
 */
public class LauncherClickTest extends UiDocumentTestBase {

    private Desktop desktop;
    private LauncherButton start;

    @Before
    public void openADesktop() {
        Desktop.setAnimationsEnabled(false);
        viewport(800f, 600f);
        document.append(new UIElement().layout(l -> l.width(800).height(600)));
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document).useStorage(Paths.get("build", "tmp", "launcher-click-test").toAbsolutePath());
        for (int i = 0; i < 10; i++) frame();
        start = desktop.taskbar().start();
    }

    private int[] at(Box box, float fx, float fy) {
        return new int[] { Math.round(box.worldX() + box.width() * fx * uiScale()),
                Math.round(box.worldY() + box.height() * fy * uiScale()) };
    }

    @Test
    public void startOpensFromAnyHeightOfTheButton() {
        for (float fy : new float[] { 0.1f, 0.5f, 0.9f }) {
            int[] p = at(start.box(), 0.5f, fy);
            click(p[0], p[1]);
            for (int i = 0; i < 5; i++) frame();
            assertTrue("a press " + fy + " down the button did not open the menu", start.launcher().isOpen());
            start.launcher().hide();
            for (int i = 0; i < 5; i++) frame();
        }
    }

    @Test
    public void aSinglePressOnAnAppInTheMenuLaunchesIt() {
        int[] launched = { 0 };
        desktop.applications().install(ApplicationKind.of("test:alpha", "Alpha").standalone().launch(context -> {
            launched[0]++;
            WindowFrame frame = new WindowFrame("Alpha");
            context.desktop().addWindow(frame);
            return new Stub(frame);
        }));
        start.toggle();
        for (int i = 0; i < 5; i++) frame();
        Launcher launcher = start.launcher();
        launcher.search("Alpha");
        for (int i = 0; i < 5; i++) frame();
        int before = desktop.windowManager().windows().size();
        int[] p = at(launcher.list().box(), 0.5f, 0.2f);
        click(p[0], p[1]);
        for (int i = 0; i < 60; i++) frame();
        assertTrue("a press on the row opened no window", desktop.windowManager().windows().size() > before);
        assertFalse("the menu stayed open over what it launched", launcher.isOpen());
    }

    /** The menu hangs off the button in the tree, so hovering it is hover on the button; Start's tip belongs to the button's own box. */
    @Test
    public void startsTipGoesWhenThePointerMovesOntoItsMenu() {
        start.toggle();
        for (int i = 0; i < 5; i++) frame();
        int[] on = at(start.box(), 0.5f, 0.5f);
        move(on[0], on[1]);
        for (int i = 0; i < 90; i++) frame(0.016f);
        Tooltip tip = tipIn(document);
        assertNotNull("no tip found for Start", tip);
        assertTrue("the tip did not show over Start", tip.isShown());
        int[] over = at(start.launcher().list().box(), 0.5f, 0.5f);
        move(over[0], over[1]);
        for (int i = 0; i < 5; i++) frame();
        assertFalse("the tip stayed up over the menu", tip.isShown());
    }

    /** The row's own menu launches without the row's press; the menu must still close over the window it opened. */
    @Test
    public void anApplicationStartedFromElsewhereClosesTheMenu() {
        ApplicationKind alpha = ApplicationKind.of("test:alpha", "Alpha").standalone().launch(context -> {
            WindowFrame frame = new WindowFrame("Alpha");
            context.desktop().addWindow(frame);
            return new Stub(frame);
        });
        desktop.applications().install(alpha);
        start.toggle();
        for (int i = 0; i < 5; i++) frame();
        assertTrue(start.launcher().isOpen());
        DesktopCommands.open(desktop, alpha);
        for (int i = 0; i < 5; i++) frame();
        assertFalse("the menu stayed open over what it launched", start.launcher().isOpen());
    }

    @Test
    public void startStaysLitWhileItsMenuIsUp() {
        start.toggle();
        for (int i = 0; i < 5; i++) frame();
        move(780, 590);
        for (int i = 0; i < 5; i++) frame();
        assertTrue("Start lost its highlight with the pointer off the menu", start.hasClass(Taskbar.MENU_OPEN_CLASS));
        start.launcher().hide();
        for (int i = 0; i < 5; i++) frame();
        assertFalse("Start stayed lit with its menu closed", start.hasClass(Taskbar.MENU_OPEN_CLASS));
    }

    @Test
    public void theMarksTintFollowsThePointerOnTheButtonNotOnItsMenu() {
        start.toggle();
        for (int i = 0; i < 5; i++) frame();
        int[] on = at(start.box(), 0.5f, 0.5f);
        move(on[0], on[1]);
        frame();
        assertTrue("no tint with the pointer on Start", start.hasClass(LauncherButton.POINTED_CLASS));
        int[] over = at(start.launcher().list().box(), 0.5f, 0.5f);
        move(over[0], over[1]);
        frame();
        assertFalse("tinted with the pointer on the menu", start.hasClass(LauncherButton.POINTED_CLASS));
    }

    @Test
    public void openingStartDeactivatesTheActiveWindow() {
        WindowFrame window = desktop.addWindow(new WindowFrame("Window"));
        desktop.windowManager().activate(window);
        for (int i = 0; i < 4; i++) frame();
        assertTrue(window.isActive());
        start.toggle();
        for (int i = 0; i < 4; i++) frame();
        assertFalse("the window stayed active under the open menu", window.isActive());
        assertEquals(null, desktop.windowManager().activeWindow());
    }

    /** Right-click opens the row's menu; pressing its Pin item pins and must not launch. */
    @Test
    public void aPressOnTheRowsContextMenuDoesNotLaunchIt() {
        int[] launched = { 0 };
        desktop.persistTo(new com.crystalgraphics.storage.config.CgInMemoryConfigStorage(), "pins");
        desktop.applications().install(ApplicationKind.of("test:alpha", "Alpha").standalone().launch(context -> {
            launched[0]++;
            WindowFrame frame = new WindowFrame("Alpha");
            context.desktop().addWindow(frame);
            return new Stub(frame);
        }));
        start.toggle();
        for (int i = 0; i < 5; i++) frame();
        Launcher launcher = start.launcher();
        launcher.search("Alpha");
        for (int i = 0; i < 5; i++) frame();
        int[] p = at(launcher.list().box(), 0.5f, 0.2f);
        press(p[0], p[1], 1);
        frame();
        release(p[0], p[1], 1);
        for (int i = 0; i < 8; i++) frame();
        com.crystalgui.widget.overlay.MenuItem pin = findItem(document, "Pin");
        assertNotNull("no Pin item in the row's menu", pin);
        int[] m = centreOf(pin);
        click(m[0], m[1]);
        for (int i = 0; i < 8; i++) frame();
        assertEquals("pinning launched the application", 0, launched[0]);
        assertTrue("it was not pinned", desktop.pins().ids().contains("test:alpha"));
    }

    private static com.crystalgui.widget.overlay.MenuItem findItem(UIElement node, String text) {
        if (node instanceof com.crystalgui.widget.overlay.MenuItem item && item.box() != null && item.getText().contains(text)) return item;
        for (UIElement child : node.children()) {
            var found = findItem(child, text);
            if (found != null) return found;
        }
        return null;
    }

    private static Tooltip tipIn(UIElement node) {
        if (node instanceof Tooltip t && t.classes().contains(Taskbar.TIP_CLASS) && "Start".equals(t.getLabel().text())) return t;
        for (UIElement child : node.children()) {
            Tooltip found = tipIn(child);
            if (found != null) return found;
        }
        return null;
    }

    private record Stub(WindowFrame window) implements Application {
        @Override public ApplicationKind kind() { return ApplicationKind.of("test:alpha", "Alpha"); }
        @Override public AppWindow mainWindow() { return AppWindow.of(window); }
        @Override public boolean open(Resource resource) { return false; }
        @Override public void activate() { }
        @Override public void dispose() { }
    }
}
