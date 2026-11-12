package com.crystalgui.ui.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgClipboard;
import com.crystalgui.testsupport.TestPlatformService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The clipboard's own rules: one value for the clipboard and drag, its text written through to the system, and
 * another program's copy since winning over ours.
 */
public class UiClipboardTest {

    private record Fragment(int nodes) {
    }

    private final UiClipboard clipboard = UiClipboard.process();

    @Before
    public void setUp() {
        TestPlatformService.install();
        clipboard.clear();
    }

    @After
    public void tearDown() {
        clipboard.clear();
    }

    @Test
    public void aCopyKeepsItsRichFormAndWritesItsTextThrough() {
        Fragment fragment = new Fragment(3);
        clipboard.put(TransferData.of(fragment, "3 nodes"));

        assertEquals("the system has the text", "3 nodes", CgClipboard.get());
        assertSame("a paste gets the typed value", fragment, clipboard.read(Fragment.class).result());
    }

    @Test
    public void anotherProgramsCopySinceOursWins() {
        clipboard.put(TransferData.of(new Fragment(3), "3 nodes"));
        TestPlatformService.MEMORY_CLIPBOARD.set("from a browser");

        assertTrue("still held, as a menu would see it", clipboard.has(Fragment.class));
        assertNull("but a paste does not offer it", clipboard.read(Fragment.class).result());
    }

    @Test
    public void aFormWithNoTextIsNeverContradicted() {
        Fragment fragment = new Fragment(1);
        clipboard.put(TransferData.of(fragment));
        TestPlatformService.MEMORY_CLIPBOARD.set("from a browser");

        assertSame(fragment, clipboard.read(Fragment.class).result());
    }

    @Test
    public void copyingNothingKeepsWhatWasCopied() {
        clipboard.put(TransferData.of(new Fragment(2), "2 nodes"));
        clipboard.put(null);

        assertTrue(clipboard.has(Fragment.class));
        assertFalse("a type nothing was copied as", clipboard.has(Integer.class));
    }

    @Test
    public void aPayloadIsFoundByTypeWhetherItOffersOrIs() {
        Fragment fragment = new Fragment(1);
        TransferData offered = TransferData.of(fragment, "text");

        assertSame(fragment, TransferData.find(offered, Fragment.class));
        assertEquals("text", offered.text());
        assertSame("a payload that is the type", fragment, TransferData.find(fragment, Fragment.class));
        assertNull(TransferData.find(offered, Integer.class));
    }
}
