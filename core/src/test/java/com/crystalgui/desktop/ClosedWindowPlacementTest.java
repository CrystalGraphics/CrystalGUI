package com.crystalgui.desktop;

import com.crystalgraphics.storage.config.CgInMemoryConfigStorage;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A closed window's size and place come back with the next window under its key, in the session and after a restart. */
public class ClosedWindowPlacementTest extends UiDocumentTestBase {

    private final CgInMemoryConfigStorage storage = new CgInMemoryConfigStorage();
    private Desktop desktop;

    @Before
    public void openADesktop() {
        Desktop.setAnimationsEnabled(false);
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document);
        desktop.persistTo(storage, "placement");
        document.update(800f, 600f);
    }

    @After
    public void animationsBackOn() {
        Desktop.setAnimationsEnabled(true);
    }

    private WindowFrame open(Desktop on) {
        return on.addWindow(new WindowFrame("Cues").setKey("test:cues"));
    }

    private WindowFrame closedAt(float left, float top, float width, float height) {
        WindowFrame first = open(desktop);
        first.resizeTo(width, height);
        first.moveTo(left, top);
        frame();
        first.destroy();
        frame();
        return first;
    }

    @Test
    public void aFirstOpenKeepsTheSizeItWasGivenBeforeBeingAdded() {
        WindowFrame window = new WindowFrame("Cues").setKey("test:cues");
        window.resizeTo(620f, 360f);
        desktop.addWindow(window);
        frame();
        assertEquals(620f, window.recordedWidth(), 0.5f);
        assertEquals(360f, window.recordedHeight(), 0.5f);
    }

    @Test
    public void aMainWindowOpensAtItsKindsSize() {
        ApplicationKind kind = ApplicationKind.of("test:sized", "Sized").windowSize(500, 300).standalone();
        WindowFrame window = new WindowFrame("Sized").setKey("test:sized").setApplication(kind);
        window.markApplicationMain();
        desktop.addWindow(window);
        frame();
        assertEquals(500f, window.recordedWidth(), 0.5f);
        assertEquals(300f, window.recordedHeight(), 0.5f);
    }

    @Test
    public void anApplicationsWindowNothingSizedTakesMostOfTheDesktop() {
        ApplicationKind kind = ApplicationKind.of("test:plain", "Plain").standalone();
        WindowFrame window = new WindowFrame("Plain").setKey("test:plain").setApplication(kind);
        window.markApplicationMain();
        desktop.addWindow(window);
        frame();
        assertEquals("80% of the desktop's width", 640f, window.recordedWidth(), 1f);
    }

    @Test
    public void aWindowClosedMaximisedReopensMaximised() {
        WindowFrame first = open(desktop);
        frame();
        first.maximize();
        frame();
        assertTrue(first.isMaximized());
        first.destroy();
        frame();

        WindowFrame again = open(desktop);
        frame();
        frame();
        assertTrue("it reopened restored", again.isMaximized());
    }

    @Test
    public void reopeningInTheSameSessionTakesTheClosedSize() {
        closedAt(40f, 50f, 320f, 210f);

        WindowFrame again = open(desktop);
        frame();
        assertEquals(320f, again.recordedWidth(), 0.5f);
        assertEquals(210f, again.recordedHeight(), 0.5f);
        assertEquals(40f, again.getWantedLeft(), 0.5f);
    }

    @Test
    public void aClosedWindowsSizeSurvivesARestart() {
        closedAt(40f, 50f, 320f, 210f);
        desktop.savePersistedState();

        UIDocument restarted = new UIDocument();
        restarted.styles().addStylesheet(StyleSheet.DEFAULT);
        Desktop next = Desktop.of(restarted);
        next.persistTo(storage, "placement");
        WindowFrame reopened = open(next);
        restarted.update(800f, 600f);
        assertEquals(320f, reopened.recordedWidth(), 0.5f);
        assertEquals(210f, reopened.recordedHeight(), 0.5f);
    }
}
