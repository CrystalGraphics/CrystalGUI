package com.crystalgui.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * Filling a class's fields from a document, by id — and every way that is refused.
 *
 * <p>Refusal is the point: there is no compile step over a resource, so a missing id has to be an error
 * at build rather than a null field that fails somewhere else three frames later.</p>
 */
public class TemplateBinderTest {

    @After
    public void forgetTemplates() {
        UiTemplates.reloadAll();
    }

    private static UIElement status() {
        UIElementRegistry.bootstrap();
        return UiTemplates.load("cguitest:ui/status").inflate();
    }

    static final class Owner {
        @Bound UIText title;
        @Bound UIText subject;
        @Bound Button close;
    }

    static final class Renamed {
        @Bound("subject") UIText caption;
    }

    static final class Optional {
        @Bound UIText title;
        @Bound(optional = true) Button debug;
    }

    static final class Missing {
        @Bound UIText nowhere;
    }

    static final class WrongType {
        @Bound Button title;
    }

    static final class AlreadyBuilt {
        @Bound UIText title = new UIText("mine");
    }

    static final class NotAnElement {
        @Bound String title;
    }

    @Test
    public void everyFieldIsFilledById() {
        Owner owner = new Owner();
        TemplateBinder.bind(owner, status());

        assertNotNull(owner.title);
        assertEquals("Status", owner.title.getText());
        assertNotNull(owner.subject);
        assertNotNull(owner.close);
    }

    @Test
    public void aFieldMayNameADifferentId() {
        Renamed owner = new Renamed();
        TemplateBinder.bind(owner, status());

        assertEquals("subject", owner.caption.id());
    }

    @Test
    public void anOptionalFieldStaysNull() {
        Optional owner = new Optional();
        TemplateBinder.bind(owner, status());

        assertNotNull(owner.title);
        assertNull(owner.debug);
    }

    @Test
    public void aMissingIdIsRefusedByName() {
        try {
            TemplateBinder.bind(new Missing(), status());
            assertTrue("a required id the document has not got must be refused", false);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nowhere"));
        }
    }

    @Test
    public void aWrongTypeIsRefusedByName() {
        try {
            TemplateBinder.bind(new WrongType(), status());
            assertTrue("a field of the wrong type must be refused", false);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("title"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("Button"));
        }
    }

    /** Two owners of one part: the initializer built one and the document has another. */
    @Test
    public void aFieldAlreadyAssignedIsRefused() {
        try {
            TemplateBinder.bind(new AlreadyBuilt(), status());
            assertTrue("a bound field with an initializer must be refused", false);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("initializer"));
        }
    }

    @Test
    public void aFieldThatIsNotAnElementIsRefused() {
        try {
            TemplateBinder.bind(new NotAnElement(), status());
            assertTrue("a non-element field must be refused", false);
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not a UIElement"));
        }
    }

    /** A class with nothing to bind pays nothing. */
    @Test
    public void aClassWithNoBoundFieldsBindsNothing() {
        assertTrue(!TemplateBinder.binds(String.class));
        assertTrue(TemplateBinder.binds(Owner.class));
    }
}
