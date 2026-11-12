package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
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
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.TaffyPosition;

/** Where the shell puts an application's popups, on the shell's real styles: over where the application drew them. */
public class PopupPlacementTest extends UiDocumentTestBase {

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

    private static final ApplicationKind KIND = ApplicationKind.of("test:placed", "Placed").standalone().separateDocument();

    private boolean animationsWere;

    @Before
    public void animationsOff() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
    }

    @After
    public void animationsBack() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private Stub backend;
    private AppDocument application;
    private PopupLayer layer;
    private PopupPortal portal;
    private Popover menu;
    private UIDocument own;

    /** A menu open in a window of an application with its own document, on a shell at UI scale 2, settled. */
    private void openMenu() {
        openMenu(720f);
    }

    private void openMenu(float workAreaHeight) {
        withDefaultStyles();
        document.boxes().setUiScale(2f);
        Desktop desktop = Desktop.of(document);
        document.workArea().layout(l -> l.height(workAreaHeight));
        own = new UIDocument();
        own.boxes().setUiScale(2f);
        own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        layer = new PopupLayer();
        own.useTopLayer(layer);
        backend = new Stub();
        application = new AppDocument(own, backend);
        AppWindows.open(desktop, KIND, application, WindowSpec.titled("Placed"));
        frame();

        menu = new Popover();
        menu.append(new UIText("A menu"));
        own.append(menu);
        menu.showAt(30f, 40f, null);
        for (int i = 0; i < 6; i++) own.frame(0.016f, 400, 300);
        layer.committed();
        for (int i = 0; i < 12; i++) frame();
        portal = (PopupPortal) document.promotedNodes().stream().filter(n -> n instanceof PopupPortal)
                .findFirst().orElseThrow();
    }

    /** Where the menu really is in the application's document, in its pixels: its box's centre. */
    private float[] menuCentrePixels() {
        Box box = menu.box();
        float scale = document.boxes().uiScale();
        return new float[] {box.worldX() + box.width() * scale / 2f, box.worldY() + box.height() * scale / 2f};
    }

    @Test
    public void aMenuIsDisplayedWhereItIsInTheWindow() {
        openMenu();
        Box window = application.active().box();
        float[] centre = menuCentrePixels();
        Box shown = portal.box();
        float scale = document.boxes().uiScale();
        float[] occupied = layer.occupied();
        // The picture is drawn at -origin in the portal's own units, so the menu's centre lands at:
        float onScreenX = shown.worldX() + (centre[0] / scale - occupied[0]) * scale;
        float onScreenY = shown.worldY() + (centre[1] / scale - occupied[1]) * scale;
        assertEquals("across", window.worldX() + centre[0], onScreenX, 2f);
        assertEquals("down", window.worldY() + centre[1], onScreenY, 2f);
    }

    @Test
    public void theApplicationLaysOutAtItsActiveWindowsSize() {
        openMenu();
        Box window = application.active().box();
        application.active().paintContent(null, window);    // what a frame does: the window reports its size
        assertEquals("wide", window.width(), application.windowWidth(), 0.5f);
        assertEquals("tall", window.height(), application.windowHeight(), 0.5f);
    }

    @Test
    public void aShortWorkAreaDoesNotMoveWhatIsDrawnOffWhatIsHitTested() {
        openMenu(120f);
        Box window = application.active().box();
        float[] centre = menuCentrePixels();
        Box shown = portal.box();
        float scale = document.boxes().uiScale();
        float[] occupied = layer.occupied();
        assertEquals("down", window.worldY() + centre[1],
                shown.worldY() + (centre[1] / scale - occupied[1]) * scale, 2f);
        assertEquals("across", window.worldX() + centre[0],
                shown.worldX() + (centre[0] / scale - occupied[0]) * scale, 2f);
    }

    @Test
    public void aPressOnAMenuLeavesKeysGoingToTheApplication() {
        openMenu();
        Box window = application.active().box();
        document.focus().requestPointerFocus(application.active());
        float[] centre = menuCentrePixels();
        click(window.worldX() + centre[0], window.worldY() + centre[1]);
        frame();
        assertEquals("the window keeps focus", application.active(), document.focus().focused());
        int before = backend.keys.size();
        keyPress(65);
        assertTrue("a key reaches the application", backend.keys.size() > before);
    }

    @Test
    public void thePointerLeavingAMenuForTheDesktopIsToldToTheApplication() {
        openMenu();
        Box window = application.active().box();
        float[] centre = menuCentrePixels();
        move(window.worldX() + centre[0], window.worldY() + centre[1]);
        frame();
        frame();
        backend.mouse.clear();
        move(2f, 2f);                                   // off every window of the application
        frame();
        assertTrue("the application heard the pointer go", !backend.mouse.isEmpty());
        CgSystemInput.Mouse.Event last = backend.mouse.get(backend.mouse.size() - 1);
        assertTrue("and where it went, not where it was", Math.abs(last.x() - centre[0]) > 20f);
    }

    @Test
    public void theSpaceBetweenTwoPopupsIsTheShells() {
        openMenu();
        UIElement other = new UIElement();
        own.append(other);
        other.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(300f).top(220f).width(50f).height(30f));
        own.promote(other);
        for (int i = 0; i < 6; i++) own.frame(0.016f, 400, 300);
        layer.committed();
        for (int i = 0; i < 12; i++) frame();
        assertEquals("two popups are shown", 2, layer.rectangles().length);
        Box window = application.active().box();
        float scale = document.boxes().uiScale();
        float[] a = layer.rectangles()[0], b = layer.rectangles()[1];
        float gapX = ((a[0] + a[2]) / 2f + (b[0] + b[2]) / 2f) / 2f * scale;
        float gapY = ((a[1] + a[3]) / 2f + (b[1] + b[3]) / 2f) / 2f * scale;
        backend.mouse.clear();
        move(window.worldX() + gapX, window.worldY() + gapY);
        frame();
        assertTrue("the shell, not the popups, has the pointer there",
                !(document.input().hoverTarget() instanceof UIElement hit) || !within(hit));
        assertTrue("and the application heard nothing of a pointer over nothing of its", backend.mouse.size() <= 1);
    }

    private boolean within(UIElement node) {
        for (UIElement at = node; at != null; at = at.parentElement()) if (at == portal) return true;
        return false;
    }

    @Test
    public void thePointerOverAMenuReachesTheApplicationOverTheSameSpot() {
        openMenu();
        Box window = application.active().box();
        float[] centre = menuCentrePixels();
        move(window.worldX() + centre[0], window.worldY() + centre[1]);
        frame();
        CgSystemInput.Mouse.Event last = backend.mouse.get(backend.mouse.size() - 1);
        assertEquals("across, in the application's pixels", centre[0], last.x(), 2f);
        assertEquals("down, in the application's pixels", centre[1], last.y(), 2f);
    }
}
