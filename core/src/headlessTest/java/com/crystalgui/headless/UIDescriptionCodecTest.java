package com.crystalgui.headless;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.CodecException;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * {@link UIDescriptionCodec} — the thing a server actually sends.
 *
 * <p>Every structural case is asserted through <b>two</b> {@link DynamicOps} implementations. That
 * idiom is inherited from the codec test this replaces, and it earns its keep: if the codec ever
 * came to depend on JSON specifics, the {@link PlainOps} half would fail while the JSON half kept
 * passing.</p>
 */
public class UIDescriptionCodecTest {

    private static final Codec<UIElement> CODEC = UIDescriptionCodec.CODEC;

    private <T> UIElement roundTrip(DynamicOps<T> ops, UIElement source) {
        return CODEC.decode(ops, CODEC.encode(ops, source));
    }

    /** Runs an assertion against both ops, so a JSON-specific regression can't hide. */
    private void inBothFormats(java.util.function.BiConsumer<DynamicOps<?>, java.util.function.Function<UIElement, UIElement>> body) {
        body.accept(JsonOps.INSTANCE, e -> roundTrip(JsonOps.INSTANCE, e));
        body.accept(PlainOps.INSTANCE, e -> roundTrip(PlainOps.INSTANCE, e));
    }

    // ── Structure ───────────────────────────────────────────────────────────

    @Test
    public void identityAndNestingSurviveBothFormats() {
        inBothFormats((ops, trip) -> {
            UIElement root = new UIElement();
            root.setId("root");
            root.addClass("panel").addClass("dark");
            UIElement child = new UIElement();
            child.setId("child");
            root.addChild(child);

            UIElement decoded = trip.apply(root);
            assertEquals("root", decoded.getId());
            assertTrue(decoded.hasClass("panel"));
            assertTrue(decoded.hasClass("dark"));
            assertEquals(1, decoded.getChildren().size());
            assertEquals("child", decoded.getChildren().get(0).getId());
        });
    }

    /** The tag has to rebuild the real subtype, not a bare div. */
    @Test
    public void concreteWidgetTypesAreRebuilt() {
        inBothFormats((ops, trip) -> {
            UIElement root = new UIElement();
            root.addChild(new Checkbox("agree"));
            root.addChild(new Slider());
            root.addChild(new UIText("hello"));

            UIElement decoded = trip.apply(root);
            assertTrue(decoded.getChildren().get(0) instanceof Checkbox);
            assertTrue(decoded.getChildren().get(1) instanceof Slider);
            assertTrue("UIText registers as 'text' — the tag must survive, not its class name",
                    decoded.getChildren().get(2) instanceof UIText);
        });
    }

    /**
     * The defect that made the old codec unusable: a Button's internal label was serialized, and on
     * decode the constructor rebuilt it AND the codec tried to re-add the copy.
     */
    @Test
    public void internalChildrenAreNeitherSentNorDuplicated() {
        Button button = new Button("Press");
        int internalsBefore = button.getChildren().size();
        assertTrue("precondition: Button builds internals in its constructor", internalsBefore > 0);

        JsonObject encoded = CODEC.encode(JsonOps.INSTANCE, button).getAsJsonObject();
        assertFalse("internal children must not be serialized", encoded.has("children"));

        UIElement decoded = CODEC.decode(JsonOps.INSTANCE, encoded);
        assertEquals("the constructor rebuilds them — exactly once",
                internalsBefore, decoded.getChildren().size());
        assertEquals("Press", ((Button) decoded).getText());
    }

    /** Structural {@code __name__} classes are widget-owned and re-derived, so they don't travel. */
    @Test
    public void structuralClassesAreNotSent() {
        UIElement element = new UIElement();
        element.addClass("author-class");
        element.addClass("__internal-marker__");

        JsonObject encoded = CODEC.encode(JsonOps.INSTANCE, element).getAsJsonObject();
        List<String> classes = encoded.getAsJsonArray("class").asList().stream()
                .map(JsonElement::getAsString).toList();
        assertEquals(List.of("author-class"), classes);
    }

    // ── Failure modes ───────────────────────────────────────────────────────

    @Test
    public void anUnknownTagFailsLoudly() {
        JsonObject bogus = new JsonObject();
        bogus.addProperty("tag", "not-a-real-widget");
        try {
            CODEC.decode(JsonOps.INSTANCE, bogus);
            fail("an unknown tag must not silently decode to a bare UIElement");
        } catch (CodecException expected) {
            assertTrue(expected.getMessage().contains("not-a-real-widget"));
        }
    }

