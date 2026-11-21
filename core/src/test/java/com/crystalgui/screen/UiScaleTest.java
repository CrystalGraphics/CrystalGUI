package com.crystalgui.screen;

import com.crystalgui.core.settings.Preferences;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The one owner of a document's scale: the screen's, times the desktop's, times its application's zoom. */
public class UiScaleTest extends UiDocumentTestBase {

    private static final String APP = "crystalgui:editor";

    @After
    public void clearPreferences() {
        Preferences.update(p -> p.withAll(Map.of()));
    }

    @Test
    public void zoomStepsAsABrowserDoes() {
        assertEquals(1.1f, UiScale.stepFrom(1f, +1), 0f);
        assertEquals(0.9f, UiScale.stepFrom(1f, -1), 0f);
        assertEquals("an unlisted zoom steps to the next listed one", 1.25f, UiScale.stepFrom(1.2f, +1), 0f);
        assertEquals("clamped at the ends", 3f, UiScale.stepFrom(3f, +1), 0f);
        assertEquals(0.5f, UiScale.stepFrom(0.5f, -1), 0f);
    }

    @Test
    public void aBoundDocumentFollowsItsApplicationsZoomAndTheDesktopsScale() {
        Connection follow = UiScale.bind(document, APP);
        frame();
        assertEquals(1f, document.boxes().uiScale(), 0f);
        assertEquals(APP, UiScale.appOf(document));

        UiScale.zoomIn(APP);
        assertEquals("the zoom is stored", 1.1f, Preferences.current().zoom(APP), 1e-6f);
        assertEquals("and multiplied in", 1.1f, UiScale.of(APP), 1e-6f);
        frame();
        assertEquals("and heard by the document", 1.1f, document.boxes().uiScale(), 1e-6f);

        Preferences.update(p -> p.withDesktopScale(2f));
        frame();
        assertEquals("the two multiply", 2.2f, document.boxes().uiScale(), 1e-5f);

        UiScale.resetZoom(APP);
        frame();
        assertEquals(2f, document.boxes().uiScale(), 0f);
        assertNull("a reset zoom is no entry at all", Preferences.current().asMap().get("appearance.zoom." + APP));
        follow.disconnect();
    }
}
