package com.crystalgui.ui;

import org.junit.Test;

import static org.junit.Assert.*;

public class ElementRegistryTest {

    @Test
    public void registerThenCreateReturnsFreshInstanceFromFactory() {
        String tag = "test-element-" + System.nanoTime();
        ElementRegistry.register(tag, UIElement::new);

        UIElement first = ElementRegistry.create(tag);
        UIElement second = ElementRegistry.create(tag);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame("each create() call must build a fresh instance, not share one", first, second);
    }

    @Test
    public void registeringDuplicateTagThrows() {
        String tag = "test-element-dup-" + System.nanoTime();
        ElementRegistry.register(tag, UIElement::new);

        try {
            ElementRegistry.register(tag, UIElement::new);
            fail("expected IllegalArgumentException for duplicate tag");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void creatingUnknownTagThrows() {
        String tag = "definitely-not-registered-" + System.nanoTime();
        try {
            ElementRegistry.create(tag);
            fail("expected IllegalArgumentException for unknown tag");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void isRegisteredReflectsCurrentState() {
        String tag = "test-element-check-" + System.nanoTime();
        assertFalse(ElementRegistry.isRegistered(tag));
        ElementRegistry.register(tag, UIElement::new);
        assertTrue(ElementRegistry.isRegistered(tag));
    }
}
