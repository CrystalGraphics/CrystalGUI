package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/** A window whose application runs in a document of its own: the frame shows a portal; the content stays in the application's document. */
public class ClientPortalTest {

    private static final class Stub implements AppDocument.Backend {
        final List<CgSystemInput.Mouse.Event> mouse = new ArrayList<>();
        final List<CgSystemInput.Keyboard.Event> keys = new ArrayList<>();
        boolean closed;

        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            mouse.add(event);
            return true;
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            keys.add(event);
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final ApplicationKind KIND = ApplicationKind.of("test:own", "Own").standalone().separateDocument();

    private boolean animationsWere;
    private Desktop desktop;
    private UIDocument applicationDocument;
    private Stub backend;
    private AppDocument document;

    @Before
    public void openDesktop() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        desktop = Desktop.of(new UIDocument());
        applicationDocument = new UIDocument();
        backend = new Stub();
        document = new AppDocument(applicationDocument, backend);
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    @Test
    public void theFrameHoldsAPortalAndTheContentStaysInTheApplicationsDocument() {
        UIElement content = new UIElement();
        AppWindow window = AppWindows.open(desktop, KIND, document, WindowSpec.titled("Own").content(content));
        assertNotNull(window.frame());
        assertTrue("the frame shows a portal", window.frame().content().children().get(0) instanceof ClientPortal);
        assertSame("the window's content is its client root", window.content(), window.content());
        assertSame("under the client root, in the application's document", applicationDocument,
                content.document());
    }

    @Test
    public void setContentGoesToTheClientNotTheFrame() {
        AppWindow window = AppWindows.open(desktop, KIND, document, WindowSpec.titled("Own"));
        UIElement later = new UIElement();
        window.setContent(later);
        assertSame(applicationDocument, later.document());
        assertTrue(window.frame().content().children().get(0) instanceof ClientPortal);
    }

    @Test
    public void closingTheWindowEndsNothingTheRegistryOwns() {
        AppWindow window = AppWindows.open(desktop, KIND, document, WindowSpec.titled("Own"));
        assertEquals(1, desktop.windowManager().registry().windows().size());
        window.close();
        assertEquals(0, desktop.windowManager().registry().windows().size());
    }
}
