package com.crystalgui.desktop.taskbar;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** A combined button's row of previews fits the screen however many windows it holds: every card squeezes, none runs off. */
public class PreviewRowSqueezeTest extends UiDocumentTestBase {

    private Desktop desktop;

    @Before
    public void openADesktop() {
        Desktop.setAnimationsEnabled(false);
        viewport(900f, 600f);
        document.append(new UIElement().layout(l -> l.width(900).height(600)));
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document);
    }

    @After
    public void animationsBackOn() {
        Desktop.setAnimationsEnabled(true);
    }

    private static WindowPreviewRow rowIn(UIElement node) {
        if (node instanceof WindowPreviewRow row) return row;
        for (UIElement child : node.children()) {
            WindowPreviewRow found = rowIn(child);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    public void manyWindowsAllFitTheScreen() {
        ApplicationKind cues = ApplicationKind.of("test:cues", "Cues").standalone();
        WindowFrame first = null;
        for (int i = 0; i < 24; i++) {
            WindowFrame frame = desktop.addWindow(new WindowFrame("Cues " + i).setApplication(cues));
            frame.resizeTo(200f, 150f);
            if (first == null) first = frame;
        }
        for (int i = 0; i < 8; i++) frame();
        var entry = desktop.taskbar().entryFor(first);
        int[] c = centreOf(entry);
        click(c[0], c[1]);
        for (int i = 0; i < 20; i++) frame();
        WindowPreviewRow row = rowIn(document);
        assertNotNull("no preview row", row);
        assertEquals(24, row.children().size());
        float screen = document.box().width();
        assertTrue("the row runs " + row.box().width() + " past a screen of " + screen, row.box().width() <= screen + 0.5f);
    }
}
