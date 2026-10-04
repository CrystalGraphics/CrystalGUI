package com.crystalgui.app.frameprofiler;

import com.crystalgraphics.render.graph.CgBufferInspector;
import com.crystalgui.ui.dom.UIDocument;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Buffers tab watches while it is open, and leaves the engine as it found it. */
public class BuffersTabTest {

    @After
    public void stopWatching() {
        CgBufferInspector.watch(false);
    }

    @Test
    public void watchesOnceShownAndStopsWhenClosed() {
        UIDocument window = new UIDocument();
        BuffersTab tab = new BuffersTab();
        window.append(tab);
        for (int i = 0; i < 3; i++) window.frame(1f / 60f, 400, 300);

        assertTrue(CgBufferInspector.watching());
        assertEquals("No compute pass has run since watching began.", tab.statusText());

        window.remove(tab);
        assertFalse(CgBufferInspector.watching());
    }

    @Test
    public void leavesAWatchItDidNotStart() {
        CgBufferInspector.watch(true);
        UIDocument window = new UIDocument();
        BuffersTab tab = new BuffersTab();
        window.append(tab);
        for (int i = 0; i < 3; i++) window.frame(1f / 60f, 400, 300);

        window.remove(tab);
        assertTrue(CgBufferInspector.watching());
    }

    @Test
    public void sortsByTheFirstNumber() {
        assertEquals(1.5, BuffersTab.firstNumber("1.5, -2.0"), 0);
        assertEquals(4294967295d, BuffersTab.firstNumber("4294967295"), 0);
        assertEquals(255, BuffersTab.firstNumber("0xff, 0x1"), 0);
        assertTrue(Double.isNaN(BuffersTab.firstNumber("")));
    }
}
