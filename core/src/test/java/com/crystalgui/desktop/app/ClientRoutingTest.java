package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/** Two windows of one application share its document: the pointer reaches the window it is over, in that window's pixels. */
public class ClientRoutingTest extends UiDocumentTestBase {

    private static final class Stub implements AppDocument.Backend {
        final List<CgSystemInput.Mouse.Event> mouse = new ArrayList<>();

        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            mouse.add(event);
            return true;
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static final ApplicationKind KIND = ApplicationKind.of("test:routing", "Routing").standalone().separateDocument();

    private boolean animationsWere;
    private UIDocument applicationDocument;
    private Stub backend;
    private ClientPortal left, right;

    @Before
    public void openTwoWindows() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document);
        applicationDocument = new UIDocument();
        applicationDocument.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET));
        backend = new Stub();
        AppDocument appDocument = new AppDocument(applicationDocument, backend);
        AppWindow a = AppWindows.open(desktop, KIND, appDocument,
                WindowSpec.titled("A").content(new UIElement()).size(300, 200).at(20, 20));
        AppWindow b = AppWindows.open(desktop, KIND, appDocument,
                WindowSpec.titled("B").content(new UIElement()).size(300, 200).at(360, 20));
        for (int pass = 0; pass < 3; pass++) frame();
        left = (ClientPortal) a.frame().content().children().get(0);
        right = (ClientPortal) b.frame().content().children().get(0);
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private boolean front(ClientPortal portal) {
        applicationDocument.frame(0.016f, 400, 300);
        return portal.client().hasClass("__client-front__");
    }

    @Test
    public void thePointerIsAimedAtTheWindowItIsOver() {
        int[] at = centreOf(right);
        move(at[0], at[1]);
        frame();
        assertTrue("the right window's client is on top", front(right));
        assertFalse("and the left one is not", front(left));

        at = centreOf(left);
        move(at[0], at[1]);
        frame();
        assertTrue("moving over the left one puts it on top", front(left));
        assertFalse(front(right));
    }

    @Test
    public void aDragAcrossToTheOtherWindowIsInThatWindowsPixels() {
        int[] from = centreOf(left);
        int[] to = centreOf(right);
        press(from[0], from[1]);
        move(to[0], to[1]);
        frame();
        assertTrue("the drag is over the right window, so its client is on top", front(right));
        CgSystemInput.Mouse.Event last = backend.mouse.get(backend.mouse.size() - 1);
        assertEquals("the centre of a 300-wide window, not the centre of the screen", 150f * uiScale(), last.x(), 40f * uiScale());
        release(to[0], to[1]);
    }
}
