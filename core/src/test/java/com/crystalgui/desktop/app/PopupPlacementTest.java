package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

/** Where the shell puts an application's popups, on the shell's real styles: over where the application drew them. */
public class PopupPlacementTest extends UiDocumentTestBase {

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

    @Test
    public void aMenuShownInTheWindowIsOverWhereTheApplicationDrewIt() {
        withDefaultStyles();
        document.boxes().setUiScale(2f);
        Desktop desktop = Desktop.of(document);
        UIDocument own = new UIDocument();
        own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        PopupLayer layer = new PopupLayer();
        own.useTopLayer(layer);
        AppDocument application = new AppDocument(own, new Stub());
        AppWindows.open(desktop, KIND, application, WindowSpec.titled("Placed"));
        frame();

        Popover menu = new Popover();
        menu.append(new UIText("A menu"));
        own.append(menu);
        menu.showAt(30f, 40f, null);
        for (int i = 0; i < 6; i++) own.frame(0.016f, 400, 300);
        layer.committed();
        for (int i = 0; i < 12; i++) frame();

        PopupPortal portal = (PopupPortal) document.promotedNodes().stream().filter(n -> n instanceof PopupPortal)
                .findFirst().orElseThrow();
        Box window = application.active().box();
        Box shown = portal.box();
        assertNotNull(shown);
        float[] occupied = layer.occupied();
        float scale = document.boxes().uiScale();
        assertEquals("across", (window.worldX() / scale + occupied[0]) * scale, shown.worldX(), 1f);
        assertEquals("down", (window.worldY() / scale + occupied[1]) * scale, shown.worldY(), 1f);
    }
}
