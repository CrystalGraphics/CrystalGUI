package com.crystalgui.net.window;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The placement hint on the wire: every form round-trips and nothing unknown fails. */
public class PresentationTest {

    @Test
    public void everyFormRoundTrips() {
        for (Presentation each : new Presentation[] {Presentation.WINDOW, Presentation.EDITOR_TAB,
                Presentation.SCREEN, Presentation.toolWindow("panel")}) {
            assertEquals(each, Presentation.parse(each.encode()));
        }
    }

    @Test
    public void aScreenIsReadWhateverItsCaseAndAnUnknownFormIsAWindow() {
        assertEquals(Presentation.Kind.SCREEN, Presentation.parse(" Screen ").kind());
        assertEquals(Presentation.WINDOW, Presentation.parse("hologram"));
    }
}