    /** A hand-written description can't smuggle children into a widget that refuses them. */
    @Test
    public void childrenOnAWidgetThatRefusesThemFailLoudly() {
        JsonObject child = new JsonObject();
        child.addProperty("tag", "element");
        JsonObject button = new JsonObject();
        button.addProperty("tag", "button");
        com.google.gson.JsonArray children = new com.google.gson.JsonArray();
        children.add(child);
        button.add("children", children);

        try {
            CODEC.decode(JsonOps.INSTANCE, button);
            fail("expected a CodecException for children on a Button");
        } catch (CodecException expected) {
            assertTrue(expected.getMessage().contains("does not accept public children"));
        }
    }

    // ── Compactness ─────────────────────────────────────────────────────────

    /** A default element should be almost nothing on the wire — absent optionals are omitted. */
    @Test
    public void aDefaultElementEncodesToJustItsTag() {
        JsonObject encoded = CODEC.encode(JsonOps.INSTANCE, new UIElement()).getAsJsonObject();
        assertEquals("only 'tag' should be present", 1, encoded.size());
        assertEquals("element", encoded.get("tag").getAsString());
    }

    // ── Flags, focus and state ──────────────────────────────────────────────

    @Test
    public void flagsAndFocusPolicyRoundTrip() {
        inBothFormats((ops, trip) -> {
            UIElement element = new UIElement();
            element.setEnabled(false);
            element.setHitTest(false);
            element.setFocusPolicy(FocusPolicy.FOCUSABLE);

            UIElement decoded = trip.apply(element);
            assertFalse(decoded.isEnabled());
            assertFalse(decoded.isHitTest());
            assertEquals(FocusPolicy.FOCUSABLE, decoded.getFocusPolicy());
        });
    }

    @Test
    public void widgetStateTravelsWithTheElement() {
        inBothFormats((ops, trip) -> {
            UIElement root = new UIElement();
            Checkbox checkbox = new Checkbox("agree");
            checkbox.setChecked(true);
            root.addChild(checkbox);
            TextField field = new TextField();
            field.setMode(TextField.Mode.INTEGER);
            field.setText("42");
            root.addChild(field);

            UIElement decoded = trip.apply(root);
            assertTrue(((Checkbox) decoded.getChildren().get(0)).isChecked());
            assertEquals("42", ((TextField) decoded.getChildren().get(1)).getText());
            assertEquals(TextField.Mode.INTEGER, ((TextField) decoded.getChildren().get(1)).getMode());
        });
    }

    // ── Styles ──────────────────────────────────────────────────────────────

    /** Author-set styles are the only ones that can't be re-derived, so they're the ones that travel. */
    @Test
    public void authorSetInlineStylesRoundTrip() {
        inBothFormats((ops, trip) -> {
            UIElement element = new UIElement();
            element.layout(l -> l.width(80).flexGrow(2f));

            UIElement decoded = trip.apply(element);
            assertEquals(dev.vfyjxf.taffy.style.TaffyDimension.length(80),
                    decoded.getStyle().getComputed(LayoutProperties.WIDTH));
            assertEquals(2f, decoded.getStyle().getComputed(LayoutProperties.FLEX_GROW), 0.001f);
        });
    }

    /**
     * A widget's own DEFAULT-origin styles must NOT travel — the client's constructor sets them, and
     * sending them would be pure duplication that also outranks nothing.
     */
    @Test
    public void widgetDefaultStylesAreNotSent() {
        JsonObject encoded = CODEC.encode(JsonOps.INSTANCE, new Button("x")).getAsJsonObject();
        assertFalse("Button sets flex-direction at DEFAULT origin; that is the client's job to redo",
                encoded.has("style"));
    }

    /** An enum-valued style serializes by name, so inserting a constant can't re-point old data. */
    @Test
    public void enumValuedStylesRoundTripByName() {
        UIElement element = new UIElement();
        element.getStyle().replaceOrPutCandidate(LayoutProperties.FLEX_DIRECTION,
                StyleSlot.of(LayoutProperties.FLEX_DIRECTION, StyleOrigin.INLINE, 0, 0L,
                        dev.vfyjxf.taffy.style.FlexDirection.COLUMN_REVERSE));

        JsonObject encoded = CODEC.encode(JsonOps.INSTANCE, element).getAsJsonObject();
        assertEquals("COLUMN_REVERSE",
                encoded.getAsJsonObject("style").get("flex-direction").getAsString());

        UIElement decoded = CODEC.decode(JsonOps.INSTANCE, encoded);
        assertEquals(dev.vfyjxf.taffy.style.FlexDirection.COLUMN_REVERSE,
                decoded.getStyle().getComputed(LayoutProperties.FLEX_DIRECTION));
    }
}
