package com.crystalgui.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * Reading a document by id — the half that goes through {@code CgIO}, so an override directory, a
 * resource pack and the classpath are all tried.
 *
 * <p>Separate from the headless round trip because that source set has no CrystalGraphics core: parsing
 * and inflating must work without one, reading a file need not.</p>
 */
public class UiTemplateLoadTest {

    @After
    public void forgetTemplates() {
        UiTemplates.reloadAll();
    }

    @Test
    public void aDocumentLoadsFromItsAssetId() {
        UIElementRegistry.bootstrap();
        UiTemplate template = UiTemplates.load("cguitest:ui/sample");

        assertEquals("cguitest:ui/sample", template.origin());
        assertEquals("root", template.inflate().id());
    }

    @Test
    public void aDocumentIsParsedOnce() {
        UIElementRegistry.bootstrap();

        assertTrue(UiTemplates.load("cguitest:ui/sample") == UiTemplates.load("cguitest:ui/sample"));

        UiTemplates.reloadAll();
        assertNull("reloading forgets it, so the next load reads the file", 
                UiTemplates.loaded("cguitest:ui/sample"));
    }

    @Test
    public void anAbsentDocumentSaysWhereItLooked() {
        try {
            UiTemplates.load("nobody:ui/missing");
            assertTrue("an absent document must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("/assets/nobody/ui/missing.cgui"));
        }
    }
}
