package com.crystalgui.ui;

import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

/**
 * {@link ElementRegistry} — the tag↔class mapping deserialization depends on.
 *
 * <p>Tests register their own throwaway element types rather than reusing {@link UIElement}, because
 * the class→tag map is deliberately <b>bijective</b>: one class owns one tag. Registering the same
 * class twice is the error case, not a fixture convenience.</p>
 */
public class ElementRegistryTest {

    // One class per registering test. They must not be shared: registration is global and permanent,
    // so a class reused across two tests makes them order-dependent.
    private static final class Alpha extends UIElement { }
    private static final class Beta extends UIElement { }
    private static final class Gamma extends UIElement { }
    private static final class Delta extends UIElement { }
    private static final class Epsilon extends UIElement { }
    /** Deliberately never registered anywhere. */
    private static final class Unregistered extends UIElement { }

    @Test
    public void registerThenCreateReturnsFreshInstanceFromFactory() {
        String tag = "test-element-" + System.nanoTime();
        ElementRegistry.register(tag, Alpha.class, Alpha::new);

        UIElement first = ElementRegistry.create(tag);
        UIElement second = ElementRegistry.create(tag);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame("each create() call must build a fresh instance, not share one", first, second);
    }

    @Test
    public void registeringDuplicateTagThrows() {
        String tag = "test-element-dup-" + System.nanoTime();
        ElementRegistry.register(tag, Beta.class, Beta::new);

        try {
            ElementRegistry.register(tag, Gamma.class, Gamma::new);
            fail("expected IllegalArgumentException for duplicate tag");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /** The reverse direction has to be unique too, or {@code tagName()} would be ambiguous. */
    @Test
    public void registeringOneClassUnderTwoTagsThrows() {
        long stamp = System.nanoTime();
        ElementRegistry.register("test-first-" + stamp, Delta.class, Delta::new);

        try {
            ElementRegistry.register("test-second-" + stamp, Delta.class, Delta::new);
            fail("expected IllegalArgumentException — one class, one tag");
        } catch (IllegalArgumentException expected) {
            assertTrue("the message should name the tag already held",
                    expected.getMessage().contains("test-first-" + stamp));
        }
        assertFalse("a rejected registration must not leave the tag half-registered",
                ElementRegistry.isRegistered("test-second-" + stamp));
    }

    @Test
    public void creatingUnknownTagThrows() {
        String tag = "definitely-not-registered-" + System.nanoTime();
        try {
            ElementRegistry.create(tag);
            fail("expected IllegalArgumentException for unknown tag");
        } catch (IllegalArgumentException expected) {
            assertTrue("the message should list what IS registered, to make the typo obvious",
                    expected.getMessage().contains("button"));
        }
    }

    @Test
    public void isRegisteredReflectsCurrentState() {
        String tag = "test-element-check-" + System.nanoTime();
        assertFalse(ElementRegistry.isRegistered(tag));
        ElementRegistry.register(tag, Epsilon.class, Epsilon::new);
        assertTrue(ElementRegistry.isRegistered(tag));
    }

    // ── Built-ins ───────────────────────────────────────────────────────────

    @Test
    public void bootstrapIsIdempotent() {
        ElementRegistry.bootstrapBuiltins();
        int before = ElementRegistry.tags().size();
        ElementRegistry.bootstrapBuiltins();
        ElementRegistry.bootstrapBuiltins();
        assertEquals(before, ElementRegistry.tags().size());
    }

    @Test
    public void everyBuiltinTagRoundTripsToItsClass() {
        ElementRegistry.bootstrapBuiltins();
        for (String tag : ElementRegistry.tags()) {
            UIElement built = ElementRegistry.create(tag);
            assertEquals("tag -> class -> tag must be stable for " + tag,
                    tag, ElementRegistry.tagOf(built.getClass()));
        }
    }

    /** The plain div had no factory at all before, so a serialized UIElement had nothing to rebuild. */
    @Test
    public void thePlainElementIsRegistered() {
        assertTrue(ElementRegistry.isRegistered("element"));
        assertEquals(UIElement.class, ElementRegistry.create("element").getClass());
    }

    // ── tagName() ───────────────────────────────────────────────────────────

    /**
     * The bug this change fixes. {@code UIText} registers as {@code "text"} but its simple name is
     * {@code uitext}, so before this it reported a tag no rule could ever have matched — a
     * {@code text { }} selector silently did nothing.
     */
    @Test
    public void uiTextReportsItsRegisteredTagNotItsClassName() {
        assertEquals("text", new UIText("hi").tagName());
        assertNotEquals("uitext", new UIText("hi").tagName());
    }

    /** An element built with {@code new} must report the same tag as one built by the registry. */
    @Test
    public void bothConstructionRoutesAgreeOnTheTag() {
        assertEquals(ElementRegistry.create("button").tagName(), new Button("x").tagName());
        assertEquals(ElementRegistry.create("textfield").tagName(), new TextField().tagName());
    }

    /** An unregistered subclass still gets a usable tag rather than throwing. */
    @Test
    public void anUnregisteredTypeFallsBackToItsSimpleName() {
        assertEquals(Unregistered.class.getSimpleName().toLowerCase(Locale.ROOT),
                new Unregistered().tagName());
        assertNull(ElementRegistry.tagOf(Unregistered.class));
    }
}
