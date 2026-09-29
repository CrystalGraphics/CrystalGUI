package com.crystalgui.widget.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgui.core.trace.FrameStats;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.widget.text.UIText;

/**
 * <b>The readout has to be driven by the engine's own frame</b>, not by its own timer: it exists to say what a
 * frame cost, so a HUD that measured the gaps between its own updates would be reporting on itself.
 */
public class FrameStatsOverlayTest extends UiDocumentTestBase {

    /** Frames at a tenth of a second, which is {@link FrameStatsOverlay#REFRESH_SECONDS}. */
    private void frames(int howMany) {
        for (int i = 0; i < howMany; i++) frame(FrameStatsOverlay.REFRESH_SECONDS);
    }

    @Test
    public void aFewFramesFillTheReadout() {
        withDefaultStyles();
        FrameStatsOverlay hud = FrameStatsOverlay.attach(document);
        frames(4);

        assertFalse("the readout never wrote a row", hud.text().isEmpty());
        assertTrue("the headline row is empty", hud.text().get(0).length() > 0);
        // WALL CLOCK, not the delta the test passes: the frames above run back to back, so the interval
        // between them is real and tiny rather than the 100ms this test claims each one took.
        assertTrue("no frame was sampled", FrameStats.get().sampleCount() > 0);
        hud.removeSelf();
    }

    @Test
    public void theReadoutTakesUpRoomOnceItHasRowsToShow() {
        withDefaultStyles();
        FrameStatsOverlay hud = FrameStatsOverlay.attach(document);
        frames(4);
        frame();   // the rows were added during a hook, so this is the frame that lays them out

        Box box = hud.box();
        assertTrue("the readout has no box at all", box != null);
        // A SIZE, NOT A PIXEL COUNT. The failure this pins is the readout collapsing to its padding and
        // vanishing -- `white-space: nowrap` made every row measure zero wide, and an `overflow` on the
        // plate then hid what they drew outside it. Asserting on the exact width would be asserting on
        // the font.
        assertTrue("the readout collapsed to " + box.width() + "x" + box.height(),
                box.width() > 40f && box.height() > 10f);
        // THE COLLECTOR IS A SINGLETON, so a test that drops its document without detaching leaves a
        // hold behind and the next one sees collection it did not ask for. Detaching is what a real
        // host does when the HUD closes; a discarded document never detaches anything.
        hud.removeSelf();
    }

    @Test
    public void onlyTheBarsRowIsHighlighted() {
        withDefaultStyles();
        FrameStatsOverlay hud = FrameStatsOverlay.attach(document);
        frames(4);

        List<UIText> rows = hud.rows();
        int bars = 0;
        for (UIText row : rows) {
            if (row.hasClass(FrameStatsOverlay.SPARK_CLASS)) bars++;
            // THE CLEAR IS THE HALF THAT ROTS. Ranges are offsets into the string that is there now, so
            // a row that stopped being the bars and kept theirs would colour whatever text replaced it.
            else assertTrue("a plain row kept highlights: " + row.getText(), row.highlights().isEmpty());
        }
        assertEquals("exactly one row is made of bars", 1, bars);
        hud.removeSelf();
    }

    @Test
    public void itCollectsOnlyWhileItIsShowingAndInATree() {
        FrameStatsOverlay hud = FrameStatsOverlay.attach(document);
        frame();
        assertTrue(FrameStats.isCollecting());

        hud.setShowing(false);
        assertFalse("a hidden readout still pays for samples nobody reads", FrameStats.isCollecting());

        hud.setShowing(true);
        assertTrue(FrameStats.isCollecting());
        hud.removeSelf();
        assertFalse("a detached readout still holds the collector", FrameStats.isCollecting());
    }
}
