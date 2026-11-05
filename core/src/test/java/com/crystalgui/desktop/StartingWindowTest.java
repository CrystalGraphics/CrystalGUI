package com.crystalgui.desktop;

import com.crystalgui.core.storage.InMemoryConfigStorage;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.StartingMark;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A launch's window on the frame it was asked for, and the application built into its place a frame later. */
public class StartingWindowTest extends UiDocumentTestBase {

    private Desktop desktop;
    private int built;
    private final ApplicationKind kind = ApplicationKind.of("test:slow", "Slow").windowSize(400, 260).standalone()
            .startingWindow();
    private final ApplicationKind quick = ApplicationKind.of("test:quick", "Quick").standalone();

    @Before
    public void openADesktop() {
        Desktop.setAnimationsEnabled(false);
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document);
        kind.launch(context -> {
            built++;
            WindowFrame main = new WindowFrame("Slow").setKey("test:slow").setApplication(kind);
            main.markApplicationMain();
            context.desktop().addWindow(main);
            return new Running(kind, main);
        });
        desktop.applications().install(kind);
        quick.launch(context -> {
            WindowFrame main = new WindowFrame("Quick").setKey("test:quick").setApplication(quick);
            main.markApplicationMain();
            context.desktop().addWindow(main);
            return new Running(quick, main);
        });
        desktop.applications().install(quick);
        desktop.persistTo(new InMemoryConfigStorage(), "starting");
        document.update(800f, 600f);
    }

    @After
    public void animationsBackOn() {
        Desktop.setAnimationsEnabled(true);
    }

    private void frames(int count) {
        for (int i = 0; i < count; i++) frame();
    }

    @Test
    public void theWindowOpensAtOnceAndTheApplicationTakesItsPlace() {
        desktop.applications().launchInStartingWindow(kind, null);
        assertTrue("no window on the frame it was asked for", desktop.isStarting(kind));
        assertEquals("built before its window showed", 0, built);
        WindowFrame starter = desktop.registry().windows().get(0);

        frames(4);
        assertEquals(1, built);
        assertFalse("the starting window outlived its application's", desktop.isStarting(kind));
        assertEquals("two windows for one launch", 1, desktop.registry().windows().size());
        WindowFrame main = desktop.registry().windows().get(0);
        assertEquals("test:slow", main.key());
        assertEquals("it moved on the swap", starter.getWantedLeft(), main.getWantedLeft(), 0.5f);
        assertEquals(400f, main.recordedWidth(), 0.5f);
    }

    /**
     * A resize or maximise made while it loads is kept by the application's window, over the placement the starting
     * window itself opened at.
     */
    @Test
    public void geometryChangedWhileItLoadsOutlivesTheRecord() {
        // A record for the application: it opened and closed once
        desktop.applications().launchInStartingWindow(kind, null);
        frames(4);
        desktop.registry().windows().get(0).requestClose();
        frames(2);
        assertTrue(desktop.registry().windows().isEmpty());

        desktop.applications().launchInStartingWindow(kind, null);
        WindowFrame starter = desktop.registry().windows().get(0);
        starter.resizeTo(300f, 220f);
        frame();
        frames(4);
        WindowFrame main = desktop.registry().windows().get(0);
        assertEquals("test:slow", main.key());
        assertEquals("the record put back the size it had before the resize", 300f, main.recordedWidth(), 0.5f);
        main.requestClose();
        frames(2);

        desktop.applications().launchInStartingWindow(kind, null);
        desktop.registry().windows().get(0).maximize();
        frames(4);
        main = desktop.registry().windows().get(0);
        assertEquals("test:slow", main.key());
        assertTrue("the record put back the window it had before the maximise", main.isMaximized());
    }

    @Test
    public void aMarkSeenLeavesOverTheApplicationsWindowAndIsGoneAfter() throws InterruptedException {
        Desktop.setAnimationsEnabled(true);
        desktop.applications().launchInStartingWindow(kind, null);
        frame();
        frames(3);
        assertTrue("it built before its mark had come in", desktop.isStarting(kind));
        Thread.sleep(2150);   // the mark is in whole, settled, 1.43 s after its first frame
        frames(3);
        WindowFrame main = desktop.registry().windows().get(0);
        assertEquals("test:slow", main.key());
        assertTrue("the application cut in with no cover leaving over it", hasCover(main));
        Thread.sleep(800);   // past the 700 ms out
        frames(2);
        assertFalse("the cover outlived its out", hasCover(main));
    }

    private static boolean hasCover(WindowFrame frame) {
        for (UIElement child : frame.content().children()) {
            if (child.hasClass(StartingMark.COVER_CLASS)) return true;
        }
        return false;
    }

    @Test
    public void closingTheStartingWindowCancelsTheLaunch() {
        desktop.applications().launchInStartingWindow(kind, null);
        desktop.registry().windows().get(0).destroy();
        frames(4);
        assertEquals("it built an application nobody wanted any more", 0, built);
        assertTrue(desktop.registry().windows().isEmpty());
    }

    @Test
    public void aDesktopClosedWhileStartingBuildsOnceItIsBack() {
        desktop.applications().launchInStartingWindow(kind, null);
        desktop.suspend();
        frames(4);
        assertEquals("it built with the desktop closed", 0, built);
        desktop.resume();
        frames(4);
        assertEquals("the launch was lost with the desktop closed", 1, built);
        assertFalse(desktop.isStarting(kind));
    }

    @Test
    public void closedWhileItsMarkComesInItGoesAndBuildsNothing() throws InterruptedException {
        Desktop.setAnimationsEnabled(true);
        desktop.applications().launchInStartingWindow(kind, null);
        WindowFrame starter = desktop.registry().windows().get(0);
        frames(3);
        UIElement close = null;
        for (UIElement control : starter.controls().children()) {
            if (control.hasClass(WindowFrame.CLOSE_CLASS)) close = control;
        }
        int[] at = centreOf(close);
        click(at[0], at[1]);
        for (int i = 0; i < 10; i++) {
            Thread.sleep(40);
            frame();
        }
        assertEquals("it built an application its window was closed on", 0, built);
        assertTrue("the starting window would not close", desktop.registry().windows().isEmpty());
    }

    @Test
    public void minimisedWhileItsMarkComesInItBuildsAndWaitsMinimised() throws InterruptedException {
        Desktop.setAnimationsEnabled(true);
        desktop.applications().launchInStartingWindow(kind, null);
        WindowFrame starter = desktop.registry().windows().get(0);
        frames(3);
        starter.minimize();
        // The build replaces the window, so it waits for the flight to land rather than cutting it
        for (int i = 0; i < 3; i++) {
            Thread.sleep(40);
            frame();
        }
        assertEquals("the build cut the minimise's flight short", 0, built);
        assertTrue("the starting window went before its flight landed", starter.animator().isPlaying());
        // Past the 350 ms minimise, well short of the mark's 1.43 s
        for (int i = 0; i < 12; i++) {
            Thread.sleep(40);
            frame();
        }
        assertEquals("it waited on a mark nobody can see", 1, built);
        WindowFrame main = desktop.registry().windows().get(0);
        assertEquals("test:slow", main.key());
        assertEquals("the application opened over the minimise", WindowState.HIDDEN, main.state());
    }

    @Test
    public void aStartingWindowClosingIsNoLongerStarting() {
        Desktop.setAnimationsEnabled(true);
        desktop.applications().launchInStartingWindow(kind, null);
        WindowFrame starter = desktop.registry().windows().get(0);
        frames(3);
        starter.requestClose();
        // Its mark may settle over the close's 125 ms, and a launch still waiting would build into it
        assertFalse("a window playing its close still counts as starting", desktop.isStarting(kind));
    }

    @Test
    public void anApplicationThatDoesNotOptInOpensWithNoStartingWindow() {
        desktop.applications().launchInStartingWindow(quick, null);
        assertFalse("a starting window it never asked for", desktop.isStarting(quick));
        assertEquals("test:quick", desktop.registry().windows().get(0).key());
    }

    @Test
    public void aSecondClickWhileStartingOpensNoSecondWindow() {
        desktop.applications().launchInStartingWindow(kind, null);
        WindowFrame starter = desktop.registry().windows().get(0);
        desktop.applications().launchInStartingWindow(kind, null);
        assertSame(starter, desktop.registry().windows().get(0));
        assertEquals(1, desktop.registry().windows().size());
    }

    private record Running(ApplicationKind kind, WindowFrame window) implements Application {
        @Override public WindowFrame mainWindow() { return window; }
        @Override public boolean open(Resource resource) { return false; }
        @Override public void activate() { }
        @Override public void dispose() { }
    }
}
