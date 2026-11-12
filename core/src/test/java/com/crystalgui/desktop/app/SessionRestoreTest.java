package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.storage.config.CgInMemoryConfigStorage;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.WorkspaceClient;
import com.crystalgui.ui.dom.UIDocument;

/** What a session leaves: the applications that were running come back, each with the state it gave. */
public class SessionRestoreTest {

    private final List<String> restored = new ArrayList<>();
    private ApplicationKind kind;
    private boolean animationsWere;

    private record Running(ApplicationKind kind, WindowFrame window) implements Application {
        @Override public AppWindow mainWindow() { return AppWindow.of(window); }
        @Override public boolean open(Resource resource) { return false; }
        @Override public void activate() { }
        @Override public void dispose() { }
    }

    @Before
    public void define() {
        animationsWere = Desktop.animationsEnabled();
        Desktop.setAnimationsEnabled(false);
        kind = ApplicationKind.of("test:restored", "Restored").standalone().launch(context -> {
            context.restoreState(restored::add);
            context.saveState("opened-" + restored.size());
            WindowFrame main = new WindowFrame("Restored").setKey("test:restored").setApplication(kind);
            main.markApplicationMain();
            context.desktop().addWindow(main);
            return new Running(kind, main);
        });
    }

    @After
    public void restore() {
        Desktop.setAnimationsEnabled(animationsWere);
    }

    private Desktop desktopOn(CgInMemoryConfigStorage storage) {
        Desktop desktop = Desktop.of(new UIDocument());
        desktop.applications().install(kind);
        desktop.persistTo(storage, "main");
        return desktop;
    }

    @Test
    public void anApplicationLeftRunningComesBackWithItsState() {
        CgInMemoryConfigStorage storage = new CgInMemoryConfigStorage();
        Desktop first = desktopOn(storage);
        first.applications().launch(kind, (WorkspaceClient) null);
        first.savePersistedState();
        assertTrue("first run: nothing to restore", restored.isEmpty());

        Desktop second = desktopOn(storage);
        assertTrue("a record alone launches nothing", second.applications().running().isEmpty());
        second.applications().restoreSession(null);

        assertEquals(1, second.applications().running(kind).size());
        assertEquals("the state it left, handed back before its first frame", List.of("opened-0"), restored);
    }

    @Test
    public void anApplicationThatQuitIsNotRestored() {
        CgInMemoryConfigStorage storage = new CgInMemoryConfigStorage();
        Desktop first = desktopOn(storage);
        Application app = first.applications().launch(kind, (WorkspaceClient) null);
        app.mainWindow().destroy();
        first.savePersistedState();

        Desktop second = desktopOn(storage);
        second.applications().restoreSession(null);
        assertFalse(second.applications().running(kind).size() > 0);
    }
}
