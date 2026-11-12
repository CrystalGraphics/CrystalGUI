package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.TaffyPosition;

/** An application's popups: its top layer is a surface of its own, the shell shows it, and the shell keeps it in the work area. */
public class PopupLayerTest {

    private static final class Stub implements AppDocument.Backend {
        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
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

    private static final ApplicationKind KIND = ApplicationKind.of("test:popups", "Popups").standalone().separateDocument();

    private boolean animationsWere;
    private UIDocument applicationDocument;
    private PopupLayer layer;
    private AppDocument document;

    @Before
    public void open() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        applicationDocument = new UIDocument();
        applicationDocument.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        layer = new PopupLayer();
        applicationDocument.useTopLayer(layer);
        document = new AppDocument(applicationDocument, new Stub());
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private UIElement popup(float left, float top, float width, float height) {
        UIElement popup = new UIElement();
        applicationDocument.append(popup);
        popup.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(left).top(top).width(width).height(height));
        applicationDocument.promote(popup);
        return popup;
    }

    @Test
    public void theTopLayerIsThePopupLayer() {
        assertSame(layer, document.popups());
        assertSame(layer, applicationDocument.topLayerNode());
    }

    @Test
    public void nothingIsOccupiedUntilSomethingIsShown() {
        applicationDocument.update(400, 300);
        layer.committed();
        assertNull(layer.occupied());
        assertNull(layer.picture());
    }

    @Test
    public void whatIsShownIsWhereItsBoundsAre() {
        popup(20f, 10f, 50f, 30f);
        popup(100f, 60f, 40f, 20f);
        applicationDocument.update(400, 300);
        applicationDocument.update(400, 300);
        layer.committed();
        float[] occupied = layer.occupied();
        assertNotNull(occupied);
        assertEquals(20f, occupied[0], 0.5f);
        assertEquals(10f, occupied[1], 0.5f);
        assertEquals(140f, occupied[2], 0.5f);
        assertEquals(80f, occupied[3], 0.5f);
    }

    @Test
    public void aPopoverShownInTheApplicationIsInTheLayer() {
        Popover popover = new Popover();
        popover.append(new UIText("A menu"));
        applicationDocument.append(popover);
        popover.showAt(24f, 40f, null);
        applicationDocument.update(400, 300);
        applicationDocument.update(400, 300);
        layer.committed();
        assertTrue("the popover is open", popover.isOpen());
        assertTrue("promoted", applicationDocument.isPromoted(popover));
        assertNotNull("and occupying the layer", layer.occupied());
    }

    @Test
    public void theShellHoldsOnePortalForTheDocument() {
        Desktop desktop = Desktop.of(new UIDocument());
        AppWindows.open(desktop, KIND, document, WindowSpec.titled("One"));
        AppWindows.open(desktop, KIND, document, WindowSpec.titled("Two"));
        long portals = desktop.document().promotedNodes().stream().filter(n -> n instanceof PopupPortal).count();
        assertEquals(1L, portals);
    }
}
