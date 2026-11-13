package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * A float of an application with a document of its own is a window of the desktop, so it can leave the window it came out
 * of; what it shows stays in the application's document.
 */
public class FloatSpaceTest extends UiDocumentTestBase {

    private static final class Live implements AppDocument.Backend {
        UIDocument own;

        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return own.input().consumeMouseEvent(event);
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return own.input().consumeKeyboardEvent(event);
        }

        @Override
        public void close() {
        }
    }

    private static final ApplicationKind KIND = ApplicationKind.of("test:floats", "Floats").standalone().separateDocument();

    private boolean animationsWere;
    private Desktop desktop;
    private UIDocument own;
    private AppDocument application;
    private AppWindow main;
    private FloatSpace floats;

    @Before
    public void arrange() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        desktop = Desktop.of(document);
        own = new UIDocument();
        own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        own.useTopLayer(new PopupLayer());
        Live live = new Live();
        live.own = own;
        application = new AppDocument(own, live);
        main = AppWindows.open(desktop, KIND, application, WindowSpec.titled("Main").size(300, 200).at(20, 20));
        floats = FloatSpace.of(new AppWindows(desktop, KIND, application), main);
        settle();
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
            ClientPortal portal = application.active();
            if (portal != null && portal.box() != null) portal.paintContent(null, portal.box());
            float w = application.windowWidth(), h = application.windowHeight();
            own.frame(0.016f, w > 0f ? (int) w : 800, h > 0f ? (int) h : 600);
            application.committed();
            frame();
        }
    }

    @Test
    public void anOwnedFloatIsAWindowOfTheDesktopWhoseContentStaysInTheApplicationsDocument() {
        UIElement view = new UIElement();
        AppWindow tool = floats.openOwned(WindowSpec.titled("Library").toolWindow().size(200, 120).at(40, 40).content(view));
        settle();

        WindowFrame frame = tool.frame();
        assertSame("a window of the desktop, not a child of the window it came from", desktop, frame.desktop());
        assertSame("owned by the main window", main.frame(), frame.ownerWindow());
        assertTrue(frame.isToolWindow());
        assertSame("what it shows is the application's", own, view.document());
    }

    @Test
    public void aFloatCanBeMovedBeyondTheEdgeOfTheWindowItCameOutOf() {
        AppWindow tool = floats.openOwned(WindowSpec.titled("Library").toolWindow().size(200, 120).at(40, 40)
                .content(new UIElement()));
        settle();

        Box owner = main.frame().box();
        float past = owner.worldX() + owner.width() + 30f;
        tool.moveTo(past, 30f);
        settle();

        assertNotNull(tool.frame().box());
        assertTrue("the float was kept inside its owner's window", tool.frame().box().worldX() >= owner.worldX() + owner.width());
    }

    @Test
    public void aPeerIsNotOwnedAndItsClientGoesWhenItIsDestroyed() {
        UIElement view = new UIElement();
        AppWindow peer = floats.open(WindowSpec.titled("notes.md").size(200, 120).content(view));
        settle();
        assertEquals("not owned by the window it came from", null, peer.frame().ownerWindow());
        assertSame(own, view.document());

        peer.destroy();
        settle();
        assertSame(WindowState.DESTROYED, peer.frame().state());
        assertFalse("its client left the document with it", view.document() == own);
    }
}
