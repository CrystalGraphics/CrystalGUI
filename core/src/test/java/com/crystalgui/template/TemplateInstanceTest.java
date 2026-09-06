package com.crystalgui.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.text.UIText;

/**
 * A document placed inside another: its content becomes a shadow tree, its slots take the placing
 * document's children, its parts stay reachable, and its overrides and parameters arrive.
 *
 * <p>In {@code test} rather than {@code headlessTest} because an instance loads the template it names,
 * and reading a document goes through {@code CgIO}.</p>
 */
public class TemplateInstanceTest {

    @After
    public void forgetTemplates() {
        UiTemplates.reloadAll();
    }

    private static UIElement page() {
        UIElementRegistry.bootstrap();
        return UiTemplates.load("cguitest:ui/page").inflate();
    }

    @Test
    public void aPlacedTemplateBecomesAShadowTree() {
        UIElement frame = page().getElementById("frame");

        assertTrue(frame instanceof TemplateInstance);
        ShadowRoot shadow = frame.shadowRoot();
        assertNotNull("the template is the instance's shadow tree", shadow);
        assertEquals(1, shadow.children().size());
        assertTrue(shadow.children().get(0).hasClass("plate"));
    }

    /** The placing document's selectors cannot reach in, so its ids are not the page's ids. */
    @Test
    public void theTemplateInternalsAreNotThePagesElements() {
        UIElement root = page();

        assertNotNull(root.getElementById("frame"));
        assertFalse("an internal id belongs to the template, not to the page",
                root.getElementById("label") != null);
    }

    @Test
    public void aParameterFillsIn() {
        TemplateInstance frame = (TemplateInstance) page().getElementById("frame");
        UIText label = (UIText) frame.shadowRoot().getElementById("label");

        assertEquals("Status", label.getText());
    }

    /** With nobody supplying one, the declared default fills in. */
    @Test
    public void aParameterFallsBackToItsDefault() {
        UIElementRegistry.bootstrap();
        TemplateInstance plate = new TemplateInstance("cguitest:ui/parts/plate");

        assertEquals("Plate", ((UIText) plate.shadowRoot().getElementById("label")).getText());
    }

    @Test
    public void anOverrideReachesAnInternalId() {
        TemplateInstance frame = (TemplateInstance) page().getElementById("frame");

        assertEquals("on", ((UIText) frame.shadowRoot().getElementById("lamp")).getText());
    }

    @Test
    public void slottedContentLands() {
        TemplateInstance frame = (TemplateInstance) page().getElementById("frame");

        assertEquals(List.of("content"), frame.slotNames());
        assertEquals(1, frame.children().size());
        assertEquals("body", frame.children().get(0).id());
    }

    /** A named slot has to survive being described, or every slotted child lands nowhere. */
    @Test
    public void aDescribedSlotKeepsItsName() {
        TemplateInstance frame = (TemplateInstance) page().getElementById("frame");

        assertEquals(List.of("content"), frame.slotNames());
    }

    @Test
    public void aTemplateMayRegisterItselfAsAKind() {
        UIElementRegistry.bootstrap();
        UiTemplate plate = UiTemplates.load("cguitest:ui/parts/plate");

        assertTrue(UiTemplates.register(plate));
        assertTrue(UIElementRegistry.isRegistered(plate.kindName()));
        assertTrue(UIElementRegistry.create(plate.kindName()) instanceof TemplateInstance);
    }

    /** A template that places itself is refused, at the depth it is found. */
    @Test
    public void aCycleIsRefused() {
        UIElementRegistry.bootstrap();
        try {
            UiTemplates.load("cguitest:ui/loop-a").inflate();
            assertTrue("a cycle must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cannot place itself"));
        }
    }

    /** Slotting into a name the template does not offer is silent otherwise: no box, no paint. */
    @Test
    public void aSlotNobodyOffersIsRefused() {
        UIElementRegistry.bootstrap();
        String document = "{\"cgui\": 1, \"root\": { \"kind\": \"instance\","
                + " \"attrs\": { \"template\": \"cguitest:ui/parts/plate\" },"
                + " \"children\": [ { \"kind\": \"text\", \"attrs\": { \"slot\": \"nowhere\" } } ] } }";
        try {
            UiTemplates.parse(document, "test:bad-slot").inflate();
            assertTrue("a slot nobody offers must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nowhere"));
        }
    }

    @Test
    public void anOverrideForAnUnknownIdIsRefused() {
        UIElementRegistry.bootstrap();
        try {
            UiTemplates.load("cguitest:ui/parts/plate").inflate(Map.of("nobody", Map.of("text", "x")));
            assertTrue("an override for an id nobody has must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nobody"));
        }
    }
}
