package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

/** Input across documents: what lands on a window's client reaches its application, and nothing else does. */
public class ClientInputTest extends UiDocumentTestBase {

    private static final class Stub implements AppDocument.Backend {
        final List<CgSystemInput.Mouse.Event> mouse = new ArrayList<>();
        final List<CgSystemInput.Keyboard.Event> keys = new ArrayList<>();

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
        }
    }

    private static final ApplicationKind KIND = ApplicationKind.of("test:input", "Input").standalone().separateDocument();

    private boolean animationsWere;
    private UIDocument applicationDocument;
    private PopupLayer layer;
    private Stub backend;
    private AppDocument appDocument;
    private ClientPortal portal;

    @Before
    public void openWindow() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document);
        applicationDocument = new UIDocument();
        applicationDocument.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        layer = new PopupLayer();
        applicationDocument.useTopLayer(layer);
        backend = new Stub();
        appDocument = new AppDocument(applicationDocument, backend);
        AppWindow window = AppWindows.open(desktop, KIND, appDocument,
                WindowSpec.titled("Own").content(new UIElement()).size(300, 200));
        for (int pass = 0; pass < 3; pass++) frame();
        portal = (ClientPortal) window.frame().content().children().get(0);
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    @Test
    public void aPressReachesTheApplicationInItsOwnPixels() {
        int[] at = centreOf(portal);
        press(at[0], at[1]);
        long presses = backend.mouse.stream().filter(CgSystemInput.Mouse.Event::state).count();
        assertEquals("one press reached the application", 1L, presses);
        CgSystemInput.Mouse.Event event = backend.mouse.get(backend.mouse.size() - 1);
        assertTrue("in the client's pixels, not the screen's", event.x() < at[0] && event.x() > 0);
    }

    @Test
    public void aDragThatLeavesTheWindowKeepsItsMovesAndItsRelease() {
        int[] at = centreOf(portal);
        press(at[0], at[1]);
        assertSame("the press captured the pointer", portal, document.input().pointerCaptureTarget());
        int before = backend.mouse.size();
        move(2, 2);
        frame();
        assertTrue("a move outside still reached it", backend.mouse.size() > before);
        release(2, 2);
        assertNull("and the release let go of it", document.input().pointerCaptureTarget());
        assertFalse("with the release delivered", backend.mouse.get(backend.mouse.size() - 1).state());
    }

    @Test
    public void aKeyGoesToTheFocusedWindowsApplicationAndNowhereElse() {
        int[] at = centreOf(portal);
        click(at[0], at[1]);
        assertSame("the press focused the window's portal", portal, document.focus().focused());
        backend.keys.clear();
        assertTrue("taken", keyPress(CgKeyCodes.KEY_TAB));
        assertEquals("Tab reached the application", 1, backend.keys.size());
        assertSame("and did not move the shell's focus", portal, document.focus().focused());
    }

    /** A surface x right of the window's right edge. */
    private float pastTheWindow() {
        var box = boxOf(portal);
        return (box.worldX() + box.width()) * uiScale() + 200f;
    }

    @Test
    public void aPressOutsideTheApplicationsPopupsClosesThem() {
        Popover popover = new Popover();
        popover.append(new UIText("A menu"));
        applicationDocument.append(popover);
        popover.showAt(24f, 40f, null);
        applicationDocument.update(400, 300);
        applicationDocument.update(400, 300);
        layer.committed();
        for (int pass = 0; pass < 3; pass++) frame();
        assertTrue("open to begin with", popover.isOpen());
        press(pastTheWindow(), 20);
        for (int pass = 0; pass < 10; pass++) applicationDocument.frame(0.1f, 400, 300);
        assertFalse("a press elsewhere in the shell closed it", popover.isOpen());
    }

    @Test
    public void aPressInsideTheApplicationsWindowLeavesThePopupsToItsOwnLightDismiss() {
        Popover popover = new Popover();
        popover.append(new UIText("A menu"));
        applicationDocument.append(popover);
        popover.showAt(24f, 40f, null);
        applicationDocument.update(400, 300);
        applicationDocument.update(400, 300);
        layer.committed();
        for (int pass = 0; pass < 3; pass++) frame();
        int[] at = centreOf(portal);
        press(at[0], at[1]);
        assertTrue("the shell did not close it; the application's own press decides", popover.isOpen());
    }
}
